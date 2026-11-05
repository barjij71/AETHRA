package org.aethra.assistant.aethra.security

import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.tools.Tool
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import java.util.UUID

/**
 * Execution policy for tools, agents and skills.
 *
 * Enforces the security pipeline Policy → Permission → Audit → Execution.
 *
 *   1. PolicyEngine:   no shell / no wildcard / no path-traversal / least privilege
 *   2. PermissionBroker: request approval for sensitive / destructive tools
 *   3. AuditLog:        record the decision (requested / granted / denied / executed)
 *   4. TaskRunner:      actual execution via the platform task runner (Android)
 *
 * This class is a pure decision + audit + delegation layer. It contains no Android
 * imports and is fully unit-testable on the JVM.
 */
class ExecutionPolicy(
    private val policyEngine: PolicyEngine,
    private val permissionBroker: PermissionBroker,
    private val auditLog: AuditLog,
    private val taskRunner: TaskRunner
) {
    private val pipeline = "Policy -> Permission -> Audit -> Execution"

    /**
     * Execute a tool through the security pipeline.
     *
     * @param task the task this tool belongs to
     * @param tool the tool declaration
     * @param context the Android context (supplied by the runtime)
     * @param args the tool arguments
     * @return a successful [Result] with the tool result map, or a failed [Result]
     *  with a specific reason (approval required, policy reject, execution error)
     */
    suspend fun execute(
        task: Task,
        tool: Tool,
        context: Any?,
        args: Map<String, Any>
    ): Result<Map<String, Any>> {
        // 1. Policy — deny shell / wildcard / path-traversal / destructive.
        if (!policyEngine.allows(tool.riskClass, tool)) {
            val entry = audit("tool.policy.reject", task.id, tool.id, tool.riskClass, "GRANTED", "policy rejected")
            auditLog.record(entry)
            return Result.failure(Exception("policy.reject: ${tool.id}"))
        }

        // 2. Permission — request approval for sensitive / destructive tools.
        var approvalToken: String? = null
        if (tool.approvalRequired || tool.riskClass.level >= RiskClass.SENSITIVE.level) {
            approvalToken = permissionBroker.requestApproval(
                actor = "aethra.runtime",
                risk = tool.riskClass,
                operation = tool.id,
                target = task.id
            )
            if (approvalToken != null) {
                val entry = audit("tool.permission.request", task.id, tool.id, tool.riskClass, "PENDING", "approval required")
                auditLog.record(entry)
                return Result.failure(Exception("permission_required:$approvalToken"))
            }
        }

        // 3. Audit — record the grant.
        val grantEntry = audit("tool.permission.grant", task.id, tool.id, tool.riskClass, "GRANTED", "approved")
        auditLog.record(grantEntry)

        // 4. Execution — delegate to the platform task runner.
        val result = try {
            val toolResult = taskRunner.run(task, args)
            val successEntry = audit("tool.execution.success", task.id, tool.id, tool.riskClass, "GRANTED", "success")
            auditLog.record(successEntry)
            toolResult
        } catch (e: Exception) {
            val failEntry = audit("tool.execution.failure", task.id, tool.id, tool.riskClass, "DENIED", e.message ?: "execution error")
            auditLog.record(failEntry)
            Result.failure(e)
        }

        return result
    }

    private fun audit(operation: String, target: String, toolId: String, risk: RiskClass, decision: String, outcome: String): AuditEntry {
        return AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "aethra.runtime",
            operation = "tool.$operation",
            target = target,
            riskClass = risk,
            decision = decision,
            outcome = outcome
        )
    }
}
