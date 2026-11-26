package org.aethra.core.tools

import kotlinx.coroutines.CancellationException

/**
 * Interface for executing Tools/Capabilities.
 *
 * This is the Core contract that makes future platform-specific implementations
 * possible without redesigning TaskEngine.
 *
 * The execution identity is preserved through ToolExecutionRequest.
 * Cancellation semantics are compatible with the Task lifecycle.
 * Failures are represented explicitly without hidden global state.
 *
 * Implementations must NOT:
 * - Directly mutate authoritative Task state
 * - Emit terminal events
 * - Bypass PermissionBroker/ExecutionPolicy
 * - Set VerificationOutcome.VERIFIED directly
 */
interface ToolExecutor {
    /**
     * Executes a tool with the given request.
     *
     * @param request the execution request containing tool identity, arguments, and context
     * @return the execution result with success status, output, evidence, and error
     * @throws CancellationException when the execution is cancelled
     */
    suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult
}