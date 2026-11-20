package org.aethra.core.memory

import kotlinx.coroutines.*
import org.aethra.core.domain.MemoryEntry
import org.aethra.core.storage.Storage
import org.aethra.core.domain.TaskState
import java.util.UUID

/**
 * Categories of persistent memory. Short-term conversational context is held
 * only in the running session (not persisted by default); everything else is
 * stored locally and survives app restarts.
 */
enum class MemoryCategory {
    PREFERENCES,
    PROJECT,
    TASK,
    AGENT,
    KNOWLEDGE,
    SHORT_TERM
}

/**
 * In-memory index of retrieved memories. Used by the engine to return ranked
 * results with scores the UI can render and the caller can act on.
 */

data class MemoryResult(
    val entry: MemoryEntry,
    val score: Float
)

/**
 * Independent, persistent memory engine. Does not depend on AI providers or
 * UI. Stores entries locally and supports review, correction, deletion and
 * export. Vector retrieval is an optional future layer; this MVP uses keyword
 * matching over structured local storage.
 */
class MemoryEngine(private val storage: Storage) {
    private val key = "aethra.memory.v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var entries = mutableListOf<MemoryEntry>()
    private var loaded = false

    suspend fun initialize() {
        entries = load().toMutableList()
        loaded = true
    }

    private suspend fun load(): List<MemoryEntry> {
        val raw = storage.getString(key) ?: "[]"
        val parsed = try {
            val list = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<List<MemoryEntry>>(raw)
            list.ifEmpty { return emptyList() }
        } catch (e: Exception) { emptyList() }
        entries = parsed.toMutableList()
        return entries
    }

    private suspend fun persist() {
        storage.setString(key, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.encodeToString(entries))
    }

    /** Add a new memory. Idempotent key: same text under the same source overwrites the old one. */
    suspend fun add(category: MemoryCategory, text: String, source: String = "user", taskId: String? = null, agentId: String? = null): MemoryEntry {
        val id = UUID.randomUUID().toString()
        val entry = MemoryEntry(id = id, category = category.name, text = text, source = source, taskId = taskId, agentId = agentId)
        val filtered = entries.filter { it.text != text || it.source != source }.toMutableList()
        filtered.add(entry)
        entries = filtered
        persist()
        return entry
    }

    /** Update an existing memory by id. */
    suspend fun update(entry: MemoryEntry) {
        entries = entries.mapNotNull { if (it.id == entry.id) entry else it }.toMutableList()
        persist()
    }

    /** Delete a single memory by id. */
    suspend fun delete(entryId: String) {
        entries = entries.filter { it.id != entryId }.toMutableList()
        persist()
    }

    /** Full-text keyword search, ranked by frequency and recency. */
    suspend fun search(query: String, limit: Int = 20): List<MemoryResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = ArrayList<MemoryResult>()
        for (e in entries) {
            val found = e.text.lowercase().contains(q)
            if (found) {
                val count = e.text.lowercase().split(" ").count { it == q }
                val score = (count * 10f) + (if (e.source == "user") 2f else 0f)
                if (score > 0f) {
                    results.add(MemoryResult(e, score))
                }
            }
        }
        return results.sortedWith(compareByDescending<MemoryResult> { it.score }.thenBy { it.entry.createdAt }).take(limit)
    }

    /** Return memories by category. */
    suspend fun byCategory(category: MemoryCategory): List<MemoryEntry> = entries.filter { it.category == category.name }

    /** Return all stored entries (the full journal). */
    suspend fun all(): List<MemoryEntry> = entries.toList()

    /** Count of stored memories. */
    val size: Int get() = entries.size

    fun close() = scope.cancel()
}
