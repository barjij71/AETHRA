package org.aethra.core.task

import org.aethra.core.domain.TaskState

/** Dependency scheduling for tasks. A task that lists dependency ids is blocked
 *  until every dependency reaches a terminal-success state (COMPLETED).
 *  FAILED, CANCELLED, and ERROR dependencies do NOT make a dependent runnable.
 */
object TaskScheduler {

    @Suppress("UNCHECKED_CAST")
    fun <T> schedule(tasks: List<T>, taskById: (String) -> Task?): List<T> = tasks

    /** Reject a candidate dependency edge set if it would introduce a cycle. */
    fun wouldCreateCycle(
        candidateId: String,
        candidateDependencies: List<String>,
        taskById: (String) -> Task?
    ): Boolean {
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(id: String): Boolean {
            if (id == candidateId) return true
            if (!visited.add(id)) return false
            if (!visiting.add(id)) return true
            val deps = taskById(id)?.dependsOn ?: return false
            for (dep in deps) {
                if (dfs(dep)) return true
            }
            visiting.remove(id)
            return false
        }

        for (dependency in candidateDependencies) {
            if (dependency == candidateId || dfs(dependency)) return true
        }
        return false
    }

    /** true only when every dependency exists and is COMPLETED (terminal-success). */
    fun isReady(depIds: List<String>, taskById: (String) -> Task?): Boolean {
        for (id in depIds) {
            val t = taskById(id) ?: return false
            if (t.state != TaskState.COMPLETED) return false
        }
        return true
    }
}
