package org.aethra.assistant.aethra.adapters

import android.content.Context
import android.content.SharedPreferences

/**
 * Implementation of [InstaBoxApiKeyStore] backed by Android SharedPreferences with
 * XOR obfuscation.
 *
 * The API key is XOR-obfuscated before being written to SharedPreferences. This
 * prevents casual inspection of the key in the preferences file, though it is not
 * cryptographic-grade encryption. For production use, consider migrating to
 * Android Keystore-backed EncryptedSharedPreferences.
 *
 * The raw API key is never stored in source code, Git history, logs, or plaintext
 * SharedPreferences.
 */
class EncryptedInstaBoxApiKeyStore(private val context: Context) : InstaBoxApiKeyStore {

    private companion object {
        private const val PREFS_NAME = "aethra.instabox.keystore"
        private const val KEY_API_KEY = "instabox.api_key"
        private val XOR_KEY = 0x5A.toByte()
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getApiKey(): String? {
        val encoded = prefs.getString(KEY_API_KEY, null) ?: return null
        return xorDecrypt(encoded)
    }

    override fun setApiKey(key: String) {
        val encoded = xorEncrypt(key)
        prefs.edit().putString(KEY_API_KEY, encoded).apply()
    }

    override fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    override fun hasApiKey(): Boolean = prefs.contains(KEY_API_KEY)

    private fun xorEncrypt(text: String): String {
        return text.toByteArray().map { it xor XOR_KEY }.joinToString("") { "%02x".format(it) }
    }

    private fun xorDecrypt(hex: String): String {
        return hex.chunked(2).map { it.toInt(16).toByte() xor XOR_KEY }.joinToString("") { it.toChar() }
    }
}
