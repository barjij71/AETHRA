package org.aethra.assistant.aethra.permissions

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.ExecutionPolicy
import org.aethra.core.security.PermissionBroker
import org.aethra.core.security.PermissionDecision
import org.aethra.core.security.PermissionRequestState
import org.aethra.core.security.PolicyEngine
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.Tool
import org.aethra.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AndroidApprovalSurfaceContractTest {
    @Test
    fun pendingRequestStateSurvivesApprovalSurfaceRecreation() = runBlocking {
        val broker = broker()
        val request = broker.requestApproval("aethra.runtime", RiskClass.SENSITIVE, "device.update", "safe-ref", "device.update")
        val firstSurface = AndroidApprovalSurface(broker) { null }
        val recreatedSurface = AndroidApprovalSurface(broker) { null }

        assertEquals(request.id, firstSurface.pendingRequests.value.single().id)
        assertEquals(request.id, recreatedSurface.pendingRequests.value.single().id)
        assertEquals("device.update", recreatedSurface.pendingRequests.value.single().operation)
        assertEquals(setOf("device.update"), recreatedSurface.pendingRequests.value.single().capabilities)
        firstSurface.close()
        recreatedSurface.close()
    }

    @Test
    fun approveUsesExactCoreIdAndExecutionPolicyConsumesItOnce() = runBlocking {
        val audit = AuditLog(TestStorage())
        val broker = PermissionBroker(audit)
        val tool = sensitiveTool()
        val registry = ToolRegistry().also { it.register(tool) }
        val policy = ExecutionPolicy(PolicyEngine(), broker, audit, successfulTaskRunner(), registry)
        val task = Task("device.update")
        val executions = AtomicInteger()
        val initial = policy.execute(task, tool, null, emptyMap()) {
            executions.incrementAndGet()
            Result.success(mapOf("done" to true))
        }
        val surface = AndroidApprovalSurface(broker) { null }
        val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
            surface.awaitDecisionAndResume(task.id, initial) { approvedId ->
                policy.execute(task, tool, null, emptyMap(), approvalRequestId = approvedId) {
                    executions.incrementAndGet()
                    Result.success(mapOf("done" to true))
                }
            }
        }

        assertFalse(initial.isSuccess)
        assertEquals(task.id, surface.pendingRequests.value.single().id)
        surface.decide(task.id, PermissionDecision.APPROVE)
        assertTrue(dispatch.await().isSuccess)
        assertEquals(1, executions.get())
        assertEquals(PermissionRequestState.COMPLETED, broker.getRequest(task.id)?.state)
        assertTrue(audit.all().any { it.target == task.id && it.operation == "permission.approved" })
        assertTrue(policy.execute(task, tool, null, emptyMap(), approvalRequestId = task.id) {
            executions.incrementAndGet()
            Result.success(mapOf("done" to true))
        }.isFailure)
        assertEquals(1, executions.get())
        surface.close()
    }

    @Test
    fun denyAndCancelBlockExecutionAndStaleOrDuplicateIdsCannotAuthorize() = runBlocking {
        for (decision in listOf(PermissionDecision.DENY, PermissionDecision.CANCEL)) {
            val audit = AuditLog(TestStorage())
            val broker = PermissionBroker(audit)
            val tool = sensitiveTool()
            val registry = ToolRegistry().also { it.register(tool) }
            val policy = ExecutionPolicy(PolicyEngine(), broker, audit, successfulTaskRunner(), registry)
            val task = Task("device.update")
            val executions = AtomicInteger()
            val initial = policy.execute(task, tool, null, emptyMap()) {
                executions.incrementAndGet()
                Result.success(emptyMap())
            }
            val surface = AndroidApprovalSurface(broker) { null }
            val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
                surface.awaitDecisionAndResume(task.id, initial) { approvedId ->
                    policy.execute(task, tool, null, emptyMap(), approvalRequestId = approvedId) {
                        executions.incrementAndGet()
                        Result.success(emptyMap())
                    }
                }
            }
            surface.decide(task.id, decision)
            assertTrue(dispatch.await().isFailure)
            assertEquals(0, executions.get())
            assertEquals(
                if (decision == PermissionDecision.DENY) PermissionRequestState.DENIED else PermissionRequestState.CANCELLED,
                broker.getRequest(task.id)?.state
            )
            assertTrue(runCatching { surface.decide("unknown-request", PermissionDecision.APPROVE) }.isFailure)
            assertTrue(runCatching { surface.decide(task.id, PermissionDecision.APPROVE) }.isFailure)
            assertTrue(policy.execute(task, tool, null, emptyMap(), approvalRequestId = task.id) {
                executions.incrementAndGet()
                Result.success(emptyMap())
            }.isFailure)
            assertEquals(0, executions.get())
            surface.close()
        }
    }

    private fun broker() = PermissionBroker(AuditLog(TestStorage()))

    private fun successfulTaskRunner() = object : TaskRunner {
        override suspend fun run(task: Task): Result<Map<String, Any>> = Result.success(emptyMap())
    }

    private fun sensitiveTool() = Tool(
        id = "device.update",
        description = "Update device state",
        category = "device",
        requiredCapabilities = listOf("device.update"),
        riskClass = RiskClass.SENSITIVE
    )
}

private class TestStorage : Storage {
    private val values = ConcurrentHashMap<String, String>()
    override suspend fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
    override suspend fun setString(key: String, value: String) { values[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?): Long? = defaultValue
    override suspend fun setLong(key: String, value: Long) = Unit
    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = defaultValue
    override suspend fun setBoolean(key: String, value: Boolean) = Unit
    override suspend fun getAll(): Map<String, String> = values.toMap()
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun clear() { values.clear() }
}
