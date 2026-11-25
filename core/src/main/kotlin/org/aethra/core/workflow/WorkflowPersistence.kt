package org.aethra.core.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.aethra.core.task.PersistenceException

/**
 * Durable workflow journal backed by the platform Storage abstraction.
 */
class WorkflowPersistence(private val storage: Storage) {
    private val journalKey = "aethra.workflows.v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun saveAll(workflows: List<PersistedWorkflow>) {
        storage.setString(journalKey, json.encodeToString(workflows))
    }

    /**
     * Load persisted workflows. Storage and decode failures are fatal rather
     * than being converted into an empty journal.
     */
    suspend fun loadAll(): List<PersistedWorkflow> {
        return try {
            val encoded = storage.getString(journalKey) ?: return emptyList()
            if (encoded.isBlank()) return emptyList()
            json.decodeFromString<List<PersistedWorkflow>>(encoded)
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Workflow journal corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Workflow journal incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Workflow journal partial: ${e.message}", e)
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Workflow journal I/O failure: ${e.message}", e)
        } catch (e: PersistenceException) {
            throw e
        } catch (e: Exception) {
            throw PersistenceException.Corrupt("Failed to load workflow journal: ${e.message}", e)
        }
    }
}

@Serializable
data class PersistedWorkflow(
    val id: String,
    val name: String,
    val state: String,
    val createdAt: Long,
    val completedAt: Long?,
    val nodes: List<PersistedWorkflowNode>
)

@Serializable
data class PersistedWorkflowNode(
    val id: String,
    val taskId: String,
    val actualTaskId: String?,
    val goal: String,
    val role: String,
    val riskClass: String,
    val dependsOn: List<String>,
    val state: String
)
