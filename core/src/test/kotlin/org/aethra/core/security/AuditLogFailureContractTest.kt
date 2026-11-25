package org.aethra.core.security

import kotlinx.coroutines.runBlocking
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogFailureContractTest {
    @Test
    fun storageReadFailureIsNotConvertedToEmptyAuditLog() = runBlocking {
        val error = runCatching {
            AuditLog(ReadFailureStorage()).load()
        }.exceptionOrNull()

        assertTrue(error is AuditLogException.IoFailure)
        assertTrue(error?.cause is StorageException.IoFailure)
    }

    @Test
    fun corruptAuditJournalFailsClosed() = runBlocking {
        val storage = CorruptStorage()
        val error = runCatching { AuditLog(storage).load() }.exceptionOrNull()
        assertTrue(error is AuditLogException.Corrupt)
    }

    private open class ReadFailureStorage : Storage {
        override suspend fun getString(key: String, defaultValue: String?): String? {
            throw StorageException.IoFailure("read failed")
        }
        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun getLong(key: String, defaultValue: Long?): Long? = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = emptyMap()
        override suspend fun remove(key: String) = Unit
        override suspend fun clear() = Unit
    }

    private class CorruptStorage : ReadFailureStorage() {
        override suspend fun getString(key: String, defaultValue: String?): String? = "{not-json"
    }
}
