package org.aethra.core.tools

import kotlinx.coroutines.CancellationException
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner

/**
 * TaskRunner implementation for Tool/Capability execution.
 *
 * This integrates with ToolRegistry for tool resolution, PermissionBroker for
 * authorization, and produces results compatible with TaskEngine's verification path.
 *
 * The execution path follows:
 *   Task → ToolRegistry → ExecutionPolicy → ToolExecutor → Result
 *
 * This is the Phase 7 Core contract that connects Skills to executable Capabilities
 * without bypassing TaskEngine's existing authority structure.
 */
class ToolTaskRunner(
    private val toolRegistry: ToolRegistry,
    private val permissionBroker: PermissionBroker? = null,
    private val auditLog: AuditLog,
    private val toolExecutor: ToolExecutor? = null
) : TaskRunner {

    /**
     * Executes a tool based on the task's goal.
     *
     * The task's goal is resolved to a tool via ToolRegistry. If resolved,
     * the tool is executed through ToolExecutor (if configured) or a default
     * execution path. The result is wrapped in a Map for TaskEngine consumption.
     *
     * @param task the task whose goal should be executed as a tool
     * @return Result containing the execution output, evidence, and status
     */
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        val goal = task.goal.trim()

        // Resolve the tool from the task goal
        val tool = toolRegistry.resolve(goal)
            ?: return Result.failure(IllegalArgumentException("tool not found: $goal"))

        val toolId = tool.id

        // Check if this tool is registered (it should be since we just resolved it)
        val registeredTool = toolRegistry.get(toolId)
            ?: return Result.failure(IllegalStateException("resolved tool not registered: $toolId"))

        // Handle permission/approval for sensitive tools
        if (registeredTool.requiresSensitiveApproval) {
            val permissionResult = checkPermission(task, registeredTool)
            if (permissionResult.isFailure) {
                return permissionResult
            }
        }

        // Create execution request
        // Task.detail contains the tool execution arguments as key-value pairs
        val arguments = task.detail.map { (key, value) -> key to value }.toMap()
        val request = ToolExecutionRequest(
            toolId = toolId,
            arguments = arguments,
            taskId = task.id,
            executionAttemptId = task.executionAttemptId,
            requester = "core.runtime"
        )

        // Execute the tool
        val result = try {
            val executionResult = toolExecutor?.execute(request)
                ?: executeDefault(tool, request)

            mapOf<String, Any>(
                "success" to executionResult.success.toString(),
                "toolId" to executionResult.toolId,
                "output" to executionResult.output,
                "evidence" to executionResult.evidence,
                "error" to (executionResult.error ?: "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapOf<String, Any>(
                "success" to "false",
                "toolId" to toolId,
                "error" to (e.message ?: e::class.simpleName ?: "unknown_error")
            )
        }

        return Result.success(result)
    }

    /**
     * Default execution path when no ToolExecutor is configured.
     *
     * This provides a basic execution contract that can be extended by platform
     * implementations without redesigning TaskEngine.
     */
    private suspend fun executeDefault(
        tool: Tool,
        request: ToolExecutionRequest
    ): ToolExecutionResult {
        // Default implementation - tools can be executed by custom ToolExecutor
        // For now, we return a "not implemented" result
        return ToolExecutionResult(
            toolId = tool.id,
            success = false,
            output = emptyMap(),
            error = "Tool executor not configured for tool: ${tool.id}",
            evidence = listOf("tool:not_configured@${tool.id}")
        )
    }

    /**
     * Check permission for sensitive tools.
     */
    private suspend fun checkPermission(
        task: Task,
        tool: Tool
    ): Result<Map<String, Any>> {
        val broker = permissionBroker ?: run {
            val entry = createAuditEntry(
                operation = "tool.permission.blocked",
                target = task.id,
                toolId = tool.id,
                risk = tool.riskClass,
                decision = "DENIED",
                outcome = "missing_broker"
            )
            auditLog.record(entry)
            return Result.failure(IllegalStateException("permission_required:no_permission_broker"))
        }

        val requestId = task.id
        val existing = broker.getRequest(requestId)

        if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
            val approvalRequest = existing ?: broker.requestApproval(
                requester = "aethra.runtime",
                risk = tool.riskClass,
                operation = tool.id,
                target = task.id,
                capability = tool.requiredCapabilities.firstOrNull() ?: tool.id,
                requestId = requestId,
                capabilities = tool.requiredCapabilities.ifEmpty { listOf(tool.id) }.toSet()
            )

            try {
                broker.beginExecution(
                    approvalRequest.id,
                    tool.riskClass,
                    tool.id,
                    tool.requiredCapabilities.firstOrNull() ?: tool.id,
                    task.id,
                    tool.requiredCapabilities.ifEmpty { listOf(tool.id) }.toSet()
                )
            } catch (e: Exception) {
                val entry = createAuditEntry(
                    operation = "tool.permission.blocked",
                    target = task.id,
                    toolId = tool.id,
                    risk = tool.riskClass,
                    decision = "DENIED",
                    outcome = "authorization_${approvalRequest.state.name.lowercase()}"
                )
                auditLog.record(entry)
                return Result.failure(IllegalStateException("permission_required:${approvalRequest.id}", e))
            }
        }

        val auditEntry = createAuditEntry(
            operation = "tool.execution.allowed",
            target = task.id,
            toolId = tool.id,
            risk = tool.riskClass,
            decision = "GRANTED",
            outcome = "policy_allowed"
        )
        auditLog.record(auditEntry)

        return Result.success(emptyMap())
    }

    private fun createAuditEntry(
        operation: String,
        target: String,
        toolId: String,
        risk: RiskClass,
        decision: String,
        outcome: String
    ): AuditEntry {
        val entry = AuditEntry(
            id = java.util.UUID.randomUUID().toString(),
            actor = "aethra.runtime",
            operation = operation,
            target = target,
            riskClass = risk,
            decision = decision,
            outcome = outcome
        )
        return entry
    }
}