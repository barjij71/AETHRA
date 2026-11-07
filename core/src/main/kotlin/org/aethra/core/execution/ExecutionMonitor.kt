package org.aethra.core.execution

import org.aethra.core.domain.TaskState
import java.time.Instant

/**
 * Execution monitor domain foundation.
 *
 * This module contains pure domain types and contract definitions for
 * monitoring the execution of tasks, workflows, skills and tools.
 *
 * Design principles:
 *  - NO UI dependency: these types are consumed by Android Compose, CLI,
 *    or any other UI later. The core never references Compose.
 *  - NO provider dependency: the monitor observes state, it does not create
 *    AI requests.
 *  - Event-driven: state changes are published via [TaskEvent] and
 *    [ExecutionEvent]. The monitor reads from the event stream, not from polling.
 *  - Resumable: execution state is stored/recoverable from the state store,
 *    not kept only in memory.
 *  - No invented data: fields that the system cannot know are represented as
 *    [UnknownExecutionValue], never as a fabricated number or token count.
 */

/** High-level execution lifecycle states. Used as the outer envelope of any execution. */
enum class ExecutionState {
    /** Nothing to execute. */
    IDLE,

    /** Waiting to be picked up. */
    QUEUED,

    /** Actively running. */
    RUNNING,

    /** Waiting for an approval decision. */
    WAITING_APPROVAL,

    /** Waiting for a dependency to finish. */
    WAITING_DEPENDENCY,

    /** Retrying after a failure. */
    RETRYING,

    /** Finished successfully. */
    COMPLETED,

    /** Failed. */
    FAILED,

    /** Cancelled by user or system. */
    CANCELLED,

    /** Unknown/unavailable result. */
    UNKNOWN,

    /** Paused by user. */
    PAUSED,

    /** Paused but can be resumed. */
    RESUMABLE,
}

/** Unknown execution value placeholder. Used when the system cannot determine the value. */
sealed class ExecutionValue<out T> {
    data class Known<T>(val value: T) : ExecutionValue<T>()
    object Unknown : ExecutionValue<Nothing>()
}

/** A string that is known but unavailable. */
data class UnavailableString(val detail: String) : ExecutionValue<String>()

/** A number that is known but unavailable. */
data class UnavailableNumber<T>(val detail: String) : ExecutionValue<T>()

/** Execution state object shared by tasks, workflows and skills. */
abstract class ExecutionSummary(
    val id: String,
    val type: ExecutionType,
    var state: ExecutionState,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** Start time if observed (some states may not have one). */
    var startTime: Long = createdAt
    /** End time if observed. */
    var endTime: Long = -1L
    /** Retry count. */
    var retryCount: Int = 0
    /** Error message if failed/failed to start. */
    var error: String? = null
    /** Current step id, if multi-step execution. */
    var currentStep: String? = null
    /** Step status, if multi-step execution. */
    var stepStatus: Map<String, ExecutionState> = emptyMap()
    /** Cancellation state. */
    var isCancelled: Boolean = false
    /** Pause/resume capability. */
    var isResumable: Boolean = false

    /** Mark this execution as starting. */
    fun start() {
        startTime = System.currentTimeMillis()
        if (startTime == 0L) startTime = Instant.now().toEpochMilli()
    }

    /** Mark this execution as ending (success or failure). */
    fun end() {
        endTime = System.currentTimeMillis()
    }

    /** Record an error. */
    fun errorOccurred(msg: String) {
        error = msg
        state = ExecutionState.FAILED
        end()
    }

    /** Mark as cancelled. */
    fun cancel() {
        isCancelled = true
        state = ExecutionState.CANCELLED
        end()
    }

    /** Mark as paused (resumable). */
    fun pause() {
        state = ExecutionState.PAUSED
        isResumable = true
    }

    /** Mark as resumed (from PAUSED). */
    fun resume() {
        state = ExecutionState.RUNNING
        isResumable = false
    }

    /** Advance progress (0.0 to 1.0) where available. */
    var progress: Float = 0f
        private set
}

/** Abstract execution type. */
enum class ExecutionType {
    TASK,
    WORKFLOW,
    SKILL,
    TOOL,
    AGENT,
    PROVIDER,
    MODEL,
    UNKNOWN
}

/** Execution event published by the monitor. */
sealed class ExecutionEvent {
    abstract val source: String
    abstract val timestamp: Long
    abstract val executionId: String
    abstract val executionType: ExecutionType

    data class Started(
        override val source: String,
        override val timestamp: Long,
        override val executionId: String,
        override val executionType: ExecutionType,
        val details: Map<String, String> = emptyMap()
    ) : ExecutionEvent()

    data class Failed(
        override val source: String,
        override val timestamp: Long,
        override val executionId: String,
        override val executionType: ExecutionType,
        val error: String,
        val retryCount: Int
    ) : ExecutionEvent()

    data class Cancelled(
        override val source: String,
        override val timestamp: Long,
        override val executionId: String,
        override val executionType: ExecutionType,
        val by: String
    ) : ExecutionEvent()

    data class Completed(
        override val source: String,
        override val timestamp: Long,
        override val executionId: String,
        override val executionType: ExecutionType,
        val result: Map<String, String> = emptyMap()
    ) : ExecutionEvent()

    data class Updated(
        override val source: String,
        override val timestamp: Long,
        override val executionId: String,
        override val executionType: ExecutionType,
        var state: ExecutionState,
        val detail: Map<String, String> = emptyMap()
    ) : ExecutionEvent()

    data class Transition(
        override val source: String,
        override val timestamp: Long,
        override val executionId: String,
        override val executionType: ExecutionType,
        val from: ExecutionState,
        val to: ExecutionState
    ) : ExecutionEvent()
}
