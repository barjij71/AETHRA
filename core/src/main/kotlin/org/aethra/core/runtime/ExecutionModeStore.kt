package org.aethra.core.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException

/**
 * Interface for managing the user's selected execution mode.
 *
 * Reads/writes via Storage with fallback to DEFAULT on corruption.
 */
interface ExecutionModeStore {
    /** Current execution mode (suspend to allow I/O) */
    suspend fun getMode(): ExecutionMode

    /** Change the current mode and persist */
    suspend fun setMode(mode: ExecutionMode)

    companion object {
        /** Creates a persistent store backed by Storage */
        fun create(storage: Storage): ExecutionModeStore = PersistentExecutionModeStore(storage)
    }
}

/**
 * Persistent implementation using Storage.
 *
 * Safely falls back to DEFAULT on any storage error.
 */
class PersistentExecutionModeStore(
    private val storage: Storage,
    private val defaults: ExecutionModeDefaults = ExecutionModeDefaults.Standard
) : ExecutionModeStore {

    private val lock = Mutex()
    private var cached: ExecutionMode? = null

    override suspend fun getMode(): ExecutionMode {
        lock.withLock {
            if (cached != null) return cached!!
            return try {
                val value = storage.getString(ExecutionMode.PREFERENCE_KEY)
                ExecutionMode.fromStorageString(value)
            } catch (e: Exception) {
                defaults.fallback
            }
        }
    }

    override suspend fun setMode(mode: ExecutionMode) {
        lock.withLock {
            try {
                storage.setString(ExecutionMode.PREFERENCE_KEY, mode.toStorageString())
                cached = mode
            } catch (e: StorageException) {
                // Ignore storage failures - keep in-memory mode only
            }
        }
    }
}

/**
 * Default configuration for execution mode behavior.
 */
interface ExecutionModeDefaults {
    /** Fallback mode when storage unavailable */
    val fallback: ExecutionMode

    companion object {
        val Standard = object : ExecutionModeDefaults {
            override val fallback: ExecutionMode = ExecutionMode.STANDARD
        }
    }
}

/**
 * In-memory only store for testing or when persistence unavailable.
 */
class MemoryExecutionModeStore(
    initial: ExecutionMode = ExecutionMode.STANDARD
) : ExecutionModeStore {
    private val mutex = Mutex()
    private var mode = initial

    override suspend fun getMode(): ExecutionMode {
        mutex.withLock { return mode }
    }

    override suspend fun setMode(mode: ExecutionMode) {
        mutex.withLock { this.mode = mode }
    }
}