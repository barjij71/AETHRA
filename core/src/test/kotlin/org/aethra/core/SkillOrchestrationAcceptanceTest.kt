package org.aethra.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.aethra.core.orchestration.*
import org.aethra.core.policy.*
import org.aethra.core.skills.*
import kotlin.test.*

class SkillOrchestrationAcceptanceTest {
    private fun contractSchema() = Json.parseToJsonElement(
        """{"type":"object","required":["n"],"properties":{"n":{"type":"integer"}},"additionalProperties":false}"""
    ).jsonObject

    private fun skill(id: String, trusted: Boolean = true, acceptsInput: Boolean = false) = SkillManifest(
        id, "1.0.0", id, source = "test", license = "MIT",
        inputSchemaVersion = "1", outputSchemaVersion = "1",
        trusted = trusted, enabled = true,
        inputSchema = if (acceptsInput) contractSchema() else null,
        outputSchema = contractSchema()
    )

    @Test fun manifestValidationRejectsInvalid() {
        assertFalse(SkillManifestValidator.validate(skill("BAD ID", false)).valid)
    }

    @Test fun manifestSchemaIsJsonObject() {
        assertTrue(SkillManifestValidator.schema().containsKey("properties"))
    }

    @Test fun threeSkillsExchangeTypedArtifacts() = runTest {
        val registry = SkillRegistry()
        listOf(skill("a"), skill("b", acceptsInput = true), skill("c", acceptsInput = true)).forEach {
            registry.register(it); registry.setTrust(it.id, TrustState.TRUSTED); registry.enable(it.id)
        }
        val store = ArtifactStore()
        val seen = mutableListOf<String>()
        val executor = SkillExecutor { step, inputs ->
            when (step.skillId) {
                "a" -> {
                    val out = Artifact(schemaVersion="1", producerSkillId="a", producerVersion="1.0.0", taskId="t1", value=mapOf("n" to 1), provenance="test")
                    StepResult(StepResult.Status.SUCCEEDED, out)
                }
                "b" -> {
                    seen += "b"
                    assertEquals("a", inputs.single().producerSkillId)
                    val out = Artifact(schemaVersion="1", producerSkillId="b", producerVersion="1.0.0", taskId="t1", value=mapOf("n" to 2), provenance="test")
                    StepResult(StepResult.Status.SUCCEEDED, out)
                }
                else -> {
                    seen += "c"
                    assertEquals("b", inputs.single().producerSkillId)
                    StepResult(StepResult.Status.SUCCEEDED)
                }
            }
        }
        val wf = Workflow("t1", listOf(WorkflowStep("a","a"), WorkflowStep("b","b",setOf("a")), WorkflowStep("c","c",setOf("b"))))
        val results = Orchestrator(registry, store, CorePolicyEngine(), executor).run(wf,"t1")
        assertEquals(StepResult.Status.SUCCEEDED, results["a"]?.status)
        assertEquals(StepResult.Status.SUCCEEDED, results["b"]?.status)
        assertEquals(StepResult.Status.SUCCEEDED, results["c"]?.status)
        assertEquals(listOf("b","c"), seen)
    }

    @Test fun failedDependencyBlocksDownstream() = runTest {
        val registry = SkillRegistry()
        listOf(skill("a"), skill("b", acceptsInput = true)).forEach {
            registry.register(it); registry.setTrust(it.id, TrustState.TRUSTED); registry.enable(it.id)
        }
        val orchestrator = Orchestrator(registry, ArtifactStore(), CorePolicyEngine(), SkillExecutor { step, _ ->
            if (step.skillId == "a") StepResult(StepResult.Status.FAILED, reason="boom") else StepResult(StepResult.Status.SUCCEEDED)
        })
        val results = orchestrator.run(Workflow("t1", listOf(WorkflowStep("a","a"), WorkflowStep("b","b",setOf("a")))),"t1")
        assertEquals(StepResult.Status.FAILED, results["a"]?.status)
        assertEquals(StepResult.Status.BLOCKED, results["b"]?.status)
        assertEquals("dependency failed", results["b"]?.reason)
    }

    @Test fun approvalIsBoundToExactPayloadAndSingleUse() {
        val policy = CorePolicyEngine()
        val a = policy.requestApproval("user","execute","device","pkg=x",RiskTier.TIER_2)!!
        assertTrue(policy.consumeApproval(a.id,"user","execute","device","pkg=x"))
        assertFalse(policy.consumeApproval(a.id,"user","execute","device","pkg=x"))
        val b = policy.requestApproval("user","execute","device","pkg=x",RiskTier.TIER_2)!!
        assertFalse(policy.consumeApproval(b.id,"user","execute","device","pkg=y"))
    }

    @Test fun sensitiveWorkflowStepRequiresAndConsumesApproval() = runTest {
        val registry = SkillRegistry()
        registry.register(skill("s")); registry.setTrust("s", TrustState.TRUSTED); registry.enable("s")
        val policy = CorePolicyEngine()
        val approval = policy.requestApproval("user", "execute", "device-1", "install:pkg=x", RiskTier.TIER_2)!!
        val executed = mutableListOf<String>()
        val executor = SkillExecutor { step, _ ->
            executed += step.id
            StepResult(StepResult.Status.SUCCEEDED)
        }
        val step = WorkflowStep(
            id = "install",
            skillId = "s",
            riskTier = RiskTier.TIER_2,
            approvalId = approval.id,
            approvalTarget = "device-1",
            approvalPayload = "install:pkg=x"
        )
        val result = Orchestrator(registry, ArtifactStore(), policy, executor).run(
            Workflow("t2", listOf(step)),
            "t2"
        )
        assertEquals(StepResult.Status.SUCCEEDED, result["install"]?.status)
        assertEquals(listOf("install"), executed)
        assertFalse(policy.consumeApproval(approval.id, "user", "execute", "device-1", "install:pkg=x"))
    }

