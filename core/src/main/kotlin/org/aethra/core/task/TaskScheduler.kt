package org.aethra.core.task

import org.aethra.core.domain.TaskState

/** Dependency scheduling for tasks. A task that lists dependency ids is blocked
 *  until every dependency reaches a terminal-success state (COMPLETED).
 *  FAILED, CANCELLED, and ERROR dependencies do NOT make a dependent runnable.
 */
object TaskScheduler {

    /**
     * Return a stable topological ordering. Dependencies always precede their
     * dependents; unrelated tasks retain their original relative order.
     * Missing dependencies are left in place rather than silently discarded.
     */
    fun <T> schedule(tasks: List<T>, taskById: (String) -> Task?): List<T> {
        if (tasks.size < 2) return tasks.toList()

        val byId = tasks.mapNotNull { item ->
            val task = taskById(extractId(item))
            if (task != null) extractId(item) to item else null
        }.toMap()

        val indegree = byId.keys.associateWith { id ->
            taskById(id)?.dependsOn?.count { dep -> dep in byId } ?: 0
        }.toMutableMap()

        val dependents = byId.keys.associateWith { mutableListOf<String>() }
        for (id in byId.keys) {
            for (dep in taskById(id)?.dependsOn.orEmpty()) {
                if (dep in byId) dependents.getValue(dep).add(id)
            }
        }

        val ready = ArrayDeque<String>()
        for (item in tasks) {
            val id = extractId(item)
            if (id in indegree && indegree[id] == 0) ready.addLast(id)
        }

        val orderedIds = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            orderedIds += id
            for (child in dependents.getValue(id)) {
                val next = indegree.getValue(child) - 1
                indegree[child] = next
                if (next == 0) ready.addLast(child)
            }
        }

        // A cycle is invalid, but preserve the original order here so this
        // utility remains total even when called before validation.
        if (orderedIds.size != byId.size) return tasks.toList()

        val ordered = orderedIds.map { byId.getValue(it) }.toMutableList()
        val knownIds = byId.keys
        for (item in tasks) {
            val id = extractId(item)
            if (id !in knownIds) ordered += item
        }
        return ordered
    }

    private fun <T> extractId(item: T): String = when (item) {
        is Task -> item.id
        else -> taskIdFrom(item)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> taskIdFrom(item: T): String {
        val task = item as? Task ?: throw IllegalArgumentException(
            "TaskScheduler.schedule requires Task items"
        )
        return task.id
    }

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
