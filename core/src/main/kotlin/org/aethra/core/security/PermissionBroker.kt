package org.aethra.core.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import java.util.UUID

enum class PermissionDecision { APPROVE, DENY, CANCEL }

enum class PermissionRequestState {
    REQUESTED,
    APPROVED,
    DENIED,
    CANCELLED,
    EXECUTING,
    COMPLETED,
    EXECUTION_FAILED
}

/** One operation-scoped authorization request. It is transient; only audit entries are durable. */
data class PermissionRequest(
    val id: String,
    val requester: String,
    val riskClass: RiskClass,
    val operation: String,
    val capability: String,
    val capabilities: Set<String> = setOf(capability),
    val target: String,
    val state: PermissionRequestState,
    val requestedAt: Long,
    val decision: PermissionDecision? = null,
    val reason: String? = null,
    val decidedAt: Long? = null,
    val executionStartedAt: Long? = null,
    val completedAt: Long? = null
)

class PermissionAuthorizationException(
    val requestId: String?,
    val code: String,
    message: String
) : IllegalStateException(message)

/**
 * Operation-scoped authorization gate. A request cannot execute until a user
 * decision explicitly approves its exact operation, capability and target.
 * Requests and grants are transient; every transition is written to AuditLog
 * before the new state is published in memory.
 */
