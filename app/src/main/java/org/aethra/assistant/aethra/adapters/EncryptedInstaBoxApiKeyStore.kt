package org.aethra.assistant.aethra.adapters

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.aethra.core.external.InstaBoxApiKeyProvider

/**
 * Production implementation of [InstaBoxApiKeyStore] backed by Android Keystore
 * via [EncryptedSharedPreferences].
 *
 * The API key is encrypted at rest using a key stored in the Android Keystore.
 * The key itself is never written to source code, Git history, logs, or ordinary
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

        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getApiKey(): String? = encryptedSharedPreferences.getString(KEY_API_KEY, null)

    override fun setApiKey(key: String) {
        encryptedSharedPreferences.edit().putString(KEY_API_KEY, key).apply()
    }

    override fun clearApiKey() {
        encryptedSharedPreferences.edit().remove(KEY_API_KEY).apply()
    }

    override fun hasApiKey(): Boolean = encryptedSharedPreferences.contains(KEY_API_KEY)
}

/**
 * Thin adapter that exposes [EncryptedInstaBoxApiKeyStore] as an
 * [InstaBoxApiKeyProvider] for use by [InstaBoxRemoteComputerProvider].
 */
class AndroidInstaBoxApiKeyProvider(private val store: InstaBoxApiKeyStore) : InstaBoxApiKeyProvider {
    override fun apiKey(): String? = store.getApiKey()
}
