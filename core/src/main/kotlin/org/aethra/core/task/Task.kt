package org.aethra.core.task

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A single unit of work owned by the AETHRA core. */
class Task(
    goal: String,
    role: String = "assistant",
    riskClass: RiskClass = RiskClass.READ_ONLY,
    dependsOn: List<String> = emptyList(),
    var onCompleted: (Task) -> Unit = {},
    postconditions: List<Postcondition> = emptyList(),
    /**
     * Provenance: identifies who created this task.
     * Defaults to "core" for tasks created by TaskEngine.
     * Skills set this to their skill ID when delegating through SkillOrchestrator.
     */
    createdAtBy: String = "core"
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

    /**
     * Phase 9: provenance of the task creator (e.g. a Skill id).
     * Defaults to "core". Restored from the durable journal by [restore].
     */
    var createdAtBy: String = createdAtBy
        private set

    /**
     * Core-declared postconditions that must hold for this task's objective to
     * be verified. They are supplied by Core (the task creator), never by the
     * Provider, Runner, Agent, or Model.
     */
    var postconditions: List<Postcondition> = postconditions
        private set

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

    // Phase 6: Verification state (Core-owned verdict + evidence)
    var verificationOutcome: VerificationOutcome = VerificationOutcome.UNKNOWN
        private set
    var verificationEvidence: List<EvidenceItem> = emptyList()
        private set
    var verificationPostconditions: List<PostconditionResult> = emptyList()
        private set
    var isVerified: Boolean = false
        private set

    // Phase 3: Execution Identity
    // Task.id = stable logical task identity
    // executionAttemptId = unique identity of ONE execution attempt
    // attemptCounter = monotonically increasing execution-attempt generation
    // retriesUsed = retry state inside current execution lifecycle
    var executionAttemptId: String = UUID.randomUUID().toString()
        private set
    var attemptCounter: Int = 1
        private set
    var retriesUsed: Int = 0
        private set

    /**
     * Atomically claims the one-shot execution cycle. Returns true for the
     * caller that owns the cycle; all racing duplicates observe false. This
     * prevents duplicate runner execution when start/pause/resume race.
     */
    fun claimExecution(): Boolean = _executed.compareAndSet(false, true)

    private val _executed = AtomicBoolean(false)

    /** Record one TaskEngine retry within the current execution cycle. */
    internal fun recordRetry(): Int {
        retriesUsed++
        return retriesUsed
    }

    fun setTimeoutMs(ms: Long) {
        if (ms < 0) throw IllegalArgumentException("timeoutMs must be >= 0")
        timeoutMs = ms
    }

    private var _completed = false

    /**
     * Marks the task COMPLETED without a Core verification verdict.
     *
     * Retained for internal/test use only. The TaskEngine never calls this on
     * the execution path: it calls [completeWithVerification] so that every
     * task completion carries a Core-owned verification verdict.
     */
    fun complete() {
        if (_completed) return
        _completed = true
        state = TaskState.COMPLETED
    }

    /**
     * Records the Core-owned verification verdict and its evidence on this task.
     *
     * The verdict is stored before the terminal transition so that the durable
     * journal always carries the evidence that justified the task's outcome.
     */
    fun recordVerification(verification: TaskVerification) {
        verificationOutcome = verification.outcome
        verificationEvidence = verification.evidence
        verificationPostconditions = verification.postconditionResults
    }

    /**
     * Completes the task using a Core-owned verification verdict.
     *
     * Only a [VerificationOutcome.VERIFIED] verdict may complete a task.
     * A FAILED/UNKNOWN/UNVERIFIABLE verdict leaves the task in RUNNING so the
     * TaskEngine can retry or fail it explicitly.
     */
    fun completeWithVerification(verification: TaskVerification) {
        verificationOutcome = verification.outcome
        verificationEvidence = verification.evidence
        verificationPostconditions = verification.postconditionResults
        if (verification.outcome != VerificationOutcome.VERIFIED) return
        if (_completed) return
        _completed = true
        isVerified = true
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
        retriesUsed = 0
        clearVerification()
    }

    /** Start a new execution attempt (for process-death recovery). */
    fun startNewExecutionAttempt() {
        executionAttemptId = UUID.randomUUID().toString()
        attemptCounter++
        _executed.set(false)
        _completed = false
        _failed = false
        _cancelled = false
        note = ""
        retriesUsed = 0
        state = TaskState.QUEUED
        clearVerification()
    }

    private fun clearVerification() {
        verificationOutcome = VerificationOutcome.UNKNOWN
        verificationEvidence = emptyList()
        verificationPostconditions = emptyList()
        isVerified = false
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
        persistedExecuted: Boolean,
        persistedExecutionAttemptId: String? = null,
        persistedAttemptCounter: Int = 1,
        persistedRetriesUsed: Int = 0,
        // Phase 6: Verification state
        persistedVerificationOutcome: VerificationOutcome = VerificationOutcome.UNKNOWN,
        persistedVerificationEvidence: List<String> = emptyList(),
        persistedVerificationPostconditions: List<PersistedPostconditionResult> = emptyList(),
        persistedIsVerified: Boolean = false,
        persistedPostconditions: List<Postcondition> = emptyList(),
        /**
         * Phase 9: provenance restored from the durable journal.
         * Defaults to "core" so pre-Phase-9 journals restore without provenance.
         */
        persistedCreatedAtBy: String = "core"
    ) {
        id = persistedId
        createdAt = persistedCreatedAt
        updatedAt = persistedUpdatedAt
        detail = persistedDetail
        note = persistedNote
        retryBudget = persistedRetryBudget
        timeoutMs = persistedTimeoutMs
        backoffMs = persistedBackoffMs
        executionAttemptId = persistedExecutionAttemptId ?: UUID.randomUUID().toString()
        attemptCounter = persistedAttemptCounter
        retriesUsed = persistedRetriesUsed
        postconditions = persistedPostconditions
        // Phase 9: provenance survives restore/recovery
        createdAtBy = persistedCreatedAtBy
        verificationOutcome = persistedVerificationOutcome
        verificationEvidence = persistedVerificationEvidence.map(EvidenceItem::decode)
        verificationPostconditions = persistedVerificationPostconditions.map {
            PostconditionResult(
                postcondition = Postcondition(
                    name = it.name,
                    description = it.description,
                    evidenceType = it.evidenceType,
                    expectedValue = it.expectedValue
                ),
                satisfied = it.satisfied,
                evidenceMissing = it.evidenceMissing
            )
        }
        // Derive isVerified from verificationOutcome, not from journal (tamper-proofing)
        isVerified = verificationOutcome == VerificationOutcome.VERIFIED

        when (persistedState) {
            TaskState.RUNNING,
            TaskState.PREPARING,
            TaskState.APPROVED -> {
                // Process-death recovery: in-flight states become new execution attempt
                startNewExecutionAttempt()
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
