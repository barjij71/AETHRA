package org.aethra.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException

/**
 * Durable task journal backed by the platform Storage abstraction.
 *
 * The task engine owns the in-memory index; this class owns only the
 * serialization boundary so persistence remains platform-independent.
 */
class TaskPersistence(private val storage: Storage) {
    private val journalKey = "aethra.tasks.v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun saveAll(tasks: List<Task>) {
        val snapshot = tasks.map { task ->
            PersistedTask(
                id = task.id,
                goal = task.goal,
                role = task.role,
                riskClass = task.riskClass.name,
                dependsOn = task.dependsOn,
                createdAt = task.createdAt,
                updatedAt = task.updatedAt,
                state = task.state.name,
                detail = task.detail,
                note = task.note,
                retryBudget = task.retryBudget,
                timeoutMs = task.timeoutMs,
                backoffMs = task.backoffMs,
                isExecuted = task.isExecuted,
                executionAttemptId = task.executionAttemptId,
                attemptCounter = task.attemptCounter,
                retriesUsed = task.retriesUsed
            )
        }
        storage.setString(journalKey, json.encodeToString(snapshot))
    }

    /**
     * Load all persisted tasks from storage.
     * Throws PersistenceException on corrupt/incompatible data (fail closed).
     * Returns empty list only for legitimately empty storage (EMPTY).
     */
    suspend fun loadAll(): List<Task> {
        val encoded = storage.getString(journalKey) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()

        return try {
            json.decodeFromString<List<PersistedTask>>(encoded).map { snapshot ->
                val task = Task(
                    goal = snapshot.goal,
                    role = snapshot.role,
                    riskClass = RiskClass.valueOf(snapshot.riskClass),
                    dependsOn = snapshot.dependsOn
                )
                task.restore(
                    persistedId = snapshot.id,
                    persistedCreatedAt = snapshot.createdAt,
                    persistedUpdatedAt = snapshot.updatedAt,
                    persistedState = TaskState.valueOf(snapshot.state),
                    persistedDetail = snapshot.detail,
                    persistedNote = snapshot.note,
                    persistedRetryBudget = snapshot.retryBudget,
                    persistedTimeoutMs = snapshot.timeoutMs,
                    persistedBackoffMs = snapshot.backoffMs,
                    persistedExecuted = snapshot.isExecuted,
                    persistedExecutionAttemptId = snapshot.executionAttemptId,
                    persistedAttemptCounter = snapshot.attemptCounter,
                    persistedRetriesUsed = snapshot.retriesUsed
                )
                task
            }
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Task journal corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Task journal incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Task journal partial: ${e.message}", e)
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Task journal I/O failure: ${e.message}", e)
        } catch (e: Exception) {
            // Phase 3: Fail closed on CORRUPT/PARTIAL/INCOMPATIBLE data
            // Do not silently return emptyList() - that would mask data corruption
            throw PersistenceException("Failed to load task journal: ${e.message}", e)
        }
    }
}

/** Base exception for persistence failures. */
open class PersistenceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Corrupt(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
    class Incompatible(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
    class Partial(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
    class IoFailure(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
}

@Serializable
private data class PersistedTask(
    val id: String,
    val goal: String,
    val role: String,
    val riskClass: String,
    val dependsOn: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val detail: Map<String, String>,
    val note: String,
    val retryBudget: Int,
    val timeoutMs: Long,
    val backoffMs: Long,
    val isExecuted: Boolean,
    val executionAttemptId: String = "",
    val attemptCounter: Int = 1,
    val retriesUsed: Int = 0
)
