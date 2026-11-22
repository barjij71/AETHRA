package org.aethra.core.workflow

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aethra.core.storage.Storage

enum class ReviewRepairLoopState {
    PLANNING,
    EXECUTING,
    TESTING,
    REPAIRING,
    VERIFYING,
    SUCCEEDED,
    FAILED;

    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED
}

enum class ReviewRepairStage { PLAN, EXECUTE, TEST, REPAIR, VERIFY }
enum class ReviewRepairOutcome { SUCCESS, FAILURE }

data class ReviewRepairEvidence(
    val loopId: String,
    val cycleNumber: Int,
    val stage: ReviewRepairStage,
    val outcome: ReviewRepairOutcome,
    val summary: String,
    val taskId: String? = null,
    val agentId: String? = null,
    val recordedAt: Long
)

data class ReviewRepairLoop(
    val id: String,
    val workflowId: String? = null,
    val agentId: String? = null,
    val maxRepairCycles: Int,
    val repairCyclesUsed: Int = 0,
    val state: ReviewRepairLoopState = ReviewRepairLoopState.PLANNING,
    val evidence: List<ReviewRepairEvidence> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null
)

/**
 * Bounded state/evidence controller only. It never invokes a task, provider,
 * tool, or agent; callers execute through existing boundaries and report the
 * resulting stage outcome here.
 */
class ReviewRepairLoopController(
    storage: Storage,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val loops = LinkedHashMap<String, ReviewRepairLoop>()
    private val stateLock = Any()
    private val mutationMutex = Mutex()
    private val persistence = ReviewRepairLoopPersistence(storage)
    private var loaded = false

    suspend fun load() = mutationMutex.withLock {
        restoreLocked()
    }

    suspend fun create(
        loopId: String,
        maxRepairCycles: Int,
        workflowId: String? = null,
        agentId: String? = null
    ): ReviewRepairLoop = mutationMutex.withLock {
        ensureLoadedLocked()
        require(loopId.isNotBlank()) { "loopId must not be blank" }
        require(maxRepairCycles >= 0) { "maxRepairCycles must be non-negative" }
        check(synchronized(stateLock) { !loops.containsKey(loopId) }) {
            "Review/repair loop '$loopId' already exists"
        }
        val now = clock()
        val loop = ReviewRepairLoop(
            id = loopId,
            workflowId = workflowId,
            agentId = agentId,
            maxRepairCycles = maxRepairCycles,
            createdAt = now,
            updatedAt = now
        ).detachedCopy()
        persistThenReplace(synchronized(stateLock) {
            loops.values.map { it.detachedCopy() } + loop
        })
        loop.detachedCopy()
    }

    fun get(loopId: String): ReviewRepairLoop? = synchronized(stateLock) {
        loops[loopId]?.detachedCopy()
    }

    fun all(): List<ReviewRepairLoop> = synchronized(stateLock) {
        loops.values.sortedBy { it.id }.map { it.detachedCopy() }
    }

    suspend fun recordStageOutcome(
        loopId: String,
        stage: ReviewRepairStage,
        outcome: ReviewRepairOutcome,
        summary: String,
        taskId: String? = null,
        agentId: String? = null
    ): ReviewRepairLoop = mutationMutex.withLock {
        ensureLoadedLocked()
        require(summary.isNotBlank()) { "Stage outcome summary must not be blank" }
        val current = synchronized(stateLock) { loops[loopId]?.detachedCopy() }
            ?: throw NoSuchElementException("No review/repair loop with id '$loopId'")
        check(!current.state.isTerminal) { "Loop '$loopId' is already terminal" }
        val expectedStage = current.state.stage()
        check(stage == expectedStage) {
            "Loop '$loopId' expects $expectedStage while in ${current.state}, not $stage"
        }

        val now = clock()
        val evidence = ReviewRepairEvidence(
            loopId = current.id,
            cycleNumber = current.repairCyclesUsed,
            stage = stage,
            outcome = outcome,
            summary = summary,
            taskId = taskId,
            agentId = agentId ?: current.agentId,
            recordedAt = now
        )
        val transition = current.transition(outcome)
        val updated = current.copy(
            repairCyclesUsed = transition.repairCyclesUsed,
            state = transition.state,
            evidence = current.evidence + evidence,
            updatedAt = now,
            completedAt = if (transition.state.isTerminal) now else null
        ).detachedCopy()
        persistThenReplace(synchronized(stateLock) {
            loops.values.filterNot { it.id == updated.id }.map { it.detachedCopy() } + updated
        })
        updated.detachedCopy()
    }

    private fun ReviewRepairLoop.transition(outcome: ReviewRepairOutcome): Transition {
        val (nextState, nextRepairCycles) = replayTransition(
            state,
            outcome,
            repairCyclesUsed,
            maxRepairCycles
        )
        return Transition(nextState, nextRepairCycles)
    }

    private fun ReviewRepairLoopState.stage(): ReviewRepairStage = when (this) {
        ReviewRepairLoopState.PLANNING -> ReviewRepairStage.PLAN
        ReviewRepairLoopState.EXECUTING -> ReviewRepairStage.EXECUTE
        ReviewRepairLoopState.TESTING -> ReviewRepairStage.TEST
        ReviewRepairLoopState.REPAIRING -> ReviewRepairStage.REPAIR
        ReviewRepairLoopState.VERIFYING -> ReviewRepairStage.VERIFY
        ReviewRepairLoopState.SUCCEEDED,
        ReviewRepairLoopState.FAILED -> error("Terminal state has no active stage")
    }

    private data class Transition(
        val state: ReviewRepairLoopState,
        val repairCyclesUsed: Int
    )

    private suspend fun persistThenReplace(candidate: List<ReviewRepairLoop>) {
        val stable = candidate.map { it.detachedCopy() }.sortedBy { it.id }
        persistence.saveAll(stable)
        synchronized(stateLock) {
            loops.clear()
            stable.forEach { loops[it.id] = it.detachedCopy() }
        }
    }

    private suspend fun ensureLoadedLocked() {
        if (!loaded) restoreLocked()
    }

    private suspend fun restoreLocked() {
        val restored = persistence.loadAll().map { it.detachedCopy() }.sortedBy { it.id }
        synchronized(stateLock) {
            loops.clear()
            restored.forEach { loops[it.id] = it.detachedCopy() }
        }
        loaded = true
    }
}

