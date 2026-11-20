package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import java.util.UUID

/**
 * Skills are optional capabilities that extend AETHRA without being bundled
 * into the core. Each skill declares its identity, version, source, permissions
 * it requests, dependencies, risk class, declared capabilities, and integrity
 * metadata. None execute automatically.
 *
 * The [Skill] class is a device-owned domain model produced from a
 * [SkillManifest] after validation and verification. Nothing inside this class
 * is executed.
 */
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String = "1.0.0",
    val source: SourceMetadata = SourceMetadata(local = true),
    val description: String = "",
    val entrypoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val integrity: IntegrityInfo? = null,
    val enabled: Boolean = true,
    val state: SkillState = SkillState.DISCOVERED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    /** Returns true if this skill is active and eligible for approval/execution. */
    val isActive: Boolean get() = enabled && state.isInstalled
}

/**
 * Lifecycle states for a skill. Transitions are controlled by
 * [SkillLifecycle.canTransition]. Nothing inside [Skill] executes
 * automatically; enable/disable and state changes are API calls.
 */
enum class SkillState {
    DISCOVERED,
    VALIDATING,
    VALID,
    INSTALLING,
    INSTALLED,
    UPDATING,
    UPDATE_AVAILABLE,
    DISABLED,
    FAILED,
    REMOVED;

    companion object {
        val installed = setOf(SkillState.INSTALLED, SkillState.UPDATING)
        val executable = setOf(SkillState.INSTALLED, SkillState.UPDATING)
    }

    /** True if the skill is installed (usable after approval). */
    val isInstalled: Boolean get() = this == SkillState.INSTALLED || this == SkillState.UPDATING

    /** True if the skill is currently active and can be approved/executed. */
    val isActive: Boolean get() = this == SkillState.INSTALLED || this == SkillState.UPDATING
}
