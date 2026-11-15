package org.aethra.core

import kotlinx.serialization.json.Json
import org.aethra.core.orchestration.*
import java.nio.file.Files
import kotlin.test.*

class WorkflowReconciliationTest {
    private val key = "task::step"

    @Test
    fun nonExecutedReconciliationRemovesStaleRunningStepForExplicitRetry() {
        val store = InMemoryWorkflowStore()
        store.save(
            WorkflowExecutionRecord(
                workflowId = "wf",
                taskId = "task",
                steps = mapOf(
                    "step" to StepExecutionRecord(
                        stepId = "step",
                        status = StepResult.Status.RUNNING,
                        idempotencyKey = key
                    )
                )
            )
        )

        val result = WorkflowReconciler(store).reconcile(
            "wf",
            "task",
            "step",
            ReconciliationResolution.NOT_EXECUTED,
            ReconciliationEvidence(
                "external-check-1",
                "external executor confirmed no action occurred",
                key
            )
        )

        assertNull(result.steps["step"])
        assertEquals("external-check-1", result.audit.last { it.event == "recovery.reconciled_not_executed" }.evidenceReference)
        assertEquals(2, result.revision)
    }

    @Test
    fun reconciliationRejectsEvidenceForDifferentIdempotencyKey() {
        val store = InMemoryWorkflowStore()
        store.save(
            WorkflowExecutionRecord(
                workflowId = "wf",
                taskId = "task",
                steps = mapOf(
                    "step" to StepExecutionRecord(
                        stepId = "step",
                        status = StepResult.Status.UNKNOWN_OUTCOME,
                        idempotencyKey = key
                    )
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WorkflowReconciler(store).reconcile(
                "wf",
                "task",
                "step",
                ReconciliationResolution.NOT_EXECUTED,
                ReconciliationEvidence(
                    "external-check-wrong",
                    "evidence for another action",
                    "task::different-step"
                )
            )
        }
    }

    @Test
    fun successfulReconciliationRequiresExistingArtifact() {
        val store = InMemoryWorkflowStore()
        val artifacts = ArtifactStore()
        val artifact = Artifact(
            id = "artifact-1",
            schemaVersion = "1",
            producerSkillId = "s",
            producerVersion = "1.0.0",
            taskId = "task",
            value = mapOf("n" to 1),
            provenance = "reconciliation-test"
        )
        artifacts.put(artifact)
        store.save(
            WorkflowExecutionRecord(
                workflowId = "wf",
                taskId = "task",
                steps = mapOf(
                    "step" to StepExecutionRecord(
                        stepId = "step",
                        status = StepResult.Status.UNKNOWN_OUTCOME,
                        idempotencyKey = key
                    )
                )
            )
        )

        val result = WorkflowReconciler(store, artifacts).reconcile(
            "wf",
            "task",
            "step",
            ReconciliationResolution.SUCCEEDED,
            ReconciliationEvidence(
                "external-check-2",
                "external executor confirmed the recorded artifact",
                key
            ),
            artifactId = artifact.id
        )

        assertEquals(StepResult.Status.SUCCEEDED, result.steps["step"]?.status)
        assertEquals(artifact.id, result.steps["step"]?.artifactId)
        assertEquals("external-check-2", result.audit.last { it.event == "recovery.reconciled_succeeded" }.evidenceReference)
    }

    @Test
    fun failedReconciliationBlocksDownstreamOnNextRun() {
        val store = InMemoryWorkflowStore()
        store.save(
            WorkflowExecutionRecord(
                workflowId = "wf",
                taskId = "task",
                steps = mapOf(
                    "step" to StepExecutionRecord(
                        stepId = "step",
                        status = StepResult.Status.UNKNOWN_OUTCOME,
                        idempotencyKey = key
                    )
                )
            )
        )

        val result = WorkflowReconciler(store).reconcile(
            "wf",
            "task",
            "step",
            ReconciliationResolution.FAILED,
            ReconciliationEvidence(
                "external-check-3",
                "external executor confirmed the action failed",
                key
            )
        )

        assertEquals(StepResult.Status.FAILED, result.steps["step"]?.status)
        assertEquals("external-check-3", result.audit.last { it.event == "recovery.reconciled_failed" }.evidenceReference)
    }

    @Test
    fun reconciliationSurvivesProcessRestartWithJsonStore() {
        val path = Files.createTempFile("aethra-reconciliation-", ".json")
        try {
            val firstStore = JsonFileWorkflowStore(path)
            firstStore.save(
                WorkflowExecutionRecord(
                    workflowId = "wf",
                    taskId = "task",
                    steps = mapOf(
                        "step" to StepExecutionRecord(
                            stepId = "step",
                            status = StepResult.Status.UNKNOWN_OUTCOME,
                            idempotencyKey = key
                        )
                    )
                )
            )

            val restartedStore = JsonFileWorkflowStore(path)
            val result = WorkflowReconciler(restartedStore).reconcile(
                "wf",
                "task",
                "step",
                ReconciliationResolution.FAILED,
                ReconciliationEvidence(
                    "external-check-restart",
                    "external executor confirmed the action failed after restart",
                    key
                )
            )

            val finalStore = JsonFileWorkflowStore(path)
            val persisted = finalStore.load("wf", "task")
            assertEquals(StepResult.Status.FAILED, result.steps["step"]?.status)
            assertEquals(StepResult.Status.FAILED, persisted?.steps?.get("step")?.status)
            assertEquals(
                "external-check-restart",
                persisted?.audit?.last { it.event == "recovery.reconciled_failed" }?.evidenceReference
            )
            assertTrue(persisted?.revision ?: 0 >= 2)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
