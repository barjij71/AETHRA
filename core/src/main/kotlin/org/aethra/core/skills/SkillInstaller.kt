package org.aethra.core.skills

import org.aethra.core.storage.Storage
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import java.util.UUID

/**
 * Installs, validates and activates skills. A skill can be authored locally or
 * fetched from a configured source (git, package). The installer inspects the
 * manifest and permission declarations before activation, and records the
 * activity in the audit log.
 */
class SkillInstaller(
    private val store: SkillRegistry,
    private val auditLog: AuditLog,
    private val broker: PermissionBroker,
    private val storage: Storage
) {
    suspend fun install(sourceReport: SourceReport, skill: Skill): Result<Skill> {
        // validate declared permissions against the broker before activation
        val declared = skill.requestedPermissions
        for (p in declared) {
            // a real implementation would consult the broker policy; here we
            // check the token path is present for high-risk declarations
            if (p.contains("root") || p.contains("install") || p.contains("boot")) {
                return Result.failure(IllegalArgumentException("refusing to enable privilege that requires explicit grant"))
            }
        }
        store.register(skill)
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(), actor = "installer", operation = "skill.install",
            target = skill.id, riskClass = RiskClass.REVERSIBLE, decision = "GRANTED", outcome = "installed"
        )
        auditLog.record(entry)
        return Result.success(skill)
    }

    suspend fun uninstall(id: String): Boolean {
        if (!store.remove(id)) return false
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(), actor = "installer", operation = "skill.uninstall",
            target = id, riskClass = RiskClass.REVERSIBLE, decision = "GRANTED", outcome = "removed"
        )
        auditLog.record(entry)
        return true
    }
}
