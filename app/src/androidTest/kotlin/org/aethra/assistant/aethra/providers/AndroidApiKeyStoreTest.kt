package org.aethra.assistant.aethra.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [AndroidApiKeyStore].
 *
 * Requires Android SDK — runs as an instrumented test.
 */
class AndroidApiKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun storeStartsWithNoApiKey() {
        val store = AndroidApiKeyStore(context)
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun storeSavesAndRetrievesApiKey() {
        val store = AndroidApiKeyStore(context)
        store.clearApiKey()
        assertFalse(store.hasApiKey())

        store.setApiKey("test-api-key-12345")
        assertTrue(store.hasApiKey())
        assertEquals("test-api-key-12345", store.getApiKey())
    }

    @Test
    fun storeClearsApiKey() {
        val store = AndroidApiKeyStore(context)
        store.setApiKey("temp-key")
        assertTrue(store.hasApiKey())

        store.clearApiKey()
        assertFalse(store.hasApiKey())
        assertNull(store.getApiKey())
    }

    @Test
    fun storeOverwritesExistingApiKey() {
        val store = AndroidApiKeyStore(context)
        store.setApiKey("first-key")
        assertEquals("first-key", store.getApiKey())

        store.setApiKey("second-key")
        assertEquals("second-key", store.getApiKey())
        assertTrue(store.hasApiKey())
    }

    @Test
    fun emptyApiKeyIsNotConsideredStored() {
        val store = AndroidApiKeyStore(context)
        store.setApiKey("")
        assertFalse(store.hasApiKey())
    }
}
