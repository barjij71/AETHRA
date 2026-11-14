package org.aethra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.aethra.core.orchestration.*
import kotlinx.serialization.json.*

class WorkflowStoreTest {
    private fun contractSchema() = Json.parseToJsonElement(
        """{"type":"object","required":["n"],"properties":{"n":{"type":"integer"}},"additionalProperties":false}"""
    ).jsonObject

    @Test
    fun savesAndLoadsWorkflowExecutionRecord() {
        val store = InMemoryWorkflowStore()
        val record = WorkflowExecutionRecord(
            workflowId = "wf-1",
            taskId = "task-1",
            steps = mapOf(
                "a" to StepExecutionRecord("a", StepResult.Status.SUCCEEDED, artifactId = "artifact-a", idempotencyKey = "task-1::a"),
                "b" to StepExecutionRecord("b", StepResult.Status.BLOCKED, reason = "dependency failed", idempotencyKey = "task-1::b")
            )
        )
        store.save(record)
        assertEquals(record, store.load("wf-1", "task-1"))
    }

    @Test
    fun missingWorkflowReturnsNull() {
        assertEquals(null, InMemoryWorkflowStore().load("missing", "task"))
    }

    @Test
    fun persistsAcrossStoreRecreationAndPreservesRevision() {
        val file = kotlin.io.path.createTempFile("aethra-workflow-", ".json")
        try {
            val first = JsonFileWorkflowStore(file)
            assertEquals(null, first.load("wf-restart", "task-restart"))
            first.save(
                WorkflowExecutionRecord(
                    workflowId = "wf-restart",
                    taskId = "task-restart",
                    revision = 3,
                    steps = mapOf(
                        "a" to StepExecutionRecord("a", StepResult.Status.RUNNING, idempotencyKey = "task-restart::a")
                    )
                )
            )
            val second = JsonFileWorkflowStore(file)
            val loaded = second.load("wf-restart", "task-restart")
            assertNotNull(loaded)
            assertEquals(3, loaded.revision)
            assertEquals(StepResult.Status.RUNNING, loaded.steps["a"]?.status)
            assertEquals("task-restart::a", loaded.steps["a"]?.idempotencyKey)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun persistsAuditEventsAcrossStoreRecreation() {
        val file = kotlin.io.path.createTempFile("aethra-audit-", ".json")
        try {
            JsonFileWorkflowStore(file).save(
                WorkflowExecutionRecord(
                    workflowId = "wf-audit",
                    taskId = "task-audit",
                    audit = listOf(
                        AuditEvent(
                            eventId = "event-1",
                            workflowId = "wf-audit",
                            taskId = "task-audit",
                            stepId = "step-a",
                            skillId = "skill-a",
                            skillVersion = "1.0.0",
                            event = "step.finished",
                            status = StepResult.Status.SUCCEEDED,
                            artifactIds = listOf("artifact-1")
                        )
                    )
                )
            )
            val loaded = JsonFileWorkflowStore(file).load("wf-audit", "task-audit")
            assertNotNull(loaded)
            assertEquals(1, loaded.audit.size)
            assertEquals("skill-a", loaded.audit.single().skillId)
            assertEquals("artifact-1", loaded.audit.single().artifactIds.single())
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun restoresSuccessfulDependencyArtifactAfterRestart() = kotlinx.coroutines.test.runTest {
        val registry = org.aethra.core.skills.SkillRegistry()
        listOf("a", "b").forEach { id ->
            registry.register(org.aethra.core.skills.SkillManifest(
                id = id, version = "1.0.0", name = id, source = "test", license = "MIT",
                inputSchemaVersion = "1", outputSchemaVersion = "1", trusted = true, enabled = true,
                inputSchema = if (id == "b") contractSchema() else null,
                outputSchema = contractSchema()
            ))
            registry.setTrust(id, org.aethra.core.skills.TrustState.TRUSTED)
            registry.enable(id)
        }
        val taskId = "task-artifact-restart"
        val artifact = Artifact(
            id = "artifact-a",
            schemaVersion = "1",
            producerSkillId = "a",
            producerVersion = "1.0.0",
            taskId = taskId,
            value = mapOf("n" to 7),
            provenance = "test"
        )
        val file = kotlin.io.path.createTempFile("aethra-artifact-recovery-", ".json")
        try {
            val originalArtifacts = ArtifactStore().apply { put(artifact) }
            JsonFileWorkflowStore(file).save(
                WorkflowExecutionRecord(
                    workflowId = "wf-artifact-restart",
                    taskId = taskId,
                    steps = mapOf(
                        "a" to StepExecutionRecord(
                            "a", StepResult.Status.SUCCEEDED,
                            artifactId = artifact.id,
                            idempotencyKey = "$taskId::a"
                        )
                    ),
                    artifacts = originalArtifacts.snapshot(taskId)
                )
            )

            var downstreamReceivedArtifact = false
            val result = Orchestrator(
                registry = registry,
                artifactStore = ArtifactStore(),
                policy = org.aethra.core.policy.CorePolicyEngine(),
                executor = SkillExecutor { step, inputs ->
                    if (step.id == "b") {
                        assertEquals(1, inputs.size)
                        val payload = inputs.single().value as? JsonObject
                        assertNotNull(payload)
                        assertEquals("7", payload["n"]?.toString())
                        downstreamReceivedArtifact = true
                    }
                    StepResult(StepResult.Status.SUCCEEDED)
                },
                workflowStore = JsonFileWorkflowStore(file)
            ).run(
                Workflow(
                    "wf-artifact-restart",
                    listOf(WorkflowStep("a", "a"), WorkflowStep("b", "b", setOf("a")))
                ),
                taskId
            )

            assertEquals(true, downstreamReceivedArtifact)
            assertEquals(StepResult.Status.SUCCEEDED, result["b"]?.status)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun nonIdempotentRunningStepRecoversAsUnknownOutcome() = kotlinx.coroutines.test.runTest {
        val registry = org.aethra.core.skills.SkillRegistry()
        registry.register(org.aethra.core.skills.SkillManifest(
            id = "once", version = "1.0.0", name = "once", source = "test", license = "MIT",
            inputSchemaVersion = "1", outputSchemaVersion = "1", trusted = true, enabled = true,
            idempotent = false, outputSchema = contractSchema()
        ))
        registry.setTrust("once", org.aethra.core.skills.TrustState.TRUSTED)
        registry.enable("once")
        val file = kotlin.io.path.createTempFile("aethra-recovery-", ".json")
        try {
            JsonFileWorkflowStore(file).save(
                WorkflowExecutionRecord(
                    workflowId = "wf-recovery",
                    taskId = "task-recovery",
                    steps = mapOf(
                        "once-step" to StepExecutionRecord(
                            "once-step", StepResult.Status.RUNNING,
                            idempotencyKey = "task-recovery::once-step"
                        )
                    )
                )
            )
            var executed = false
            val result = Orchestrator(
                registry, ArtifactStore(), org.aethra.core.policy.CorePolicyEngine(),
                SkillExecutor { _, _ -> executed = true; StepResult(StepResult.Status.SUCCEEDED) },
                workflowStore = JsonFileWorkflowStore(file)
            ).run(
                Workflow("wf-recovery", listOf(WorkflowStep("once-step", "once"))),
                "task-recovery"
            )
            assertEquals(StepResult.Status.UNKNOWN_OUTCOME, result["once-step"]?.status)
            assertEquals(false, executed)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}
