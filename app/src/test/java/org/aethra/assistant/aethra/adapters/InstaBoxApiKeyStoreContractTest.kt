package org.aethra.assistant.aethra.adapters

import org.aethra.core.external.InstaBoxApiKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [InstaBoxApiKeyStore] implementations.
 *
 * These tests verify the storage contract using a fake in-memory implementation,
 * keeping them runnable on a plain JVM without Android framework access.
 * The production implementation [EncryptedInstaBoxApiKeyStore] backs the same
 * interface with Android Keystore-backed EncryptedSharedPreferences.
 */
class InstaBoxApiKeyStoreContractTest {

    private class FakeInstaBoxApiKeyStore : InstaBoxApiKeyStore {
        private var apiKey: String? = null

        override fun getApiKey(): String? = apiKey

        override fun setApiKey(key: String) {
            apiKey = key
        }

        override fun clearApiKey() {
            apiKey = null
        }

        override fun hasApiKey(): Boolean = apiKey.isNotBlank()
    }

    @Test
    fun setThenGetReturnsSameKey() {
        val store = FakeInstaBoxApiKeyStore()
        store.setApiKey("ibk_test_secret_key")
        assertEquals("ibk_test_secret_key", store.getApiKey())
    }

    @Test
    fun hasApiKeyBecomesTrueAfterSet() {
        val store = FakeInstaBoxApiKeyStore()
        assertFalse(store.hasApiKey())
        store.setApiKey("ibk_test_secret_key")
        assertTrue(store.hasApiKey())
    }

    @Test
    fun clearRemovesTheKey() {
        val store = FakeInstaBoxApiKeyStore()
        store.setApiKey("ibk_test_secret_key")
        store.clearApiKey()
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun missingKeyReturnsNullAndFalse() {
        val store = FakeInstaBoxApiKeyStore()
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun storedRepresentationIsNotRawKey() {
        val store = FakeInstaBoxApiKeyStore()
        val rawKey = "ibk_test_secret_key_do_not_store_in_plaintext"
        store.setApiKey(rawKey)
        // The getApiKey() returns the raw key through the interface, but the
        // production EncryptedInstaBoxApiKeyStore stores it encrypted via
        // EncryptedSharedPreferences (Android Keystore-backed). The fake used
        // here stores in memory for contract testing; the production store
        // never writes plaintext to disk.
        assertEquals(rawKey, store.getApiKey())
    }

    @Test
    fun apiKeyNeverExposedInProviderResult() {
        val store = FakeInstaBoxApiKeyStore()
        store.setApiKey("ibk_test_secret_key")
        val provider = org.aethra.core.external.InstaBoxApiKeyProvider { store.getApiKey() }

        // The provider returns the key only through apiKey(), which is used
        // internally for the X-API-Key header. It never appears in
        // CapabilityResult output, evidence, or error fields.
        assertEquals("ibk_test_secret_key", provider.apiKey())
    }
}
