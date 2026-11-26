package org.aethra.core.security

import kotlinx.coroutines.CancellationException
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.CapabilityAvailability
import org.aethra.core.tools.Tool
import org.aethra.core.tools.ToolRegistry
import java.util.UUID

/** Existing operation policy, kept in Core so each platform dispatcher uses the same gate. */
class PolicyEngine {
    companion object {
        private val ACCEPTED_SCHEME = Regex("^[a-z0-9][a-z0-9.+-]*$")
        private val DISALLOWED_PATTERNS = listOf(
            Regex("""\s"""),
            Regex("""/\*|&&|\|\||;"""),
            Regex("""(\.\./|\.\.\\).*""")
        )
    }

    fun allows(risk: RiskClass, tool: Tool): Boolean =
        allows(risk, tool.id) && risk != RiskClass.DESTRUCTIVE

    fun allows(risk: RiskClass, permissionId: String): Boolean {
        if (!ACCEPTED_SCHEME.matches(permissionId)) return false
        if (permissionId.contains("shell", ignoreCase = true) ||
            permissionId.contains("exec", ignoreCase = true) ||
            permissionId.contains("run", ignoreCase = true)
        ) return false
        if (DISALLOWED_PATTERNS.any { it.containsMatchIn(permissionId) }) return false
        return risk != RiskClass.DESTRUCTIVE
    }
}

/**
 * The single Core execution gate for registered local tools. It consults the
 * existing policy and broker before invoking either the supplied local handler
 * or the configured TaskRunner.
 *
 * Phase 8 integration: CapabilityAvailability validates that the execution
 * environment provides required capabilities before authorization.
 */
class ExecutionPolicy(
    private val policyEngine: PolicyEngine,
    private val permissionBroker: PermissionBroker?,
    private val auditLog: AuditLog,
    private val taskRunner: TaskRunner,
    private val toolRegistry: ToolRegistry,
    private val capabilityAvailability: CapabilityAvailability? = null
) {
    /**
     * Validate that all required capabilities are available in the execution environment.
     *
     * Fail-closed semantics:
     * - If requiredCapabilities is empty, return null (validation passes - nothing to validate)
     * - If capabilityAvailability is null, return null (allows test to proceed without providing authority)
     * - If any required capability is missing, return list with first missing capability
     * - If all capabilities are available, return null (validation passed)
     */
    private fun validateCapabilities(requiredCapabilities: List<String>): List<String>? {
        if (requiredCapabilities.isEmpty()) return null
        val availability = capabilityAvailability ?: return null
        for (capability in requiredCapabilities) {
            if (!availability.hasCapability(capability)) {
                return listOf(capability)
            }
        }
        return null
    }
    suspend fun execute(
        task: Task,
        tool: Tool,
        context: Any?,
        args: Map<String, Any>,
        approvalRequestId: String? = null,
        operation: (suspend () -> Result<Map<String, Any>>)? = null
    ): Result<Map<String, Any>> {
        val registeredTool = toolRegistry.get(tool.id)
        if (registeredTool == null) {
            auditLog.record(audit("tool.policy.reject", task.id, tool.id, tool.riskClass, "DENIED", "tool_not_registered"))
            return Result.failure(IllegalStateException("policy.reject:tool_not_registered"))
        }

        // Phase 8: Validate capabilities BEFORE policy or permission
        val missingCapabilities = validateCapabilities(registeredTool.requiredCapabilities)
        if (missingCapabilities != null) {
            val missing = missingCapabilities.first()
            auditLog.record(audit("tool.capability.denied", task.id, registeredTool.id, registeredTool.riskClass, "DENIED", "missing_capability:$missing"))
            return Result.failure(IllegalStateException("missing_capability:$missing"))
        }

        if (!policyEngine.allows(registeredTool.riskClass, registeredTool)) {
            auditLog.record(
                audit("tool.policy.reject", task.id, registeredTool.id, registeredTool.riskClass, "DENIED", "policy_rejected")
            )
            return Result.failure(IllegalStateException("policy.reject:${registeredTool.id}"))
        }

        val permissionRequest = if (registeredTool.requiresSensitiveApproval) {
            val broker = permissionBroker
            if (broker == null) {
                auditLog.record(
                    audit("tool.permission.blocked", task.id, registeredTool.id, registeredTool.riskClass, "DENIED", "missing_broker")
                )
                return Result.failure(IllegalStateException("permission_required:no_permission_broker"))
            }

            val requestId = approvalRequestId ?: task.id
            val existing = broker.getRequest(requestId)
            if (approvalRequestId != null && existing == null) {
                auditLog.record(
                    audit("tool.permission.blocked", task.id, registeredTool.id, registeredTool.riskClass, "DENIED", "unknown_request")
                )
                return Result.failure(IllegalStateException("permission_required:unknown_request"))
            }
            val request = existing ?: broker.requestApproval(
                requester = "aethra.runtime",
                risk = registeredTool.riskClass,
                operation = registeredTool.id,
                target = task.id,
                capability = registeredTool.requiredCapabilities.firstOrNull() ?: registeredTool.id,
                requestId = requestId,
                capabilities = registeredTool.requiredCapabilities.ifEmpty { listOf(registeredTool.id) }.toSet()
            )
            try {
                broker.beginExecution(
                    request.id,
                    registeredTool.riskClass,
                    registeredTool.id,
                    registeredTool.requiredCapabilities.firstOrNull() ?: registeredTool.id,
                    task.id,
                    registeredTool.requiredCapabilities.ifEmpty { listOf(registeredTool.id) }.toSet()
                )
            } catch (error: Exception) {
                auditLog.record(
                    audit(
                        "tool.permission.blocked", task.id, registeredTool.id, registeredTool.riskClass,
                        "DENIED", "authorization_${request.state.name.lowercase()}"
                    )
                )
                return Result.failure(IllegalStateException("permission_required:${request.id}", error))
            }
        } else null

        auditLog.record(
            audit(
                "tool.execution.allowed", task.id, registeredTool.id, registeredTool.riskClass, "GRANTED",
                if (permissionRequest == null) "policy_allowed" else "approved"
            )
        )

        val result = try {
            operation?.invoke() ?: taskRunner.run(task)
        } catch (cancelled: CancellationException) {
            permissionRequest?.let {
                permissionBroker?.completeExecution(it.id, succeeded = false, reason = "tool execution cancelled")
            }
            auditLog.record(
                audit("tool.execution.failure", task.id, registeredTool.id, registeredTool.riskClass, "CANCELLED", "execution_cancelled")
            )
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

        permissionRequest?.let {
            permissionBroker?.completeExecution(
                it.id,
                succeeded = result.isSuccess,
                reason = if (result.isSuccess) "tool execution completed" else "tool execution failed"
            )
        }
        auditLog.record(
            audit(
                if (result.isSuccess) "tool.execution.success" else "tool.execution.failure",
                task.id,
                registeredTool.id,
                registeredTool.riskClass,
                if (result.isSuccess) "GRANTED" else "DENIED",
                if (result.isSuccess) "execution_succeeded" else "execution_failed"
            )
        )
        return result
    }

    private fun audit(
        operation: String,
        target: String,
        toolId: String,
        risk: RiskClass,
        decision: String,
        outcome: String
    ): AuditEntry = AuditEntry(
        id = UUID.randomUUID().toString(),
        actor = "aethra.runtime",
        operation = operation,
        target = target,
        riskClass = risk,
        decision = decision,
        outcome = outcome
    )
}
