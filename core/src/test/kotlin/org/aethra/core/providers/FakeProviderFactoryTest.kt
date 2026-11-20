package org.aethra.core.providers

import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.aethra.core.storage.Storage
import org.aethra.core.runtime.events.EventBus
import org.junit.Assert.*

/**
 * Test for [ProviderFactory] abstraction.
 *
 * This factory is used ONLY in tests. Production code must not register it;
 * it exists so the contract tests can verify [ProviderModelRouter] wiring
 * without depending on Android platform implementations.
 */
class FakeProviderFactory : ProviderFactory {
    override fun create(providerDef: ProviderDefinition): LanguageModelProvider {
        return FakeProvider(
            id = providerDef.providerId,
            displayName = providerDef.displayName,
            models = providerDef.supportedModels,
            storage = TestStorage(),
            eventBus = EventBus()
        )
    }
}

/**
 * Minimal test storage used only by the contract tests.
 * Not part of the production API.
 */
class TestStorage : Storage {
    private val data = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun getString(key: String, defaultValue: String?) = data[key]
    override suspend fun setString(key: String, value: String) { data[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?) = data[key]?.toLongOrNull()
    override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
    override suspend fun getBoolean(key: String, defaultValue: Boolean?) = data[key]?.toBooleanStrictOrNull()
    override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
    override suspend fun getAll(): Map<String, String> = data.toMap()
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun clear() { data.clear() }
}

class FakeProviderFactoryTest {
    @Test
    fun `factory creates provider from definition`() {
        val def = ProviderDefinition(
            providerId = "fake",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://fake",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        val factory = FakeProviderFactory()
        val impl = factory.create(def)
        assertEquals("fake", impl.id)
        assertEquals("Fake Tester", impl.displayName)
        assertEquals(listOf("fake-v1"), impl.models)
    }
}
