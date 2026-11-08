package org.aethra.core.task

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.Task
import org.aethra.core.domain.TaskState
import java.util.UUID

/**
 * Guarantees legal transitions between task states. A task must follow this
 * machine; callers that request an illegal transition receive false.
 */
object TaskStateMachine {
    private val allowed = mapOf(
        TaskState.QUEUED to setOf(TaskState.PREPARING, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR),
        TaskState.PREPARING to setOf(TaskState.WAITING_DEPENDENCY, TaskState.APPROVED, TaskState.WAITING_APPROVAL, TaskState.CANCELLED),
        // WAITING_DEPENDENCY: blocked until dependencies complete.
        // On failure/cancellation/error of a dependency, the dependent is
        // propagated accordingly (cascading failure policy), not allowed to run.
        TaskState.WAITING_DEPENDENCY to setOf(TaskState.APPROVED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR),
        TaskState.APPROVED to setOf(TaskState.RUNNING, TaskState.CANCELLED),
        TaskState.RUNNING to setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR, TaskState.PAUSED),
        TaskState.PAUSED to setOf(TaskState.RUNNING, TaskState.CANCELLED),
        TaskState.WAITING_APPROVAL to setOf(TaskState.APPROVED, TaskState.CANCELLED),
        TaskState.FAILED to setOf(TaskState.RUNNING, TaskState.COMPLETED),
        TaskState.COMPLETED to emptySet(),
        TaskState.CANCELLED to emptySet(),
        TaskState.ERROR to emptySet()
    )

    fun canTransition(from: TaskState, to: TaskState): Boolean = allowed[from]?.contains(to) == true

    fun next(from: TaskState, to: TaskState): TaskState = if (canTransition(from, to)) to else from
}
