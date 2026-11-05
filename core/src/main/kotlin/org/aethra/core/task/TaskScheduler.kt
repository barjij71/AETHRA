package org.aethra.core.task

import org.aethra.core.domain.TaskState

/**
 * Dependency scheduling for tasks. A task that lists dependency ids is blocked
 * until every dependency reaches a terminal state (COMPLETED, FAILED, CANCELLED,
 * or ERROR). In this MVP the engine handles the resolution synchronously on
 * [TaskEngine.create]/[TaskEngine.run]; this file exists as the documented
 * extension point for a persistent scheduler in later phases.
 */
object TaskScheduler {
    @Suppress("UNCHECKED_CAST")
    fun <T> schedule(tasks: List<T>, taskById: (String) -> Task?): List<T> = tasks

    fun isReady(depIds: List<String>, taskById: (String) -> Task?): Boolean {
        for (id in depIds) {
            val t = taskById(id) ?: return false
            if (!t.isTerminal()) return false
        }
        return true
    }
}
