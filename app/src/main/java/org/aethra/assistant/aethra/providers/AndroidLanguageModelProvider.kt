package org.aethra.assistant.aethra.providers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.aethra.core.providers.LanguageModelProvider
import org.aethra.core.providers.ProviderRequest
import org.aethra.core.providers.ProviderResponse
import org.aethra.core.providers.ProviderFailureKind
import org.aethra.core.providers.ProviderInvocationException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charsets

/** Android implementation of [LanguageModelProvider].
 *
 * Vendor-neutral HTTP provider adapter. The API key is supplied through
 * [AndroidApiKeyStore] and is never exposed through [ProviderRequest],
 * [ProviderResponse], [org.aethra.core.task.Task], audit log, or events.
 *
 * The endpoint and model are configured through [org.aethra.core.providers.ProviderDefinition]
 * and [org.aethra.core.providers.ModelDefinition] respectively — no credentials
 * are embedded in source.
 */
class AndroidLanguageModelProvider(
    private val apiKeyStore: AndroidApiKeyStore,
    private val endpoint: String,
    private val modelId: String,
    private val requestTimeoutMs: Long = 30_000
) : LanguageModelProvider {

    override val id: String = "android-provider"
    override val displayName: String = "Android Provider"
    override val supportsToolCalling: Boolean = false
    override val supportsStreaming: Boolean = false
    override val models: List<String> = listOf(modelId)

    override fun capabilities(): Map<String, Any> =
        mapOf(
            "supportsToolCalling" to supportsToolCalling,
            "supportsStreaming" to supportsStreaming,
            "models" to models
        )

    override suspend fun chat(
        messages: List<Map<String, Any>>,
        availableTools: List<Map<String, Any>>? = null
    ): kotlin.Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey == null || apiKey.isBlank()) {
            kotlin.Result.failure(
                ProviderInvocationException(
                    ProviderFailureKind.PROVIDER_UNAVAILABLE,
                    "Provider API key is not configured"
                )
            )
        } else {
            sendChatRequest(apiKey, messages)
        }
    }

    override suspend fun health(): kotlin.Result<Boolean> = withContext(Dispatchers.IO) {
        if (apiKeyStore.getApiKey() == null || apiKeyStore.getApiKey()?.isBlank() == true) {
            kotlin.Result.success(false)
        } else {
            try {
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = requestTimeoutMs.toInt()
                    readTimeout = requestTimeoutMs.toInt()
                    setRequestProperty("Authorization", "Bearer ${apiKeyStore.getApiKey()}")
                    doOutput = false
                }
                val statusCode = connection.responseCode
                kotlin.Result.success(statusCode in 200..299)
            } catch (_: Exception) {
                kotlin.Result.success(false)
            }
        }
    }

    /** Sends a chat request to the provider endpoint and returns the parsed response. */
    private suspend fun sendChatRequest(
        apiKey: String,
        messages: List<Map<String, Any>>
    ): kotlin.Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = requestTimeoutMs.toInt()
                readTimeout = requestTimeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }

            val requestBody = buildJsonObject {
                put("model", modelId)
                put("messages", messages.map { msg ->
                    buildJsonObject {
                        put("role", msg["role"] ?: "user")
                        put("content", msg["content"] ?: "")
                    }
                }
            }.toString()

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (statusCode in 200..299) {
                val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, Any>>(responseBody)
                val content = parsed["content"] as? String
                kotlin.Result.success(
                    mapOf(
                        "content" to content,
                        "provider" to id,
                        "model" to modelId
                    )
                )
            } else {
                kotlin.Result.failure(
                    ProviderInvocationException(
                        ProviderFailureKind.INVOCATION,
                        "Provider HTTP $statusCode: $responseBody"
                    )
                )
            }
        } catch (e: Exception) {
            kotlin.Result.failure(
                ProviderInvocationException(
                    ProviderFailureKind.INVOCATION,
                    "Provider request failed: ${e.message ?: e::class.simpleName ?: "unknown error"}",
                    e
                )
            )
        }
    }
}
