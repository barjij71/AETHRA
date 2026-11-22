package org.aethra.core.security

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.external.CapabilityHealth
import org.aethra.core.external.CapabilityRequest
import org.aethra.core.external.CapabilityResult
import org.aethra.core.external.ExternalCapability
import org.aethra.core.external.ExternalCapabilityRegistry
import org.aethra.core.external.ExternalCapabilityRouter
import org.aethra.core.external.ExternalCapabilityType
import org.aethra.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SensitiveOperationPermissionContractTest {
    @Test
    fun `unresolved request blocks capability side effect until exact request is approved`() = runBlocking {
        val storage = MemoryStorage()
        val audit = AuditLog(storage)
        val broker = PermissionBroker(audit)
        val capability = RecordingCapability()
        val router = router(capability, broker, audit)
        val request = sensitiveRequest("remote.write")

        val blocked = router.execute(capability.id, request)

        assertTrue(blocked.isFailure)
        assertEquals(0, capability.executions)
        val requestId = blocked.exceptionOrNull()!!.message!!.substringAfter("permission_required:").substringBefore(":")
        assertEquals(PermissionRequestState.REQUESTED, broker.getRequest(requestId)?.state)

        broker.decide(requestId, PermissionDecision.APPROVE, "approved for this request")
        val completed = router.execute(capability.id, request.copy(approvalRequestId = requestId)).getOrThrow()

        assertTrue(completed.success)
        assertEquals(1, capability.executions)
        assertEquals(PermissionRequestState.COMPLETED, broker.getRequest(requestId)?.state)
        assertTrue(audit.all().any { it.operation == "permission.execution.allowed" && it.target == requestId })
        assertTrue(audit.all().any { it.operation == "permission.execution.completed" && it.target == requestId })
    }

    @Test
    fun `denied and cancelled requests never invoke the capability`() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val capability = RecordingCapability()
        val router = router(capability, broker, audit)

        val deniedRequest = sensitiveRequest("remote.write")
        val deniedId = requestIdFromFailure(router.execute(capability.id, deniedRequest).exceptionOrNull()!!)
        broker.decide(deniedId, PermissionDecision.DENY, "denied")
        assertTrue(router.execute(capability.id, deniedRequest.copy(approvalRequestId = deniedId)).isFailure)

        val cancelledRequest = sensitiveRequest("remote.delete")
        val cancelledId = requestIdFromFailure(router.execute(capability.id, cancelledRequest).exceptionOrNull()!!)
        broker.decide(cancelledId, PermissionDecision.CANCEL, "cancelled")
        assertTrue(router.execute(capability.id, cancelledRequest.copy(approvalRequestId = cancelledId)).isFailure)

        assertEquals(0, capability.executions)
        assertEquals(PermissionRequestState.DENIED, broker.getRequest(deniedId)?.state)
        assertEquals(PermissionRequestState.CANCELLED, broker.getRequest(cancelledId)?.state)
        assertTrue(audit.all().any { it.operation == "permission.execution.blocked" && it.target == deniedId })
        assertTrue(audit.all().any { it.operation == "permission.execution.blocked" && it.target == cancelledId })
    }

    @Test
    fun `approval for one operation cannot authorize another operation`() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val capability = RecordingCapability()
        val router = router(capability, broker, audit)
        val original = sensitiveRequest("remote.write")
        val requestId = requestIdFromFailure(router.execute(capability.id, original).exceptionOrNull()!!)
        broker.decide(requestId, PermissionDecision.APPROVE, "approved write only")

        val otherOperation = router.execute(
            capability.id,
            original.copy(operation = "remote.delete", approvalRequestId = requestId)
        )

        assertTrue(otherOperation.isFailure)
        assertEquals(0, capability.executions)
        assertEquals(PermissionRequestState.APPROVED, broker.getRequest(requestId)?.state)

        router.execute(capability.id, original.copy(approvalRequestId = requestId)).getOrThrow()
        assertEquals(1, capability.executions)
    }

    @Test
    fun `sensitive operation without broker fails closed`() = runBlocking {
        val capability = RecordingCapability()
        val router = router(capability, null, AuditLog(MemoryStorage()))

        val result = router.execute(capability.id, sensitiveRequest("remote.write"))

        assertTrue(result.isFailure)
        assertEquals(0, capability.executions)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no permission broker"))
    }

    private fun requestIdFromFailure(error: Throwable): String {
        val message = error.message ?: error.toString()
        return message.substringAfter("permission_required:").substringBefore(":")
    }

    private fun sensitiveRequest(operation: String) = CapabilityRequest(
        operation = operation,
        riskClass = RiskClass.SENSITIVE,
        requiresApproval = true,
        targetId = "task-contract"
    )

    private fun router(
        capability: RecordingCapability,
        broker: PermissionBroker?,
        audit: AuditLog
    ) = ExternalCapabilityRouter(
        ExternalCapabilityRegistry().apply { register(capability) },
        permissionBroker = broker,
        auditLog = audit
    )

    private class RecordingCapability : ExternalCapability {
        override val id = "remote.test"
        override val type = ExternalCapabilityType.REMOTE_COMPUTER
        override val displayName = "Recording capability"
        override val capabilities = setOf("remote.write", "remote.delete")
        var executions = 0
            private set

        override suspend fun health() = CapabilityHealth.READY
        override suspend fun execute(request: CapabilityRequest): Result<CapabilityResult> {
            executions++
            return Result.success(CapabilityResult(id, request.operation, success = true))
        }
    }

    private class MemoryStorage : Storage {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }
}
