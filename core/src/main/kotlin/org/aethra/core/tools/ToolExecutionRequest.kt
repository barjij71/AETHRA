package org.aethra.core.tools

import kotlinx.serialization.Serializable

/**
 * A request to execute a Tool/Capability.
 *
 * This is the smallest immutable execution context needed to safely carry
 * execution identity across the Skill → Tool boundary.
 *
 * @property toolId the stable identifier of the tool to execute
 * @property arguments the input arguments for the tool execution
 * @property taskId the owning task identifier
 * @property executionAttemptId the specific execution attempt being performed
 * @property requester the identifier of who/what is requesting the execution
 */
@Serializable
data class ToolExecutionRequest(
    val toolId: String,
    val arguments: Map<String, String> = emptyMap(),
    val taskId: String,
    val executionAttemptId: String,
    val requester: String = "core.runtime"
) {
    init {
        require(toolId.isNotBlank()) { "toolId must not be blank" }
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(executionAttemptId.isNotBlank()) { "executionAttemptId must not be blank" }
    }
}