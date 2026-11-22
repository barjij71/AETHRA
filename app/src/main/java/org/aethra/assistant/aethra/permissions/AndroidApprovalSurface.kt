package org.aethra.assistant.aethra.permissions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import org.aethra.core.security.PermissionBroker
import org.aethra.core.security.PermissionDecision
import org.aethra.core.security.PermissionRequest
import org.aethra.core.security.PermissionRequestState
import org.aethra.core.task.TaskEngine
import org.aethra.core.domain.TaskState

/** Activity-owned presentation boundary. Core remains the authority for decisions and execution. */
internal interface AndroidApprovalUi {
    val pendingRequests: StateFlow<List<PermissionRequest>>
    suspend fun decide(requestId: String, decision: PermissionDecision): PermissionRequest
}

internal class AndroidApprovalSurface(
    private val broker: PermissionBroker,
    private val taskEngineProvider: () -> TaskEngine?
) : AndroidApprovalUi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val pendingRequests = broker.pendingRequests

    override suspend fun decide(requestId: String, decision: PermissionDecision): PermissionRequest {
        // The UI may submit only the ID it rendered. The broker re-reads and validates that ID.
        check(broker.pendingRequests.value.any { it.id == requestId }) {
            "permission_request_stale"
        }
        val decided = broker.decide(
            requestId = requestId,
            decision = decision,
            reason = when (decision) {
                PermissionDecision.APPROVE -> "android_ui_approve"
                PermissionDecision.DENY -> "android_ui_deny"
                PermissionDecision.CANCEL -> "android_ui_cancel"
            }
        )
        if (decision == PermissionDecision.APPROVE) scope.launch {
            retryTaskOnlyIfApprovalArrivedAfterItsAttempt(decided)
        }
        return decided
    }

    fun close() = scope.cancel()

    /** Join the Android decision to the original dispatch and re-enter the Core policy gate. */
    suspend fun awaitDecisionAndResume(
        requestId: String,
        initial: Result<Map<String, Any>>,
        resumeApproved: suspend (String) -> Result<Map<String, Any>>
    ): Result<Map<String, Any>> {
        val message = initial.exceptionOrNull()?.message
        val expectedPrefix = "permission_required:$requestId"
        if (initial.isSuccess || (message != expectedPrefix && message?.startsWith("$expectedPrefix:") != true)) {
            return initial
        }
        val request = broker.getRequest(requestId) ?: return initial
        if (request.state != PermissionRequestState.REQUESTED) return initial
        return when (broker.awaitDecision(requestId).state) {
            PermissionRequestState.APPROVED -> resumeApproved(requestId)
            PermissionRequestState.CANCELLED -> Result.failure(kotlinx.coroutines.CancellationException("permission_cancelled"))
            else -> initial
        }
    }

    /**
     * Normally the active dispatcher resumes through ExecutionPolicy after the decision.
     * If its bounded task attempt already timed out while the request was visible, retry only
     * that failed task; the same approved request is still consumed by ExecutionPolicy.
     */
    private suspend fun retryTaskOnlyIfApprovalArrivedAfterItsAttempt(request: PermissionRequest) {
        val engine = taskEngineProvider() ?: return
        val taskId = request.target
        val task = withTimeoutOrNull(45_000L) {
            while (true) {
                val current = engine.getTask(taskId) ?: return@withTimeoutOrNull null
                when (current.state) {
                    TaskState.FAILED -> return@withTimeoutOrNull current
                    TaskState.COMPLETED, TaskState.CANCELLED, TaskState.ERROR -> return@withTimeoutOrNull null
                    else -> delay(100)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        if (task?.state == TaskState.FAILED) engine.retry(taskId)
    }
}
