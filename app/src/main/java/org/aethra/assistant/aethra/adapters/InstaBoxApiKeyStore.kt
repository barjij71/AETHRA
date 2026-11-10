package org.aethra.assistant.aethra.adapters

/**
 * Minimal storage abstraction for the InstaBox API key.
 *
 * The implementation must never persist the raw API key in source-controlled
 * configuration or ordinary SharedPreferences. On Android, the concrete
 * implementation should use Android Keystore-backed EncryptedSharedPreferences
 * or a similar secure store. This interface keeps the credential storage
 * separate from the provider so the provider remains testable with a fake.
 */
interface InstaBoxApiKeyStore {
    /** Returns the stored API key, or null if none has been configured. */
    fun getApiKey(): String?

    /** Stores the API key. Implementations must encrypt at rest. */
    fun setApiKey(key: String)

    /** Removes any stored API key. */
    fun clearApiKey()

    /** Returns true if a non-empty API key is currently stored. */
    fun hasApiKey(): Boolean
}
