package org.aethra.core.task

import org.aethra.core.domain.TaskState

/** Dependency scheduling for tasks. A task that lists dependency ids is blocked
 *  until every dependency reaches a terminal-success state (COMPLETED).
 *  FAILED, CANCELLED, and ERROR dependencies do NOT make a dependent runnable.
 */
object TaskScheduler {

    @Suppress("UNCHECKED_CAST")
    fun <T> schedule(tasks: List<T>, taskById: (String) -> Task?): List<T> = tasks

    /** true only when every dependency exists and is COMPLETED (terminal-success). */
    fun isReady(depIds: List<String>, taskById: (String) -> Task?): Boolean {
        for (id in depIds) {
            val t = taskById(id) ?: return false
            if (t.state != TaskState.COMPLETED) return false
        }
        return true
    }
}
