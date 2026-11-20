package org.aethra.core.domain

import kotlinx.serialization.Serializable

/**
 * Lifecycle state of an AETHRA task.
 */

enum class TaskState {
    // Queue
    QUEUED,
    // Preparing inputs/risk checks
    PREPARING,
    // Must wait for all dependency tasks to finish successfully
    WAITING_DEPENDENCY,
    // Approved and about to execute
    APPROVED,
    // Running
    RUNNING,
    // Suspended (waiting for approval or external input)
    WAITING_APPROVAL,
    // Paused by user
    PAUSED,
    // Done
    COMPLETED,
    // Failed or aborted
    FAILED,
    // Cancelled
    CANCELLED,
    // Unrecoverable failure
    ERROR
}

enum class RiskClass {
    // No device access, pure reasoning
    READ_ONLY,
    // Reversible, scoped writes
    REVERSIBLE,
    // Requires approval, may change state
    SENSITIVE,
    // Destructive or high-impact
    DESTRUCTIVE;
    val level: Int get() = this.ordinal
}

data class Task(
    val id: String,
    val goal: String,
    val role: String = "assistant",
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val dependsOn: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,

    var state: TaskState = TaskState.QUEUED,

    var detail: Map<String, String> = emptyMap(),
    var createdAtBy: String = "core",
    var updatedAtBy: String = "core"
) {
    val isTerminal: Boolean
        get() = state in listOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)
}

@Serializable
data class MemoryEntry(
    val id: String,
    val category: String,
    val text: String,
    val source: String = "user",
    val taskId: String? = null,
    val agentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),

    var confidence: Float = 1f,

    var rank: Int? = null,

    var supersededBy: String? = null
)

data class Agent(
    val id: String,
    val name: String,
    val role: String,
    val description: String,
    val provider: String = "fake",
    val model: String = "auto",

    var enabled: Boolean = true,

    var tools: List<String> = emptyList(),

    var memoryScope: String = "global",

    var retryBudget: Int = 2,

    var createdAt: Long = System.currentTimeMillis(),

    var updatedAt: Long = createdAt
)

@Serializable
data class AuditEntry(
    val id: String,
    val actor: String,
    val operation: String,
    val target: String,
    val riskClass: RiskClass,
    val decision: String,
    val outcome: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ApprovalGrant(
    val token: String,
    val target: String,
    val actor: String,
    val riskClass: RiskClass,
    val operation: String,
    val grantedAt: Long = System.currentTimeMillis(),
    var expiresAt: Long? = null,
    var granted: Boolean = false
)