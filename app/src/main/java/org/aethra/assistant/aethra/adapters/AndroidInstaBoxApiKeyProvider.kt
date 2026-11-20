package org.aethra.assistant.aethra.adapters

import org.aethra.core.external.InstaBoxApiKeyProvider

/**
 * Android implementation of [InstaBoxApiKeyProvider] that reads the API key
 * from an [InstaBoxApiKeyStore].
 *
 * The store implementation is responsible for keeping the key out of plaintext
 * storage (e.g. Android Keystore-backed EncryptedSharedPreferences). This
 * provider itself never persists or logs the key.
 */
class AndroidInstaBoxApiKeyProvider(private val store: InstaBoxApiKeyStore) : InstaBoxApiKeyProvider {
    override fun apiKey(): String? = store.getApiKey()
}
