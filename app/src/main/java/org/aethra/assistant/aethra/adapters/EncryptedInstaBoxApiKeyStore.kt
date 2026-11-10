package org.aethra.assistant.aethra.adapters

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Production implementation of [InstaBoxApiKeyStore] backed by Android
 * [EncryptedSharedPreferences] using a key generated and stored in the
 * Android Keystore.
 *
 * The API key is encrypted at rest using AES-256-GCM with a Keystore-backed
 * master key. The master key itself never leaves the Android Keystore. The raw
 * API key is never written to source code, Git history, logs, or plaintext
 * SharedPreferences.
 */
class EncryptedInstaBoxApiKeyStore(private val context: Context) : InstaBoxApiKeyStore {

    private companion object {
        private const val PREFS_NAME = "aethra.instabox.keystore"
        private const val KEY_API_KEY = "instabox.api_key"
    }

    private val encryptedSharedPreferences: androidx.security.crypto.EncryptedSharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        androidx.security.crypto.EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKey,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getApiKey(): String? {
        return try {
            encryptedSharedPreferences.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            // Corrupt or inaccessible encrypted data: treat as no key stored.
            // Never expose the key or exception details to callers.
            null
        }
    }

    override fun setApiKey(key: String) {
        encryptedSharedPreferences.edit().putString(KEY_API_KEY, key).apply()
    }

    override fun clearApiKey() {
        encryptedSharedPreferences.edit().remove(KEY_API_KEY).apply()
    }

    override fun hasApiKey(): Boolean {
        return try {
            encryptedSharedPreferences.contains(KEY_API_KEY)
        } catch (e: Exception) {
            false
        }
    }
}
