package org.aethra.core.providers


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

    /** Check availability (network, quota) without mutating state. */
    suspend fun health(): Result<Boolean>
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
