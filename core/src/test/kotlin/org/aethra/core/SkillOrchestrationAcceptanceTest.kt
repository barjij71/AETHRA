package org.aethra.core

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
\n    @Test fun manifestSchemaIsJsonObject() {\n        assertTrue(SkillManifestValidator.schema().containsKey("properties"))\n    }\n
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
        val a = policy.requestApproval("user","install","device","pkg=x",RiskTier.TIER_2)!!
        assertTrue(policy.consumeApproval(a.id,"user","install","device","pkg=x"))
        assertFalse(policy.consumeApproval(a.id,"user","install","device","pkg=x"))
        val b = policy.requestApproval("user","install","device","pkg=x",RiskTier.TIER_2)!!
        assertFalse(policy.consumeApproval(b.id,"user","install","device","pkg=y"))
    }

    @Test fun untrustedSkillCannotEnable() {
        val registry = SkillRegistry()
        registry.register(skill("u", false))
        assertFailsWith<IllegalArgumentException> { registry.enable("u") }
    }
}
