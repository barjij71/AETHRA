package org.aethra.core.external

/** Minimal HTTP boundary so the InstaBox provider remains offline-testable. */
interface InstaBoxTransport {
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): InstaBoxHttpResponse
}

data class InstaBoxHttpResponse(
    val statusCode: Int,
    val body: String
)

fun interface InstaBoxApiKeyProvider {
    fun apiKey(): String?
}
