package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import java.util.UUID

/**
 * Skills are optional capabilities that extend AETHRA without being bundled
 * into the core. Each skill declares its identity, version, source, permissions
 * it requests, dependencies, risk class, declared capabilities, and integrity
 * metadata. None execute automatically.
 *
 * Trust is a separate Core-owned state. A manifest or imported instruction
 * cannot make itself trusted or executable.
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
    val enabled: Boolean = false,
    val trustState: SkillTrustState = SkillTrustState.UNTRUSTED,
    val state: SkillState = SkillState.DISCOVERED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    val isActive: Boolean
        get() = enabled &&
            trustState == SkillTrustState.TRUSTED &&
            state.isInstalled
}

/** Core-owned trust state. External skill metadata cannot transition this state. */
enum class SkillTrustState {
    UNTRUSTED,
    TRUSTED,
    REVOKED
}

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

    val isInstalled: Boolean
        get() = this == SkillState.INSTALLED || this == SkillState.UPDATING

    val isActive: Boolean
        get() = this == SkillState.INSTALLED || this == SkillState.UPDATING
}
