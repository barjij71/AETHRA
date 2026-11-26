package org.aethra.core.tools

import kotlinx.serialization.Serializable

/**
 * The result of a Tool execution.
 *
 * This represents the execution outcome - not a verification decision.
 * The TaskEngine remains responsible for verification authority.
 *
 * @property toolId the identifier of the tool that was executed
 * @property success true when the execution completed without throwing
 * @property output the tool's output as key-value pairs
 * @property evidence evidence candidates produced by the execution
 * @property error error message if the execution failed
 */
@Serializable
data class ToolExecutionResult(
    val toolId: String,
    val success: Boolean,
    val output: Map<String, String> = emptyMap(),
    val evidence: List<String> = emptyList(),
    val error: String? = null
) {
    init {
        require(toolId.isNotBlank()) { "toolId must not be blank" }
    }

    /**
     * Creates a successful result with the given output.
     */
    companion object {
        fun success(
            toolId: String,
            output: Map<String, String> = emptyMap(),
            evidence: List<String> = emptyList()
        ): ToolExecutionResult = ToolExecutionResult(
            toolId = toolId,
            success = true,
            output = output,
            evidence = evidence
        )

        /**
         * Creates a failed result with an error message.
         */
        fun failure(
            toolId: String,
            error: String,
            output: Map<String, String> = emptyMap()
        ): ToolExecutionResult = ToolExecutionResult(
            toolId = toolId,
            success = false,
            output = output,
            error = error
        )
    }
}