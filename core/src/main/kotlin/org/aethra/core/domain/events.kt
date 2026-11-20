package org.aethra.core.domain


sealed class DomainEvent {
    abstract val source: String
    abstract val timestamp: Long
}

data class TaskEvent(
    override val source: String,
    override val timestamp: Long,
    val taskId: String,
    val previousState: TaskState?,
    val newState: TaskState,
    val detail: Map<String, String> = emptyMap()
) : DomainEvent()

data class PermissionEvent(
    override val source: String,
    override val timestamp: Long,
    val actor: String,
    val operation: String,
    val risk: RiskClass,
    val granted: Boolean,
    val reason: String
) : DomainEvent()

data class AgentEvent(
    override val source: String,
    override val timestamp: Long,
    val agentId: String,
    val operation: String,
    val actor: String
) : DomainEvent()

data class MemoryEvent(
    override val source: String,
    override val timestamp: Long,
    val entryId: String,
    val category: String,
    val operation: String
) : DomainEvent()
