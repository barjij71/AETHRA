package org.aethra.assistant.aethra.adapters

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the production [EncryptedInstaBoxApiKeyStore].
 *
 * These tests run on an Android device/emulator and verify that the actual
 * Android Keystore-backed EncryptedSharedPreferences implementation correctly
 * stores, retrieves, and clears the InstaBox API key.
 *
 * Only fake test credentials are used. No real InstaBox API key is ever stored
 * or tested.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedInstaBoxApiKeyStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var store: EncryptedInstaBoxApiKeyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        store = EncryptedInstaBoxApiKeyStore(context)
        // Start from a clean state
        store.clearApiKey()
    }

    @Test
    fun setApiKey_thenGetApiKey_returnsSameKey() {
        store.setApiKey("ibk_test_secret")
        assertEquals("ibk_test_secret", store.getApiKey())
    }

    @Test
    fun hasApiKey_isFalse_beforeSet() {
        assertFalse(store.hasApiKey())
    }

    @Test
    fun hasApiKey_becomesTrue_afterSet() {
        store.setApiKey("ibk_test_secret")
        assertTrue(store.hasApiKey())
    }

    @Test
    fun clearApiKey_removesValue() {
        store.setApiKey("ibk_test_secret")
        store.clearApiKey()
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun getApiKey_returnsNull_whenNoKeyStored() {
        assertNull(store.getApiKey())
    }

    @Test
    fun recreatedStore_recoversEncryptedValue() {
        store.setApiKey("ibk_test_secret")
        // A new store instance backed by the same Context should read the
        // same Keystore-encrypted value.
        val store2 = EncryptedInstaBoxApiKeyStore(context)
        assertEquals("ibk_test_secret", store2.getApiKey())
        assertTrue(store2.hasApiKey())
    }
}
