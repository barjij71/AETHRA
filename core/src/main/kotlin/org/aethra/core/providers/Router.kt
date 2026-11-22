package org.aethra.core.providers

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.task.Task

/** Route result */
data class RouteResult(
    val providerId: String,
    val model: String,
    val usedFallback: Boolean,
    val reply: Map<String, Any>
)

/** Provider system manages the provider roster and dispatches requests. */
class ProviderSystem {
    private val providers = ArrayList<LanguageModelProvider>()
    private val fallbackPolicy = hashMapOf<String, FallbackPolicy>()

    fun add(provider: LanguageModelProvider) { providers += provider }
    fun enableFallback(policy: FallbackPolicy) { fallbackPolicy[policy.providerId] = policy }
    fun list(): List<LanguageModelProvider> = providers.toList()
    fun policies(): Map<String, FallbackPolicy> = fallbackPolicy.toMap()
}

/** Provider/Model Router - domain foundation */
sealed class RoutingDecision {
    data class Success(
        val providerId: String,
        val modelId: String,
        val usedFallback: Boolean,
        val provider: LanguageModelProvider?,
        val selectionEvidence: ProviderSelectionEvidence? = null
    ) : RoutingDecision()
    data class Rejected(
        val reason: String,
        val policy: RiskClass
    ) : RoutingDecision()
    data class UnableToRoute(
        val reason: String,
        val selectionEvidence: ProviderSelectionEvidence? = null
    ) : RoutingDecision()
}

enum class PrivacyRequirement {
    ALLOW_REMOTE,
    LOCAL_ONLY
}

/** Requirements that every adaptive primary candidate must satisfy. */
data class ProviderSelectionContext(
    val requiredCapabilities: Set<String> = emptySet(),
    val requiresTools: Boolean = false,
    val requiresStreaming: Boolean = false,
    val privacyRequirement: PrivacyRequirement = PrivacyRequirement.ALLOW_REMOTE
)

enum class SelectionCandidateStatus {
    SELECTED,
    INELIGIBLE,
    LOWER_RANKED
}

enum class SelectionReason {
    REQUIREMENTS_SATISFIED,
    MISSING_REQUIRED_CAPABILITY,
    TOOL_SUPPORT_MISSING,
    STREAMING_SUPPORT_MISSING,
    LOCAL_PRIVACY_REQUIRED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_UNHEALTHY,
    MODEL_NOT_REGISTERED,
    MODEL_PROVIDER_MISMATCH,
    MODEL_UNAVAILABLE,
    MODEL_UNHEALTHY,
    RUNTIME_PROVIDER_UNAVAILABLE,
    LOWEST_KNOWN_COST,
    HIGHER_COST,
    COST_UNKNOWN_RANKED_AFTER_KNOWN,
    DETERMINISTIC_TIE_BREAK
}

enum class SelectionRankingRule {
    ELIGIBILITY_THEN_KNOWN_COST_THEN_DEFAULT_THEN_PROVIDER_MODEL_ID
}

/** Safe selection evidence: identifiers and reason codes, never endpoints or credentials. */
data class CandidateSelectionEvidence(
    val providerId: String,
    val modelId: String,
    val status: SelectionCandidateStatus,
    val reasons: Set<SelectionReason>,
    val missingCapabilities: Set<String> = emptySet()
)

data class ProviderSelectionEvidence(
    val rankingRule: SelectionRankingRule,
    val candidates: List<CandidateSelectionEvidence>
)

/** Decision for fallback candidacy. */
sealed class FallbackDecision {
    data class Candidate(
        val providerDef: ProviderDefinition,
        val modelId: String
    ) : FallbackDecision()

    data class Denied(
        val reason: String,
        val blockedBy: String
    ) : FallbackDecision()
}

data class ConversationProviderConfig(
    val conversationId: String,
    val preferredProviderId: String?,
    val preferredModelId: String?,
    val hasCustomSelection: Boolean = false,
    val selectionContext: ProviderSelectionContext = ProviderSelectionContext()
)