    @Test fun parallelIndependentStepsRunConcurrently() = runTest {
        val registry = SkillRegistry()
        listOf(skill("a"), skill("b")).forEach {
            registry.register(it); registry.setTrust(it.id, TrustState.TRUSTED); registry.enable(it.id)
        }
        var running = 0
        var maxRunning = 0
        val executor = SkillExecutor { _, _ ->
            running += 1
            maxRunning = maxOf(maxRunning, running)
            delay(40)
            running -= 1
            StepResult(StepResult.Status.SUCCEEDED)
        }
        val results = Orchestrator(registry, ArtifactStore(), CorePolicyEngine(), executor, maxParallelism = 2).run(
            Workflow("parallel", listOf(WorkflowStep("a","a"), WorkflowStep("b","b"))),
            "parallel"
        )
        assertEquals(StepResult.Status.SUCCEEDED, results["a"]?.status)
        assertEquals(StepResult.Status.SUCCEEDED, results["b"]?.status)
        assertEquals(2, maxRunning)
    }

    @Test fun untrustedSkillCannotEnable() {
        val registry = SkillRegistry()
        registry.register(skill("u", false))
        assertFailsWith<IllegalArgumentException> { registry.enable("u") }
    }

    @Test fun orchestratorPersistsTerminalStepState() = runTest {
        val registry = SkillRegistry()
        registry.register(skill("a")); registry.setTrust("a", TrustState.TRUSTED); registry.enable("a")
        val workflowStore = InMemoryWorkflowStore()
        val result = Orchestrator(
            registry = registry,
            artifactStore = ArtifactStore(),
            policy = CorePolicyEngine(),
            executor = SkillExecutor { _, _ -> StepResult(StepResult.Status.SUCCEEDED) },
            workflowStore = workflowStore
        ).run(
            Workflow("wf-store", listOf(WorkflowStep("a", "a"))),
            "task-store"
        )

        assertEquals(StepResult.Status.SUCCEEDED, result["a"]?.status)
        val record = workflowStore.load("wf-store", "task-store")
        assertNotNull(record)
        assertEquals(StepResult.Status.SUCCEEDED, record.steps["a"]?.status)
    }

    @Test fun manifestRejectsUnsupportedSchemaKeyword() {
        val invalidSchema = Json.parseToJsonElement(
            """{"type":"object","unknownValidationKeyword":true}"""
        ).jsonObject
        assertFalse(SkillManifestValidator.validate(skill("bad").copy(outputSchema = invalidSchema)).valid)
    }

    @Test fun invalidOutputArtifactIsRejectedBeforeStorage() = runTest {
        val registry = SkillRegistry()
        registry.register(skill("producer"))
        registry.setTrust("producer", TrustState.TRUSTED)
        registry.enable("producer")
        val store = ArtifactStore()
        val workflowStore = InMemoryWorkflowStore()
        val result = Orchestrator(
            registry,
            store,
            CorePolicyEngine(),
            SkillExecutor { _, _ ->
                StepResult(
                    StepResult.Status.SUCCEEDED,
                    Artifact(
                        schemaVersion = "1",
                        producerSkillId = "producer",
                        producerVersion = "1.0.0",
                        taskId = "invalid-output-task",
                        value = mapOf("n" to "not-an-integer"),
                        provenance = "test"
                    )
                )
            },
            workflowStore = workflowStore
        ).run(
            Workflow("invalid-output-workflow", listOf(WorkflowStep("produce", "producer"))),
            "invalid-output-task"
        )

        assertEquals(StepResult.Status.FAILED, result["produce"]?.status)
        assertTrue(store.all().isEmpty())
        assertTrue(
            workflowStore.load("invalid-output-workflow", "invalid-output-task")
                ?.audit?.any { it.event == "artifact.validation_failed" } == true
        )
    }

    @Test fun invalidInputArtifactBlocksExecutor() = runTest {
        val registry = SkillRegistry()
        listOf(skill("producer"), skill("consumer", acceptsInput = true)).forEach {
            registry.register(it)
            registry.setTrust(it.id, TrustState.TRUSTED)
            registry.enable(it.id)
        }
        val store = ArtifactStore()
        store.put(
            Artifact(
                id = "invalid-input",
                schemaVersion = "1",
                producerSkillId = "producer",
                producerVersion = "1.0.0",
                taskId = "invalid-input-task",
                value = mapOf("n" to "wrong"),
                provenance = "test"
            )
        )
        var executed = false
        val result = Orchestrator(
            registry,
            store,
            CorePolicyEngine(),
            SkillExecutor { _, _ ->
                executed = true
                StepResult(StepResult.Status.SUCCEEDED)
            }
        ).run(
            Workflow(
                "invalid-input-workflow",
                listOf(WorkflowStep("consume", "consumer", inputArtifactIds = setOf("invalid-input")))
            ),
            "invalid-input-task"
        )

        assertEquals(StepResult.Status.BLOCKED, result["consume"]?.status)
        assertEquals(false, executed)
    }

}
