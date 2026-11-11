package org.aethra.core.workflow

import kotlinx.coroutines.*
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.domain.TaskEvent
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner

class WorkflowEngine(
    private val storage: org.aethra.core.storage.Storage,
    private val eventBus: EventBus,
    private val taskEngine: TaskEngine,
    private val taskRunner: org.aethra.core.task.TaskRunner
) {
    private val workflows = HashMap<String, Workflow>()
    private val workflowTaskIds = HashMap<String, Map<String, String>>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Create a new workflow from a list of [WorkflowNode] definitions.
     *
     * Validation performed (all throw [IllegalArgumentException]):
     *   - duplicate node IDs are rejected
     *   - duplicate task IDs are rejected
     *   - self-dependencies are rejected
     *   - missing dependency references are rejected
     *   - direct and indirect dependency cycles are rejected
     *
     * Disconnected DAG components are allowed; they are not considered cycles.
     */
    fun createWorkflow(name: String, nodes: List<WorkflowNode>): Workflow {
        // Validate unique node IDs
        val nodeIds = nodes.map { it.id }.toSet()
        if (nodeIds.size != nodes.size) {
            throw IllegalArgumentException("Duplicate node IDs are not allowed in a workflow")
        }

        // Validate unique task IDs
        val taskIds = nodes.map { it.taskId }.toSet()
        if (taskIds.size != nodes.size) {
            throw IllegalArgumentException("Duplicate task IDs are not allowed in a workflow")
        }

        // Validate each node's dependencies
        for (node in nodes) {
            // Self-dependency
            if (node.dependsOn.contains(node.id)) {
                throw IllegalArgumentException("Workflow node '${node.id}' cannot depend on itself")
            }
            // Missing dependency reference
            for (depId in node.dependsOn) {
                require(depId in nodeIds) {
                    "Workflow node '${node.id}' depends on unknown node '$depId'"
                }
            }
        }

        // Detect direct and indirect cycles
        validateNoCycles(nodes)

        val workflow = Workflow(name = name, nodes = nodes)

        synchronized(lock) {
            workflows[workflow.id] = workflow
        }

        eventBus.publish(TaskEvent(
            "workflow_created", System.currentTimeMillis(), workflow.id,
            TaskState.QUEUED, TaskState.QUEUED,
            mapOf("name" to name, "nodeCount" to nodes.size.toString())
        ))

        return workflow
    }

    /**
     * Detect direct and indirect dependency cycles using DFS.
     * Throws [IllegalArgumentException] with the cycle path if one is found.
     */
    private fun validateNoCycles(nodes: List<WorkflowNode>) {
        val nodeMap = nodes.associate { it.id to it }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(id: String, path: MutableList<String>) {
            if (id in visiting) {
                val cycleStart = path.indexOf(id)
                val cycle = path.subList(cycleStart, path.size) + id
                throw IllegalArgumentException(
                    "Workflow contains a dependency cycle: ${cycle.joinToString(" -> ")}"
                )
            }
            if (id in visited) return

            visiting.add(id)
            path.add(id)
            val node = nodeMap[id] ?: return
            for (depId in node.dependsOn) {
                dfs(depId, path)
            }
            path.removeAt(path.lastIndex)
            visiting.remove(id)
            visited.add(id)
        }

        for (node in nodes) {
            dfs(node.id, mutableListOf())
        }
    }

    /**
     * Return nodes in topological dependency order (dependencies before dependents).
     * Unrelated nodes retain their original relative order.
     * Assumes the graph has already been validated as acyclic by [validateNoCycles].
     */
    private fun topologicalSort(nodes: List<WorkflowNode>): List<WorkflowNode> {
        val nodeMap = nodes.associate { it.id to it }
        val indegree = nodes.associate { it.id to nodeMap[it.id]!!.dependsOn.count { it in nodeMap } }.toMutableMap()
        val dependents = nodes.associate { it.id to mutableListOf<String>() }.toMutableMap()
        for (node in nodes) {
            for (depId in node.dependsOn) {
                if (depId in nodeMap) {
                    dependents.getValue(depId).add(node.id)
                }
            }
        }

        val ready = ArrayDeque<String>()
        for (node in nodes) {
            if (indegree.getValue(node.id) == 0) ready.addLast(node.id)
        }

        val ordered = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            ordered += id
            for (childId in dependents.getValue(id)) {
                val next = indegree.getValue(childId) - 1
                indegree[childId] = next
                if (next == 0) ready.addLast(childId)
            }
        }

        return ordered.mapNotNull { nodeMap[it] }
    }

    /**
     * Execute the workflow by creating tasks via [TaskEngine.create] and triggering
     * their execution via [TaskEngine.run].
     *
     * Tasks are created in topological dependency order so that every dependency's
     * task ID is resolved before any dependent task needs it. Declaration order of
     * nodes never affects dependency correctness.
     *
     * The RUNNING state transition and task-map population happen atomically under
     * [lock], so [cancel] can never observe RUNNING with an incomplete task map.
     *
     * @return true if the workflow was started, false if not found or already running.
     */
    fun execute(workflowId: String): Boolean {
        var workflow: Workflow? = null
        val nodeToTaskId = mutableMapOf<String, String>()
        synchronized(lock) {
            workflow = workflows[workflowId] ?: return false
            if (workflow.state != TaskState.QUEUED) return false
            workflow.state = TaskState.RUNNING

            // Create all tasks atomically with the RUNNING state transition.
            // Topological order guarantees each dependency's task ID exists
            // before any dependent node resolves its dependsOn.
            for (node in topologicalSort(workflow.nodes)) {
                val task = taskEngine.create(
                    goal = node.goal,
                    role = node.role,
                    riskClass = node.riskClass,
                    dependsOn = node.dependsOn.map { depId -> nodeToTaskId[depId] ?: "" }.filter { depId -> depId.isNotEmpty() }
                )
                nodeToTaskId[node.id] = task.id
            }
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        eventBus.publish(TaskEvent(
            "workflow_started", System.currentTimeMillis(), workflowId,
            TaskState.QUEUED, TaskState.RUNNING
        ))

        // Run tasks outside the lock. Skip any task already cancelled by a
        // concurrent cancel() call (see cancellation-race invariant).
        for (node in workflow!!.nodes) {
            val taskId = nodeToTaskId[node.id]!!
            val task = taskEngine.getTask(taskId)
            if (task?.state == TaskState.CANCELLED) continue
            taskEngine.run(taskId)
        }

        scope.launch {
            monitorWorkflow(workflowId)
        }

        return true
    }

    fun getWorkflow(workflowId: String): Workflow? {
        synchronized(lock) {
            return workflows[workflowId]
        }
    }

    /**
     * Cancel a workflow. Once a workflow is externally observable as RUNNING,
     * cancellation cannot miss any task that WorkflowEngine is responsible for
     * executing, because the RUNNING transition and task-map population are
     * atomic under [lock].
     *
     * Cancellation is idempotent: cancelling an already-terminal workflow
     * returns false and has no effect.
     *
     * @return true if the workflow was cancelled, false if not found or already terminal.
     */
    fun cancel(workflowId: String): Boolean {
        synchronized(lock) {
            val workflow = workflows[workflowId] ?: return false
            if (workflow.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) return false
            workflow.state = TaskState.CANCELLED
        }

        val taskIdMap = synchronized(lock) { workflowTaskIds[workflowId] }
        if (taskIdMap != null) {
            for (node in workflows[workflowId]!!.nodes) {
                val taskId = taskIdMap[node.id]
                if (taskId != null) {
                    taskEngine.cancel(taskId)
                }
            }
        }

        eventBus.publish(TaskEvent(
            "workflow_cancelled", System.currentTimeMillis(), workflowId,
            TaskState.RUNNING, TaskState.CANCELLED
        ))

        return true
    }

    /**
     * Monitor a workflow until all tasks reach a terminal state, then update
     * the workflow state to COMPLETED or FAILED accordingly.
     *
     * A cancelled workflow never transitions to COMPLETED or FAILED: the
     * monitor returns early when workflow.state is no longer RUNNING.
     */
    private suspend fun monitorWorkflow(workflowId: String) {
        while (true) {
            delay(200)
            synchronized(lock) {
                val workflow = workflows[workflowId] ?: return
                if (workflow.state != TaskState.RUNNING) return
                val nodes = workflow.nodes
                val taskIdMap = workflowTaskIds[workflowId]
                if (taskIdMap == null) return

                // Check terminal states for all tasks
                val allTerminal = nodes.all { node ->
                    val taskId = taskIdMap[node.id]
                    val task = taskId?.let { taskEngine.getTask(it) }
                    task?.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)
                }

                // Check if any non-terminal task has a failed dependency —
                // such a task can never complete, so the workflow fails.
                val hasFailedDependency = nodes.any { node ->
                    val taskId = taskIdMap[node.id]
                    val task = taskId?.let { taskEngine.getTask(it) }
                    if (task == null || task.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) {
                        false
                    } else {
                        node.dependsOn.any { depNodeId ->
                            val depTaskId = taskIdMap[depNodeId]
                            val depTask = depTaskId?.let { taskEngine.getTask(it) }
                            depTask?.state == TaskState.FAILED || depTask?.state == TaskState.ERROR
                        }
                    }
                }

                if (allTerminal || hasFailedDependency) {
                    val hasFailed = nodes.any { node ->
                        val taskId = taskIdMap[node.id]
                        val task = taskId?.let { taskEngine.getTask(it) }
                        task?.state == TaskState.FAILED || task?.state == TaskState.ERROR
                    }
                    val newState = if (hasFailed || hasFailedDependency) TaskState.FAILED else TaskState.COMPLETED
                    workflow.state = newState
                    workflow.completedAt = System.currentTimeMillis()
                    eventBus.publish(TaskEvent(
                        "workflow_${newState.name.lowercase()}",
                        System.currentTimeMillis(),
                        workflowId,
                        TaskState.RUNNING,
                        newState,
                        mapOf("nodeCount" to nodes.size.toString())
                    ))
                    return
                }
            }
        }
    }
}
