package org.aethra.core.skills

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage

/**
 * Core-owned registry of installed skills.
 *
 * Registration always starts UNTRUSTED and disabled. Trust is a separate
 * internal state and must be granted by Core verification code. Manifest flags,
 * imported instructions, or callers outside the Core module cannot self-enable
 * a skill.
 *
 * Persistence is fail-closed: a state transition is committed in memory only
 * after the corresponding durable journal write succeeds.
 */
class SkillRegistry(private val storage: Storage) {
    private val skills = LinkedHashMap<String, Skill>()
    private val lock = Any()

    init {
        loadPersisted()
    }

    fun registerManifest(rawManifest: String): Result<Skill> = runCatching {
        val manifest = SkillManifest.parse(rawManifest).getOrThrow()
        val skill = manifest.toUntrustedSkill()
        require(register(skill)) { "skill already registered: ${skill.id}" }
        skill
    }

    fun register(skill: Skill): Boolean {
        synchronized(lock) {
            if (skills.containsKey(skill.id)) return false
            val merged = skill.copy(
                enabled = false,
                trustState = SkillTrustState.UNTRUSTED,
                createdAt = skill.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            skills[merged.id] = merged
            try {
                persistState()
            } catch (e: Exception) {
                skills.remove(merged.id)
                throw SkillPersistenceException("Failed to persist skill registration", e)
            }
            return true
        }
    }

    fun get(id: String): Skill? = synchronized(lock) { skills[id] }

    fun all(): List<Skill> = synchronized(lock) { skills.values.toList() }

    fun find(name: String): Skill? =
        all().find { it.name.equals(name, ignoreCase = true) }

    fun findByCapability(capability: String): List<Skill> {
        val lower = capability.lowercase()
        return all().filter { it.capabilities.any { it.lowercase() == lower } }
    }

    fun findByPlatform(platform: String): List<Skill> {
        val lower = platform.lowercase()
        return all().filter {
            it.platforms.isEmpty() || it.platforms.any { it.lowercase() == lower }
        }
    }

    /**
     * Core-owned installation transition. Installation never grants trust or execution.
     * Lifecycle transition from DISCOVERED/VALID/VALIDATING/UPDATE_AVAILABLE to INSTALLED.
     * Resets trust to UNTRUSTED and forces enabled=false.
     */
    fun install(id: String): Boolean {
        synchronized(lock) {
            val skill = skills[id] ?: return false
            if (skill.state == SkillState.REMOVED) return false
            if (skill.state == SkillState.INSTALLED) return true

            val allowedSources = setOf(
                SkillState.DISCOVERED,
                SkillState.VALID,
                SkillState.VALIDATING,
                SkillState.UPDATE_AVAILABLE
            )
            if (skill.state !in allowedSources) return false

            skills[id] = skill.copy(
                state = SkillState.INSTALLED,
                trustState = SkillTrustState.UNTRUSTED,
                enabled = false,
                updatedAt = System.currentTimeMillis()
            )
            try {
                persistState()
            } catch (e: Exception) {
                skills[id] = skill
                throw SkillPersistenceException("Failed to persist skill installation", e)
            }
            return true
        }
    }

    /**
     * Marks an installed skill as having a newer artifact available without activating it.
     * Preserves identity trust metadata but forces disabled state.
     * The new artifact must go through external verification -> install() -> trust() -> enable().
     * Only allowed from INSTALLED state (not UPDATING).
     */
    fun markUpdateAvailable(id: String): Boolean {
        synchronized(lock) {
            val skill = skills[id] ?: return false
            if (skill.state != SkillState.INSTALLED) return false

            skills[id] = skill.copy(
                state = SkillState.UPDATE_AVAILABLE,
                enabled = false,
                updatedAt = System.currentTimeMillis()
            )
            try {
                persistState()
            } catch (e: Exception) {
                skills[id] = skill
                throw SkillPersistenceException("Failed to persist update available state", e)
            }
            return true
        }
    }

    fun enable(id: String): Boolean {
        synchronized(lock) {
            val skill = skills[id] ?: return false
            if (skill.trustState != SkillTrustState.TRUSTED) return false
            // Allow enable from INSTALLED or DISABLED (re-activation)
            if (skill.state != SkillState.INSTALLED && skill.state != SkillState.DISABLED) return false
            if (skill.enabled && skill.state == SkillState.INSTALLED) return true

            skills[id] = skill.copy(
                state = SkillState.INSTALLED,
                enabled = true,
                updatedAt = System.currentTimeMillis()
            )
            try {
                persistState()
            } catch (e: Exception) {
                skills[id] = skill
                throw SkillPersistenceException("Failed to persist skill enablement", e)
            }
            return true
        }
    }

    fun disable(id: String): Boolean {
        synchronized(lock) {
            val skill = skills[id] ?: return false
            // Already disabled and in DISABLED state -> idempotent
            if (!skill.enabled && skill.state == SkillState.DISABLED) return true

            // UPDATE_AVAILABLE and UPDATING are NOT converted to DISABLED
            // They already guarantee enabled=false and isActive=false
            // Converting them would lose semantic information about pending update
            if (skill.state == SkillState.UPDATE_AVAILABLE ||
                skill.state == SkillState.UPDATING) {
                return false
            }
            // REMOVED rejects any mutation
            if (skill.state == SkillState.REMOVED) return false

            // Only disable from INSTALLED (and UPDATING if we allowed it, but we reject above)
            if (!skill.state.isInstalled) return false

            skills[id] = skill.copy(
                state = SkillState.DISABLED,
                enabled = false,
                updatedAt = System.currentTimeMillis()
            )
            try {
                persistState()
            } catch (e: Exception) {
                skills[id] = skill
                throw SkillPersistenceException("Failed to persist skill disablement", e)
            }
            return true
        }
    }

    /** Core-internal trust transition; external adapters cannot self-grant trust. */
    internal fun setTrust(id: String, trustState: SkillTrustState): Boolean {
        synchronized(lock) {
            val skill = skills[id] ?: return false
            val enabled = trustState == SkillTrustState.TRUSTED &&
                skill.state.isInstalled &&
                skill.enabled
            val updated = skill.copy(
                trustState = trustState,
                enabled = enabled,
                updatedAt = System.currentTimeMillis()
            )
            skills[id] = updated
            try {
                persistState()
            } catch (e: Exception) {
                skills[id] = skill
                throw SkillPersistenceException("Failed to persist skill trust transition", e)
            }
            return true
        }
    }

    internal fun trust(id: String): Boolean =
        setTrust(id, SkillTrustState.TRUSTED)

    internal fun revokeTrust(id: String): Boolean =
        setTrust(id, SkillTrustState.REVOKED)

    fun remove(id: String): Boolean {
        synchronized(lock) {
            val previous = skills.remove(id) ?: return false
            try {
                persistState()
            } catch (e: Exception) {
                skills[id] = previous
                throw SkillPersistenceException("Failed to persist skill removal", e)
            }
            return true
        }
    }

    val size: Int
        get() = synchronized(lock) { skills.size }

    val highestRisk: RiskClass
        get() = all()
            .filter { it.enabled && it.trustState == SkillTrustState.TRUSTED }
            .map { it.riskClass }
            .maxByOrNull { it.level }
            ?: RiskClass.READ_ONLY

    private fun persistState() {
        val entries = all().map { skill ->
            SkillEntry(
                id = skill.id,
                name = skill.name,
                version = skill.version,
                source = skill.source,
                description = skill.description,
                entrypoints = skill.entrypoints,
                capabilities = skill.capabilities,
                requestedPermissions = skill.requestedPermissions,
                dependencies = skill.dependencies,
                platforms = skill.platforms,
                riskClass = skill.riskClass,
                integrity = skill.integrity,
                enabled = skill.enabled,
                trustState = skill.trustState,
                state = skill.state,
                createdAt = skill.createdAt,
                updatedAt = skill.updatedAt
            )
        }
        val raw = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .encodeToString(entries)
        runBlocking { storage.setString("aethra.skills.v1", raw) }
    }

    private fun loadPersisted() {
        val raw = runBlocking { storage.getString("aethra.skills.v1") } ?: return
        if (raw.isBlank()) return

        val entries = try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<List<SkillEntry>>(raw)
        } catch (e: Exception) {
            throw SkillPersistenceException("Skill journal is corrupt or incompatible", e)
        }

        val restored = LinkedHashMap<String, Skill>()
        for (entry in entries) {
            val executable = entry.trustState == SkillTrustState.TRUSTED &&
                entry.enabled &&
                entry.state.isInstalled

            val skill = Skill(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                source = entry.source,
                description = entry.description,
                entrypoints = entry.entrypoints,
                capabilities = entry.capabilities,
                requestedPermissions = entry.requestedPermissions,
                dependencies = entry.dependencies,
                platforms = entry.platforms,
                riskClass = entry.riskClass,
                integrity = entry.integrity,
                enabled = executable,
                trustState = entry.trustState,
                state = entry.state,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt
            )
            require(restored.put(skill.id, skill) == null) {
                "Duplicate skill id in persisted journal: ${skill.id}"
            }
        }

        synchronized(lock) {
            skills.clear()
            skills.putAll(restored)
        }
    }
}

class SkillPersistenceException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

@Serializable
data class SkillEntry(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val source: SourceMetadata = SourceMetadata(),
    val description: String = "",
    val entrypoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val integrity: IntegrityInfo? = null,
    val enabled: Boolean = false,
    val trustState: SkillTrustState = SkillTrustState.UNTRUSTED,
    val state: SkillState = SkillState.DISCOVERED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
