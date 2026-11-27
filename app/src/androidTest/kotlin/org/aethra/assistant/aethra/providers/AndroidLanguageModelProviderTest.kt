package org.aethra.assistant.aethra.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.aethra.core.providers.ProviderFailureKind
import org.aethra.core.providers.ProviderInvocationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [AndroidLanguageModelProvider].
 *
 * Requires Android SDK — runs as an instrumented test.
 */
class AndroidLanguageModelProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun providerHasCorrectProperties() {
        val store = AndroidApiKeyStore(context)
        val provider = AndroidLanguageModelProvider(
            apiKeyStore = store,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        assertNotNull(provider)
        assertEquals("android-provider", provider.id)
        assertEquals("Android Provider", provider.displayName)
        assertFalse(provider.supportsToolCalling)
        assertFalse(provider.supportsStreaming)
        assertEquals(listOf("default-model"), provider.models)
    }

    @Test
    fun capabilitiesReturnsCorrectMap() {
        val store = AndroidApiKeyStore(context)
        val provider = AndroidLanguageModelProvider(
            apiKeyStore = store,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        val caps = provider.capabilities()
        assertEquals(false, caps["supportsToolCalling"])
        assertEquals(false, caps["supportsStreaming"])
        assertEquals(listOf("default-model"), caps["models"])
    }

    @Test
    fun chatWithoutApiKeyReturnsFailure() {
        val store = AndroidApiKeyStore(context)
        store.clearApiKey()
        assertFalse(store.hasApiKey())

        val provider = AndroidLanguageModelProvider(
            apiKeyStore = store,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        val result = provider.chat(
            messages = listOf(mapOf("role" to "user", "content" to "Hello"))
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is ProviderInvocationException)
        assertEquals(ProviderFailureKind.PROVIDER_UNAVAILABLE, (exception as ProviderInvocationException).kind)
    }

    @Test
    fun healthReturnsFalseWithoutApiKey() {
        val store = AndroidApiKeyStore(context)
        store.clearApiKey()

        val provider = AndroidLanguageModelProvider(
            apiKeyStore = store,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "default-model"
        )

        val result = provider.health()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun modelsReturnsConfiguredModelId() {
        val store = AndroidApiKeyStore(context)
        val provider = AndroidLanguageModelProvider(
            apiKeyStore = store,
            endpoint = "https://api.example.com/v1/chat/completions",
            modelId = "custom-model-v2"
        )

        assertEquals(listOf("custom-model-v2"), provider.models)
    }
}
