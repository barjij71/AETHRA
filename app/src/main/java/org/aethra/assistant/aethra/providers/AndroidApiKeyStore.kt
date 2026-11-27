package org.aethra.assistant.aethra.providers

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Generic provider credential store backed by Android Keystore-backed
 * [EncryptedSharedPreferences].
 *
 * The API key is encrypted at rest using AES-256-GCM with a Keystore-backed
 * master key. The master key itself never leaves the Android Keystore. The raw
 * API key is never written to source code, Git history, logs, or plaintext
 * SharedPreferences.
 */
class AndroidApiKeyStore(private val context: Context) {

    private companion object {
        private const val PREFS_NAME = "aethra.provider.keystore"
        private const val KEY_API_KEY = "provider.api_key"
    }

    private val encryptedSharedPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Returns the stored API key, or null if none has been configured. */
    fun getApiKey(): String? {
        return try {
            encryptedSharedPreferences.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            null
        }
    }

    /** Stores the API key. Implementations must encrypt at rest. */
    fun setApiKey(key: String) {
        encryptedSharedPreferences.edit().putString(KEY_API_KEY, key).apply()
    }

    /** Removes any stored API key. */
    fun clearApiKey() {
        encryptedSharedPreferences.edit().remove(KEY_API_KEY).apply()
    }

    /** Returns true if a non-empty API key is currently stored. */
    fun hasApiKey(): Boolean {
        return try {
            encryptedSharedPreferences.contains(KEY_API_KEY)
        } catch (e: Exception) {
            false
        }
    }
}
