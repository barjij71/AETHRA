package org.aethra.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage

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
                isExecuted = task.isExecuted
            )
        }
        storage.setString(journalKey, json.encodeToString(snapshot))
    }

    suspend fun loadAll(): List<Task> {
        val encoded = storage.getString(journalKey) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()

        return runCatching {
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
                    persistedExecuted = snapshot.isExecuted
                )
                task
            }
        }.getOrElse { emptyList() }
    }
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
    val isExecuted: Boolean
)
