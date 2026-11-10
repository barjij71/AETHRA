package org.aethra.core.external

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstaBoxRemoteComputerProviderTest {
    @Test
    fun lifecycleUsesIdempotencyKeyAndCorrectEndpoint() = runBlocking {
        val transport = RecordingTransport(InstaBoxHttpResponse(200, """{"status":"running"}"""))
        val provider = InstaBoxRemoteComputerProvider(
            machineIdProvider = { "machine-123" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { "ibk_test" }
        )

        val result = provider.execute(
            CapabilityRequest(operation = "machine.start")
        ).getOrThrow()

        assertTrue(result.success)
        assertEquals("POST", transport.method)
        assertEquals("/v1/machines/machine-123/start", transport.path)
        assertEquals("ibk_test", transport.headers["X-API-Key"])
        assertTrue(transport.headers["Idempotency-Key"]?.isNotBlank() == true)
    }

    @Test
    fun healthWithoutApiKeyDoesNotCallNetwork() = runBlocking {
        val transport = RecordingTransport(InstaBoxHttpResponse(200, "{}"))
        val provider = InstaBoxRemoteComputerProvider(
            machineIdProvider = { "machine-123" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { null }
        )

        assertEquals(CapabilityHealth.UNAVAILABLE, provider.health())
        assertTrue(transport.method == null)
    }

    @Test
    fun execUsesApiKeyAndMachineEndpoint() = runBlocking {
        val transport = RecordingTransport(InstaBoxHttpResponse(200, """{"exit":0,"stdout":"ok"}"""))
        val provider = InstaBoxRemoteComputerProvider(
            machineIdProvider = { "machine-123" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { "ibk_test" }
        )

        val result = provider.execute(
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "printf ok")
            )
        ).getOrThrow()

        assertTrue(result.success)
        assertEquals("POST", transport.method)
        assertEquals("/v1/machines/machine-123/exec", transport.path)
        assertEquals("ibk_test", transport.headers["X-API-Key"])
        assertTrue(transport.body?.contains("printf ok") == true)
    }

    @Test
    fun missingApiKeyFailsBeforeNetworkCall() = runBlocking {
        val transport = RecordingTransport(InstaBoxHttpResponse(200, "{}"))
        val provider = InstaBoxRemoteComputerProvider(
            machineIdProvider = { "machine-123" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { null }
        )

        val result = provider.execute(
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "echo ok")
            )
        )

        assertTrue(result.isFailure)
        assertTrue(transport.method == null)
    }

    @Test
    fun missingSelectedMachineFailsBeforeNetworkCall() = runBlocking {
        val transport = RecordingTransport(InstaBoxHttpResponse(200, "{}"))
        val provider = InstaBoxRemoteComputerProvider(
            machineIdProvider = { "" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { "ibk_test" }
        )

        val result = provider.execute(
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "echo ok")
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("InstaBox machine is not selected"))
        assertTrue(transport.method == null)
    }

    @Test
    fun providerNeverExposesApiKeyInResult() = runBlocking {
        val transport = RecordingTransport(InstaBoxHttpResponse(200, """{"exit":0,"stdout":"ok"}"""))
        val provider = InstaBoxRemoteComputerProvider(
            machineIdProvider = { "machine-123" },
            transport = transport,
            apiKeyProvider = InstaBoxApiKeyProvider { "ibk_test_secret" }
        )

        val result = provider.execute(
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "echo ok")
            )
        ).getOrThrow()

        assertTrue(result.success)
        // API key must never appear in output or evidence
        assertFalse(result.output.values.any { it.toString().contains("ibk_test_secret") })
        assertFalse(result.evidence.any { it.contains("ibk_test_secret") })
        assertFalse(result.error?.contains("ibk_test_secret") == true)
        // Health check also must not expose key
        assertTrue(transport.headers?.get("X-API-Key") == "ibk_test_secret")
        assertTrue(transport.headers.size == 3) // X-API-Key, Content-Type, Accept
    }

    @Test
    fun blockedOperationIsRejectedByRouter() = runBlocking {
        val registry = ExternalCapabilityRegistry()
        registry.register(
            InstaBoxRemoteComputerProvider(
                machineIdProvider = { "machine-123" },
                transport = RecordingTransport(InstaBoxHttpResponse(200, "{}")),
                apiKeyProvider = InstaBoxApiKeyProvider { "ibk_test" }
            )
        )

        val result = ExternalCapabilityRouter(registry).execute(
            "instabox.remote",
            CapabilityRequest(
                operation = "remote.exec",
                arguments = mapOf("cmd" to "port_scan localhost")
            )
        )

        assertTrue(result.isFailure)
    }

    private class RecordingTransport(
        private val response: InstaBoxHttpResponse
    ) : InstaBoxTransport {
        var method: String? = null
        var path: String? = null
        var body: String? = null
        var headers: Map<String, String> = emptyMap()

        override suspend fun request(
            method: String,
            path: String,
            body: String?,
            headers: Map<String, String>
        ): InstaBoxHttpResponse {
            this.method = method
            this.path = path
            this.body = body
            this.headers = headers
            return response
        }
    }
}
