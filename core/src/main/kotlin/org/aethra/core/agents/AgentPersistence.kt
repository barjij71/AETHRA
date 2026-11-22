package org.aethra.core.agents

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.aethra.core.providers.PrivacyRequirement
import org.aethra.core.providers.ProviderSelectionContext
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.aethra.core.task.PersistenceException

/** Durable serialization boundary for the reusable-agent registry. */
class AgentPersistence(private val storage: Storage) {
    private val registryKey = "aethra.agents.v1"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Compatibility surface for active-registry callers; preserves durable proposals. */
    suspend fun saveAll(agents: List<Agent>) {
        val proposals = loadRegistry().proposals
        saveRegistry(AgentRegistrySnapshot(agents, proposals))
    }

    internal suspend fun saveRegistry(snapshot: AgentRegistrySnapshot) {
        val persisted = PersistedAgentRegistry(
            schemaVersion = SCHEMA_VERSION,
            agents = snapshot.agents.map { it.toPersistedAgent() },
            proposals = snapshot.proposals.map { it.toPersistedProposal() }
        )
        try {
            storage.setString(registryKey, json.encodeToString(persisted))
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Agent registry write failed: ${e.message}", e)
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Agent registry storage is corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Agent registry storage is incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Agent registry storage is partial: ${e.message}", e)
        }
    }

    suspend fun loadAll(): List<Agent> = loadRegistry().agents

    /** Empty or absent storage means a new registry; failures and invalid data fail closed. */
    internal suspend fun loadRegistry(): AgentRegistrySnapshot {
        val raw = try {
            storage.getString(registryKey)
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Agent registry read failed: ${e.message}", e)
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Agent registry storage is corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Agent registry storage is incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Agent registry storage is partial: ${e.message}", e)
        }
        if (raw == null || raw.isBlank()) return AgentRegistrySnapshot(emptyList(), emptyList())

        try {
            val registry = json.decodeFromString<PersistedAgentRegistry>(raw)
            if (registry.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
                throw PersistenceException.Incompatible(
                    "Agent registry schema ${registry.schemaVersion} is not supported"
                )
            }
            val restoredAgents = registry.agents.map { it.toAgent() }
            if (restoredAgents.map { it.id }.toSet().size != restoredAgents.size) {
                throw PersistenceException.Corrupt("Agent registry contains duplicate IDs")
            }
            val restoredProposals = registry.proposals.map { it.toProposal() }
            if (restoredProposals.map { it.id }.toSet().size != restoredProposals.size) {
                throw PersistenceException.Corrupt("Agent registry contains duplicate proposal IDs")
            }
            return AgentRegistrySnapshot(restoredAgents, restoredProposals)
        } catch (e: PersistenceException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            throw PersistenceException.Corrupt("Agent registry data could not be decoded", e)
        } catch (e: Exception) {
            throw PersistenceException.Corrupt("Agent registry data is invalid", e)
        }
    }

    private fun Agent.toPersistedAgent() = PersistedAgent(
        id = id,
        name = name,
        role = role,
        description = description,
        provider = provider,
        model = model,
        enabled = enabled,
        tools = tools.toList(),
        memoryScope = memoryScope,
        retryBudget = retryBudget,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = status.name
    )

    private fun PersistedAgent.toAgent() = Agent(
        id = id,
        name = name,
        role = role,
        description = description,
        provider = provider,
        model = model,
        enabled = enabled,
        tools = tools.toList(),
        memoryScope = memoryScope,
        retryBudget = retryBudget,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = AgentStatus.valueOf(status)
    )

