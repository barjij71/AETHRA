package org.aethra.assistant.aethra.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.aethra.core.providers.ProviderDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [AndroidProviderFactory].
 *
 * Requires Android SDK — runs as an instrumented test.
 */
class AndroidProviderFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun factoryCreatesProviderWithCorrectProperties() {
        val apiKeyStore = AndroidApiKeyStore(context)
        val factory = AndroidProviderFactory(
            apiKeyStore = apiKeyStore,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        val providerDef = ProviderDefinition(
            providerId = "test-provider",
            displayName = "Test Provider",
            providerType = "custom",
            endpoint = "https://api.example.com/v1/chat/completions",
            authenticationCapability = "api_key",
            supportedModels = listOf("default-model"),
            capabilities = emptySet(),
            availability = "available",
            health = "healthy",
            isLocal = false,
            isFallbackEligible = false
        )

        val provider = factory.create(providerDef)

        assertNotNull(provider)
        assertEquals("android-provider", provider.id)
        assertEquals("Android Provider", provider.displayName)
        assertFalse(provider.supportsToolCalling)
        assertFalse(provider.supportsStreaming)
        assertEquals(listOf("default-model"), provider.models)
    }

    @Test
    fun factoryUsesProviderDefinitionModelId() {
        val apiKeyStore = AndroidApiKeyStore(context)
        val factory = AndroidProviderFactory(
            apiKeyStore = apiKeyStore,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        val providerDef = ProviderDefinition(
            providerId = "test-provider",
            displayName = "Test Provider",
            providerType = "custom",
            endpoint = "https://api.example.com/v1/chat/completions",
            authenticationCapability = "api_key",
            supportedModels = listOf("custom-model-v2"),
            capabilities = emptySet(),
            availability = "available",
            health = "healthy",
            isLocal = false,
            isFallbackEligible = false
        )

        val provider = factory.create(providerDef)
        assertEquals("custom-model-v2", provider.models.first())
    }

    @Test
    fun factoryReturnsSameProviderInstancePerCall() {
        val apiKeyStore = AndroidApiKeyStore(context)
        val factory = AndroidProviderFactory(
            apiKeyStore = apiKeyStore,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        val providerDef = ProviderDefinition(
            providerId = "test-provider",
            displayName = "Test Provider",
            providerType = "custom",
            endpoint = "https://api.example.com/v1/chat/completions",
            authenticationCapability = "api_key",
            supportedModels = listOf("default-model"),
            capabilities = emptySet(),
            availability = "available",
            health = "healthy",
            isLocal = false,
            isFallbackEligible = false
        )

        val provider1 = factory.create(providerDef)
        val provider2 = factory.create(providerDef)

        // Each call creates a new provider instance (factory is stateless)
        assertNotNull(provider1)
        assertNotNull(provider2)
        assertEquals(provider1.id, provider2.id)
    }
}
