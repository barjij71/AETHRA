package org.aethra.core.external

import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionAuthorizationException
import org.aethra.core.task.Task
import org.aethra.core.security.PermissionBroker
import org.aethra.core.security.PermissionRequest
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.task.TaskRunner
import kotlin.Result

/**
 * Resolves an external capability by id and dispatches a structured operation.
 *
 * The router enforces the external capability safety policy ([ExternalCapabilityPolicy])
 * and consults the [PermissionBroker] for sensitive operations. All operations are
 * recorded in the [AuditLog].
 *
 * Also implements [TaskRunner] so external capabilities can be invoked through the
 * core [org.aethra.core.task.TaskEngine] as part of the normal task execution flow:
 *   User -> TaskEngine -> ExternalCapabilityRouter -> InstaBoxRemoteComputerProvider -> InstaBox API
 */
class ExternalCapabilityRouter(
    private val registry: ExternalCapabilityRegistry,
    private val permissionBroker: PermissionBroker? = null,
    private val auditLog: AuditLog? = null,
    private val eventBus: EventBus? = null
) : TaskRunner {

    /**
     * Execute a structured operation against an external capability.
     *
     * The request is checked against the safety policy, then against the permission
     * broker for sensitive operations, then dispatched to the capability. Every
     * attempt is recorded in the audit log.
     */
    suspend fun execute(capabilityId: String, request: CapabilityRequest): Result<CapabilityResult> {
        val capability = registry.get(capabilityId)
            ?: return Result.failure(IllegalArgumentException("external capability not found: $capabilityId"))

        if (!ExternalCapabilityPolicy.allows(request)) {
            recordAudit(
                operation = "external.policy.reject",
                target = capabilityId,
                decision = "DENIED",
                outcome = "POLICY_REJECTED",
                riskClass = request.riskClass
            )
            return Result.failure(IllegalArgumentException("external operation rejected by safety policy"))
        }

        if (request.operation !in capability.capabilities) {
            return Result.failure(
                IllegalArgumentException("operation '${request.operation}' is not supported by $capabilityId")
            )
        }

        // Permission/approval gate for sensitive operations.
        val authorizationResult = checkPermission(capabilityId, request)
        if (authorizationResult.isFailure) {
            val authorizationError = authorizationResult.exceptionOrNull()
            recordAudit(
                operation = "external.permission.reject",
                target = capabilityId,
                decision = "DENIED",
                outcome = authorizationError?.message ?: "PERMISSION_REQUIRED",
                riskClass = request.riskClass
            )
            return Result.failure(authorizationError ?: IllegalStateException("permission_required"))
        }
        val authorization = authorizationResult.getOrThrow()

        val result = try {
            capability.execute(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            authorization?.let {
                permissionBroker?.completeExecution(it.id, succeeded = false, reason = "execution cancelled")
            }
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        authorization?.let { requestRecord ->
            permissionBroker?.completeExecution(
                requestRecord.id,
                succeeded = result.isSuccess && result.getOrNull()?.success == true,
                reason = if (result.isSuccess && result.getOrNull()?.success == true) "operation completed" else "operation failed"
            )
        }
        val capResult = result.getOrDefault(CapabilityResult(capabilityId, request.operation, false))

        recordAudit(
            operation = "external.execute",
            target = capabilityId,
            decision = if (capResult.success) "GRANTED" else "DENIED",
            outcome = if (capResult.success) "SUCCESS" else "FAILED",
            riskClass = request.riskClass
        )

        return result
    }

    /**
     * [TaskRunner] implementation. Maps a [Task] to a [CapabilityRequest] and
     * dispatches it through [execute] against the first registered remote-computer
     * capability that supports the task's goal as an operation.
     *
     * The result is adapted from [CapabilityResult] to [Map] for the task engine.
     */
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        val request = CapabilityRequest(
            operation = task.goal.trim(),
            riskClass = task.riskClass,
            requiresApproval = task.riskClass.level >= RiskClass.SENSITIVE.level,
            approvalRequestId = task.id,
            targetId = task.id
        )

        val capability = registry.all()
            .filter { it.type == ExternalCapabilityType.REMOTE_COMPUTER }
            .firstOrNull { it.capabilities.contains(task.goal.trim()) }
            ?: return Result.failure(
                IllegalArgumentException("no external capability supports operation: ${task.goal}")
            )

        return execute(capability.id, request).map { cr ->
            mapOf<String, Any>(
                "capabilityId" to cr.capabilityId,
                "operation" to cr.operation,
                "success" to cr.success.toString(),
                "output" to cr.output,
                "evidence" to cr.evidence,
                "error" to (cr.error ?: "")
            )
        }
    }

    /**
     * Check the permission broker for sensitive operations. Returns failure if
     * approval is required but not granted.
     */
    private suspend fun checkPermission(
        capabilityId: String,
        request: CapabilityRequest
    ): Result<PermissionRequest?> {
        if (request.riskClass.level < RiskClass.SENSITIVE.level && !request.requiresApproval) {
            return Result.success(null)
        }

        val broker = permissionBroker ?: run {
            // No permission broker configured: sensitive operations cannot proceed.
            return Result.failure(
                IllegalStateException("permission_required: no permission broker configured")
            )
        }

        val target = request.targetId ?: "external:$capabilityId"
        val approval = try {
            if (request.approvalRequestId == null) {
                broker.requestApproval(
                    requester = "aethra.runtime",
                    risk = request.riskClass,
                    operation = request.operation,
                    target = target,
                    capability = capabilityId
                )
            } else {
                broker.getRequest(request.approvalRequestId) ?: broker.requestApproval(
                    requester = "aethra.runtime",
                    risk = request.riskClass,
                    operation = request.operation,
                    target = target,
                    capability = capabilityId,
                    requestId = request.approvalRequestId
                )
            }
        } catch (error: Exception) {
            return Result.failure(error)
        }

        // Let the broker enforce state and record the blocked attempt as part
        // of the same authoritative authorization boundary.
        return try {
            Result.success(
                broker.beginExecution(
                    approval.id,
                    request.riskClass,
                    request.operation,
                    capabilityId,
                    target
                )
            )
        } catch (error: PermissionAuthorizationException) {
            Result.failure(
                PermissionAuthorizationException(
                    approval.id,
                    error.code,
                    "permission_required:${approval.id}:${approval.state.name}"
                )
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun recordAudit(
        operation: String,
        target: String,
        decision: String,
        outcome: String,
        riskClass: RiskClass
    ) {
        auditLog?.let { log ->
            val entry = AuditEntry(
                id = java.util.UUID.randomUUID().toString(),
                actor = "aethra.runtime",
                operation = operation,
                target = target,
                riskClass = riskClass,
                decision = decision,
                outcome = outcome
            )
            log.record(entry)
        }

        eventBus?.let { bus ->
            // Publish a permission event for observable traceability
            bus.publish(
                org.aethra.core.domain.PermissionEvent(
                    source = "external",
                    timestamp = System.currentTimeMillis(),
                    actor = "aethra.runtime",
                    operation = operation,
                    risk = riskClass,
                    granted = decision == "GRANTED",
                    reason = outcome
                )
            )
        }
    }
}
