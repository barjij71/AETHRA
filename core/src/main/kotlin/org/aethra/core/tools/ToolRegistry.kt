package org.aethra.core.tools

import org.aethra.core.domain.RiskClass

/**
 * Unified tool registry. Tools are discovered in priority order:
 * 1. Native Android tools (implemented in the platform layer).
 * 2. Installed skills that expose tools.
 * 3. External integrations (MCP, remote services) — registered later.
 *
 * Each tool declares input/output schemas, risk classification, approval
 * requirements, reversibility and timeout. The PermissionBroker consults this
 * registry before any tool runs on the user's behalf.
 */

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    init {
        for (t in NativeTools().tools) {
            tools[t.id] = t
        }
    }

    fun register(tool: Tool) { tools[tool.id] = tool }

    fun get(id: String): Tool? = tools[id]
    fun all(): List<Tool> = tools.values.toList()
    fun byRisk(risk: RiskClass): List<Tool> = tools.values.filter { it.riskClass.level <= risk.level }.toList()

    val highestRisk: RiskClass get() = tools.values.map { it.riskClass }.maxByOrNull { it.level } ?: RiskClass.READ_ONLY
    val total: Int get() = tools.size
}
