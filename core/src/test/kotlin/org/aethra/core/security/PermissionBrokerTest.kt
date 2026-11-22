package org.aethra.core.security

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AuditLogTest {
    @Test
    fun recordsAndLoadsEntries() = runBlocking {
        val store = TestStorage()
        val log = AuditLog(store)
        val entry = org.aethra.core.domain.AuditEntry(
            id = "entry-task-1", actor = "test", operation = "tool-1", target = "task-1",
            riskClass = RiskClass.READ_ONLY, decision = "GRANTED", outcome = "success", timestamp = 100L
        )

        log.record(entry)

        assertEquals(listOf(entry), log.all())
    }
}

class PermissionBrokerTest {
    @Test
    fun pendingRequestsAreObservableAndWaiterReceivesTheExactDecision() = runBlocking {
        val broker = broker()
        val request = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "task-observed", "message.send")

        assertEquals(listOf(request.id), broker.pendingRequests.value.map { it.id })
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { broker.awaitDecision(request.id) }
        val approved = broker.decide(request.id, PermissionDecision.APPROVE, "approved")

        assertEquals(request.id, waiter.await().id)
        assertEquals(PermissionRequestState.APPROVED, approved.state)
        assertTrue(broker.pendingRequests.value.isEmpty())
        assertEquals(approved, broker.getRequest(request.id))
    }

    @Test
    fun sensitiveRequestStartsUnresolvedAndCannotBeginExecution() = runBlocking {
        val log = AuditLog(TestStorage())
        val broker = PermissionBroker(log)
        val request = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "task-1", "message.send")

        assertEquals(PermissionRequestState.REQUESTED, request.state)
        assertTrue(runCatching { broker.beginExecution(request.id, request.riskClass, request.operation, request.capability, request.target) }.isFailure)
        assertEquals(PermissionRequestState.REQUESTED, broker.getRequest(request.id)?.state)
        assertTrue(log.all().any { it.target == request.id && it.operation == "permission.execution.blocked" })
    }

    @Test
    fun approveAllowsOnlyTheMatchingRequestToBeginAndComplete() = runBlocking {
        val log = AuditLog(TestStorage())
        val broker = PermissionBroker(log)
        val request = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "task-2", "message.send")
        val approved = broker.decide(request.id, PermissionDecision.APPROVE, "user approved")

        assertEquals(PermissionRequestState.APPROVED, approved.state)
        val executing = broker.beginExecution(request.id, request.riskClass, request.operation, request.capability, request.target)
        assertEquals(PermissionRequestState.EXECUTING, executing.state)
        val completed = broker.completeExecution(request.id, succeeded = true, reason = "operation completed")
        assertEquals(PermissionRequestState.COMPLETED, completed.state)
        assertTrue(runCatching { broker.beginExecution(request.id, request.riskClass, request.operation, request.capability, request.target) }.isFailure)
    }

    @Test
    fun deniedRequestCannotBeginExecution() = runBlocking {
        val broker = broker()
        val request = broker.requestApproval("agent", RiskClass.SENSITIVE, "delete", "task-3", "file.delete")
        val denied = broker.decide(request.id, PermissionDecision.DENY, "user denied")

        assertEquals(PermissionRequestState.DENIED, denied.state)
        assertTrue(runCatching { broker.beginExecution(request.id, request.riskClass, request.operation, request.capability, request.target) }.isFailure)
    }

    @Test
    fun requestedOrApprovedCancellationIsFinalBeforeExecution() = runBlocking {
        val broker = broker()
        val requested = broker.requestApproval("agent", RiskClass.SENSITIVE, "write", "task-4", "file.write")
        assertEquals(PermissionRequestState.CANCELLED, broker.decide(requested.id, PermissionDecision.CANCEL, "user cancelled").state)
        assertTrue(runCatching { broker.beginExecution(requested.id, requested.riskClass, requested.operation, requested.capability, requested.target) }.isFailure)

        val approved = broker.requestApproval("agent", RiskClass.SENSITIVE, "update", "task-5", "device.update")
        broker.decide(approved.id, PermissionDecision.APPROVE, "user approved")
        assertEquals(PermissionRequestState.CANCELLED, broker.decide(approved.id, PermissionDecision.CANCEL, "cancel before start").state)
        assertTrue(runCatching { broker.beginExecution(approved.id, approved.riskClass, approved.operation, approved.capability, approved.target) }.isFailure)
    }

    @Test
    fun approvalCannotAuthorizeAnotherRequestOperationOrCapability() = runBlocking {
        val broker = broker()
        val request = broker.requestApproval(
            "agent", RiskClass.SENSITIVE, "send", "task-6", "message.send",
            capabilities = setOf("message.send", "device.info")
        )
        broker.decide(request.id, PermissionDecision.APPROVE, "approved")

        assertTrue(runCatching { broker.beginExecution(request.id, request.riskClass, "delete", request.capability, request.target) }.isFailure)
        assertTrue(runCatching { broker.beginExecution(request.id, request.riskClass, request.operation, "file.delete", request.target) }.isFailure)
        assertTrue(runCatching { broker.beginExecution(request.id, request.riskClass, request.operation, request.capability, "other-task") }.isFailure)
        assertEquals(PermissionRequestState.APPROVED, broker.getRequest(request.id)?.state)
        assertEquals(
            PermissionRequestState.EXECUTING,
            broker.beginExecution(
                request.id, request.riskClass, request.operation, request.capability, request.target,
                capabilities = setOf("message.send", "device.info")
            ).state
        )
    }

    @Test
    fun conflictingSecondDecisionIsRejected() = runBlocking {
        val broker = broker()
        val request = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "task-7")
        broker.decide(request.id, PermissionDecision.APPROVE, "approved")

        assertTrue(runCatching { broker.decide(request.id, PermissionDecision.DENY, "conflicting") }.isFailure)
        assertEquals(PermissionRequestState.APPROVED, broker.getRequest(request.id)?.state)
    }

    @Test
    fun decisionsAreAuditedAsUserDecisionsWithoutAcceptingAnActorClaim() = runBlocking {
        val log = AuditLog(TestStorage())
        val broker = PermissionBroker(log) { 100L }
        val request = broker.requestApproval("agent-1", RiskClass.SENSITIVE, "send", "task-8", "message.send")
        broker.decide(request.id, PermissionDecision.APPROVE, "user approved this operation")

        val entries = log.all()
        assertTrue(entries.any { it.target == request.id && it.operation == "permission.request" && it.decision == "PENDING" })
        assertTrue(entries.any {
            it.target == request.id && it.operation == "permission.approved" &&
                it.actor == "user" && it.outcome == "decision_recorded"
        })
        assertFalse(entries.any { it.outcome.contains("user approved this operation") })
        assertEquals(100L, entries.first { it.operation == "permission.approved" }.timestamp)
    }

    @Test
    fun decisionsAreNotPermanentGrantsAndBrokerRecreationDoesNotRestoreThem() = runBlocking {
        val storage = TestStorage()
        val broker = PermissionBroker(AuditLog(storage))
        val first = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "task-9")
        broker.decide(first.id, PermissionDecision.APPROVE, "approved")
        broker.beginExecution(first.id, first.riskClass, first.operation, first.capability, first.target)
        broker.completeExecution(first.id, succeeded = true, reason = "done")

        val next = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "task-9")
        val restored = PermissionBroker(AuditLog(storage))

        assertNotEquals(first.id, next.id)
        assertEquals(PermissionRequestState.REQUESTED, next.state)
        assertNull(restored.getRequest(first.id))
    }

    private fun broker(): PermissionBroker = PermissionBroker(AuditLog(TestStorage()))

}

private class TestStorage : Storage {
    private val data = ConcurrentHashMap<String, String>()
    override suspend fun getString(key: String, defaultValue: String?): String? = data[key] ?: defaultValue
    override suspend fun setString(key: String, value: String) { data[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
    override suspend fun setLong(key: String, value: Long) = Unit
    override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
    override suspend fun setBoolean(key: String, value: Boolean) = Unit
    override suspend fun getAll(): Map<String, String> = data.toMap()
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun clear() { data.clear() }
}
