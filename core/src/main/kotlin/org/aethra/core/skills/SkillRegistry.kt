package org.aethra.core.skills

enum class TrustState { UNTRUSTED, REVIEWED, TRUSTED, REVOKED }

data class RegisteredSkill(
    val manifest: SkillManifest,
    val trustState: TrustState,
    val registeredAt: Long = System.currentTimeMillis()
)

class SkillRegistry {
    private val entries = LinkedHashMap<String, RegisteredSkill>()

    @Synchronized
    fun register(manifest: SkillManifest) {
        val validation = SkillManifestValidator.validate(manifest)
        require(validation.valid) { validation.errors.joinToString("; ") }
        // Imported manifest fields are claims, not Core authorization decisions.
        // Every registration starts untrusted and disabled.
        entries[manifest.id] = RegisteredSkill(
            manifest.copy(enabled = false),
            TrustState.UNTRUSTED
        )
    }

    @Synchronized fun get(id: String): RegisteredSkill? = entries[id]
    @Synchronized fun all(): List<RegisteredSkill> = entries.values.toList()

    @Synchronized
    fun enable(id: String): Boolean {
        val current = entries[id] ?: return false
        require(current.trustState == TrustState.TRUSTED && current.manifest.trusted) {
            "skill must be trusted before enable"
        }
        entries[id] = current.copy(manifest = current.manifest.copy(enabled = true))
        return true
    }

    @Synchronized
    fun disable(id: String): Boolean {
        val current = entries[id] ?: return false
        entries[id] = current.copy(manifest = current.manifest.copy(enabled = false))
        return true
    }

    /**
     * Trust transitions are a Core-internal operation. External importers and skills
     * may register candidates, but cannot promote or re-enable them.
     */
    @Synchronized
    internal fun setTrust(id: String, trustState: TrustState): Boolean {
        val current = entries[id] ?: return false
        if (trustState == TrustState.TRUSTED) require(current.manifest.trusted) { "manifest is not trusted" }
        val manifest = if (trustState == TrustState.TRUSTED) {
            current.manifest
        } else {
            current.manifest.copy(enabled = false)
        }
        entries[id] = current.copy(manifest = manifest, trustState = trustState)
        return true
    }
}
