package org.aethra.assistant.aethra.permissions

import android.content.Context
import android.content.pm.PackageManager
import org.aethra.assistant.aethra.security.PolicyEngine
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import java.util.UUID

data class Permission(
    val id: String,
    val androidPermission: String,
    val riskClass: RiskClass,
    val target: String
)

data class PermissionDecision(
    val granted: Boolean,
    val permission: Permission,
    val taskId: String,
    val agentId: String?,
    val timestamp: Long,
    val outcome: String
)

class PermissionBridge(
    private val context: Context,
    private val policyEngine: PolicyEngine,
    private val auditLog: AuditLog
) {
    suspend fun requestPermission(
        permission: Permission,
        taskId: String,
        agentId: String? = null
    ): PermissionDecision {
        if (!policyEngine.allows(permission.riskClass, permission.id)) {
            auditLog.record(
                auditEntry(
                    "permission.policy.reject",
                    taskId,
                    permission.id,
                    permission.riskClass,
                    "DENIED",
                    "policy rejected"
                )
            )
            return PermissionDecision(
                granted = false,
                permission = permission,
                taskId = taskId,
                agentId = agentId,
                timestamp = System.currentTimeMillis(),
                outcome = "policy_reject"
            )
        }

        val granted =
            context.checkSelfPermission(permission.androidPermission) ==
                PackageManager.PERMISSION_GRANTED

        auditLog.record(
            AuditEntry(
                id = UUID.randomUUID().toString(),
                actor = "aethra.runtime",
                operation = "permission.request",
                target = taskId,
                riskClass = permission.riskClass,
                decision = if (granted) "GRANTED" else "DENIED",
                outcome = "requested"
            )
        )

        return PermissionDecision(
            granted = granted,
            permission = permission,
            taskId = taskId,
            agentId = agentId,
            timestamp = System.currentTimeMillis(),
            outcome = if (granted) "granted" else "denied"
        )
    }

    suspend fun recordDecision(
        permission: Permission,
        taskId: String,
        granted: Boolean,
        agentId: String? = null
    ) {
        auditLog.record(
            AuditEntry(
                id = UUID.randomUUID().toString(),
                actor = "aethra.runtime",
                operation = "permission.decision",
                target = taskId,
                riskClass = permission.riskClass,
                decision = if (granted) "GRANTED" else "DENIED",
                outcome = if (granted) "granted" else "denied"
            )
        )
    }

    private fun auditEntry(
        operation: String,
        target: String,
        operationName: String,
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
