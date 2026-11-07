package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import kotlinx.serialization.Serializable
import org.aethra.core.storage.Storage
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Registry of installed skills. Enforces:
 * - Duplicate ID rejection
 * - Platform filtering
 * - Capability lookup
 * - enable/disable semantics (disabled skills cannot execute)
 * - Persistence to [Storage]
 *
 * No skill can execute by itself; execution goes through
 * [ToolRegistry] -> [ExecutionPolicy] -> [PermissionBroker] -> [AuditLog].
 */
class SkillRegistry(private val storage: Storage) {
    private val skills = LinkedHashMap<String, Skill>()

    init {
        loadPersisted()
    }

    private val lock = Any()

    /** Register a skill. Rejects duplicates. */
    fun register(skill: Skill): Boolean {
        synchronized(lock) {
            if (skills.containsKey(skill.id)) return false
            val merged = skill.copy(
                createdAt = skill.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            skills[merged.id] = merged
            persistState()
            return true
        }
    }

    /** Get a skill by ID. */
    fun get(id: String): Skill? = skills[id]

    /** List all skills. */
    fun all(): List<Skill> = skills.values.toList()

    /** Find a skill by name (case-insensitive). */
    fun find(name: String): Skill? = all().find { it.name.equals(name, ignoreCase = true) }

    /**
     * Find skills that expose a specific capability.
     */
    fun findByCapability(capability: String): List<Skill> {
        val lower = capability.lowercase()
        return all().filter { it.capabilities.any { it.lowercase() == lower } }
    }

    /**
     * Find skills for a target platform.
     */
    fun findByPlatform(platform: String): List<Skill> {
        val lower = platform.lowercase()
        return all().filter { it.platforms.isEmpty() || it.platforms.any { it.lowercase() == lower } }
    }

    /** Enable or disable a skill. */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        synchronized(lock) {
            val s = skills[id] ?: return false
            val updated = s.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
            skills[id] = updated
            persistState()
            return true
        }
    }

    fun enable(id: String): Boolean = setEnabled(id, true)
    fun disable(id: String): Boolean = setEnabled(id, false)

    /** Remove a skill by ID. Returns true if removed. */
    fun remove(id: String): Boolean {
        synchronized(lock) {
            if (!skills.containsKey(id)) return false
            skills.remove(id)
            persistState()
            return true
        }
    }

    /** Total number of registered skills. */
    val size: Int get() = skills.size

    /** Highest risk class among enabled skills. */
    val highestRisk: RiskClass get() = all().filtered().map { it.riskClass }.maxByOrNull { it.level } ?: RiskClass.READ_ONLY

    private fun List<Skill>.filtered() = filter { it.enabled }

    /** Persist the state to storage. */
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
                state = skill.state,
                createdAt = skill.createdAt,
                updatedAt = skill.updatedAt
            )
        }
        val raw = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.encodeToString(entries)
        try {
            runBlocking {
                storage.setString("aethra.skills.v1", raw)
            }
        } catch (e: Exception) {
            // persist failure must not crash the owning flow
        }
    }

    /** Load persisted state from storage. */
    private fun loadPersisted() {
        try {
        val raw: String? = runBlocking {
            storage.getString("aethra.skills.v1")
        }
            if (raw == null || raw.length == 0) return
            val entries = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<List<SkillEntry>>(raw)
            for (e in entries) {
                val skill = Skill(
                    id = e.id,
                    name = e.name,
                    version = e.version,
                    source = e.source,
                    description = e.description,
                    entrypoints = e.entrypoints,
                    capabilities = e.capabilities,
                    requestedPermissions = e.requestedPermissions,
                    dependencies = e.dependencies,
                    platforms = e.platforms,
                    riskClass = e.riskClass,
                    integrity = e.integrity,
                    enabled = e.enabled,
                    state = e.state,
                    createdAt = e.createdAt,
                    updatedAt = e.updatedAt
                )
                register(skill)
            }
        } catch (e: Exception) {
            // corrupt/legacy data: start fresh
        }
    }
}

/**
 * Dataclass wrapper used for persistence serialization.
 */
@Serializable
data class SkillEntry(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
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
    val state: SkillState = SkillState.DISCOVERED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
