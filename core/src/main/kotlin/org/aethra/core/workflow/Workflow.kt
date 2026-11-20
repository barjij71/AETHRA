package org.aethra.core.workflow

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import java.util.UUID

data class WorkflowNode(
    val id: String,
    val taskId: String,
    val goal: String,
    val role: String = "assistant",
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val dependsOn: List<String> = emptyList(),
    var state: TaskState = TaskState.QUEUED
)

data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nodes: List<WorkflowNode>,
    var state: TaskState = TaskState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null
)