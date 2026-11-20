package org.aethra.assistant.aethra.security

import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.Tool
import java.util.UUID

class ExecutionPolicy(
    private val policyEngine: PolicyEngine,
    private val permissionBroker: PermissionBroker,
    private val auditLog: AuditLog,
    private val taskRunner: TaskRunner
) {
    suspend fun execute(
        task: Task,
        tool: Tool,
        context: Any?,
        args: Map<String, Any>
    ): Result<Map<String, Any>> {
        if (!policyEngine.allows(tool.riskClass, tool)) {
            auditLog.record(
                audit("tool.policy.reject", task.id, tool.id, tool.riskClass, "DENIED", "policy rejected")
            )
            return Result.failure(Exception("policy.reject: " + tool.id))
        }

        if (tool.approvalRequired || tool.riskClass.level >= RiskClass.SENSITIVE.level) {
            val token = permissionBroker.requestApproval(
                actor = "aethra.runtime",
                risk = tool.riskClass,
                operation = tool.id,
                target = task.id
            )
            if (token != null) {
                auditLog.record(
                    audit("tool.permission.request", task.id, tool.id, tool.riskClass, "PENDING", "approval required")
                )
                return Result.failure(Exception("permission_required:" + token))
            }
        }

        auditLog.record(
            audit("tool.permission.grant", task.id, tool.id, tool.riskClass, "GRANTED", "approved")
        )

        return try {
            val result = taskRunner.run(task)
            auditLog.record(
                audit("tool.execution.success", task.id, tool.id, tool.riskClass, "GRANTED", "success")
            )
            result
        } catch (e: Exception) {
            auditLog.record(
                audit("tool.execution.failure", task.id, tool.id, tool.riskClass, "DENIED", "execution error")
            )
            Result.failure(e)
        }
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
