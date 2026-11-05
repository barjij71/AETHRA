package org.aethra.assistant.aethra.memory

import org.aethra.core.memory.MemoryCategory
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.domain.MemoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android wrapper around [MemoryEngine].
 *
 * Provides a thin, lifecycle-aware API for the Android UI / runtime. The core
 * [MemoryEngine] uses the [Storage] abstraction; this class is the Android-side
 * glue that connects it to the app's storage and isolates any Android Storage
 * API from the core.
 *
 * The memory layer is NOT a Knowledge Base / RAG implementation; that is a
 * future layer. This API only exposes the memory operations the core already
 * defines (add / search / update / delete / list).
 */
class MemoryAndroid(private val memoryEngine: MemoryEngine) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Initialise the engine (load persisted memories). Safe to call multiple times. */
    suspend fun initialize() {
        memoryEngine.initialize()
    }

    /** Add a memory entry. */
    suspend fun add(
        category: MemoryCategory,
        text: String,
        source: String = "user",
        taskId: String? = null,
        agentId: String? = null
    ): MemoryEntry = memoryEngine.add(category, text, source, taskId, agentId)

    /** Add a memory from a tool/task result (plain-string convenience). */
    suspend fun addTaskResult(taskId: String, text: String, source: String = "tool"): MemoryEntry {
        return memoryEngine.add(MemoryCategory.TASK, text, source, taskId)
    }

    /** Search memories by keyword. */
    suspend fun search(query: String, limit: Int = 20): List<MemoryResult> = memoryEngine.search(query, limit)

    /** Update an existing memory by id. */
    suspend fun update(entry: MemoryEntry) {
        memoryEngine.update(entry)
    }

    /** Delete a single memory by id. */
    suspend fun delete(entryId: String) {
        memoryEngine.delete(entryId)
    }

    /** Return ALL persisted memories (for the UI / audit view). */
    suspend fun all(): List<MemoryEntry> = memoryEngine.all()

    fun close() {
        scope.cancel()
    }
}

/** Read-only result of a memory search (entry + relevance score). */
data class MemoryResult(val entry: MemoryEntry, val score: Float)
