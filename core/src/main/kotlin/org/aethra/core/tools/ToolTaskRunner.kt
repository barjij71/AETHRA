package org.aethra.core.tools

import kotlinx.coroutines.CancellationException
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.runtime.ExecutionMode
import org.aethra.core.runtime.ExecutionModeStore
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
 *   Task → ToolRegistry → PolicyEngine → PermissionBroker (if required) → ToolExecutor → Result
 *
 * This is the Phase 7 Core contract that connects Skills to executable Capabilities
 * without bypassing TaskEngine's existing authority structure.
 */
class ToolTaskRunner(
    private val toolRegistry: ToolRegistry,
    private val executionModeStore: ExecutionModeStore,
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

        // Check authorization for the tool (mode-aware policy and permission check)
        val authResult = checkAuthorization(task, registeredTool)
        if (authResult.isFailure) {
            return authResult
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

        // Require ToolExecutor for actual execution - no silent fallback
        val executor = toolExecutor ?: return Result.failure(
            IllegalStateException("ToolExecutor not configured for tool: ${tool.id}")
        )

        // Execute the tool
        val result = try {
            val executionResult = executor.execute(request)

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
     * Check authorization for tool execution.
     *
     * This verifies:
     * 1. ExecutionPolicy compliance
     * 2. PermissionBroker approval if required (based on ExecutionMode)
     */
    private suspend fun checkAuthorization(
        task: Task,
        tool: Tool
    ): Result<Map<String, Any>> {
        // Phase 7: Enforce policy based on tool metadata
        val policyDecision = evaluatePolicy(tool, task)
        if (policyDecision.isFailure) {
            return policyDecision
        }

        // Check if PermissionBroker is required based on tool risk AND execution mode
        if (shouldRequestAuthorization(tool)) {
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

    /**
     * Determine if interactive authorization is required based on tool risk and execution mode.
     *
     * Execution Mode semantics:
     * - ASK: Always require authorization for tools that need approval
     * - STANDARD: Require authorization for SENSITIVE and higher risk tools
     * - FULL_ACCESS: Only authorize if tool explicitly requires sensitive approval
     */
    private suspend fun shouldRequestAuthorization(tool: Tool): Boolean {
        val mode = executionModeStore.getMode()

        return when (mode) {
            // ASK: Always request approval for operations requiring authorization
            ExecutionMode.ASK -> tool.requiresSensitiveApproval || tool.riskClass.level >= RiskClass.SENSITIVE.level

            // STANDARD: Request approval for sensitive operations
            ExecutionMode.STANDARD -> tool.requiresSensitiveApproval || tool.riskClass.level >= RiskClass.SENSITIVE.level

            // FULL_ACCESS: Only request approval if explicitly required by tool
            ExecutionMode.FULL_ACCESS -> tool.requiresSensitiveApproval
        }
    }

    /**
     * Evaluate policy for tool execution.
     *
     * Returns failure if policy denies execution.
     */
    private suspend fun evaluatePolicy(tool: Tool, task: Task): Result<Map<String, Any>> {
        // Policy allows if tool policy level <= task policy level
        // (higher risk task can run lower risk tools)
        // But tools requiring sensitive approval must be authorized regardless
        if (tool.riskClass.level > task.riskClass.level && !tool.requiresSensitiveApproval) {
            val entry = createAuditEntry(
                operation = "tool.policy.denied",
                target = task.id,
                toolId = tool.id,
                risk = tool.riskClass,
                decision = "DENIED",
                outcome = "policy_violation"
            )
            auditLog.record(entry)
            return Result.failure(IllegalStateException(
                "execution_denied:tool_risk_${tool.riskClass} exceeds_task_risk_${task.riskClass}"
            ))
        }

        // Check if tool is allowed by policy engine (e.g., not destructively blocked)
        if (tool.riskClass == RiskClass.DESTRUCTIVE) {
            val entry = createAuditEntry(
                operation = "tool.policy.denied",
                target = task.id,
                toolId = tool.id,
                risk = tool.riskClass,
                decision = "DENIED",
                outcome = "destructive_blocked"
            )
            auditLog.record(entry)
            return Result.failure(IllegalStateException(
                "execution_denied:destructive_operation_blocked"
            ))
        }

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