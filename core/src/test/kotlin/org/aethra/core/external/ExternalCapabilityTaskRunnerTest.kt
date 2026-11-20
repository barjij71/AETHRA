package org.aethra.core.external

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.ApprovalGrant
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.core.runtime.events.EventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalCapabilityTaskRunnerTest {

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    private class FakeTransport(
        private val responses: Map<String, InstaBoxHttpResponse>
    ) : InstaBoxTransport {
        var lastMethod: String? = null
        var lastPath: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()

        override suspend fun request(
            method: String,
            path: String,
            body: String?,
            headers: Map<String, String>
        ): InstaBoxHttpResponse {
            lastMethod = method
            lastPath = path
            lastBody = body
            lastHeaders = headers
            return responses[path] ?: InstaBoxHttpResponse(404, "not found")
        }
    }

    private class StorageStub : Storage {
        private val data = mutableMapOf<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = data[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { data[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull()
        override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = null
        override suspend fun setBoolean(key: String, value: Boolean) {}
        override suspend fun getAll(): Map<String, String> = data.toMap()
        override suspend fun remove(key: String) { data.remove(key) }
        override suspend fun clear() { data.clear() }
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private fun provider(
        transport: InstaBoxTransport,
        apiKey: String? = "ibk_test"
    ): InstaBoxRemoteComputerProvider =
        InstaBoxRemoteComputerProvider(
            machineIdProvider = { "machine-123" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { apiKey }
        )

    private fun router(
        transport: InstaBoxTransport,
        apiKey: String? = "ibk_test",
        auditLog: AuditLog? = null,
        permissionBroker: PermissionBroker? = null
    ): ExternalCapabilityRouter {
        val registry = ExternalCapabilityRegistry()
        registry.register(provider(transport, apiKey))
        return ExternalCapabilityRouter(registry, permissionBroker, auditLog)
    }

    // -----------------------------------------------------------------------
    // Tests: missing API key
    // -----------------------------------------------------------------------

    @Test
    fun missingApiKeyFailsBeforeNetworkCall() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123" to InstaBoxHttpResponse(200, "{}"))
        )
        val router = router(transport, apiKey = null)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.get")
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("API key is not configured"))
        assertNull(transport.lastMethod)
    }

    // -----------------------------------------------------------------------
    // Tests: health request
    // -----------------------------------------------------------------------

    @Test
    fun healthRequestReturnsReady() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123" to InstaBoxHttpResponse(200, """{"status":"running"}"""))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.get")
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("machine.get", result.operation)
        assertTrue(result.evidence.contains("http:200"))
    }

    // -----------------------------------------------------------------------
    // Tests: lifecycle requests
    // -----------------------------------------------------------------------

    @Test
    fun machineStartUsesCorrectEndpoint() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123/start" to InstaBoxHttpResponse(200, """{"status":"started"}"""))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.start")
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("machine.start", result.operation)
        assertTrue(result.evidence.contains("http:200"))
    }

    @Test
    fun machineStopUsesCorrectEndpoint() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123/stop" to InstaBoxHttpResponse(200, """{"status":"stopped"}"""))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.stop")
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("machine.stop", result.operation)
    }

    @Test
    fun machineSuspendUsesCorrectEndpoint() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123/suspend" to InstaBoxHttpResponse(200, """{"status":"suspended"}"""))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.suspend")
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("machine.suspend", result.operation)
    }

    @Test
    fun machineResumeUsesCorrectEndpoint() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123/resume" to InstaBoxHttpResponse(200, """{"status":"running"}"""))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.resume")
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("machine.resume", result.operation)
    }

    // -----------------------------------------------------------------------
    // Tests: remote command execution
    // -----------------------------------------------------------------------

    @Test
    fun remoteExecSendsCommandAndReturnsStructuredResult() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123/exec" to InstaBoxHttpResponse(200, """{"exit":0,"stdout":"hello world"}"""))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "echo hello world")
            )
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("remote.exec", result.operation)
        assertTrue(result.evidence.contains("http:200"))
        assertNull(result.error)
    }

    @Test
    fun remoteExecRequiresNonEmptyCommand() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "")
            )
        )
        assertTrue(result.isFailure)
        assertNull(transport.lastMethod)
    }

    // -----------------------------------------------------------------------
    // Tests: policy rejection
    // -----------------------------------------------------------------------

    @Test
    fun blockedOperationIsRejectedByPolicy() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "port_scan localhost")
            )
        )
        assertTrue(result.isFailure)
        assertNull(transport.lastMethod)
    }

    @Test
    fun cryptoMiningIsRejectedByPolicy() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "crypto_mining --algorithm sha256")
            )
        )
        assertTrue(result.isFailure)
        assertNull(transport.lastMethod)
    }

    // -----------------------------------------------------------------------
    // Tests: approval-required operation
    // -----------------------------------------------------------------------

    @Test
    fun sensitiveOperationRequiresApproval() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val storage = StorageStub()
        val auditLog = AuditLog(storage)
        val permissionBroker = PermissionBroker(auditLog)
        val router = ExternalCapabilityRouter(
            ExternalCapabilityRegistry().apply {
                register(provider(transport))
            },
            permissionBroker = permissionBroker,
            auditLog = auditLog
        )
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "machine.start",
                riskClass = RiskClass.SENSITIVE,
                requiresApproval = true
            )
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("permission_required"))
    }

    // -----------------------------------------------------------------------
    // Tests: audit evidence
    // -----------------------------------------------------------------------

    @Test
    fun successfulOperationIsAudited() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123" to InstaBoxHttpResponse(200, "{}"))
        )
        val storage = StorageStub()
        val auditLog = AuditLog(storage)
        val router = router(transport, auditLog = auditLog)
        router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.get")
        ).getOrThrow()

        val executeEntry = auditLog.all().first { it.operation == "external.execute" }
        assertEquals("instabox.remote", executeEntry.target)
        assertEquals("GRANTED", executeEntry.decision)
        assertEquals("SUCCESS", executeEntry.outcome)
    }

    @Test
    fun failedOperationIsAudited() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123" to InstaBoxHttpResponse(500, "error"))
        )
        val storage = StorageStub()
        val auditLog = AuditLog(storage)
        val router = router(transport, auditLog = auditLog)
        router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.get")
        )

        val executeEntry = auditLog.all().first { it.operation == "external.execute" }
        assertEquals("DENIED", executeEntry.decision)
        assertEquals("FAILED", executeEntry.outcome)
    }

    @Test
    fun rejectedOperationIsAudited() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val storage = StorageStub()
        val auditLog = AuditLog(storage)
        val router = router(transport, auditLog = auditLog)
        router.execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "port_scan localhost")
            )
        )

        val rejectEntry = auditLog.all().first { it.operation == "external.policy.reject" }
        assertEquals("DENIED", rejectEntry.decision)
        assertEquals("POLICY_REJECTED", rejectEntry.outcome)
    }

    // -----------------------------------------------------------------------
    // Tests: provider failure propagation
    // -----------------------------------------------------------------------

    @Test
    fun providerFailureReturnsErrorResult() = runBlocking {
        val transport = FakeTransport(
            mapOf("/v1/machines/machine-123" to InstaBoxHttpResponse(500, "internal error"))
        )
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "machine.get")
        )
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().success)
        assertTrue(result.getOrThrow().error!!.contains("500"))
    }

    @Test
    fun unknownCapabilityIdFails() = runBlocking {
        val registry = ExternalCapabilityRegistry()
        val router = ExternalCapabilityRouter(registry)
        val result = router.execute(
            "nonexistent.remote",
            CapabilityRequest(operation = "machine.get")
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found"))
    }

    @Test
    fun unknownOperationIsRejected() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val router = router(transport)
        val result = router.execute(
            "instabox.remote",
            CapabilityRequest(operation = "nonexistent.op")
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not supported"))
    }
}
