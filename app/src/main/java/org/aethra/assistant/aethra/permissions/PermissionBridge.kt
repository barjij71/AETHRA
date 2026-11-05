package org.aethra.assistant.aethra.permissions

import android.content.Context
import android.content.pm.PackageManager
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.assistant.aethra.security.PolicyEngine
import java.util.UUID

/**
 * Android permission bridge.
 *
 * Abstracts the mapping between an AETHRA internal [Permission] and the Android
 * runtime permission. Every request passes through the PolicyEngine first, then
 * reads or asks for the matching Android runtime permission, and always records
 * an [AuditEntry] so the full lifecycle is visible in the audit log.
 *
 * The bridge never gives a tool direct Android permission access; all access is
 * mediated by this bridge. This keeps the policy, audit and Android layers
 * cleanly separated.
 *
 *   AETHRA Permission → PolicyEngine → Android Permission Bridge → Android runtime permission
 */
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
    val outcome: String   // "granted" | "denied" | "pending" | "policy_reject"
)

class PermissionBridge(
    private val context: Context,
    private val policyEngine: PolicyEngine,
    private val auditLog: AuditLog
) {
    private val REQUEST_CODE = 1001

    /**
     * Request an AETHRA permission through the full pipeline.
     *
     * @param permission the AETHRA permission declaration
     * @param taskId the task id (for audit)
     * @param agentId optional agent id (for audit)
     * @return a [PermissionDecision] (granted / denied / pending / policy_reject)
     */
    suspend fun requestPermission(
        permission: Permission,
        taskId: String,
        agentId: String? = null
    ): PermissionDecision {
        // 1. Policy first — deny shell / wildcard / path-traversal before asking.
        if (!policyEngine.allows(permission.riskClass, permission)) {
            val entry = auditEntry("permission.policy.reject", taskId, permission.id, permission.riskClass, "DENIED", "policy rejected")
            auditLog.record(entry)
            return PermissionDecision(
                granted = false,
                permission = permission,
                taskId = taskId,
                agentId = agentId,
                timestamp = System.currentTimeMillis(),
                outcome = "policy_reject"
            )
        }

        // 2. Read current Android runtime permission state.
        val granted = context.checkSelfPermission(permission.androidPermission) == PackageManager.PERMISSION_GRANTED

        // 3. Audit the decision (requested / granted / denied).
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "aethra.runtime",
            operation = "permission.request",
            target = taskId,
            riskClass = permission.riskClass,
            decision = if (granted) "GRANTED" else "DENIED",
            outcome = "requested"
        )
        auditLog.record(entry)

        return PermissionDecision(
            granted = granted,
            permission = permission,
            taskId = taskId,
            agentId = agentId,
            timestamp = System.currentTimeMillis(),
            outcome = if (granted) "granted" else "denied"
        )
    }

    /** Record the user's runtime-permission decision. */
    suspend fun recordDecision(
        permission: Permission,
        taskId: String,
        granted: Boolean,
        agentId: String? = null
    ) {
        val outcome = if (granted) "granted" else "denied"
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "aethra.runtime",
            operation = "permission.decision",
            target = taskId,
            riskClass = permission.riskClass,
            decision = if (granted) "GRANTED" else "DENIED",
            outcome = outcome
        )
        auditLog.record(entry)
    }

    private fun auditEntry(operation: String, target: String, operationName: String, risk: RiskClass, decision: String, outcome: String): AuditEntry {
        return AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "aethra.runtime",
            operation = "permission.$operation",
            target = target,
            riskClass = risk,
            decision = decision,
            outcome = outcome
        )
    }
}
