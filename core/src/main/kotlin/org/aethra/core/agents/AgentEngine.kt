package org.aethra.core.agents

import org.aethra.core.domain.Agent
import java.util.UUID

/**
 * Execution engine for agents. It coordinates what an agent may do and reports
 * status; it does not perform sensitive execution itself, because policy is
 * enforced by the PermissionBroker and the ToolRegistry. Execution bodies are
 * supplied by the platform layer or by tests.
 */
class AgentExecutionEngine {
    private val state = LinkedHashMap<String, AgentExecutionState>()

    fun begin(agent: Agent, scope: String): AgentExecutionState {
        val id = UUID.randomUUID().toString()
        val s = AgentExecutionState(id, agent.id, agent.name, scope, startedAt = System.currentTimeMillis())
        state[id] = s
        return s
    }

    fun advance(id: String, detail: Map<String, String> = emptyMap()) {
        val s = state[id] ?: return
        s.updatedAt = System.currentTimeMillis()
        s.detail = s.detail + detail
    }

    fun fail(id: String, reason: String) {
        val s = state[id] ?: return
        s.status = AgentExecutionStatus.FAILED
        s.detail = s.detail + mapOf("reason" to reason)
    }

    fun complete(id: String) {
        val s = state[id] ?: return
        s.status = AgentExecutionStatus.COMPLETED
    }

    fun cancel(id: String) {
        val s = state[id] ?: return
        s.status = AgentExecutionStatus.STOPPED
    }

    fun all(): List<AgentExecutionState> = state.values.toList()
}

data class AgentExecutionState(
    val id: String,
    val agentId: String,
    val agentName: String,
    val scope: String,

    var status: AgentExecutionStatus = AgentExecutionStatus.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),

    var updatedAt: Long = System.currentTimeMillis(),

    var detail: Map<String, String> = emptyMap()
)

enum class AgentExecutionStatus {
    RUNNING, COMPLETED, FAILED, STOPPED
}
