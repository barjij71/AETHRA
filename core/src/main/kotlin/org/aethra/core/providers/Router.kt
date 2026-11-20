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
        val provider: LanguageModelProvider?
    ) : RoutingDecision()
    data class Rejected(
        val reason: String,
        val policy: RiskClass
    ) : RoutingDecision()
    data class UnableToRoute(
        val reason: String
    ) : RoutingDecision()
}

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
    val hasCustomSelection: Boolean = false
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

    fun resolve(config: ConversationProviderConfig, task: Task): RoutingDecision {
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

        val runtimeProvider = providerRegistry.resolveProvider(providerDef)
            ?: return RoutingDecision.UnableToRoute("Provider '${preferredProviderId}' has no registered runtime implementation")

        return RoutingDecision.Success(
            providerId = providerDef.providerId,
            modelId = modelId,
            usedFallback = false,
            provider = runtimeProvider
        )
    }

    /** Register a fallback policy for a provider. */
    fun enableFallback(policy: FallbackPolicy) {
        fallbackPolicy[policy.providerId] = policy
    }

    fun getFallbackCandidate(
        primaryProviderId: String,
        task: Task
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

        return FallbackDecision.Candidate(fallbackDef, fallbackModelId)
    }

    fun checkFallbackEligibility(providerId: String): FallbackEligibility {
        val def = providerRegistry.get(providerId) ?: return FallbackEligibility(false, false)
        return FallbackEligibility(
            fallbackEnabled = fallbackPolicy[providerId]?.enabled == true,
            isFallbackEligible = def.isFallbackEligible
        )
    }
}
