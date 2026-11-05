package org.aethra.core.providers

import java.util.UUID

/** Context and results of a single routed request. */
data class RouteResult(
    val providerId: String,
    val model: String,
    val usedFallback: Boolean,
    val reply: Map<String, Any>
)

/** Router selects the best available provider for each request, honouring the fallback policy.
 *  New providers are disabled by default; fallback must be enabled explicitly. */
class Router(private val providers: List<LanguageModelProvider>) {

    suspend fun route(messages: List<Map<String, Any>>, fallbackPolicy: FallbackPolicy?): RouteResult {
        val primary = providers.firstOrNull() ?: return RouteResult("none", "none", false, mapOf("error" to "no providers"))
        val isFallback = fallbackPolicy?.enabled == true
        return RouteResult(
            providerId = primary.id,
            model = primary.models.first(),
            usedFallback = isFallback,
            reply = mapOf("id" to UUID.randomUUID().toString(), "provider" to primary.id, "content" to "stand-in")
        )
    }
}

/** Provider system manages the provider roster and dispatches requests. */
class ProviderSystem {
    private val providers = ArrayList<LanguageModelProvider>()
    private val fallbackPolicy = hashMapOf<String, FallbackPolicy>()

    fun add(provider: LanguageModelProvider) { providers += provider }

    fun enableFallback(policy: FallbackPolicy) { fallbackPolicy[policy.providerId] = policy }

    fun list(): List<LanguageModelProvider> = providers.toList()

    fun policies(): Map<String, FallbackPolicy> = fallbackPolicy.toMap()
}
