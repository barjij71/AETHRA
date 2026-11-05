package org.aethra.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.io.File

/** Portable key/value persistence surface used by the core runtime.
 *  Backed by a single JSON file on every platform (JVM test, Android app).
 *  NOTE: persistent, secure credential storage for end users is provided by the
 *  Android platform layer ([org.aethra.core.platform.android.AndroidStorage]),
 *  not by this portable implementation. */
interface Storage {
    suspend fun getString(key: String, defaultValue: String? = null): String?
    suspend fun setString(key: String, value: String)
    suspend fun getLong(key: String, defaultValue: Long? = null): Long?
    suspend fun setLong(key: String, value: Long)
    suspend fun getBoolean(key: String, defaultValue: Boolean? = null): Boolean?
    suspend fun setBoolean(key: String, value: Boolean)
    suspend fun getAll(): Map<String, String>
    suspend fun remove(key: String)
    suspend fun clear()
}

data class KVStorageConfig(
    val file: String,
    val encrypt: Boolean = false,
    val codecFactory: ((String, String) -> String)? = null
)

class KVStore(private val file: File) : Storage {
    private val lock = Any()
    private var map: Map<String, String> = emptyMap()
    private var loaded = false
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val io = Dispatchers.IO

    override suspend fun getString(key: String, defaultValue: String?): String? {
        return withContext(io) { load(); map[key] ?: defaultValue }
    }

    override suspend fun setString(key: String, value: String) {
        withContext(io) { persist(set(key, value)) }
    }

    override suspend fun getLong(key: String, defaultValue: Long?): Long? {
        return withContext(io) { load(); map[key]?.toLongOrNull() ?: defaultValue }
    }

    override suspend fun setLong(key: String, value: Long) {
        withContext(io) { persist(set(key, value.toString())) }
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? {
        return withContext(io) { load(); map[key]?.toBooleanStrictOrNull() ?: defaultValue }
    }

    override suspend fun setBoolean(key: String, value: Boolean) {
        withContext(io) { persist(set(key, value.toString())) }
    }

    override suspend fun getAll(): Map<String, String> = withContext(io) { load(); map }

    override suspend fun remove(key: String) {
        withContext(io) { persist(removeKey(key)) }
    }

    override suspend fun clear() {
        withContext(io) { persist(emptyMap()) }
    }

    private fun load(): Map<String, String> {
        synchronized(lock) {
            if (loaded) return map
            val loadedMap = if (file.exists()) {
                try {
                    val text = file.readText()
                    if (text.isBlank()) emptyMap() else json.decodeFromString<Map<String, String>>(text)
                } catch (e: Exception) {
                    emptyMap() // unreadable/corrupt file: start fresh, do not crash
                }
            } else emptyMap()
            map = loadedMap
            loaded = true
            return map
        }
    }

    private fun persist(changed: Map<String, String>) {
        synchronized(lock) {
            map = changed
            loaded = true
            try {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString<Map<String, String>>(changed))
            } catch (e: Exception) {
                // persist failure must never kill the owning task; write the error to the session log
            }
        }
    }

    private fun set(key: String, value: String) = map + (key to value)
    private fun removeKey(key: String) = map - key
}
