package org.aethra.core.security

import kotlinx.serialization.json.Json
import org.aethra.core.domain.AuditEntry
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException

/**
 * Audit log. A malformed or unreadable journal is a persistence failure;
 * returning an empty list would erase the security history from the caller's
 * point of view and violate fail-closed recovery.
 */
class AuditLog(private val storage: Storage) {
    private val journalKey = "aethra.audit.v1"

    suspend fun record(entry: AuditEntry) {
        val list = load().toMutableList()
        list.add(0, entry)
        storage.setString(
            journalKey,
            Json { ignoreUnknownKeys = true }.encodeToString(list)
        )
    }

    suspend fun load(): List<AuditEntry> {
        return try {
            val raw = storage.getString(journalKey) ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            Json { ignoreUnknownKeys = true }
                .decodeFromString<List<AuditEntry>>(raw)
        } catch (e: StorageException.Corrupt) {
            throw AuditLogException.Corrupt("Audit journal corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw AuditLogException.Incompatible("Audit journal incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw AuditLogException.Partial("Audit journal partial: ${e.message}", e)
        } catch (e: StorageException.IoFailure) {
            throw AuditLogException.IoFailure("Audit journal I/O failure: ${e.message}", e)
        } catch (e: AuditLogException) {
            throw e
        } catch (e: Exception) {
            throw AuditLogException.Corrupt("Failed to load audit journal: ${e.message}", e)
        }
    }

    suspend fun all(): List<AuditEntry> = load()
}

open class AuditLogException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class Corrupt(message: String, cause: Throwable? = null) : AuditLogException(message, cause)
    class Incompatible(message: String, cause: Throwable? = null) : AuditLogException(message, cause)
    class Partial(message: String, cause: Throwable? = null) : AuditLogException(message, cause)
    class IoFailure(message: String, cause: Throwable? = null) : AuditLogException(message, cause)
}