class PermissionBroker(
    private val auditLog: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val requests = LinkedHashMap<String, PermissionRequest>()
    private val mutationMutex = Mutex()
    private val mutableRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())
    private val mutablePendingRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())

    private val requestUpdates: StateFlow<List<PermissionRequest>> = mutableRequests.asStateFlow()

    /** Pending-only projection for presentation; decisions always go through [decide]. */
    val pendingRequests: StateFlow<List<PermissionRequest>> = mutablePendingRequests.asStateFlow()

    suspend fun requestApproval(
        requester: String,
        risk: RiskClass,
        operation: String,
        target: String,
        capability: String = operation,
        requestId: String? = null,
        capabilities: Set<String> = setOf(capability)
    ): PermissionRequest = mutationMutex.withLock {
        require(requester.isNotBlank()) { "requester must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(capability.isNotBlank()) { "capability must not be blank" }
        require(target.isNotBlank()) { "target must not be blank" }
        require(requestId == null || requestId.isNotBlank()) { "requestId must not be blank" }
        val requiredCapabilities = capabilities.toSortedSet()
        require(requiredCapabilities.isNotEmpty() && requiredCapabilities.none(String::isBlank)) {
            "capabilities must contain non-blank values"
        }

        requestId?.let { existingId ->
            requests[existingId]?.let { existing ->
                val sameScope = existing.requester == requester && existing.riskClass == risk &&
                    existing.operation == operation && existing.capability == capability &&
                    existing.capabilities == requiredCapabilities && existing.target == target
                if (!sameScope) {
                    throw PermissionAuthorizationException(existingId, "request_scope_mismatch", "Request ID belongs to another operation")
                }
                return@withLock existing
            }
        }

        val now = clock()
        val request = PermissionRequest(
            id = requestId ?: UUID.randomUUID().toString(),
            requester = requester,
            riskClass = risk,
            operation = operation,
            capability = capability,
            capabilities = requiredCapabilities,
            target = target,
            state = PermissionRequestState.REQUESTED,
            requestedAt = now
        )
        audit(
            request,
            operation = "permission.request",
            actor = "aethra.runtime",
            decision = "PENDING",
            outcome = "requested"
        )
        requests[request.id] = request
        publishRequests()
        request
    }

    suspend fun getRequest(requestId: String): PermissionRequest? = mutationMutex.withLock {
        requests[requestId]
    }

    /** Wait for the broker's existing state to leave REQUESTED; cancellation propagates naturally. */
    suspend fun awaitDecision(requestId: String): PermissionRequest {
        val current = getRequest(requestId)
            ?: throw PermissionAuthorizationException(requestId, "unknown_request", "Permission request not found")
        if (current.state != PermissionRequestState.REQUESTED) return current
        return requestUpdates.first { snapshot ->
            snapshot.any { it.id == requestId && it.state != PermissionRequestState.REQUESTED }
        }.first { it.id == requestId }
    }

    /** UI-facing decision API. The caller cannot supply an actor identity. */
    suspend fun decide(
        requestId: String,
        decision: PermissionDecision,
        reason: String
    ): PermissionRequest = mutationMutex.withLock {
        require(reason.isNotBlank()) { "decision reason must not be blank" }
        val current = requests[requestId]
            ?: throw PermissionAuthorizationException(requestId, "unknown_request", "Permission request not found")

        val nextState = when (decision) {
            PermissionDecision.APPROVE -> {
                check(current.state == PermissionRequestState.REQUESTED) {
                    "Only a requested operation can be approved"
                }
                PermissionRequestState.APPROVED
            }
            PermissionDecision.DENY -> {
                check(current.state == PermissionRequestState.REQUESTED) {
                    "Only a requested operation can be denied"
                }
                PermissionRequestState.DENIED
            }
            PermissionDecision.CANCEL -> {
                check(current.state in setOf(PermissionRequestState.REQUESTED, PermissionRequestState.APPROVED)) {
                    "An operation can only be cancelled before execution starts"
                }
                PermissionRequestState.CANCELLED
            }
        }

        val now = clock()
        val updated = current.copy(
            state = nextState,
            decision = decision,
            reason = reason,
            decidedAt = now
        )
        audit(
            current,
            operation = "permission.${nextState.name.lowercase()}",
            actor = "user",
            decision = decision.name,
            outcome = "decision_recorded",
            timestamp = now
        )
        requests[requestId] = updated
        publishRequests()
        updated
    }

    /** Atomically consumes a matching approved request at the execution boundary. */
    suspend fun beginExecution(
        requestId: String,
        risk: RiskClass,
        operation: String,
        capability: String,
        target: String,
        capabilities: Set<String> = setOf(capability)
    ): PermissionRequest = mutationMutex.withLock {
        val current = requests[requestId]
            ?: throw PermissionAuthorizationException(requestId, "unknown_request", "Permission request not found")
        val matches = current.riskClass == risk && current.operation == operation &&
            current.capability == capability && current.capabilities == capabilities.toSortedSet() &&
            current.target == target
        if (!matches || current.state != PermissionRequestState.APPROVED) {
            audit(
                current,
                operation = "permission.execution.blocked",
                actor = "aethra.runtime",
                decision = "DENIED",
                outcome = if (!matches) "request_scope_mismatch" else "authorization_${current.state.name.lowercase()}"
            )
            throw PermissionAuthorizationException(
                requestId,
                if (!matches) "request_scope_mismatch" else "authorization_not_approved",
                "Execution requires an approved matching permission request"
            )
        }

        val now = clock()
        val updated = current.copy(state = PermissionRequestState.EXECUTING, executionStartedAt = now)
        audit(
            current,
            operation = "permission.execution.allowed",
            actor = "aethra.runtime",
            decision = "GRANTED",
            outcome = "execution_started",
            timestamp = now
        )
        requests[requestId] = updated
        publishRequests()
        updated
    }

    suspend fun completeExecution(
        requestId: String,
        succeeded: Boolean,
        reason: String
    ): PermissionRequest = mutationMutex.withLock {
        require(reason.isNotBlank()) { "execution result reason must not be blank" }
        val current = requests[requestId]
            ?: throw PermissionAuthorizationException(requestId, "unknown_request", "Permission request not found")
        check(current.state == PermissionRequestState.EXECUTING) {
            "Only an executing operation can be completed"
        }
        val now = clock()
        val nextState = if (succeeded) PermissionRequestState.COMPLETED else PermissionRequestState.EXECUTION_FAILED
        val updated = current.copy(state = nextState, reason = reason, completedAt = now)
        audit(
            current,
            operation = "permission.execution.${if (succeeded) "completed" else "failed"}",
            actor = "aethra.runtime",
            decision = if (succeeded) "GRANTED" else "FAILED",
            outcome = if (succeeded) "execution_succeeded" else "execution_failed",
            timestamp = now
        )
        requests[requestId] = updated
        publishRequests()
        updated
    }

    private fun publishRequests() {
        val snapshot = requests.values.toList()
        mutableRequests.value = snapshot
        mutablePendingRequests.value = snapshot.filter { it.state == PermissionRequestState.REQUESTED }
    }

    private suspend fun audit(
        request: PermissionRequest,
        operation: String,
        actor: String,
        decision: String,
        outcome: String,
        timestamp: Long = clock()
    ) {
        auditLog.record(
            AuditEntry(
                id = UUID.randomUUID().toString(),
                actor = actor,
                operation = operation,
                target = request.id,
                riskClass = request.riskClass,
                decision = decision,
                outcome = outcome,
                timestamp = timestamp
            )
        )
    }
}
