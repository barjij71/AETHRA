package org.aethra.core.agents

import org.aethra.core.domain.RiskClass
import org.aethra.core.providers.ConversationProviderConfig
import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.providers.RoutingDecision
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.task.Task
import org.aethra.core.tools.ToolRegistry

data class GeneratedAgentActivationEligibility(
    val allowed: Boolean,
    val reason: String,
    val evidenceIds: List<String> = emptyList(),
    val blockedBy: String? = null
)

/** A trusted Core policy is mandatory for every explicit activation call. */
fun interface GeneratedAgentActivationPolicy {
    suspend fun evaluate(proposal: GeneratedAgentProposal): GeneratedAgentActivationEligibility
}

internal object DenyGeneratedAgentActivationPolicy : GeneratedAgentActivationPolicy {
    override suspend fun evaluate(
        proposal: GeneratedAgentProposal
    ): GeneratedAgentActivationEligibility = GeneratedAgentActivationEligibility(
        allowed = false,
        reason = "No trusted generated-agent activation policy is configured",
        blockedBy = "policy_not_configured"
    )
}

class GeneratedAgentActivationDeniedException(
    val blockedBy: String,
    message: String
) : IllegalStateException(message)

/**
 * Reuses the existing registries and provider router. Activation only records
 * tool/skill allowlists; actual tool execution still goes through the existing
 * ExecutionPolicy and PermissionBroker, so activation grants no permission.
 */
class RegistryBackedGeneratedAgentActivationPolicy(
    private val toolRegistry: ToolRegistry,
    private val skillRegistry: SkillRegistry,
    private val providerModelRouter: ProviderModelRouter
) : GeneratedAgentActivationPolicy {
    override suspend fun evaluate(
        proposal: GeneratedAgentProposal
    ): GeneratedAgentActivationEligibility {
        val tools = proposal.requestedTools.map { toolId -> toolId to toolRegistry.get(toolId) }
        val missingTools = tools.filter { it.second == null }.map { it.first }
        if (missingTools.isNotEmpty()) {
            return denied("tool_requirement", "Unknown requested tools: ${missingTools.sorted().joinToString()}")
        }

        val skills = proposal.requestedSkills.map { skillId -> skillId to skillRegistry.get(skillId) }
        val unavailableSkills = skills.filter { it.second?.isActive != true }.map { it.first }
        if (unavailableSkills.isNotEmpty()) {
            return denied(
                "skill_requirement",
                "Requested skills are missing or inactive: ${unavailableSkills.sorted().joinToString()}"
            )
        }

        val availableCapabilities = buildSet {
            tools.mapNotNull { it.second }.flatMapTo(this) { tool ->
                tool.requiredCapabilities.map { it.lowercase() }
            }
            skills.mapNotNull { it.second }.flatMapTo(this) { skill ->
                skill.capabilities.map { it.lowercase() }
            }
        }
        val missingCapabilities = proposal.requiredCapabilities
            .filter { it.lowercase() !in availableCapabilities }
            .sorted()
        if (missingCapabilities.isNotEmpty()) {
            return denied(
                "capability_requirement",
                "Required capabilities are unavailable: ${missingCapabilities.joinToString()}"
            )
        }

        val providerId = proposal.proposedAgent.provider.explicitRequirement()
        val modelId = proposal.proposedAgent.model.explicitRequirement()
        val route = providerModelRouter.resolve(
            ConversationProviderConfig(
                conversationId = "generated-agent:${proposal.id}",
                preferredProviderId = providerId,
                preferredModelId = modelId,
                hasCustomSelection = providerId != null || modelId != null,
                selectionContext = proposal.providerSelectionContext
            ),
            Task("validate generated agent provider eligibility", riskClass = RiskClass.READ_ONLY)
        )
        if (route !is RoutingDecision.Success) {
            val detail = when (route) {
                is RoutingDecision.Rejected -> route.reason
                is RoutingDecision.UnableToRoute -> route.reason
                else -> "provider/model selection failed"
            }
            return denied("provider_requirement", "Provider/model policy rejected activation: $detail")
        }

        val evidence = buildList {
            proposal.requestedTools.sorted().forEach { add("tool:$it") }
            proposal.requestedSkills.sorted().forEach { add("skill:$it") }
            add("provider-selection:${route.providerId}/${route.modelId}")
        }
        return GeneratedAgentActivationEligibility(
            allowed = true,
            reason = "Registry and provider eligibility checks passed",
            evidenceIds = evidence
        )
    }

    private fun denied(blockedBy: String, reason: String) =
        GeneratedAgentActivationEligibility(
            allowed = false,
            reason = reason,
            blockedBy = blockedBy
        )

    private fun String.explicitRequirement(): String? =
        trim().takeUnless { it.isEmpty() || it.equals("auto", ignoreCase = true) }
}
