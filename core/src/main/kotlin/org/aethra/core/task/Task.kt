package org.aethra.core.task

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.Task
import org.aethra.core.domain.TaskState
import java.util.UUID

/**
 * A single unit of work owned by the AETHRA core.
 *
 * Guarantees:
 * - unique id
 * - explicit, auditable state transitions
 * - persistence of the current state
 * - cancellation and retry
 */
class Task(
    goal: String,
    role: String = "assistant",
    riskClass: RiskClass = RiskClass.READ_ONLY,
    dependsOn: List<String> = emptyList(),
    var onCompleted: (Task) -> Unit = {}
) {
    var id: String = UUID.randomUUID().toString()
        private set
    var goal: String = goal
        private set
    var role: String = role
        private set
    var riskClass: RiskClass = riskClass
        private set
    val dependsOn: List<String> = dependsOn
    var createdAt: Long = System.currentTimeMillis()
        private set
    var updatedAt: Long = createdAt
        private set
    var state: TaskState = TaskState.QUEUED
        private set
    var detail: Map<String, String> = emptyMap()
        private set
    var note: String = ""
        private set
    var retryBudget: Int = 2
    var timeoutMs: Long = 30_000
        private set
    var backoffMs: Long = 1000
        private set
    var isExecuted: Boolean
        get() = _executed
        set(value) { _executed = value }
    private var _executed = false
    fun setTimeoutMs(ms: Long) {
        if (ms < 0) throw IllegalArgumentException("timeoutMs must be >= 0")
        timeoutMs = ms
    }
    private var _completed = false
    fun complete() {
        if (_completed) return
        _completed = true
        state = TaskState.COMPLETED
    }

    private var _failed = false
    fun fail(reason: String) {
        if (_failed) return
        _failed = true
        state = TaskState.FAILED
        note = reason
    }

    private var _cancelled = false
    fun cancel() {
        if (_cancelled) return
        _cancelled = true
        if (state != TaskState.CANCELLED && state != TaskState.COMPLETED) {
            state = TaskState.CANCELLED
        }
    }

    /** Reset one-shot guards when an explicit retry starts a new execution cycle. */
    fun resetForRetry() {
        _executed = false
        _completed = false
        _failed = false
        _cancelled = false
        note = ""
    }

    fun isCancelled(): Boolean = _cancelled
    fun isTerminal(): Boolean = state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)

    /** Check whether the transition requested by [to] is allowed from [current]. */
    fun canTransitionTo(to: TaskState): Boolean = TaskStateMachine.canTransition(state, to)

    /**
     * Update this task's state and record the facts.
     * Returns true if the transition was applied; false if it was rejected as illegal.
     */
    fun updateState(to: TaskState, detail: Map<String, String> = this.detail): Boolean {
        if (!canTransitionTo(to)) return false
        this.state = to
        this.updatedAt = System.currentTimeMillis()
        this.detail = detail
        return true
    }
}
