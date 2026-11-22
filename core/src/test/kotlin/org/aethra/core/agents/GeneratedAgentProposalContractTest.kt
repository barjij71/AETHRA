package org.aethra.core.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.aethra.core.providers.LanguageModelProvider
import org.aethra.core.providers.ModelDefinition
import org.aethra.core.providers.ModelRegistry
import org.aethra.core.providers.PrivacyRequirement
import org.aethra.core.providers.ProviderDefinition
import org.aethra.core.providers.ProviderFactory
import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.providers.ProviderRegistry
import org.aethra.core.providers.ProviderSelectionContext
import org.aethra.core.security.AuditLog
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.aethra.core.task.PersistenceException
import org.aethra.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class GeneratedAgentProposalContractTest {
    @Test
    fun `proposal creation captures metadata and starts outside active registry`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))

        val proposal = engine.proposeAgent(
            proposedAgent = generatedAgent("generated-agent"),
            requiredCapabilities = setOf("file.read"),
            requestedTools = listOf("file.read"),
            requestedSkills = listOf("review-skill"),
            providerSelectionContext = ProviderSelectionContext(
                requiredCapabilities = setOf("reasoning"),
                privacyRequirement = PrivacyRequirement.LOCAL_ONLY
            ),
            evidenceIds = listOf("source:evidence-1"),
            proposalId = "proposal-1"
        )

        assertEquals("proposal-1", proposal.id)
        assertEquals("generated-agent", proposal.proposedAgent.id)
        assertEquals("planner", proposal.proposedAgent.role)
        assertEquals("generated planning agent", proposal.proposedAgent.description)
        assertEquals(setOf("file.read"), proposal.requiredCapabilities)
        assertEquals(listOf("file.read"), proposal.requestedTools)
        assertEquals(listOf("review-skill"), proposal.requestedSkills)
        assertEquals(GeneratedAgentProposalState.PROPOSED, proposal.state)
        assertEquals(GeneratedAgentValidationStatus.NOT_RUN, proposal.validationStatus)
        assertEquals(GeneratedAgentReviewStatus.NOT_REQUESTED, proposal.reviewStatus)
        assertEquals(GeneratedAgentActivationStatus.INACTIVE, proposal.activationStatus)
        assertFalse(proposal.proposedAgent.enabled)
        assertEquals(AgentStatus.CREATED, proposal.proposedAgent.status)
        assertTrue(engine.all().isEmpty())
        assertNull(engine.get("generated-agent"))
    }

    @Test
    fun `proposal and proposed agent identities remain stable across reads`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val created = engine.proposeAgent(
            proposedAgent = generatedAgent("stable-agent"),
            proposalId = "stable-proposal"
        )

        val first = engine.getProposal(created.id)
        val second = engine.getProposal(created.id)

        assertEquals("stable-proposal", first?.id)
        assertEquals(first?.id, second?.id)
        assertEquals("stable-agent", first?.proposedAgent?.id)
        assertEquals(first?.proposedAgent?.id, second?.proposedAgent?.id)
    }

    @Test
    fun `proposal snapshots do not retain caller mutable collections`() = runBlocking {
        val capabilities = mutableSetOf("file.read")
        val tools = mutableListOf("file.read")
        val skills = mutableListOf("review-skill")
        val modelCapabilities = mutableSetOf("reasoning")
        val evidence = mutableListOf("evidence-1")
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        engine.proposeAgent(
            proposedAgent = generatedAgent("snapshot-agent"),
            requiredCapabilities = capabilities,
            requestedTools = tools,
            requestedSkills = skills,
            providerSelectionContext = ProviderSelectionContext(requiredCapabilities = modelCapabilities),
            evidenceIds = evidence,
            proposalId = "snapshot-proposal"
        )

        capabilities += "file.write"
        tools += "file.write"
        skills += "mutated-skill"
        modelCapabilities += "vision"
        evidence += "evidence-2"

        val stored = engine.getProposal("snapshot-proposal")!!
        assertEquals(setOf("file.read"), stored.requiredCapabilities)
        assertEquals(listOf("file.read"), stored.requestedTools)
        assertEquals(listOf("review-skill"), stored.requestedSkills)
        assertEquals(setOf("reasoning"), stored.providerSelectionContext.requiredCapabilities)
        assertEquals(listOf("evidence-1"), stored.evidenceIds)
    }

    @Test
    fun `proposal and evidence survive engine recreation without becoming active`() = runBlocking {
        val storage = MemoryStorage()
        AgentEngine(AgentPersistence(storage)).proposeAgent(
            proposedAgent = generatedAgent("durable-agent"),
            requiredCapabilities = setOf("file.read"),
            evidenceIds = listOf("validation-source"),
            proposalId = "durable-proposal"
        )

        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }
        val proposal = restored.getProposal("durable-proposal")

        assertNotNull(proposal)
        assertEquals(listOf("validation-source"), proposal?.evidenceIds)
        assertEquals("durable-agent", proposal?.proposedAgent?.id)
        assertTrue(restored.all().isEmpty())
    }

    @Test
    fun `schema one active registry loads with no proposals and migrates on write`() = runBlocking {
        val storage = MemoryStorage().apply {
            values[AGENT_KEY] = """{"schemaVersion":1,"agents":[{"id":"legacy","name":"legacy","role":"assistant","description":"legacy agent","provider":"local","model":"legacy-model","enabled":true,"tools":[],"memoryScope":"global","retryBudget":2,"createdAt":10,"updatedAt":20,"status":"IDLE"}]}"""
        }
        val engine = AgentEngine(AgentPersistence(storage)).apply { load() }

        assertEquals("legacy", engine.get("legacy")?.id)
        assertTrue(engine.allProposals().isEmpty())

        engine.proposeAgent(generatedAgent("new-agent"), proposalId = "new-proposal")
        assertTrue(storage.values.getValue(AGENT_KEY).contains("\"schemaVersion\":2"))
    }

    @Test
    fun `corrupt persisted proposal state fails closed`() = runBlocking {
        val storage = MemoryStorage()
        AgentEngine(AgentPersistence(storage)).proposeAgent(
            generatedAgent("corrupt-agent"),
            proposalId = "corrupt-proposal"
        )
        storage.values[AGENT_KEY] = storage.values.getValue(AGENT_KEY)
            .replace("\"state\":\"PROPOSED\"", "\"state\":\"UNKNOWN_STATE\"")

        val failure = runCatching {
            AgentEngine(AgentPersistence(storage)).load()
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Corrupt)
    }

    @Test
    fun `persisted activated state without validation review and activation timestamp fails closed`() = runBlocking {
        val storage = MemoryStorage()
        AgentEngine(AgentPersistence(storage)).proposeAgent(
            generatedAgent("impossible-agent"),
            proposalId = "impossible-proposal"
        )
        storage.values[AGENT_KEY] = storage.values.getValue(AGENT_KEY)
            .replace("\"state\":\"PROPOSED\"", "\"state\":\"ACTIVATED\"")

        val failure = runCatching {
            AgentEngine(AgentPersistence(storage)).load()
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Corrupt)
    }

    @Test
    fun `duplicate persisted proposal ids fail closed`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage))
        engine.proposeAgent(generatedAgent("agent-a"), proposalId = "proposal-a")
        engine.proposeAgent(generatedAgent("agent-b"), proposalId = "proposal-b")
        storage.values[AGENT_KEY] = storage.values.getValue(AGENT_KEY)
            .replace("\"id\":\"proposal-b\"", "\"id\":\"proposal-a\"")

        val failure = runCatching {
            AgentEngine(AgentPersistence(storage)).load()
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Corrupt)
    }

    @Test
    fun `proposal persistence failure leaves memory and active registry unchanged`() = runBlocking {
        val storage = MemoryStorage().apply { failWrites = true }
        val engine = AgentEngine(AgentPersistence(storage))

        val failure = runCatching {
            engine.proposeAgent(generatedAgent("write-failure"), proposalId = "failed-proposal")
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
        assertNull(engine.getProposal("failed-proposal"))
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `successful validation records evidence without activating agent`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val proposed = engine.proposeAgent(
            generatedAgent("validated-agent"),
            evidenceIds = listOf("proposal-evidence"),
            proposalId = "validated-proposal"
        )

        val validated = engine.validateProposal(
            proposed.id,
            GeneratedAgentValidator {
                GeneratedAgentValidationOutcome(
                    passed = true,
                    summary = "isolated checks passed",
                    evidenceIds = listOf("validation-evidence")
                )
            }
        )

        assertEquals(GeneratedAgentProposalState.VALIDATED, validated.state)
        assertEquals(GeneratedAgentValidationStatus.PASSED, validated.validationStatus)
        assertEquals("validated-proposal", validated.validation?.proposalId)
        assertEquals("isolated checks passed", validated.validation?.summary)
        assertEquals(
            listOf("proposal-evidence", "validation-evidence"),
            validated.evidenceIds
        )
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `failed validation enters validation failed and remains non active`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val proposal = engine.proposeAgent(
            generatedAgent("failed-validation-agent"),
            proposalId = "failed-validation-proposal"
        )

        val failed = engine.validateProposal(
            proposal.id,
            GeneratedAgentValidator {
                GeneratedAgentValidationOutcome(false, "tests failed", listOf("failed-test"))
            }
        )

        assertEquals(GeneratedAgentProposalState.VALIDATION_FAILED, failed.state)
        assertEquals(GeneratedAgentValidationStatus.FAILED, failed.validationStatus)
        assertEquals("tests failed", failed.stateReason)
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `validator receives detached proposal and cannot mutate active registry through contract`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val proposal = engine.proposeAgent(
            generatedAgent("isolated-agent"),
            proposalId = "isolated-proposal"
        )
        var observedProposal: GeneratedAgentProposal? = null

        engine.validateProposal(
            proposal.id,
            GeneratedAgentValidator { snapshot ->
                observedProposal = snapshot
                assertFalse(snapshot.proposedAgent.enabled)
                assertEquals(AgentStatus.CREATED, snapshot.proposedAgent.status)
                assertTrue(engine.all().isEmpty())
                GeneratedAgentValidationOutcome(true, "isolated")
            }
        )

        assertNotNull(observedProposal)
        assertTrue(engine.all().isEmpty())
        assertNull(engine.get("isolated-agent"))
    }

    @Test
    fun `validator exception leaves proposal unchanged`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val proposal = engine.proposeAgent(
            generatedAgent("exception-agent"),
            proposalId = "exception-proposal"
        )

        val failure = runCatching {
            engine.validateProposal(proposal.id, GeneratedAgentValidator { error("validator crashed") })
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(GeneratedAgentProposalState.PROPOSED, engine.getProposal(proposal.id)?.state)
        assertNull(engine.getProposal(proposal.id)?.validation)
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `validator cancellation propagates and leaves proposal unchanged`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val proposal = engine.proposeAgent(
            generatedAgent("cancel-agent"),
            proposalId = "cancel-proposal"
        )

        val failure = runCatching {
            engine.validateProposal(
                proposal.id,
                GeneratedAgentValidator { throw CancellationException("cancel validation") }
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(GeneratedAgentProposalState.PROPOSED, engine.getProposal(proposal.id)?.state)
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `validated proposal requires an explicit review request`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val validated = validatedProposal(engine, "review-required")

        assertEquals(GeneratedAgentProposalState.VALIDATED, validated.state)
        assertEquals(GeneratedAgentReviewStatus.NOT_REQUESTED, validated.reviewStatus)

        val requested = engine.requestProposalReview(validated.id)
        assertEquals(GeneratedAgentProposalState.REVIEW_REQUIRED, requested.state)
        assertEquals(GeneratedAgentReviewStatus.REQUIRED, requested.reviewStatus)
        assertNull(requested.review)
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `explicit review rejection records reviewer and blocks progression`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val reviewRequired = reviewRequiredProposal(engine, "rejected")

        val rejected = engine.reviewProposal(
            proposalId = reviewRequired.id,
            decision = GeneratedAgentReviewDecision.REJECT,
            reviewerRef = "reviewer:security",
            reason = "tool scope too broad",
            evidenceIds = listOf("review-evidence")
        )

        assertEquals(GeneratedAgentProposalState.REJECTED, rejected.state)
        assertEquals(GeneratedAgentReviewStatus.REJECTED, rejected.reviewStatus)
        assertEquals("reviewer:security", rejected.review?.reviewerRef)
        assertEquals("tool scope too broad", rejected.review?.reason)
        assertEquals("rejected", rejected.review?.proposalId)
        assertTrue("review-evidence" in rejected.evidenceIds)
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `review approval does not activate or register proposed agent`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val reviewRequired = reviewRequiredProposal(engine, "approved")

        val approved = engine.reviewProposal(
            proposalId = reviewRequired.id,
            decision = GeneratedAgentReviewDecision.APPROVE,
            reviewerRef = "reviewer:human",
            reason = "scope reviewed",
            evidenceIds = listOf("approval-evidence")
        )

        assertEquals(GeneratedAgentProposalState.APPROVED, approved.state)
        assertEquals(GeneratedAgentReviewStatus.APPROVED, approved.reviewStatus)
        assertEquals(GeneratedAgentActivationStatus.INACTIVE, approved.activationStatus)
        assertNull(engine.get(approved.proposedAgent.id))
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `disabled proposal remains durable and cannot reenter review lifecycle`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val validated = validatedProposal(engine, "disabled")

        val disabled = engine.disableProposal(
            proposalId = validated.id,
            reason = "owner disabled proposal",
            evidenceIds = listOf("disable-evidence")
        )

        assertEquals(GeneratedAgentProposalState.DISABLED, disabled.state)
        assertEquals(GeneratedAgentActivationStatus.DISABLED, disabled.activationStatus)
        assertEquals("owner disabled proposal", disabled.stateReason)
        assertTrue("disable-evidence" in disabled.evidenceIds)
        assertTrue(runCatching { engine.requestProposalReview(disabled.id) }.isFailure)
    }

    @Test
    fun `invalid proposal transitions are rejected without changing state`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val proposed = engine.proposeAgent(
            generatedAgent("invalid-transition-agent"),
            proposalId = "invalid-transition"
        )

        assertTrue(runCatching { engine.requestProposalReview(proposed.id) }.isFailure)
        assertTrue(runCatching {
            engine.reviewProposal(
                proposed.id,
                GeneratedAgentReviewDecision.APPROVE,
                "reviewer",
                "premature"
            )
        }.isFailure)
        assertEquals(GeneratedAgentProposalState.PROPOSED, engine.getProposal(proposed.id)?.state)

        val validated = engine.validateProposal(
            proposed.id,
            GeneratedAgentValidator { GeneratedAgentValidationOutcome(true, "passed") }
        )
        assertTrue(runCatching {
            engine.validateProposal(
                validated.id,
                GeneratedAgentValidator { GeneratedAgentValidationOutcome(true, "again") }
            )
        }.isFailure)
        assertEquals(GeneratedAgentProposalState.VALIDATED, engine.getProposal(proposed.id)?.state)
    }

    @Test
    fun `approved review and traceable evidence survive persistence`() = runBlocking {
        val storage = MemoryStorage()
        val first = AgentEngine(AgentPersistence(storage))
        val reviewRequired = reviewRequiredProposal(first, "durable-review")
        first.reviewProposal(
            proposalId = reviewRequired.id,
            decision = GeneratedAgentReviewDecision.APPROVE,
            reviewerRef = "reviewer:persisted",
            reason = "approved after review",
            evidenceIds = listOf("review-record")
        )

        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }
            .getProposal("durable-review")!!

        assertEquals(GeneratedAgentProposalState.APPROVED, restored.state)
        assertEquals("reviewer:persisted", restored.review?.reviewerRef)
        assertEquals("approved after review", restored.review?.reason)
        assertTrue("validation-durable-review" in restored.evidenceIds)
        assertTrue("review-record" in restored.evidenceIds)
        assertTrue(restored.review?.reviewedAt != null && restored.review.reviewedAt > 0)
        assertTrue(restored.validation?.validatedAt != null && restored.validation.validatedAt > 0)
    }

    @Test
    fun `explicit activation registers enabled agent only after approval and policy success`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(
            AgentPersistence(storage),
            activationPolicy = registryPolicy(storage, providerIsLocal = true)
        )
        val approved = approvedProposal(
            engine = engine,
            id = "activate-success",
            requiredCapabilities = setOf("file.read"),
            requestedTools = listOf("file.read")
        )
        assertNull(engine.get(approved.proposedAgent.id))

        val activated = engine.activateProposal(approved.id)

        assertEquals(approved.proposedAgent.id, activated.id)
        assertTrue(activated.enabled)
        assertEquals(AgentStatus.IDLE, activated.status)
        assertEquals(activated, engine.get(activated.id))
        val durableProposal = engine.getProposal(approved.id)!!
        assertEquals(GeneratedAgentProposalState.ACTIVATED, durableProposal.state)
        assertEquals(GeneratedAgentActivationStatus.ACTIVE, durableProposal.activationStatus)
        assertNotNull(durableProposal.activatedAt)
        assertTrue(durableProposal.evidenceIds.any { it.startsWith("provider-selection:") })
    }

    @Test
    fun `policy denial leaves approved proposal and active registry unchanged`() = runBlocking {
        val denyPolicy = GeneratedAgentActivationPolicy {
            GeneratedAgentActivationEligibility(false, "denied by policy", listOf("policy-denial"))
        }
        val engine = AgentEngine(AgentPersistence(MemoryStorage()), activationPolicy = denyPolicy)
        val approved = approvedProposal(engine, "policy-denied")

        val failure = runCatching { engine.activateProposal(approved.id) }.exceptionOrNull()

        assertTrue(failure is GeneratedAgentActivationDeniedException)
        assertEquals(GeneratedAgentProposalState.APPROVED, engine.getProposal(approved.id)?.state)
        assertNull(engine.get(approved.proposedAgent.id))
    }

    @Test
    fun `registry policy rejects an unknown requested tool`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(
            AgentPersistence(storage),
            activationPolicy = registryPolicy(storage)
        )
        val approved = approvedProposal(
            engine,
            "unknown-tool",
            requestedTools = listOf("unknown.tool")
        )

        val failure = runCatching {
            engine.activateProposal(approved.id)
        }.exceptionOrNull()

        assertTrue(failure is GeneratedAgentActivationDeniedException)
        assertTrue(failure?.message?.contains("tool", ignoreCase = true) == true)
        assertNull(engine.get(approved.proposedAgent.id))
    }

    @Test
    fun `registry policy rejects an unknown requested skill`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(
            AgentPersistence(storage),
            activationPolicy = registryPolicy(storage)
        )
        val approved = approvedProposal(
            engine,
            "unknown-skill",
            requestedSkills = listOf("missing-skill")
        )

        val failure = runCatching {
            engine.activateProposal(approved.id)
        }.exceptionOrNull()

        assertTrue(failure is GeneratedAgentActivationDeniedException)
        assertTrue(failure?.message?.contains("skill", ignoreCase = true) == true)
        assertNull(engine.get(approved.proposedAgent.id))
    }

    @Test
    fun `registry policy enforces local only provider privacy`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(
            AgentPersistence(storage),
            activationPolicy = registryPolicy(storage, providerIsLocal = false)
        )
        val approved = approvedProposal(
            engine = engine,
            id = "remote-privacy",
            providerSelectionContext = ProviderSelectionContext(
                privacyRequirement = PrivacyRequirement.LOCAL_ONLY
            )
        )

        val failure = runCatching {
            engine.activateProposal(approved.id)
        }.exceptionOrNull()

        assertTrue(failure is GeneratedAgentActivationDeniedException)
        assertTrue(failure?.message?.contains("provider", ignoreCase = true) == true)
        assertNull(engine.get(approved.proposedAgent.id))
    }

    @Test
    fun `duplicate active agent identity is rejected without replacement`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage), activationPolicy = allowPolicy())
        val approved = approvedProposal(engine, "duplicate-active")
        val existing = generatedAgent(approved.proposedAgent.id).copy(role = "existing")
        assertTrue(engine.register(existing))

        val failure = runCatching {
            engine.activateProposal(approved.id)
        }.exceptionOrNull()

        assertTrue(failure is GeneratedAgentActivationDeniedException)
        assertEquals("existing", engine.get(existing.id)?.role)
        assertEquals(GeneratedAgentProposalState.APPROVED, engine.getProposal(approved.id)?.state)
    }

    @Test
    fun `activation persistence failure publishes neither active agent nor activated state`() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage), activationPolicy = allowPolicy())
        val approved = approvedProposal(engine, "activation-write-failure")
        storage.failWrites = true

        val failure = runCatching {
            engine.activateProposal(approved.id)
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
        assertNull(engine.get(approved.proposedAgent.id))
        assertEquals(GeneratedAgentProposalState.APPROVED, engine.getProposal(approved.id)?.state)
    }

    @Test
    fun `activated agent state and policy evidence survive restoration`() = runBlocking {
        val storage = MemoryStorage()
        val first = AgentEngine(
            AgentPersistence(storage),
            activationPolicy = GeneratedAgentActivationPolicy {
                GeneratedAgentActivationEligibility(true, "eligible", listOf("eligibility-evidence"))
            }
        )
        val approved = approvedProposal(first, "durable-activation")
        first.activateProposal(approved.id)

        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }
        val active = restored.get(approved.proposedAgent.id)
        val proposal = restored.getProposal(approved.id)

        assertNotNull(active)
        assertTrue(active!!.enabled)
        assertEquals(GeneratedAgentProposalState.ACTIVATED, proposal?.state)
        assertTrue("eligibility-evidence" in proposal!!.evidenceIds)
        assertNotNull(proposal.activatedAt)
    }

    @Test
    fun `rejected and disabled proposals cannot activate`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()), activationPolicy = allowPolicy())
        val rejectedReview = reviewRequiredProposal(engine, "cannot-activate-rejected")
        val rejected = engine.reviewProposal(
            rejectedReview.id,
            GeneratedAgentReviewDecision.REJECT,
            "reviewer",
            "rejected"
        )
        val disabled = engine.disableProposal(
            validatedProposal(engine, "cannot-activate-disabled").id,
            "disabled"
        )

        assertTrue(runCatching { engine.activateProposal(rejected.id) }.isFailure)
        assertTrue(runCatching { engine.activateProposal(disabled.id) }.isFailure)
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun `repeated activation is rejected and keeps one active agent`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()), activationPolicy = allowPolicy())
        val approved = approvedProposal(engine, "repeat-activation")
        engine.activateProposal(approved.id)

        val second = runCatching { engine.activateProposal(approved.id) }

        assertTrue(second.isFailure)
        assertEquals(1, engine.all().count { it.id == approved.proposedAgent.id })
    }

    @Test
    fun `concurrent activation publishes exactly one active agent`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()), activationPolicy = allowPolicy())
        val approved = approvedProposal(engine, "concurrent-activation")

        val results = listOf(1, 2).map {
            async(Dispatchers.Default) {
                runCatching { engine.activateProposal(approved.id) }
            }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(1, engine.all().count { it.id == approved.proposedAgent.id })
    }

    @Test
    fun `engine without configured activation policy fails closed`() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val approved = approvedProposal(engine, "default-deny")

        val failure = runCatching { engine.activateProposal(approved.id) }.exceptionOrNull()

        assertTrue(failure is GeneratedAgentActivationDeniedException)
        assertTrue(failure?.message?.contains("policy", ignoreCase = true) == true)
        assertNull(engine.get(approved.proposedAgent.id))
        assertEquals(GeneratedAgentProposalState.APPROVED, engine.getProposal(approved.id)?.state)
    }

    private suspend fun validatedProposal(
        engine: AgentEngine,
        id: String
    ): GeneratedAgentProposal {
        engine.proposeAgent(generatedAgent("agent-$id"), proposalId = id)
        return engine.validateProposal(
            id,
            GeneratedAgentValidator {
                GeneratedAgentValidationOutcome(true, "passed", listOf("validation-$id"))
            }
        )
    }

    private suspend fun reviewRequiredProposal(
        engine: AgentEngine,
        id: String
    ): GeneratedAgentProposal = engine.requestProposalReview(validatedProposal(engine, id).id)

    private suspend fun approvedProposal(
        engine: AgentEngine,
        id: String,
        requiredCapabilities: Set<String> = emptySet(),
        requestedTools: List<String> = emptyList(),
        requestedSkills: List<String> = emptyList(),
        providerSelectionContext: ProviderSelectionContext = ProviderSelectionContext()
    ): GeneratedAgentProposal {
        engine.proposeAgent(
            proposedAgent = generatedAgent("agent-$id"),
            requiredCapabilities = requiredCapabilities,
            requestedTools = requestedTools,
            requestedSkills = requestedSkills,
            providerSelectionContext = providerSelectionContext,
            proposalId = id
        )
        engine.validateProposal(
            id,
            GeneratedAgentValidator {
                GeneratedAgentValidationOutcome(true, "validated", listOf("validation-$id"))
            }
        )
        engine.requestProposalReview(id)
        return engine.reviewProposal(
            proposalId = id,
            decision = GeneratedAgentReviewDecision.APPROVE,
            reviewerRef = "reviewer:$id",
            reason = "approved for activation",
            evidenceIds = listOf("review-$id")
        )
    }

    private fun allowPolicy() = GeneratedAgentActivationPolicy {
        GeneratedAgentActivationEligibility(true, "eligible", listOf("allow-policy"))
    }

    private fun registryPolicy(
        storage: MemoryStorage,
        providerIsLocal: Boolean = true
    ): RegistryBackedGeneratedAgentActivationPolicy {
        val audit = AuditLog(storage)
        val providers = ProviderRegistry(storage, audit)
        val models = ModelRegistry(storage, audit)
        check(providers.register(
            ProviderDefinition(
                providerId = "local-provider",
                displayName = "Local Provider",
                providerType = "test",
                endpoint = "test://provider",
                authenticationCapability = "none",
                supportedModels = listOf("local-model"),
                capabilities = emptySet(),
                availability = "available",
                health = "healthy",
                isLocal = providerIsLocal,
                isFallbackEligible = false
            )
        ))
        check(models.register(
            ModelDefinition(
                creator = "test",
                modelId = "local-model",
                displayName = "Local Model",
                accessProvider = "local-provider",
                route = "test://provider/model",
                capabilities = emptySet(),
                contextLimit = null,
                isLocal = providerIsLocal,
                availability = "available",
                health = "healthy",
                inputOutput = "text",
                supportsTools = false,
                supportsVision = false,
                supportsAudio = false,
                supportsStreaming = false,
                supportsReasoning = false
            )
        ))
        providers.registerProviderFactory(object : ProviderFactory {
            override fun create(providerDef: ProviderDefinition): LanguageModelProvider =
                TestLanguageModelProvider(providerDef.providerId, providerDef.supportedModels)
        })
        return RegistryBackedGeneratedAgentActivationPolicy(
            toolRegistry = ToolRegistry(),
            skillRegistry = SkillRegistry(storage),
            providerModelRouter = ProviderModelRouter(providers, models)
        )
    }

    private fun generatedAgent(id: String) = Agent(
        id = id,
        name = "Generated $id",
        role = "planner",
        description = "generated planning agent",
        provider = "local-provider",
        model = "local-model",
        enabled = true,
        tools = listOf("file.read"),
        memoryScope = "proposal",
        retryBudget = 1,
        createdAt = 100L,
        updatedAt = 100L,
        status = AgentStatus.IDLE
    )

    private class TestLanguageModelProvider(
        override val id: String,
        override val models: List<String>
    ) : LanguageModelProvider {
        override val displayName: String = id
        override val supportsToolCalling: Boolean = false
        override val supportsStreaming: Boolean = false
        override fun capabilities(): Map<String, Any> = emptyMap()
        override suspend fun chat(
            messages: List<Map<String, Any>>,
            availableTools: List<Map<String, Any>>?
        ): Result<Map<String, Any>> = Result.success(mapOf("content" to "unused"))
        override suspend fun health(): Result<Boolean> = Result.success(true)
    }

    private class MemoryStorage : Storage {
        val values = ConcurrentHashMap<String, String>()
        @Volatile var failWrites = false

        override suspend fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) {
            if (failWrites) throw StorageException.IoFailure("write failed")
            values[key] = value
        }
        override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }

    private companion object {
        const val AGENT_KEY = "aethra.agents.v1"
    }
}