data class FallbackEligibility(
    val fallbackEnabled: Boolean,
    val isFallbackEligible: Boolean
)

class ProviderModelRouter(
    private val providerRegistry: ProviderRegistry,
    private val modelRegistry: ModelRegistry
) {
    private val fallbackPolicy = hashMapOf<String, FallbackPolicy>()

    /**
     * Select the best eligible primary candidate using declared metadata only.
     * Eligibility is evaluated before cost, so a cheaper candidate can never
     * bypass capability, privacy, availability, or runtime requirements.
     */
    fun select(
        context: ProviderSelectionContext,
        task: Task
    ): RoutingDecision {
        if (task.riskClass == RiskClass.DESTRUCTIVE) {
            return RoutingDecision.Rejected(
                "Destructive tasks require explicit policy approval",
                task.riskClass
            )
        }

        val assessed = providerRegistry.list().flatMap { providerDef ->
            providerDef.supportedModels.distinct().sorted().map { modelId ->
                assessCandidate(providerDef, modelId, context)
            }
        }
        val eligible = assessed.filter { it.reasons.isEmpty() && it.provider != null && it.modelDef != null }
            .sortedWith(
                compareBy<CandidateAssessment>(
                    { it.modelDef?.costTier == null },
                    { it.modelDef?.costTier ?: 0 },
                    { it.providerDef.providerId != "default" },
                    { it.providerDef.providerId },
                    { it.modelId }
                )
            )

        val selected = eligible.firstOrNull()
        val evidence = buildSelectionEvidence(assessed, eligible, selected)
        if (selected == null) {
            return RoutingDecision.UnableToRoute(
                "No eligible provider/model candidates",
                evidence
            )
        }

        return RoutingDecision.Success(
            providerId = selected.providerDef.providerId,
            modelId = selected.modelId,
            usedFallback = false,
            provider = selected.provider,
            selectionEvidence = evidence
        )
    }

    fun resolve(config: ConversationProviderConfig, task: Task): RoutingDecision {
        if (!config.hasCustomSelection &&
            config.preferredProviderId == null &&
            config.preferredModelId == null
        ) {
            return select(config.selectionContext, task)
        }

        val preferredProviderId = config.preferredProviderId ?: "default"
        val providerDef = providerRegistry.get(preferredProviderId)
            ?: return RoutingDecision.UnableToRoute("No provider with id '${preferredProviderId}'")

        var modelId: String
        var modelDef: ModelDefinition? = null
        if (config.preferredModelId != null) {
            modelDef = modelRegistry.get(config.preferredModelId)
            if (modelDef == null) {
                return RoutingDecision.UnableToRoute("No model with id '${config.preferredModelId}'")
            }
            if (modelDef.accessProvider != providerDef.providerId) {
                return RoutingDecision.UnableToRoute(
                    "Model '${modelDef.modelId}' belongs to provider '${modelDef.accessProvider}', " +
                    "not '${providerDef.providerId}'"
                )
            }
            modelId = modelDef.modelId
        } else {
            modelId = providerDef.supportedModels.firstOrNull()
                ?: return RoutingDecision.UnableToRoute("Provider '${preferredProviderId}' has no models")
        }

        if (task.riskClass == RiskClass.DESTRUCTIVE) {
            return RoutingDecision.Rejected("Destructive tasks require explicit policy approval", task.riskClass)
        }

        val assessed = assessCandidate(providerDef, modelId, config.selectionContext)
        val eligible = if (assessed.reasons.isEmpty() && assessed.provider != null && assessed.modelDef != null) {
            listOf(assessed)
        } else {
            emptyList()
        }
        val selected = eligible.singleOrNull()
        val evidence = buildSelectionEvidence(listOf(assessed), eligible, selected)
        if (selected == null) {
            return RoutingDecision.UnableToRoute(
                "Explicit provider/model candidate is ineligible",
                evidence
            )
        }

        return RoutingDecision.Success(
            providerId = providerDef.providerId,
            modelId = modelId,
            usedFallback = false,
            provider = selected.provider,
            selectionEvidence = evidence
        )
    }

    /** Register a fallback policy for a provider. */
    fun enableFallback(policy: FallbackPolicy) {
        fallbackPolicy[policy.providerId] = policy
    }

    fun getFallbackCandidate(
        primaryProviderId: String,
        task: Task,
        selectionContext: ProviderSelectionContext = ProviderSelectionContext()
    ): FallbackDecision {
        val primaryDef = providerRegistry.get(primaryProviderId)
            ?: return FallbackDecision.Denied("Primary provider not found", "provider_not_found")

        val policy = fallbackPolicy[primaryProviderId]
            ?: return FallbackDecision.Denied("No fallback policy registered for '${primaryProviderId}'", "policy_not_found")

        if (!policy.enabled) {
            return FallbackDecision.Denied("Fallback policy disabled", "policy_disabled")
        }

        val fallbackProviderId = policy.fallbackTo
            ?: return FallbackDecision.Denied("No fallback target defined", "no_fallback_target")

        val fallbackDef = providerRegistry.get(fallbackProviderId)
            ?: return FallbackDecision.Denied("Fallback provider '${fallbackProviderId}' not found", "fallback_not_found")

        if (!fallbackDef.isFallbackEligible) {
            return FallbackDecision.Denied("Fallback provider '${fallbackProviderId}' is not fallback-eligible", "not_eligible")
        }

        val fallbackModelId = fallbackDef.supportedModels.firstOrNull()
            ?: return FallbackDecision.Denied("Fallback provider '${fallbackProviderId}' has no supported models", "no_models")

        if (task.riskClass == RiskClass.DESTRUCTIVE) {
            return FallbackDecision.Denied("Destructive tasks do not allow fallback", "destructive_blocked")
        }

        fallbackEligibilityDenial(fallbackDef, fallbackModelId, selectionContext)?.let { return it }

        return FallbackDecision.Candidate(fallbackDef, fallbackModelId)
    }

    fun checkFallbackEligibility(providerId: String): FallbackEligibility {
        val def = providerRegistry.get(providerId) ?: return FallbackEligibility(false, false)
        return FallbackEligibility(
            fallbackEnabled = fallbackPolicy[providerId]?.enabled == true,
            isFallbackEligible = def.isFallbackEligible
        )
    }

    private fun assessCandidate(
        providerDef: ProviderDefinition,
        modelId: String,
        context: ProviderSelectionContext
    ): CandidateAssessment {
        val reasons = linkedSetOf<SelectionReason>()
        val modelDef = modelRegistry.get(modelId)
        if (!providerDef.availability.equals("available", ignoreCase = true)) {
            reasons += SelectionReason.PROVIDER_UNAVAILABLE
        }
        if (!providerDef.health.isEligibleHealth()) {
            reasons += SelectionReason.PROVIDER_UNHEALTHY
        }
        if (modelDef == null) {
            reasons += SelectionReason.MODEL_NOT_REGISTERED
        } else {
            if (modelDef.accessProvider != providerDef.providerId) {
                reasons += SelectionReason.MODEL_PROVIDER_MISMATCH
            }
            if (!modelDef.availability.equals("available", ignoreCase = true)) {
                reasons += SelectionReason.MODEL_UNAVAILABLE
            }
            if (!modelDef.health.isEligibleHealth()) {
                reasons += SelectionReason.MODEL_UNHEALTHY
            }
            if (context.privacyRequirement == PrivacyRequirement.LOCAL_ONLY &&
                (!providerDef.isLocal || !modelDef.isLocal)
            ) {
                reasons += SelectionReason.LOCAL_PRIVACY_REQUIRED
            }
        }

        val missingCapabilities = if (modelDef == null) {
            context.requiredCapabilities
        } else {
            context.requiredCapabilities.filterTo(linkedSetOf()) { capability ->
                capability !in providerDef.capabilities || capability !in modelDef.capabilities
            }
        }
        if (missingCapabilities.isNotEmpty()) {
            reasons += SelectionReason.MISSING_REQUIRED_CAPABILITY
        }

        val runtimeProvider = try {
            providerRegistry.resolveProvider(providerDef)
        } catch (_: Exception) {
            null
        }
        if (runtimeProvider == null) {
            reasons += SelectionReason.RUNTIME_PROVIDER_UNAVAILABLE
        }

        if (context.requiresTools && modelDef != null && runtimeProvider != null &&
            ("tool_calling" !in providerDef.capabilities ||
                "tool_calling" !in modelDef.capabilities ||
                !modelDef.supportsTools ||
                !runtimeProvider.supportsToolCalling)
        ) {
            reasons += SelectionReason.TOOL_SUPPORT_MISSING
        }
        if (context.requiresTools && (modelDef == null || runtimeProvider == null)) {
            reasons += SelectionReason.TOOL_SUPPORT_MISSING
        }

        if (context.requiresStreaming && modelDef != null && runtimeProvider != null &&
            ("streaming" !in providerDef.capabilities ||
                "streaming" !in modelDef.capabilities ||
                !modelDef.supportsStreaming ||
                !runtimeProvider.supportsStreaming)
        ) {
            reasons += SelectionReason.STREAMING_SUPPORT_MISSING
        }
        if (context.requiresStreaming && (modelDef == null || runtimeProvider == null)) {
            reasons += SelectionReason.STREAMING_SUPPORT_MISSING
        }

        return CandidateAssessment(
            providerDef = providerDef,
            modelId = modelId,
            modelDef = modelDef,
            provider = runtimeProvider,
            reasons = reasons,
            missingCapabilities = missingCapabilities
        )
    }

    private fun buildSelectionEvidence(
        assessed: List<CandidateAssessment>,
        eligible: List<CandidateAssessment>,
        selected: CandidateAssessment?
    ): ProviderSelectionEvidence {
        val selectedCost = selected?.modelDef?.costTier
        val hasCostTie = selected != null && eligible.any { candidate ->
            candidate !== selected && candidate.modelDef?.costTier == selectedCost
        }
        val hasMoreExpensiveCandidate = selectedCost != null && eligible.any { candidate ->
            val candidateCost = candidate.modelDef?.costTier
            candidateCost == null || candidateCost > selectedCost
        }

        val candidates = assessed
            .sortedWith(compareBy({ it.providerDef.providerId }, { it.modelId }))
            .map { candidate ->
                when {
                    candidate.reasons.isNotEmpty() -> CandidateSelectionEvidence(
                        providerId = candidate.providerDef.providerId,
                        modelId = candidate.modelId,
                        status = SelectionCandidateStatus.INELIGIBLE,
                        reasons = candidate.reasons.toSet(),
                        missingCapabilities = candidate.missingCapabilities.toSet()
                    )
                    candidate === selected -> CandidateSelectionEvidence(
                        providerId = candidate.providerDef.providerId,
                        modelId = candidate.modelId,
                        status = SelectionCandidateStatus.SELECTED,
                        reasons = buildSet {
                            add(SelectionReason.REQUIREMENTS_SATISFIED)
                            if (hasMoreExpensiveCandidate) add(SelectionReason.LOWEST_KNOWN_COST)
                            if (hasCostTie) add(SelectionReason.DETERMINISTIC_TIE_BREAK)
                        }
                    )
                    else -> CandidateSelectionEvidence(
                        providerId = candidate.providerDef.providerId,
                        modelId = candidate.modelId,
                        status = SelectionCandidateStatus.LOWER_RANKED,
                        reasons = setOf(
                            when {
                                selectedCost != null && candidate.modelDef?.costTier == null ->
                                    SelectionReason.COST_UNKNOWN_RANKED_AFTER_KNOWN
                                selectedCost != null && candidate.modelDef?.costTier != null &&
                                    candidate.modelDef.costTier > selectedCost ->
                                    SelectionReason.HIGHER_COST
                                else -> SelectionReason.DETERMINISTIC_TIE_BREAK
                            }
                        )
                    )
                }
            }
        return ProviderSelectionEvidence(
            rankingRule = SelectionRankingRule.ELIGIBILITY_THEN_KNOWN_COST_THEN_DEFAULT_THEN_PROVIDER_MODEL_ID,
            candidates = candidates
        )
    }

    private fun fallbackEligibilityDenial(
        providerDef: ProviderDefinition,
        modelId: String,
        context: ProviderSelectionContext
    ): FallbackDecision.Denied? {
        if (!providerDef.availability.equals("available", ignoreCase = true) ||
            !providerDef.health.isEligibleHealth()
        ) {
            return FallbackDecision.Denied(
                "Fallback provider '${providerDef.providerId}' is unavailable",
                "availability_requirement"
            )
        }

        val modelDef = modelRegistry.get(modelId)
        if (modelDef != null &&
            (modelDef.accessProvider != providerDef.providerId ||
                !modelDef.availability.equals("available", ignoreCase = true) ||
                !modelDef.health.isEligibleHealth())
        ) {
            return FallbackDecision.Denied(
                "Fallback model '$modelId' is unavailable or does not belong to '${providerDef.providerId}'",
                "availability_requirement"
            )
        }

        if (context.privacyRequirement == PrivacyRequirement.LOCAL_ONLY &&
            (!providerDef.isLocal || modelDef?.isLocal != true)
        ) {
            return FallbackDecision.Denied(
                "Fallback candidate violates the local-only privacy requirement",
                "privacy_requirement"
            )
        }

        val lacksCapabilities = context.requiredCapabilities.any { capability ->
            capability !in providerDef.capabilities ||
                modelDef == null ||
                capability !in modelDef.capabilities
        }
        if (lacksCapabilities) {
            return FallbackDecision.Denied(
                "Fallback candidate lacks required capabilities",
                "capability_requirement"
            )
        }

        if (context.requiresTools || context.requiresStreaming) {
            val runtimeProvider = try {
                providerRegistry.resolveProvider(providerDef)
            } catch (_: Exception) {
                null
            }
            if (runtimeProvider == null) {
                return FallbackDecision.Denied(
                    "Fallback provider has no runtime implementation",
                    "runtime_requirement"
                )
            }
            if (context.requiresTools &&
                (modelDef == null ||
                    "tool_calling" !in providerDef.capabilities ||
                    "tool_calling" !in modelDef.capabilities ||
                    !modelDef.supportsTools ||
                    !runtimeProvider.supportsToolCalling)
            ) {
                return FallbackDecision.Denied(
                    "Fallback candidate lacks required tool support",
                    "tool_requirement"
                )
            }
            if (context.requiresStreaming &&
                (modelDef == null ||
                    "streaming" !in providerDef.capabilities ||
                    "streaming" !in modelDef.capabilities ||
                    !modelDef.supportsStreaming ||
                    !runtimeProvider.supportsStreaming)
            ) {
                return FallbackDecision.Denied(
                    "Fallback candidate lacks required streaming support",
                    "streaming_requirement"
                )
            }
        }

        return null
    }

    private fun String.isEligibleHealth(): Boolean =
        equals("healthy", ignoreCase = true) || equals("degraded", ignoreCase = true)

    private data class CandidateAssessment(
        val providerDef: ProviderDefinition,
        val modelId: String,
        val modelDef: ModelDefinition?,
        val provider: LanguageModelProvider?,
        val reasons: Set<SelectionReason>,
        val missingCapabilities: Set<String>
    )
}
