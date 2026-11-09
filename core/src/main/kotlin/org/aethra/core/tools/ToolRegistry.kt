package org.aethra.core.tools

import org.aethra.core.domain.RiskClass
import org.aethra.core.skills.Skill

/**
 * Unified tool registry. Tools are discovered in priority order:
 * 1. Native Android tools (implemented in the platform layer).
 * 2. Installed skills that expose tools.
 * 3. External integrations (MCP, remote services) — registered later.
 *
 * Each tool declares input/output schemas, risk classification, approval
 * requirements, reversibility and timeout. The PermissionBroker consults this
 * registry before any tool runs on the user's behalf.
 *
 * Skills declare capabilities via [Skill.capabilities]. The registry maps
 * those capabilities to tools, so execution goes through:
 * Skill → ToolRegistry → ExecutionPolicy → PermissionBroker → AuditLog.
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    init {
        for (t in NativeTools().tools) {
            tools[t.id] = t
        }
    }

    /** Register a tool. Duplicate IDs override older entries. */
    fun register(tool: Tool) {
        tools[tool.id] = tool
    }

    /** Get a tool by ID. */
    fun get(id: String): Tool? = tools[id]

    /** Resolve an exact tool ID, explicit alias, or ID-prefixed command. */
    fun resolve(command: String): Tool? {
        val normalized = command.trim()
        if (normalized.isEmpty()) return null
        tools[normalized]?.let { return it }
        tools.values.firstOrNull { tool ->
            tool.aliases.any { alias -> alias.equals(normalized, ignoreCase = true) }
        }?.let { return it }
        return tools.values
            .sortedByDescending { it.id.length }
            .firstOrNull { normalized.startsWith(it.id + " ") }
    }

    /** List all tools. */
    fun all(): List<Tool> = tools.values.toList()

    /**
     * Find tools by risk class. Tools with level <= [risk] are returned.
     */
    fun byRisk(risk: RiskClass): List<Tool> = tools.values.filter { it.riskClass.level <= risk.level }.toList()

    /**
     * Find tools by required capability.
     * Matches against the tool's [Tool.requiredCapabilities].
     */
    fun findByCapability(capability: String): List<Tool> {
        val lower = capability.lowercase()
        return all().filter { it.requiredCapabilities.any { it.lowercase() == lower } }
    }

    /** Highest risk class among all registered tools. */
    val highestRisk: RiskClass get() = tools.values.map { it.riskClass }.maxByOrNull { it.level } ?: RiskClass.READ_ONLY

    /** Total number of registered tools. */
    val total: Int get() = tools.size

    /**
     * Return the tool IDs associated with the given skill.
     * Each skill declares capabilities; the registry maps capabilities to tools.
     */
    fun toolsForSkill(skill: org.aethra.core.skills.Skill): List<String> {
        val ids = mutableListOf<String>()
        for (cap in skill.capabilities) {
            for (t in all()) {
                if (t.requiredCapabilities.any { it.lowercase() == cap.lowercase() }) {
                    ids.add(t.id)
                }
            }
        }
        return ids.distinct()
    }
}
