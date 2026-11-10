package org.aethra.assistant.aethra.adapters

/**
 * In-memory implementation of [InstaBoxApiKeyStore] that starts with no key.
 *
 * This is a placeholder for development and testing. A production Android
 * implementation should back this with Android Keystore-backed
 * EncryptedSharedPreferences or equivalent secure storage. The key is never
 * written to source code, Git history, or ordinary SharedPreferences.
 */
class EmptyInstaBoxApiKeyStore : InstaBoxApiKeyStore {
    private var apiKey: String? = null

    override fun getApiKey(): String? = apiKey

    override fun setApiKey(key: String) {
        apiKey = key
    }
}