    private fun GeneratedAgentProposal.toPersistedProposal() = PersistedGeneratedAgentProposal(
        id = id,
        proposedAgent = proposedAgent.toPersistedAgent(),
        requiredCapabilities = requiredCapabilities.sorted(),
        requestedTools = requestedTools.toList(),
        requestedSkills = requestedSkills.toList(),
        providerRequiredCapabilities = providerSelectionContext.requiredCapabilities.sorted(),
        providerRequiresTools = providerSelectionContext.requiresTools,
        providerRequiresStreaming = providerSelectionContext.requiresStreaming,
        privacyRequirement = providerSelectionContext.privacyRequirement.name,
        state = state.name,
        stateReason = stateReason,
        validation = validation?.let {
            PersistedGeneratedAgentValidation(
                proposalId = it.proposalId,
                passed = it.passed,
                summary = it.summary,
                evidenceIds = it.evidenceIds.toList(),
                validatedAt = it.validatedAt
            )
        },
        review = review?.let {
            PersistedGeneratedAgentReview(
                proposalId = it.proposalId,
                decision = it.decision.name,
                reviewerRef = it.reviewerRef,
                reason = it.reason,
                evidenceIds = it.evidenceIds.toList(),
                reviewedAt = it.reviewedAt
            )
        },
        evidenceIds = evidenceIds.toList(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        activatedAt = activatedAt
    )

    private fun PersistedGeneratedAgentProposal.toProposal() = GeneratedAgentProposal(
        id = id,
        proposedAgent = proposedAgent.toAgent(),
        requiredCapabilities = requiredCapabilities.toSet(),
        requestedTools = requestedTools.toList(),
        requestedSkills = requestedSkills.toList(),
        providerSelectionContext = ProviderSelectionContext(
            requiredCapabilities = providerRequiredCapabilities.toSet(),
            requiresTools = providerRequiresTools,
            requiresStreaming = providerRequiresStreaming,
            privacyRequirement = PrivacyRequirement.valueOf(privacyRequirement)
        ),
        state = GeneratedAgentProposalState.valueOf(state),
        stateReason = stateReason,
        validation = validation?.let {
            GeneratedAgentValidationRecord(
                proposalId = it.proposalId,
                passed = it.passed,
                summary = it.summary,
                evidenceIds = it.evidenceIds.toList(),
                validatedAt = it.validatedAt
            )
        },
        review = review?.let {
            GeneratedAgentReviewRecord(
                proposalId = it.proposalId,
                decision = GeneratedAgentReviewDecision.valueOf(it.decision),
                reviewerRef = it.reviewerRef,
                reason = it.reason,
                evidenceIds = it.evidenceIds.toList(),
                reviewedAt = it.reviewedAt
            )
        },
        evidenceIds = evidenceIds.toList(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        activatedAt = activatedAt
    ).also { proposal ->
        proposal.validation?.let {
            if (it.proposalId != proposal.id) {
                throw PersistenceException.Corrupt("Validation record proposal ID does not match")
            }
        }
        proposal.review?.let {
            if (it.proposalId != proposal.id) {
                throw PersistenceException.Corrupt("Review record proposal ID does not match")
            }
        }
    }.requireConsistentLifecycle()

    @Serializable
    private data class PersistedAgentRegistry(
        val schemaVersion: Int,
        val agents: List<PersistedAgent>,
        val proposals: List<PersistedGeneratedAgentProposal> = emptyList()
    )

    @Serializable
    private data class PersistedAgent(
        val id: String,
        val name: String,
        val role: String,
        val description: String,
        val provider: String,
        val model: String,
        val enabled: Boolean,
        val tools: List<String>,
        val memoryScope: String,
        val retryBudget: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val status: String
    )

    @Serializable
    private data class PersistedGeneratedAgentProposal(
        val id: String,
        val proposedAgent: PersistedAgent,
        val requiredCapabilities: List<String> = emptyList(),
        val requestedTools: List<String> = emptyList(),
        val requestedSkills: List<String> = emptyList(),
        val providerRequiredCapabilities: List<String> = emptyList(),
        val providerRequiresTools: Boolean = false,
        val providerRequiresStreaming: Boolean = false,
        val privacyRequirement: String = PrivacyRequirement.ALLOW_REMOTE.name,
        val state: String,
        val stateReason: String? = null,
        val validation: PersistedGeneratedAgentValidation? = null,
        val review: PersistedGeneratedAgentReview? = null,
        val evidenceIds: List<String> = emptyList(),
        val createdAt: Long,
        val updatedAt: Long,
        val activatedAt: Long? = null
    )

    @Serializable
    private data class PersistedGeneratedAgentValidation(
        val proposalId: String,
        val passed: Boolean,
        val summary: String,
        val evidenceIds: List<String> = emptyList(),
        val validatedAt: Long
    )

    @Serializable
    private data class PersistedGeneratedAgentReview(
        val proposalId: String,
        val decision: String,
        val reviewerRef: String,
        val reason: String,
        val evidenceIds: List<String> = emptyList(),
        val reviewedAt: Long
    )

    private companion object {
        const val SCHEMA_VERSION = 2
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, SCHEMA_VERSION)
    }
}
