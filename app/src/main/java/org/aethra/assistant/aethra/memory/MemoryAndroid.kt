package org.aethra.assistant.aethra.memory

import org.aethra.core.memory.MemoryCategory
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.memory.MemoryResult
import org.aethra.core.domain.MemoryEntry

class MemoryAndroid(private val memoryEngine: MemoryEngine) {
    suspend fun initialize() {
        memoryEngine.initialize()
    }

    suspend fun add(
        category: MemoryCategory,
        text: String,
        source: String = "user",
        taskId: String? = null,
        agentId: String? = null
    ): MemoryEntry = memoryEngine.add(category, text, source, taskId, agentId)

    suspend fun addTaskResult(taskId: String, text: String, source: String = "tool"): MemoryEntry {
        return memoryEngine.add(MemoryCategory.TASK, text, source, taskId)
    }

    suspend fun search(query: String, limit: Int = 20): List<MemoryResult> =
        memoryEngine.search(query, limit)

    suspend fun update(entry: MemoryEntry) {
        memoryEngine.update(entry)
    }

    suspend fun delete(entryId: String) {
        memoryEngine.delete(entryId)
    }

    suspend fun all(): List<MemoryEntry> = memoryEngine.all()
}
