package org.aethra.assistant.aethra.skills

import org.aethra.core.domain.Skill
import org.aethra.core.skills.SkillInstaller
import org.aethra.core.skills.SkillRegistry

/**
 * Android-side adapter over the core [SkillRegistry].
 *
 * This class exposes the skill registry to the Android UI / runtime. The core
 * [SkillInstaller] and [SkillRegistry] are unchanged (no Android imports in core),
 * but this adapter is the exactly-one place the UI and runtime talk to the skill
 * subsystem. It implements the interface that the full Skill Manager will later
 * use (multi-source discovery, package manager, install/update/remove/rollback).
 *
 * In this phase only the registry surface is wired; the full management UX is
 * deferred to the next phase (Skill Manager), as requested.
 */
class SkillRegistryAndroid(private val skillRegistry: SkillRegistry) {
    /** Register a skill (declared by the core registry). */
    fun register(skill: Skill) {
        skillRegistry.register(skill)
    }

    /** All registered skills. */
    fun all(): List<Skill> = skillRegistry.all()

    /** Get a skill by id. */
    fun get(id: String): Skill? = skillRegistry.get(id)

    /** Enable / disable a skill. */
    fun enable(id: String, enabled: Boolean): Boolean = skillRegistry.enable(id, enabled)

    /** Disable a skill. */
    fun disable(id: String): Boolean = skillRegistry.disable(id)

    /** Remove (disable and drop) a skill. */
    fun remove(id: String): Boolean = skillRegistry.remove(id)

    /** Find a skill by name (case-insensitive). */
    fun find(name: String): Skill? = skillRegistry.find(name)

    /** Highest-security skill declared in the registry. */
    val highestSecurity: Skill?
        get() = skillRegistry.highestSecurity
}
