package org.aethra.core

import org.aethra.core.orchestration.*
import kotlin.test.*

class WorkflowReconciliationTest {
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
                        idempotencyKey = "task::step"
                    )
                )
            )
        )

        val result = WorkflowReconciler(store).reconcile(
            "wf",
            "task",
            "step",
            ReconciliationResolution.NOT_EXECUTED,
            ReconciliationEvidence("external-check-1", "external executor confirmed no action occurred")
        )

        assertNull(result.steps["step"])
        assertTrue(result.audit.any { it.event == "recovery.reconciled_not_executed" })
        assertEquals(2, result.revision)
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
                        idempotencyKey = "task::step"
                    )
                )
            )
        )

        val result = WorkflowReconciler(store, artifacts).reconcile(
            "wf",
            "task",
            "step",
            ReconciliationResolution.SUCCEEDED,
            ReconciliationEvidence("external-check-2", "external executor confirmed the recorded artifact"),
            artifactId = artifact.id
        )

        assertEquals(StepResult.Status.SUCCEEDED, result.steps["step"]?.status)
        assertEquals(artifact.id, result.steps["step"]?.artifactId)
        assertTrue(result.audit.any { it.event == "recovery.reconciled_succeeded" })
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
                        idempotencyKey = "task::step"
                    )
                )
            )
        )

        val result = WorkflowReconciler(store).reconcile(
            "wf",
            "task",
            "step",
            ReconciliationResolution.FAILED,
            ReconciliationEvidence("external-check-3", "external executor confirmed the action failed")
        )

        assertEquals(StepResult.Status.FAILED, result.steps["step"]?.status)
        assertTrue(result.audit.any { it.event == "recovery.reconciled_failed" })
    }
}
