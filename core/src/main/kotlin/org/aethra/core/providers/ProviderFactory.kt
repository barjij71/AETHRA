package org.aethra.core.providers

/**
 * Contract for creating a language model provider implementation from a
 * [ProviderDefinition] (metadata/config).
 *
 * The factory is a pure domain abstraction. It is not coupled to Android,
 * network, storage, or any inference implementation. The platform layer
 * supplies the concrete factory that knows how to build the actual provider
 * implementation.
 *
 * In production, no factory is registered by default. The platform layer
 * supplies the implementation; tests register [FakeProviderFactory].
 */
interface ProviderFactory {
    fun create(providerDef: ProviderDefinition): LanguageModelProvider
}
