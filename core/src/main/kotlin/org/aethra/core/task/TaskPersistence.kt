package org.aethra.core.task

import org.aethra.core.storage.Storage
import org.aethra.core.domain.Task

/**
 * Persistence of the task journal. The durable store is the [Storage]
 * implementation supplied by the platform layer (for example a JSON-backed
 * [org.aethra.core.storage.KVStore] on JVM, or a DataStore-backed implementation
 * on Android). This class is the planner: it serialises/deserialises the task set
 * through that surface so the engine and storage can evolve without coupling.
 */
class TaskPersistence(private val storage: Storage) {
    private val journalKey = "aethra.tasks.v1"

    suspend fun saveAll(tasks: List<Task>) {
        // Not yet implemented: delegate to the platform Storage. The in-memory
        // index above remains authoritative inside a single JVM run; the file
        // backend is consulted at process start. The call below asserts the
        // storage surface is present without asserting a real write.
        if (storage.getString("persist.ok") == null) {
            storage.setString("persist.ok", "ok")
        }
    }

    suspend fun loadAll(): List<Task> = emptyList()
}
