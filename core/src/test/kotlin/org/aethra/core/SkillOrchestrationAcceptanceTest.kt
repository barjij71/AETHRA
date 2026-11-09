package org.aethra.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.aethra.core.orchestration.*
import org.aethra.core.policy.*
import org.aethra.core.skills.*
import kotlin.test.*

class SkillOrchestrationAcceptanceTest {
    private fun skill(id: String, trusted: Boolean = true) = SkillManifest(id, "1.0.0", id, source="test", license="MIT", inputSchemaVersion="1", outputSchemaVersion="1", trusted=trusted, enabled=true)

    @Test fun manifestValidationRejectsInvalid() {
        assertFalse(SkillManifestValidator.validate(skill("BAD ID", false)).valid)
    }

    @Test fun manifestSchemaIsJsonObject() {
        assertTrue(SkillManifestValidator.schema().containsKey("properties"))
    }

    @Test fun threeSkillsExchangeTypedArtifacts() = runTest {
        val registry = SkillRegistry()
        listOf("a","b","c").forEach { registry.register(skill(it)); registry.setTrust(it, TrustState.TRUSTED); registry.enable(it) }
        val store = ArtifactStore()
        val seen = mutableListOf<String>()
        val executor = SkillExecutor { step, inputs ->
            when (step.skillId) {
                "a" -> {
                    val out = Artifact(schemaVersion="1", producerSkillId="a", producerVersion="1.0.0", taskId="t1", value=mapOf("n" to 1), provenance="test")
                    store.put(out); StepResult(StepResult.Status.SUCCEEDED, out)
                }
                "b" -> {
                    seen += "b"
                    assertEquals("a", inputs.single().producerSkillId)
                    val out = Artifact(schemaVersion="1", producerSkillId="b", producerVersion="1.0.0", taskId="t1", value=mapOf("n" to 2), provenance="test")
                    store.put(out); StepResult(StepResult.Status.SUCCEEDED, out)
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
        listOf("a","b").forEach { registry.register(skill(it)); registry.setTrust(it, TrustState.TRUSTED); registry.enable(it) }
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
        listOf("a","b").forEach { registry.register(skill(it)); registry.setTrust(it, TrustState.TRUSTED); registry.enable(it) }
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
}
