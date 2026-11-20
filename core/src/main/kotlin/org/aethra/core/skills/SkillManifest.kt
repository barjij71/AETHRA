package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import kotlinx.serialization.Serializable

/** A parsed skill manifest. It is a device-owned data class, not a URL parser and not a network client. */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val source: SourceMetadata = SourceMetadata(),
    val description: String = "",
    val entrypoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val integrity: IntegrityInfo? = null,
    val enabled: Boolean = true,
    val state: SkillState = SkillState.DISCOVERED
) {
    /** True if the manifest is internally consistent and installable. */
    val isValid: Boolean
        get() = id.isNotBlank() && name.isNotBlank() && version.isNotBlank()

    /** True if the manifest is active (installed and enabled). */
    val isActive: Boolean
        get() = enabled && (state == SkillState.INSTALLED || state == SkillState.UPDATING)
}

/** Build a [Skill] from a [SkillManifest] after validation. */
fun SkillManifest.toSkill(): Skill = Skill(
    id = id,
    name = name,
    version = version,
    source = source,
    description = description,
    entrypoints = entrypoints,
    capabilities = capabilities,
    requestedPermissions = requestedPermissions,
    dependencies = dependencies,
    platforms = platforms,
    riskClass = riskClass,
    integrity = integrity,
    enabled = enabled,
    state = state
)
