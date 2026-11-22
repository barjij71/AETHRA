package org.aethra.core.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow


/**
 * Minimal protocol for an AI language model provider. The provider is
 * responsible for transport, credential handling and model capability
 * declarations. AETHRA never assumes a specific provider; it selects via the
 * router. Provider integrations are optional adapters.
 */

interface LanguageModelProvider {
    val id: String
    val displayName: String
    val supportsToolCalling: Boolean
    val supportsStreaming: Boolean
    val models: List<String>

    /** Return a best-effort capability summary for UI display. */
    fun capabilities(): Map<String, Any>

    /**
     * Send a chat request and return the model's reply plus any tool calls.
     *
     * @param messages conversation history
     * @param availableTools tools the model may call (provider may ignore)
     * @return success with a reply map, or a failure with an error string
     */
    suspend fun chat(
        messages: List<Map<String, Any>>,
        availableTools: List<Map<String, Any>>? = null
    ): Result<Map<String, Any>>

    /**
     * Provider-neutral invocation API. Legacy providers remain compatible via [chat];
     * providers that support structured tool-result continuation should override this.
     */
    suspend fun invoke(request: ProviderRequest): Result<ProviderResponse> {
        if (request.toolResults.isNotEmpty()) {
            return Result.failure(
                ProviderInvocationException(
                    ProviderFailureKind.TOOL_PROTOCOL,
                    "This provider does not support structured tool-result continuation"
                )
            )
        }
        return try {
            chat(request.messages, request.availableTools).fold(
                onSuccess = { raw ->
                    Result.success(
                        ProviderResponse(
                            content = raw["content"] as? String,
                            metadata = raw.filterKeys { it != "content" }
                        )
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Result.failure(
                        ProviderInvocationException(
                            ProviderFailureKind.INVOCATION,
                            error.message ?: "Provider invocation failed",
                            error
                        )
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(
                ProviderInvocationException(
                    ProviderFailureKind.INVOCATION,
                    error.message ?: "Provider invocation failed",
                    error
                )
            )
        }
    }

    /**
     * Stream a response as deterministic typed events. The default adapter emits
     * the legacy invocation's structured calls, content and completion in order.
     */
    fun stream(request: ProviderRequest): Flow<ProviderStreamEvent> = flow {
        val result = try {
            invoke(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(
                ProviderStreamEvent.Failed(
                    ProviderFailure(
                        ProviderFailureKind.INVOCATION,
                        error.message ?: "Provider invocation failed",
                        error
                    )
                )
            )
            return@flow
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: IllegalStateException("Provider invocation failed")
            if (error is CancellationException) throw error
            val kind = (error as? ProviderInvocationException)?.kind ?: ProviderFailureKind.INVOCATION
            emit(ProviderStreamEvent.Failed(ProviderFailure(kind, error.message ?: "Provider invocation failed", error)))
            return@flow
        }
        val response = result.getOrThrow()
        response.toolCalls.forEach { emit(ProviderStreamEvent.ToolCallRequested(it)) }
        response.content?.let { emit(ProviderStreamEvent.TextChunk(it)) }
        emit(ProviderStreamEvent.Completed(response))
    }

    /** Check availability (network, quota) without mutating state. */
    suspend fun health(): Result<Boolean>
}

/** Request and response values shared by provider adapters without vendor types. */
data class ProviderRequest(
    val messages: List<Map<String, Any>>,
    val availableTools: List<Map<String, Any>>? = null,
    val toolResults: List<ProviderToolResult> = emptyList()
)

data class ProviderResponse(
    val content: String? = null,
    val toolCalls: List<ProviderToolCall> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)

data class ProviderToolCall(
    val invocationId: String,
    val toolId: String,
    val arguments: Map<String, Any>
)

data class ProviderToolResult(
    val invocationId: String,
    val output: Any? = null,
    val error: String? = null
)

enum class ProviderFailureKind { PROVIDER_UNAVAILABLE, INVOCATION, STREAMING, TOOL_PROTOCOL }

data class ProviderFailure(
    val kind: ProviderFailureKind,
    val message: String,
    val cause: Throwable? = null
)

class ProviderInvocationException(
    val kind: ProviderFailureKind,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

sealed interface ProviderStreamEvent {
    data class TextChunk(val text: String) : ProviderStreamEvent
    data class ToolCallRequested(val call: ProviderToolCall) : ProviderStreamEvent
    data class Completed(val response: ProviderResponse) : ProviderStreamEvent
    data class Failed(val failure: ProviderFailure) : ProviderStreamEvent
    data object Cancelled : ProviderStreamEvent
}

/**
 * Per-provider fallback policy. A new provider is NOT automatically a fallback.
 * The user must explicitly enable fallback for that provider; this flag is
 * persisted and exposed in the UI.
 */

data class FallbackPolicy(
    val providerId: String,
    val enabled: Boolean = false,
    val fallbackTo: String? = null
)

/**
 * Lightweight rate/quota telemetry. Not a dependency; deliberately simple.
 */

data class Usage(
    val providerId: String,
    val requests: Long = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val errors: Long = 0
)
