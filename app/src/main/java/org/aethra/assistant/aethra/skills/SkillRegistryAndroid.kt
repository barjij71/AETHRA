package org.aethra.assistant.aethra.skills

import org.aethra.core.skills.Skill
import org.aethra.core.skills.SkillRegistry

class SkillRegistryAndroid(private val skillRegistry: SkillRegistry) {
    fun register(skill: Skill): Boolean = skillRegistry.register(skill)
    fun all(): List<Skill> = skillRegistry.all()
    fun get(id: String): Skill? = skillRegistry.get(id)
    fun enable(id: String, enabled: Boolean): Boolean = skillRegistry.setEnabled(id, enabled)
    fun disable(id: String): Boolean = skillRegistry.disable(id)
    fun remove(id: String): Boolean = skillRegistry.remove(id)
    fun find(name: String): Skill? = skillRegistry.find(name)

    val highestSecurity: Skill?
        get() = skillRegistry.all()
            .filter { it.enabled }
            .maxByOrNull { it.riskClass.level }
}
