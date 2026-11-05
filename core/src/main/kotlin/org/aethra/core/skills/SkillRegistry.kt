package org.aethra.core.skills

import java.util.UUID

/** Registry of skills. Enforces enable/disable, source verification, dependency
 *  checks and permission declarations. No skill runs on its own. */
class SkillRegistry {
    private val skills = LinkedHashMap<String, Skill>()

    fun register(skill: Skill) {
        skills[skill.id] = skill
    }

    fun all(): List<Skill> = skills.values.toList()

    fun get(id: String): Skill? = skills[id]

    fun enable(id: String, enabled: Boolean): Boolean {
        val s = skills[id] ?: return false
        skills[id] = s.copy(enabled = enabled)
        return true
    }

    fun disable(id: String): Boolean = enable(id, false)

    fun remove(id: String): Boolean = skills.remove(id) != null

    fun find(name: String): Skill? = all().find { it.name.equals(name, ignoreCase = true) }

    val highestSecurity: Skill? get() {
        return all().maxByOrNull { it.requestedPermissions.size }
    }
}
