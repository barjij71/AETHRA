package org.aethra.core.orchestration

data class StepExecutionRecord(
    val stepId: String,
    val status: StepResult.Status,
    val reason: String? = null,
    val artifactId: String? = null
)

data class WorkflowExecutionRecord(
    val workflowId: String,
    val taskId: String,
    val revision: Int = 1,
    val steps: Map<String, StepExecutionRecord> = emptyMap()
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
        records[key(record.workflowId, record.taskId)] = record
    }

    @Synchronized
    override fun load(workflowId: String, taskId: String): WorkflowExecutionRecord? =
        records[key(workflowId, taskId)]
}
