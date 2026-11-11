package org.aethra.core.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage

/**
 * Durable workflow journal backed by the platform Storage abstraction.
 *
 * Persists workflow definitions, node metadata, state, and the node-to-task
 * mapping so that workflows can be recovered after a process restart.
 *
 * The workflow engine owns the in-memory index; this class owns only the
 * serialization boundary so persistence remains platform-independent.
 */
class WorkflowPersistence(private val storage: Storage) {
    private val journalKey = "aethra.workflows.v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Persist all workflows to storage. Replaces the entire journal.
     * Callers should pass the complete current set of workflows.
     */
    suspend fun saveAll(workflows: List<PersistedWorkflow>) {
        storage.setString(journalKey, json.encodeToString(workflows))
    }

    /**
     * Load all persisted workflows from storage. Returns an empty list if
     * no workflows have been persisted or if the stored data is corrupt.
     */
    suspend fun loadAll(): List<PersistedWorkflow> {
        val encoded = storage.getString(journalKey) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()

        return runCatching {
            json.decodeFromString<List<PersistedWorkflow>>(encoded)
        }.getOrElse { emptyList() }
    }
}

/**
 * Serializable representation of a [Workflow] for durable storage.
 */
@Serializable
data class PersistedWorkflow(
    val id: String,
    val name: String,
    val state: String,
    val createdAt: Long,
    val completedAt: Long?,
    val nodes: List<PersistedWorkflowNode>
)

/**
 * Serializable representation of a [WorkflowNode] for durable storage.
 *
 * The [actualTaskId] is the ID assigned by [TaskEngine] when the node's task
 * is created. It is null before [WorkflowEngine.execute] has been called for
 * this workflow, or after a fresh restart before tasks have been recreated.
 */
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