internal fun ReviewRepairLoop.detachedCopy(): ReviewRepairLoop = copy(
    evidence = evidence.map { it.copy() }
)

/** Replay durable evidence to reject impossible or forged current states. */
internal fun ReviewRepairLoop.requireConsistentHistory(): ReviewRepairLoop {
    require(id.isNotBlank()) { "Loop ID must not be blank" }
    require(maxRepairCycles >= 0) { "Repair budget must be non-negative" }
    require(repairCyclesUsed in 0..maxRepairCycles) { "Repair usage exceeds budget" }
    require(updatedAt >= createdAt) { "Loop update time precedes creation" }

    var replayState = ReviewRepairLoopState.PLANNING
    var replayCycles = 0
    evidence.forEach { entry ->
        require(!replayState.isTerminal) { "Evidence continues after terminal state" }
        require(entry.loopId == id) { "Evidence loop ID mismatch" }
        require(entry.summary.isNotBlank()) { "Evidence summary must not be blank" }
        require(entry.cycleNumber == replayCycles) { "Evidence cycle does not match budget state" }
        require(entry.stage == replayState.replayStage()) { "Evidence stage does not match loop state" }
        val transition = replayTransition(replayState, entry.outcome, replayCycles, maxRepairCycles)
        replayState = transition.first
        replayCycles = transition.second
    }

    require(replayState == state) { "Persisted state is not justified by evidence" }
    require(replayCycles == repairCyclesUsed) { "Persisted repair usage is not justified by evidence" }
    if (state.isTerminal) require(completedAt != null) { "Terminal loop lacks completion time" }
    else require(completedAt == null) { "Active loop has a completion time" }
    return this
}

private fun ReviewRepairLoopState.replayStage(): ReviewRepairStage = when (this) {
    ReviewRepairLoopState.PLANNING -> ReviewRepairStage.PLAN
    ReviewRepairLoopState.EXECUTING -> ReviewRepairStage.EXECUTE
    ReviewRepairLoopState.TESTING -> ReviewRepairStage.TEST
    ReviewRepairLoopState.REPAIRING -> ReviewRepairStage.REPAIR
    ReviewRepairLoopState.VERIFYING -> ReviewRepairStage.VERIFY
    ReviewRepairLoopState.SUCCEEDED,
    ReviewRepairLoopState.FAILED -> error("Terminal state has no stage")
}

private fun replayTransition(
    state: ReviewRepairLoopState,
    outcome: ReviewRepairOutcome,
    repairCyclesUsed: Int,
    maxRepairCycles: Int
): Pair<ReviewRepairLoopState, Int> = when (state) {
    ReviewRepairLoopState.PLANNING ->
        if (outcome == ReviewRepairOutcome.SUCCESS) ReviewRepairLoopState.EXECUTING to repairCyclesUsed
        else ReviewRepairLoopState.FAILED to repairCyclesUsed
    ReviewRepairLoopState.EXECUTING ->
        if (outcome == ReviewRepairOutcome.SUCCESS) ReviewRepairLoopState.TESTING to repairCyclesUsed
        else ReviewRepairLoopState.FAILED to repairCyclesUsed
    ReviewRepairLoopState.TESTING ->
        if (outcome == ReviewRepairOutcome.SUCCESS) ReviewRepairLoopState.VERIFYING to repairCyclesUsed
        else if (repairCyclesUsed < maxRepairCycles) ReviewRepairLoopState.REPAIRING to repairCyclesUsed + 1
        else ReviewRepairLoopState.FAILED to repairCyclesUsed
    ReviewRepairLoopState.REPAIRING ->
        if (outcome == ReviewRepairOutcome.SUCCESS) ReviewRepairLoopState.EXECUTING to repairCyclesUsed
        else ReviewRepairLoopState.FAILED to repairCyclesUsed
    ReviewRepairLoopState.VERIFYING ->
        if (outcome == ReviewRepairOutcome.SUCCESS) ReviewRepairLoopState.SUCCEEDED to repairCyclesUsed
        else if (repairCyclesUsed < maxRepairCycles) ReviewRepairLoopState.REPAIRING to repairCyclesUsed + 1
        else ReviewRepairLoopState.FAILED to repairCyclesUsed
    ReviewRepairLoopState.SUCCEEDED,
    ReviewRepairLoopState.FAILED -> error("Terminal state cannot transition")
}
