package org.aethra.core.orchestration

import java.util.UUID

enum class ReconciliationResolution {
    NOT_EXECUTED,
    SUCCEEDED,
    FAILED
}

data class ReconciliationEvidence(
    val reference: String,
    val summary: String,
    val idempotencyKey: String
)

internal class WorkflowReconciler(
    private val workflowStore: WorkflowStore,
    private val artifactStore: ArtifactStore? = null
) {
    fun reconcile(
        workflowId: String,
        taskId: String,
        stepId: String,
        resolution: ReconciliationResolution,
        evidence: ReconciliationEvidence,
        artifactId: String? = null,
        reason: String? = null,
        approvalId: String? = null
    ): WorkflowExecutionRecord {
        require(evidence.reference.isNotBlank()) { "reconciliation evidence reference is required" }
        require(evidence.summary.isNotBlank()) { "reconciliation evidence summary is required" }
        require(evidence.idempotencyKey.isNotBlank()) { "reconciliation idempotency key is required" }

        val current = workflowStore.load(workflowId, taskId)
            ?: error("workflow execution record not found")
        val step = current.steps[stepId]
            ?: error("workflow step record not found: $stepId")
        require(step.status == StepResult.Status.RUNNING || step.status == StepResult.Status.UNKNOWN_OUTCOME) {
            "step is not awaiting reconciliation: $stepId"
        }
        require(step.idempotencyKey.isNotBlank()) { "persisted step idempotency key is missing" }
        require(evidence.idempotencyKey == step.idempotencyKey) {
            "reconciliation evidence is bound to a different idempotency key"
        }

        val auditEvent = AuditEvent(
            eventId = UUID.randomUUID().toString(),
            workflowId = workflowId,
            taskId = taskId,
            stepId = stepId,
            event = when (resolution) {
                ReconciliationResolution.NOT_EXECUTED -> "recovery.reconciled_not_executed"
                ReconciliationResolution.SUCCEEDED -> "recovery.reconciled_succeeded"
                ReconciliationResolution.FAILED -> "recovery.reconciled_failed"
            },
            status = when (resolution) {
                ReconciliationResolution.NOT_EXECUTED -> null
                ReconciliationResolution.SUCCEEDED -> StepResult.Status.SUCCEEDED
                ReconciliationResolution.FAILED -> StepResult.Status.FAILED
            },
            reason = reason ?: evidence.summary,
            artifactIds = listOfNotNull(artifactId),
            approvalId = approvalId,
            evidenceReference = evidence.reference,
            idempotencyKey = evidence.idempotencyKey
        )

        val updatedSteps = current.steps.toMutableMap()
        val updatedArtifacts = current.artifacts.toMutableMap()
        when (resolution) {
            ReconciliationResolution.NOT_EXECUTED -> {
                updatedSteps.remove(stepId)
            }
            ReconciliationResolution.SUCCEEDED -> {
                require(artifactStore != null) { "artifact store is required to reconcile success" }
                require(artifactId != null) { "artifact id is required to reconcile success" }
                val storedArtifact = artifactStore.snapshot(taskId)[artifactId]
                    ?: error("reconciled success artifact is not present for this task in the artifact store")
                updatedArtifacts[artifactId] = storedArtifact
                updatedSteps[stepId] = step.copy(
                    status = StepResult.Status.SUCCEEDED,
                    reason = evidence.summary,
                    artifactId = artifactId
                )
            }
            ReconciliationResolution.FAILED -> {
                updatedSteps[stepId] = step.copy(
                    status = StepResult.Status.FAILED,
                    reason = evidence.summary
                )
            }
        }

        val updated = current.copy(
            revision = current.revision + 1,
            steps = updatedSteps,
            audit = current.audit + auditEvent,
            artifacts = updatedArtifacts
        )
        workflowStore.save(updated)
        return workflowStore.load(workflowId, taskId) ?: updated
    }
}
