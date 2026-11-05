package org.aethra.core.agents

import org.aethra.core.domain.Agent
import java.util.UUID

/**
 * An agent is a named role with a profile (tools, memory scope, provider,
 * retry budget, status). The AgentEngine owns creation, editing, deletion and
 * status; it does not execute tools itself. Execution is delegated to the core
 * runtime so agents cannot grant themselves permissions.
 */

data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String,
    val description: String,
    val provider: String = "fake",
    val model: String = "auto",
    val enabled: Boolean = true,
    val tools: List<String> = emptyList(),
    val memoryScope: String = "global",
    val retryBudget: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val status: AgentStatus = AgentStatus.IDLE
)

enum class AgentStatus {
    CREATED, IDLE, EXECUTING, WAITING_APPROVAL, FAILED, STOPPED
}

/**
 * Manages the agent lifecycle and policy enforcement. New agents start with a
 * narrow role and only the tools the caller grants. Agents cannot escalate their
 * own permissions.
 */
class AgentEngine {
    private val agents = LinkedHashMap<String, Agent>()

    fun create(agent: Agent): Agent {
        val existing = agents[agent.id] ?: return agent
        val merged = existing.copy(
            name = agent.name, role = agent.role, description = agent.description,
            provider = agent.provider, model = agent.model, enabled = agent.enabled,
            tools = agent.tools, memoryScope = agent.memoryScope,
            retryBudget = agent.retryBudget
        )
        agents[agent.id] = merged
        return merged
    }

    fun all(): List<Agent> = agents.values.toList()

    fun get(id: String): Agent? = agents[id]

    fun update(agent: Agent): Agent {
        val existing = agents[agent.id] ?: return agent
        val updated = existing.copy(
            name = agent.name, role = agent.role, description = agent.description,
            provider = agent.provider, model = agent.model, enabled = agent.enabled,
            tools = agent.tools, memoryScope = agent.memoryScope,
            retryBudget = agent.retryBudget
        )
        agents[agent.id] = updated
        return updated
    }

    fun delete(id: String): Boolean = agents.remove(id) != null

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val a = agents[id] ?: return false
        agents[id] = a.copy(enabled = enabled)
        return true
    }
}
