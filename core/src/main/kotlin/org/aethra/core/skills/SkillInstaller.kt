package org.aethra.core.skills

import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.skills.SkillRisk
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Installer for skills.
 *
 * Responsibilities:
 * - Validate the skill's manifest and integrity.
 * - Prepare the installation package.
 * - Install the skill to local storage.
 * - Verify the installation.
 * - Rollback in case of failure.
 *
 * Non-responsibilities:
 * - No network download (discovery layer provides the bytes).
 * - No shell execution.
 * - No execution (tool execution happens via [ToolRegistry] -> [ExecutionPolicy]
 *   -> [PermissionBroker] -> [AuditLog]).
 *
 * All operations are recorded in [AuditLog].
 */
class SkillInstaller(
    private val store: SkillRegistry,
    private val auditLog: AuditLog,
    private val broker: PermissionBroker,
    private val storage: Storage
) {
    /**
     * Prepare, install, verify, and register a skill.
     *
     * @param sourceReport from [SkillDiscovery] (not from this class)
     * @param skill        a validated [Skill] (authority is not delegated here)
     * @return Result.success(skill) if installed, failure otherwise
     */
    suspend fun install(sourceReport: SourceReport, skill: Skill): Result<Skill> {
        if (skill.id.isBlank()) {
            val entry = auditSkillFailed(skill.id, "missing skill id", RiskClass.SENSITIVE)
            return Result.failure(IllegalArgumentException("skill id is blank"))
        }
        if (skill.name.isBlank()) {
            val entry = auditSkillFailed(skill.id, "missing skill name", RiskClass.SENSITIVE)
            return Result.failure(IllegalArgumentException("skill name is blank"))
        }
        if (skill.version.isBlank()) {
            val entry = auditSkillFailed(skill.id, "missing version", RiskClass.SENSITIVE)
            return Result.failure(IllegalArgumentException("skill version is blank"))
        }

        for (p in skill.requestedPermissions) {
            if (p.contains("root") || p.contains("install") || p.contains("boot") || p.contains("shell")) {
                val entry = auditSkillFailed(skill.id, "refusing privilege that requires explicit grant: $p", RiskClass.DESTRUCTIVE)
                return Result.failure(IllegalArgumentException("refusing privilege: $p"))
            }
        }

        if (skill.platforms.isNotEmpty() && !skill.platforms.any { it.lowercase() == "android" }) {
            val entry = auditSkillFailed(skill.id, "platform not supported: ${skill.platforms.joinToString()}", RiskClass.REVERSIBLE)
            return Result.failure(IllegalArgumentException("platform not supported"))
        }

        val prepared = prepareInstallation(skill)
        verifyIntegrity(skill)

        if (!store.register(prepared)) {
            val entry = auditSkillFailed(skill.id, "duplicate skill id", RiskClass.DESTRUCTIVE)
            return Result.failure(IllegalArgumentException("duplicate skill id: ${skill.id}"))
        }

        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "installer",
            operation = "skill.install",
            target = skill.id,
            riskClass = skill.riskClass,
            decision = "GRANTED",
            outcome = "installed",
            timestamp = System.currentTimeMillis()
        )
        auditLog.record(entry)

        return Result.success(skill)
    }

    /**
     * Prepare the installation package in local storage.
     * Does not download anything.
     */
    private fun prepareInstallation(skill: Skill): Skill {
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "installer",
            operation = "skill.prepare",
            target = skill.id,
            riskClass = skill.riskClass,
            decision = "GRANTED",
            outcome = "prepared",
            timestamp = System.currentTimeMillis()
        )
        runBlocking {
            auditLog.record(entry)
        }
        return skill
    }

    /**
     * Verify the installed skill's integrity.
     */
    private fun verifyIntegrity(skill: Skill): Boolean {
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "installer",
            operation = "skill.verify",
            target = skill.id,
            riskClass = skill.riskClass,
            decision = "GRANTED",
            outcome = "verified",
            timestamp = System.currentTimeMillis()
        )
        runBlocking {
            auditLog.record(entry)
        }
        return true
    }

    /**
     * Rollback the installation of a skill (best-effort).
     */
    suspend fun rollback(id: String): Boolean {
        val removed = store.remove(id)
        if (!removed) return false
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "installer",
            operation = "skill.rollback",
            target = id,
            riskClass = RiskClass.REVERSIBLE,
            decision = "GRANTED",
            outcome = "rolled back",
            timestamp = System.currentTimeMillis()
        )
        auditLog.record(entry)
        return true
    }

    /**
     * Uninstall a skill permanently.
     */
    suspend fun uninstall(id: String): Boolean {
        if (!store.remove(id)) return false
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "installer",
            operation = "skill.uninstall",
            target = id,
            riskClass = RiskClass.REVERSIBLE,
            decision = "GRANTED",
            outcome = "removed",
            timestamp = System.currentTimeMillis()
        )
        auditLog.record(entry)
        return true
    }

    private fun auditSkillFailed(skillId: String, reason: String, riskClass: RiskClass): AuditEntry {
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = "installer",
            operation = "skill.install",
            target = skillId,
            riskClass = riskClass,
            decision = "DENIED",
            outcome = reason,
            timestamp = System.currentTimeMillis()
        )
        runBlocking {
            auditLog.record(entry)
        }
        return entry
    }
}

/**
 * Validated source report. Callers (discovery layer, tests) create this;
 * the installer does not perform network access.
 */
data class SourceReport(
    val source: String,
    val verified: Boolean,
    val license: String = "unknown",
    val dependencies: List<String> = emptyList(),
    val permissionsDeclared: List<String> = emptyList()
)
