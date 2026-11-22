package org.aethra.core.agents

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aethra.core.providers.ProviderSelectionContext
import java.util.UUID

/**
 * An agent is a named role with a profile (tools, memory scope, provider,
 * retry budget, status). The AgentEngine owns creation, editing, deletion and
 * status; it does not execute tools itself. Execution is delegated to the core
 * runtime so agents cannot grant themselves permissions.
 */

data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String,
    val description: String,
    val provider: String = "fake",
    val model: String = "auto",
    val enabled: Boolean = true,
    val tools: List<String> = emptyList(),
    val memoryScope: String = "global",
    val retryBudget: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val status: AgentStatus = AgentStatus.IDLE
)

enum class AgentStatus {
    CREATED, IDLE, EXECUTING, WAITING_APPROVAL, FAILED, STOPPED
}

/**
 * Manages the agent lifecycle and policy enforcement. New agents start with a
 * narrow role and only the tools the caller grants. Agents cannot escalate their
 * own permissions.
 */
class AgentEngine(
    private val persistence: AgentPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activationPolicy: GeneratedAgentActivationPolicy = DenyGeneratedAgentActivationPolicy
) {
    private val agents = LinkedHashMap<String, Agent>()
    private val proposals = LinkedHashMap<String, GeneratedAgentProposal>()
    private val stateLock = Any()
    private val mutationMutex = Mutex()

    /** Restore the registry before serving requests. Storage errors fail closed. */
    suspend fun load() = mutationMutex.withLock {
        val restored = persistence.loadRegistry()
        val restoredAgents = restored.agents.associateBy { it.id }
        val restoredProposals = restored.proposals.associateBy { it.id }
        synchronized(stateLock) {
            agents.clear()
            proposals.clear()
            restoredAgents.toSortedMap().forEach { (id, agent) -> agents[id] = agent.snapshot() }
            restoredProposals.toSortedMap().forEach { (id, proposal) ->
                proposals[id] = proposal.detachedCopy()
            }
        }
    }

    /** Register only new IDs. Returns false for a duplicate without overwriting it. */
    suspend fun register(agent: Agent): Boolean = mutationMutex.withLock {
        val candidate = synchronized(stateLock) {
            if (agents.containsKey(agent.id)) return@withLock false
            agents.values.map { it.snapshot() } + agent.snapshot()
        }
        persistThenReplace(candidate)
        true
    }

    /** Create retains the prior upsert behavior for existing IDs. */
    suspend fun create(agent: Agent): Agent = mutationMutex.withLock {
        val existing = synchronized(stateLock) { agents[agent.id]?.snapshot() }
        val merged = existing?.copy(
            name = agent.name, role = agent.role, description = agent.description,
            provider = agent.provider, model = agent.model, enabled = agent.enabled,
            tools = agent.tools.toList(), memoryScope = agent.memoryScope,
            retryBudget = agent.retryBudget
        ) ?: agent.snapshot()
        val candidate = synchronized(stateLock) {
            agents.values.filterNot { it.id == agent.id }.map { it.snapshot() } + merged
        }
        persistThenReplace(candidate)
        merged.snapshot()
    }

    fun all(): List<Agent> = synchronized(stateLock) {
        agents.values.sortedBy { it.id }.map { it.snapshot() }
    }

    fun get(id: String): Agent? = synchronized(stateLock) { agents[id]?.snapshot() }

    /** Create a durable, non-active proposal. It never enters [all] until explicit activation. */
    suspend fun proposeAgent(
        proposedAgent: Agent,
        requiredCapabilities: Set<String> = emptySet(),
        requestedTools: List<String> = proposedAgent.tools,
        requestedSkills: List<String> = emptyList(),
        providerSelectionContext: ProviderSelectionContext = ProviderSelectionContext(),
        evidenceIds: List<String> = emptyList(),
        proposalId: String = UUID.randomUUID().toString()
    ): GeneratedAgentProposal = mutationMutex.withLock {
        require(proposalId.isNotBlank()) { "proposalId must not be blank" }
        val duplicate = synchronized(stateLock) { proposals.containsKey(proposalId) }
        require(!duplicate) { "Proposal '$proposalId' already exists" }

        val now = clock()
        val stableTools = requestedTools.distinct().toList()
        val proposal = GeneratedAgentProposal(
            id = proposalId,
            proposedAgent = proposedAgent.copy(
                enabled = false,
                tools = stableTools,
                status = AgentStatus.CREATED
            ),
            requiredCapabilities = requiredCapabilities.toSet(),
            requestedTools = stableTools,
            requestedSkills = requestedSkills.distinct().toList(),
            providerSelectionContext = providerSelectionContext.copy(
                requiredCapabilities = providerSelectionContext.requiredCapabilities.toSet()
            ),
            state = GeneratedAgentProposalState.PROPOSED,
            evidenceIds = evidenceIds.distinct().toList(),
            createdAt = now,
            updatedAt = now
        ).detachedCopy()
        val activeSnapshot = synchronized(stateLock) { agents.values.map { it.snapshot() } }
        val proposalCandidate = synchronized(stateLock) {
            proposals.values.map { it.detachedCopy() } + proposal
        }
        persistThenReplace(activeSnapshot, proposalCandidate)
        proposal.detachedCopy()
    }

    fun getProposal(id: String): GeneratedAgentProposal? = synchronized(stateLock) {
        proposals[id]?.detachedCopy()
    }

    fun allProposals(): List<GeneratedAgentProposal> = synchronized(stateLock) {
        proposals.values.sortedBy { it.id }.map { it.detachedCopy() }
    }

    suspend fun validateProposal(
        proposalId: String,
        validator: GeneratedAgentValidator
    ): GeneratedAgentProposal = mutationMutex.withLock {
        val proposal = proposalForTransition(proposalId, GeneratedAgentProposalState.PROPOSED)
        val outcome = validator.validate(proposal.detachedCopy())
        val now = clock()
        val validation = GeneratedAgentValidationRecord(
            proposalId = proposal.id,
            passed = outcome.passed,
            summary = outcome.summary,
            evidenceIds = outcome.evidenceIds.distinct().toList(),
            validatedAt = now
        )
        val updated = proposal.copy(
            state = if (outcome.passed) {
                GeneratedAgentProposalState.VALIDATED
            } else {
                GeneratedAgentProposalState.VALIDATION_FAILED
            },
            stateReason = outcome.summary.takeUnless { outcome.passed },
            validation = validation,
            evidenceIds = (proposal.evidenceIds + validation.evidenceIds).distinct(),
            updatedAt = now
        ).detachedCopy()
        persistProposalUpdate(updated)
    }

    suspend fun requestProposalReview(proposalId: String): GeneratedAgentProposal =
        mutationMutex.withLock {
            val proposal = proposalForTransition(proposalId, GeneratedAgentProposalState.VALIDATED)
            val updated = proposal.copy(
                state = GeneratedAgentProposalState.REVIEW_REQUIRED,
                stateReason = null,
                updatedAt = clock()
            ).detachedCopy()
            persistProposalUpdate(updated)
        }

    suspend fun reviewProposal(
        proposalId: String,
        decision: GeneratedAgentReviewDecision,
        reviewerRef: String,
        reason: String,
        evidenceIds: List<String> = emptyList()
    ): GeneratedAgentProposal = mutationMutex.withLock {
        require(reviewerRef.isNotBlank()) { "reviewerRef must not be blank" }
        require(reason.isNotBlank()) { "review reason must not be blank" }
        val proposal = proposalForTransition(proposalId, GeneratedAgentProposalState.REVIEW_REQUIRED)
        val now = clock()
        val review = GeneratedAgentReviewRecord(
            proposalId = proposal.id,
            decision = decision,
            reviewerRef = reviewerRef,
            reason = reason,
            evidenceIds = evidenceIds.distinct().toList(),
            reviewedAt = now
        )
        val updated = proposal.copy(
            state = if (decision == GeneratedAgentReviewDecision.APPROVE) {
                GeneratedAgentProposalState.APPROVED
            } else {
                GeneratedAgentProposalState.REJECTED
            },
            stateReason = reason,
            review = review,
            evidenceIds = (proposal.evidenceIds + review.evidenceIds).distinct(),
            updatedAt = now
        ).detachedCopy()
        persistProposalUpdate(updated)
    }

    suspend fun disableProposal(
        proposalId: String,
        reason: String,
        evidenceIds: List<String> = emptyList()
    ): GeneratedAgentProposal = mutationMutex.withLock {
        require(reason.isNotBlank()) { "disable reason must not be blank" }
        val proposal = synchronized(stateLock) { proposals[proposalId]?.detachedCopy() }
            ?: throw NoSuchElementException("No proposal with id '$proposalId'")
        check(proposal.state !in setOf(
            GeneratedAgentProposalState.ACTIVATED,
            GeneratedAgentProposalState.DISABLED
        )) { "Proposal '${proposal.id}' cannot be disabled from ${proposal.state}" }
        val updated = proposal.copy(
            state = GeneratedAgentProposalState.DISABLED,
            stateReason = reason,
            evidenceIds = (proposal.evidenceIds + evidenceIds).distinct(),
            updatedAt = clock()
        ).detachedCopy()
        persistProposalUpdate(updated)
    }

    /**
     * Explicitly activate one approved proposal. The candidate active agent and
     * ACTIVATED proposal are written in one snapshot before either is published
     * to in-memory readers. No activation event exists in the current contract.
     */
    suspend fun activateProposal(
        proposalId: String
    ): Agent = mutationMutex.withLock {
        val proposal = proposalForTransition(proposalId, GeneratedAgentProposalState.APPROVED)
        val duplicateAgent = synchronized(stateLock) { agents.containsKey(proposal.proposedAgent.id) }
        if (duplicateAgent) {
            throw GeneratedAgentActivationDeniedException(
                "duplicate_agent_id",
                "Active agent '${proposal.proposedAgent.id}' already exists"
            )
        }

        val eligibility = activationPolicy.evaluate(proposal.detachedCopy())
        if (!eligibility.allowed) {
            throw GeneratedAgentActivationDeniedException(
                eligibility.blockedBy ?: "policy_requirement",
                eligibility.reason
            )
        }

        val now = clock()
        val activatedAgent = proposal.proposedAgent.copy(
            enabled = true,
            tools = proposal.requestedTools.toList(),
            updatedAt = now,
            status = AgentStatus.IDLE
        ).snapshot()
        val activatedProposal = proposal.copy(
            state = GeneratedAgentProposalState.ACTIVATED,
            stateReason = eligibility.reason,
            evidenceIds = (proposal.evidenceIds + eligibility.evidenceIds).distinct(),
            updatedAt = now,
            activatedAt = now
        ).detachedCopy()

        val activeCandidate = synchronized(stateLock) {
            agents.values.map { it.snapshot() } + activatedAgent
        }
        val proposalCandidate = synchronized(stateLock) {
            proposals.values.filterNot { it.id == proposal.id }.map { it.detachedCopy() } + activatedProposal
        }
        persistThenReplace(activeCandidate, proposalCandidate)
        activatedAgent.snapshot()
    }

    suspend fun update(agent: Agent): Agent = mutationMutex.withLock {
        val existing = synchronized(stateLock) { agents[agent.id]?.snapshot() }
            ?: return@withLock agent.snapshot()
        val updated = existing.copy(
            name = agent.name, role = agent.role, description = agent.description,
            provider = agent.provider, model = agent.model, enabled = agent.enabled,
            tools = agent.tools.toList(), memoryScope = agent.memoryScope,
            retryBudget = agent.retryBudget
        )
        val candidate = synchronized(stateLock) {
            agents.values.filterNot { it.id == agent.id }.map { it.snapshot() } + updated
        }
        persistThenReplace(candidate)
        updated.snapshot()
    }

    suspend fun delete(id: String): Boolean = mutationMutex.withLock {
        val candidate = synchronized(stateLock) {
            if (!agents.containsKey(id)) return@withLock false
            agents.values.filterNot { it.id == id }.map { it.snapshot() }
        }
        persistThenReplace(candidate)
        true
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = mutationMutex.withLock {
        val agent = synchronized(stateLock) { agents[id]?.snapshot() }
            ?: return@withLock false
        val updated = agent.copy(enabled = enabled)
        val candidate = synchronized(stateLock) {
            agents.values.filterNot { it.id == id }.map { it.snapshot() } + updated
        }
        persistThenReplace(candidate)
        true
    }

    /** Serialize writes and publish in-memory state only after one durable snapshot succeeds. */
    private suspend fun persistThenReplace(
        candidate: List<Agent>,
        proposalCandidate: List<GeneratedAgentProposal> = synchronized(stateLock) {
            proposals.values.map { it.detachedCopy() }
        }
    ) {
        val stable = candidate.map { it.snapshot() }.sortedBy { it.id }
        val stableProposals = proposalCandidate.map { it.detachedCopy() }.sortedBy { it.id }
        persistence.saveRegistry(AgentRegistrySnapshot(stable, stableProposals))
        synchronized(stateLock) {
            agents.clear()
            proposals.clear()
            stable.forEach { agents[it.id] = it.snapshot() }
            stableProposals.forEach { proposals[it.id] = it.detachedCopy() }
        }
    }

    private fun proposalForTransition(
        proposalId: String,
        requiredState: GeneratedAgentProposalState
    ): GeneratedAgentProposal {
        val proposal = synchronized(stateLock) { proposals[proposalId]?.detachedCopy() }
            ?: throw NoSuchElementException("No proposal with id '$proposalId'")
        check(proposal.state == requiredState) {
            "Proposal '${proposal.id}' must be $requiredState, but is ${proposal.state}"
        }
        return proposal
    }

    private suspend fun persistProposalUpdate(updated: GeneratedAgentProposal): GeneratedAgentProposal {
        val activeSnapshot = synchronized(stateLock) { agents.values.map { it.snapshot() } }
        val proposalCandidate = synchronized(stateLock) {
            proposals.values.filterNot { it.id == updated.id }.map { it.detachedCopy() } + updated
        }
        persistThenReplace(activeSnapshot, proposalCandidate)
        return updated.detachedCopy()
    }

    private fun Agent.snapshot() = copy(tools = tools.toList())
}
