package org.aethra.core.security

import org.aethra.core.domain.AuditEntry
import org.aethra.core.storage.Storage
import kotlinx.serialization.json.Json

/**
 * Audit log. Immutable (append-only) record of permission decisions and
 * executions. The platform layer supplies the durable storage; this class is
 * the append point and a read surface.
 */
class AuditLog(private val storage: Storage) {
    private val journalKey = "aethra.audit.v1"

    suspend fun record(entry: AuditEntry) {
        val list = load().toMutableList()
        list.add(0, entry)
        storage.setString(journalKey, Json { ignoreUnknownKeys = true }.encodeToString(list))
    }

    suspend fun load(): List<AuditEntry> {
        val raw = storage.getString(journalKey) ?: "[]"
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString<List<AuditEntry>>(raw)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun all(): List<AuditEntry> = load()
}
