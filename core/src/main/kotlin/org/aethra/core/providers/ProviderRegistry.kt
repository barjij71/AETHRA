package org.aethra.core.providers

import org.aethra.core.security.AuditLog
import org.aethra.core.storage.Storage

/** Provider Registry.

 * Purpose: Maintain a registry of available AI model providers.
 * The registry stores provider definitions (metadata/config) and their capabilities.

 * Key principles:
 *  - Core does NOT depend on any specific provider.
 *  - New providers are NOT automatically fallback. Fallback must be enabled
 *    explicitly via [FallbackPolicy].
 *  - The registry is purely declarative: it describes what providers exist
 *    and what they can do. It does not make routing decisions.
 */
data class ProviderDefinition(
    /** Internal unique identifier (e.g., "openai", "anthropic", "fake") */
    val providerId: String,
    /** Display name shown in UI */
    val displayName: String,
    /** Provider type: "openai", "anthropic", "gemini", "openrouter", "local", "ollama", "lm-studio", "custom" */
    val providerType: String,
    /** Endpoint URL for this provider */
    val endpoint: String,
    /** Authentication capability (e.g., "api_key", "bearer_token", "none") */
    val authenticationCapability: String,
    /** List of model IDs this provider supports */
    val supportedModels: List<String>,
    /** Capabilities (e.g., "tool_calling", "vision", "streaming") */
    val capabilities: Set<String>,
    /** Availability status */
    val availability: String,
    /** Health status (e.g., "healthy", "degraded", "unhealthy") */
    val health: String,
    /** Local vs remote */
    val isLocal: Boolean,
    /** Whether this provider can be used as a fallback */
    val isFallbackEligible: Boolean
)

class ProviderRegistry(
    private val storage: Storage,
    private val auditLog: AuditLog
) {
    private val providers = LinkedHashMap<String, ProviderDefinition>()

    /**
     * Set the implementation factory.
     *
     * This factory maps a [ProviderDefinition] (metadata/config) to a runtime
     * [LanguageModelProvider] implementation. It keeps the registry decoupled
     * from concrete provider implementations.
     */
    private var providerFactory: ProviderFactory? = null

    fun registerProviderFactory(factory: ProviderFactory) {
        this.providerFactory = factory
    }

    /**
     * Resolve a registered provider definition to its runtime implementation.
     *
     * This is the only allowed boundary between metadata and implementation.
     * If no factory is registered or the definition has no matching
     * implementation, returns null.
     */
    fun resolveProvider(providerDef: ProviderDefinition): LanguageModelProvider? {
        return providerFactory?.create(providerDef)
    }

    fun register(provider: ProviderDefinition): Boolean {
        if (providers.containsKey(provider.providerId)) {
            return false
        }
        providers[provider.providerId] = provider
        persist()
        return true
    }

    fun unregister(providerId: String): Boolean {
        if (!providers.containsKey(providerId)) {
            return false
        }
        providers.remove(providerId)
        persist()
        return true
    }

    fun get(providerId: String): ProviderDefinition? = providers[providerId]

    fun list(): List<ProviderDefinition> = providers.values.sortedBy { it.providerId }.toList()

    fun findByCapability(capability: String): List<ProviderDefinition> {
        return providers.values.filter { it.capabilities.contains(capability) }.toList()
    }

    fun findByModel(modelId: String): List<ProviderDefinition> {
        return providers.values.filter { it.supportedModels.contains(modelId) }.toList()
    }

    fun isFallbackEligible(providerId: String): Boolean {
        val def = providers[providerId] ?: return false
        return def.isFallbackEligible
    }

    private fun persist() {
        // TODO: Persist to storage when storage implementation is available
        // This is a domain foundation; actual persistence is delegated to platform
    }
}

/**
 * Per-provider fallback policy. A new provider is NOT automatically a fallback.
 * The user must explicitly enable fallback for that provider; this flag is
 * persisted and exposed in the UI.
 */
