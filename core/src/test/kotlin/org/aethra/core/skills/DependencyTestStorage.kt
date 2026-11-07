package org.aethra.core.skills

import org.aethra.core.storage.Storage
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Common in-memory test storage used across skill tests.
 */
class DependencyTestStorage : Storage {
    private val data = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun getString(key: String, defaultValue: String?): String? {
        return data[key]
    }

    override suspend fun setString(key: String, value: String) {
        data[key] = value
    }

    override suspend fun getLong(key: String, defaultValue: Long?): Long? {
        return data[key]?.toLongOrNull()
    }

    override suspend fun setLong(key: String, value: Long) {
        data[key] = value.toString()
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? {
        return data[key]?.toBooleanStrictOrNull()
    }

    override suspend fun setBoolean(key: String, value: Boolean) {
        data[key] = value.toString()
    }

    override suspend fun getAll(): Map<String, String> {
        return data.toMap()
    }

    override suspend fun remove(key: String) {
        data.remove(key)
    }

    override suspend fun clear() {
        data.clear()
    }
}
