package org.aethra.core.task

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.Task
import org.aethra.core.domain.TaskState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A single unit of work owned by the AETHRA core. */
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
        get() = _executed.get()
        set(value) { _executed.set(value) }

    /**
     * Atomically claims the one-shot execution cycle. Returns true for the
     * caller that owns the cycle; all racing duplicates observe false. This
     * prevents duplicate runner execution when start/pause/resume race.
     */
    fun claimExecution(): Boolean = _executed.compareAndSet(false, true)

    private val _executed = AtomicBoolean(false)

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
        _executed.set(false)
        _completed = false
        _failed = false
        _cancelled = false
        note = ""
    }

    fun isCancelled(): Boolean = _cancelled
    fun isTerminal(): Boolean = state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)

    fun canTransitionTo(to: TaskState): Boolean = TaskStateMachine.canTransition(state, to)

    fun updateState(to: TaskState, detail: Map<String, String> = this.detail): Boolean {
        if (!canTransitionTo(to)) return false
        this.state = to
        this.updatedAt = System.currentTimeMillis()
        this.detail = detail
        return true
    }

    /**
     * Restore durable task state after a process restart.
     * In-flight execution cannot safely be resumed in-place, so transient execution
     * states are recovered to QUEUED and will be scheduled again by TaskEngine.
     */
    internal fun restore(
        persistedId: String,
        persistedCreatedAt: Long,
        persistedUpdatedAt: Long,
        persistedState: TaskState,
        persistedDetail: Map<String, String>,
        persistedNote: String,
        persistedRetryBudget: Int,
        persistedTimeoutMs: Long,
        persistedBackoffMs: Long,
        persistedExecuted: Boolean
    ) {
        id = persistedId
        createdAt = persistedCreatedAt
        updatedAt = persistedUpdatedAt
        detail = persistedDetail
        note = persistedNote
        retryBudget = persistedRetryBudget
        timeoutMs = persistedTimeoutMs
        backoffMs = persistedBackoffMs

        when (persistedState) {
            TaskState.RUNNING,
            TaskState.PREPARING,
            TaskState.APPROVED -> {
                state = TaskState.QUEUED
                _executed.set(false)
                _completed = false
                _failed = false
                _cancelled = false
            }
            else -> {
                state = persistedState
                _executed.set(persistedExecuted)
                _completed = persistedState == TaskState.COMPLETED
                _failed = persistedState == TaskState.FAILED
                _cancelled = persistedState == TaskState.CANCELLED
            }
        }
    }
}
