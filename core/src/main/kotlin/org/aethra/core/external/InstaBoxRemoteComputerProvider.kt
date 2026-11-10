package org.aethra.core.external

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * Real InstaBox HTTP API adapter.
 *
 * Credentials are injected through [InstaBoxApiKeyProvider] and are never persisted
 * by this provider. The transport is injectable for deterministic Core tests.
 *
 * The machine ID is obtained from [machineIdProvider], allowing the platform to
 * change the selected machine without rebuilding this provider.
 */
class InstaBoxRemoteComputerProvider(
    private val machineIdProvider: () -> String,
    private val transport: InstaBoxTransport,
    private val apiKeyProvider: InstaBoxApiKeyProvider
) : ExternalCapability {
    override val id: String = "instabox.remote"
    override val type: ExternalCapabilityType = ExternalCapabilityType.REMOTE_COMPUTER
    override val displayName: String = "InstaBox Remote Computer"
    override val capabilities: Set<String> = setOf(
        "machine.list",
        "machine.get",
        "machine.start",
        "machine.stop",
        "machine.suspend",
        "machine.resume",
        "remote.exec"
    )

    private val machineId: String
        get() = machineIdProvider()

    override suspend fun health(): CapabilityHealth {
        if (apiKeyProvider.apiKey()?.isNotBlank() != true) return CapabilityHealth.UNAVAILABLE
        if (machineId.isBlank()) return CapabilityHealth.UNAVAILABLE
        return runCatching {
            val response = transport.request("GET", "/v1/machines/$machineId", headers = headers())
            if (response.statusCode in 200..299) CapabilityHealth.READY
            else CapabilityHealth.DEGRADED
        }.getOrElse { CapabilityHealth.UNAVAILABLE }
    }

    override suspend fun execute(request: CapabilityRequest): Result<CapabilityResult> {
        apiKeyProvider.apiKey()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("InstaBox API key is not configured"))

        if (machineId.isBlank()) {
            return Result.failure(IllegalStateException("InstaBox machine is not selected"))
        }

        return runCatching {
            when (request.operation) {
                "machine.list" -> listMachines()
                "machine.get" -> machineGet()
                "machine.start" -> lifecycle("start")
                "machine.stop" -> lifecycle("stop")
                "machine.suspend" -> lifecycle("suspend")
                "machine.resume" -> lifecycle("resume")
                "remote.exec" -> exec(request)
                else -> throw IllegalArgumentException("unsupported InstaBox operation: ${request.operation}")
            }
        }
    }

    private suspend fun listMachines(): CapabilityResult {
        val response = transport.request(
            "GET",
            "/v1/machines",
            headers = headers()
        )
        return response.toResult("machine.list")
    }

    private suspend fun machineGet(): CapabilityResult {
        val response = transport.request(
            "GET",
            "/v1/machines/$machineId",
            headers = headers()
        )
        return response.toResult("machine.get")
    }

    private suspend fun lifecycle(action: String): CapabilityResult {
        val response = transport.request(
            "POST",
            "/v1/machines/$machineId/$action",
            headers = headers() + ("Idempotency-Key" to java.util.UUID.randomUUID().toString())
        )
        return response.toResult("machine.$action")
    }

    private suspend fun exec(request: CapabilityRequest): CapabilityResult {
        val command = request.arguments["cmd"]?.trim()
            ?: throw IllegalArgumentException("remote.exec requires 'cmd'")
        if (command.isEmpty()) throw IllegalArgumentException("remote.exec command must not be empty")

        val body = buildJsonObject { put("cmd", command) }.toString()
        val response = transport.request(
            "POST",
            "/v1/machines/$machineId/exec",
            body = body,
            headers = headers()
        )
        return response.toResult("remote.exec")
    }

    private fun headers(): Map<String, String> {
        val key = apiKeyProvider.apiKey()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(
            "X-API-Key" to key,
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    }

    private fun InstaBoxHttpResponse.toResult(operation: String): CapabilityResult {
        val success = statusCode in 200..299
        return CapabilityResult(
            capabilityId = id,
            operation = operation,
            success = success,
            output = if (body.isBlank()) emptyMap() else mapOf("body" to body),
            evidence = listOf("http:$statusCode"),
            error = if (success) null else "InstaBox HTTP $statusCode"
        )
    }
}
