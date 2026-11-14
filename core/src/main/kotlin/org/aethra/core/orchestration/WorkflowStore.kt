package org.aethra.core.orchestration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists

@Serializable
data class StepExecutionRecord(
    val stepId: String,
    val status: StepResult.Status,
    val reason: String? = null,
    val artifactId: String? = null,
    val idempotencyKey: String = ""
)

@Serializable
data class AuditEvent(
    val eventId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val workflowId: String,
    val taskId: String,
    val stepId: String? = null,
    val skillId: String? = null,
    val skillVersion: String? = null,
    val event: String,
    val status: StepResult.Status? = null,
    val reason: String? = null,
    val artifactIds: List<String> = emptyList(),
    val approvalId: String? = null,
    val evidenceReference: String? = null
)

@Serializable
data class StoredArtifact(
    val id: String,
    val schemaVersion: String,
    val producerSkillId: String,
    val producerVersion: String,
    val taskId: String,
    val payload: JsonElement,
    val provenance: String,
    val createdAt: Long
)

@Serializable
data class WorkflowExecutionRecord(
    val workflowId: String,
    val taskId: String,
    val revision: Int = 1,
    val steps: Map<String, StepExecutionRecord> = emptyMap(),
    val audit: List<AuditEvent> = emptyList(),
    val artifacts: Map<String, StoredArtifact> = emptyMap()
)

interface WorkflowStore {
    fun save(record: WorkflowExecutionRecord)
    fun load(workflowId: String, taskId: String): WorkflowExecutionRecord?
}

class InMemoryWorkflowStore : WorkflowStore {
    private val records = mutableMapOf<String, WorkflowExecutionRecord>()

    private fun key(workflowId: String, taskId: String) = "$workflowId::$taskId"

    @Synchronized
    override fun save(record: WorkflowExecutionRecord) {
        val k = key(record.workflowId, record.taskId)
        val previous = records[k]
        records[k] = if (previous == null) record else record.copy(revision = maxOf(record.revision, previous.revision + 1))
    }

    @Synchronized
    override fun load(workflowId: String, taskId: String): WorkflowExecutionRecord? =
        records[key(workflowId, taskId)]
}

class JsonFileWorkflowStore(
    private val file: Path,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
) : WorkflowStore {
    @Synchronized
    override fun save(record: WorkflowExecutionRecord) {
        val records = readAll().toMutableMap()
        val k = key(record.workflowId, record.taskId)
        val previous = records[k]
        records[k] = if (previous == null) record else record.copy(revision = maxOf(record.revision, previous.revision + 1))
        file.createParentDirectories()
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(records))
        Files.move(
            tmp,
            file,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    @Synchronized
    override fun load(workflowId: String, taskId: String): WorkflowExecutionRecord? =
        readAll()[key(workflowId, taskId)]

    private fun readAll(): Map<String, WorkflowExecutionRecord> {
        if (!file.exists()) return emptyMap()
        val content = Files.readString(file)
        if (content.isBlank()) return emptyMap()
        return json.decodeFromString<Map<String, WorkflowExecutionRecord>>(content)
    }

    private fun key(workflowId: String, taskId: String) = "$workflowId::$taskId"
}
