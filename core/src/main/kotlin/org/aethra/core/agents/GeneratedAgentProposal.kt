package org.aethra.core.agents

import org.aethra.core.providers.ProviderSelectionContext

/** Durable lifecycle for generated definitions before they may enter the active registry. */
enum class GeneratedAgentProposalState {
    PROPOSED,
    VALIDATED,
    REVIEW_REQUIRED,
    APPROVED,
    ACTIVATED,
    VALIDATION_FAILED,
    REJECTED,
    DISABLED
}

enum class GeneratedAgentValidationStatus { NOT_RUN, PASSED, FAILED }
enum class GeneratedAgentReviewStatus { NOT_REQUESTED, REQUIRED, APPROVED, REJECTED }
enum class GeneratedAgentActivationStatus { INACTIVE, ACTIVE, DISABLED }
enum class GeneratedAgentReviewDecision { APPROVE, REJECT }

data class GeneratedAgentValidationOutcome(
    val passed: Boolean,
    val summary: String,
    val evidenceIds: List<String> = emptyList()
)

/**
 * Logical isolation boundary: validators receive only a detached proposal and
 * return evidence. This contract does not claim process or container isolation.
 */
fun interface GeneratedAgentValidator {
    suspend fun validate(proposal: GeneratedAgentProposal): GeneratedAgentValidationOutcome
}

data class GeneratedAgentValidationRecord(
    val proposalId: String,
    val passed: Boolean,
    val summary: String,
    val evidenceIds: List<String> = emptyList(),
    val validatedAt: Long
)

data class GeneratedAgentReviewRecord(
    val proposalId: String,
    val decision: GeneratedAgentReviewDecision,
    val reviewerRef: String,
    val reason: String,
    val evidenceIds: List<String> = emptyList(),
    val reviewedAt: Long
)

data class GeneratedAgentProposal(
    val id: String,
    val proposedAgent: Agent,
    val requiredCapabilities: Set<String> = emptySet(),
    val requestedTools: List<String> = emptyList(),
    val requestedSkills: List<String> = emptyList(),
    val providerSelectionContext: ProviderSelectionContext = ProviderSelectionContext(),
    val state: GeneratedAgentProposalState = GeneratedAgentProposalState.PROPOSED,
    val stateReason: String? = null,
    val validation: GeneratedAgentValidationRecord? = null,
    val review: GeneratedAgentReviewRecord? = null,
    val evidenceIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val activatedAt: Long? = null
) {
    val validationStatus: GeneratedAgentValidationStatus
        get() = when (validation?.passed) {
            true -> GeneratedAgentValidationStatus.PASSED
            false -> GeneratedAgentValidationStatus.FAILED
            null -> GeneratedAgentValidationStatus.NOT_RUN
        }

    val reviewStatus: GeneratedAgentReviewStatus
        get() = when (review?.decision) {
            GeneratedAgentReviewDecision.APPROVE -> GeneratedAgentReviewStatus.APPROVED
            GeneratedAgentReviewDecision.REJECT -> GeneratedAgentReviewStatus.REJECTED
            null -> if (state == GeneratedAgentProposalState.REVIEW_REQUIRED) {
                GeneratedAgentReviewStatus.REQUIRED
            } else {
                GeneratedAgentReviewStatus.NOT_REQUESTED
            }
        }

    val activationStatus: GeneratedAgentActivationStatus
        get() = when (state) {
            GeneratedAgentProposalState.ACTIVATED -> GeneratedAgentActivationStatus.ACTIVE
            GeneratedAgentProposalState.DISABLED -> GeneratedAgentActivationStatus.DISABLED
            else -> GeneratedAgentActivationStatus.INACTIVE
        }
}

/** One storage record keeps activation durable as a single write. */
internal data class AgentRegistrySnapshot(
    val agents: List<Agent>,
    val proposals: List<GeneratedAgentProposal>
)

internal fun GeneratedAgentProposal.detachedCopy(): GeneratedAgentProposal = copy(
    proposedAgent = proposedAgent.copy(tools = proposedAgent.tools.toList()),
    requiredCapabilities = requiredCapabilities.toSet(),
    requestedTools = requestedTools.toList(),
    requestedSkills = requestedSkills.toList(),
    providerSelectionContext = providerSelectionContext.copy(
        requiredCapabilities = providerSelectionContext.requiredCapabilities.toSet()
    ),
    validation = validation?.copy(evidenceIds = validation.evidenceIds.toList()),
    review = review?.copy(evidenceIds = review.evidenceIds.toList()),
    evidenceIds = evidenceIds.toList()
)

/** Reject persisted combinations that could bypass validation/review on reload. */
internal fun GeneratedAgentProposal.requireConsistentLifecycle(): GeneratedAgentProposal {
    require(id.isNotBlank()) { "Proposal ID must not be blank" }
    require(proposedAgent.id.isNotBlank()) { "Proposed agent ID must not be blank" }
    require(!proposedAgent.enabled) { "Proposed agent must remain inactive inside proposal state" }
    require(proposedAgent.tools == requestedTools) { "Proposed agent tools must match requested tools" }
    require(updatedAt >= createdAt) { "Proposal update time precedes creation time" }

    val validationPassed = validation?.passed == true
    val validationFailed = validation?.passed == false
    val reviewApproved = review?.decision == GeneratedAgentReviewDecision.APPROVE
    val reviewRejected = review?.decision == GeneratedAgentReviewDecision.REJECT
    when (state) {
        GeneratedAgentProposalState.PROPOSED ->
            require(validation == null && review == null && activatedAt == null)
        GeneratedAgentProposalState.VALIDATED,
        GeneratedAgentProposalState.REVIEW_REQUIRED ->
            require(validationPassed && review == null && activatedAt == null)
        GeneratedAgentProposalState.APPROVED ->
            require(validationPassed && reviewApproved && activatedAt == null)
        GeneratedAgentProposalState.ACTIVATED ->
            require(validationPassed && reviewApproved && activatedAt != null)
        GeneratedAgentProposalState.VALIDATION_FAILED ->
            require(validationFailed && review == null && activatedAt == null)
        GeneratedAgentProposalState.REJECTED ->
            require(validationPassed && reviewRejected && activatedAt == null)
        GeneratedAgentProposalState.DISABLED ->
            require(activatedAt == null)
    }
    return this
}
