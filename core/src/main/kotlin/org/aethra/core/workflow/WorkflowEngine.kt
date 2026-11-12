package org.aethra.core.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val workflowPersistence = WorkflowPersistence(storage)
    private val persistenceMutex = Mutex()
    private val recoveryDeferred = CompletableDeferred<Unit>()

    init {
        recoverWorkflows()
    }

    internal suspend fun awaitRecovery() {
        recoveryDeferred.await()
    }

    suspend fun createWorkflow(name: String, nodes: List<WorkflowNode>): Workflow {
        awaitRecovery()

        validateWorkflowGraph(nodes)

        val workflow = Workflow(name = name, nodes = nodes)

        synchronized(lock) {
            workflows[workflow.id] = workflow
        }

        persistWorkflow(workflow)

        eventBus.publish(TaskEvent(
            "workflow_created", System.currentTimeMillis(), workflow.id,
            TaskState.QUEUED, TaskState.QUEUED,
            mapOf("name" to name, "nodeCount" to nodes.size.toString())
        ))

        return workflow
    }

    private fun validateWorkflowGraph(nodes: List<WorkflowNode>) {
        val nodeIds = nodes.map { it.id }.toSet()
        if (nodeIds.size != nodes.size) {
            throw IllegalArgumentException("Duplicate node IDs are not allowed in a workflow")
        }

        val taskIds = nodes.map { it.taskId }.toSet()
        if (taskIds.size != nodes.size) {
            throw IllegalArgumentException("Duplicate task IDs are not allowed in a workflow")
        }

        for (node in nodes) {
            if (node.dependsOn.contains(node.id)) {
                throw IllegalArgumentException("Workflow node '${node.id}' cannot depend on itself")
            }
            for (depId in node.dependsOn) {
                require(depId in nodeIds) {
                    "Workflow node '${node.id}' depends on unknown node '$depId'"
                }
            }
        }

        validateNoCycles(nodes)
    }

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

    suspend fun execute(workflowId: String): Boolean {
        awaitRecovery()
        var workflow: Workflow? = null
        val nodeToTaskId = mutableMapOf<String, String>()
        var orderedNodes: List<WorkflowNode> = emptyList()
        synchronized(lock) {
            workflow = workflows[workflowId] ?: return false
            if (workflow.state != TaskState.QUEUED) return false
            orderedNodes = topologicalSort(workflow.nodes)
        }

        // Durable execution intent before any task creation.
        // Persist as QUEUED with executionAttemptId so recovery knows
        // an execution was attempted even if tasks are not yet created.
        val executionAttemptId = "exec-$workflowId"
        persistWorkflow(workflow!!, executionAttemptId, emptyMap())

        // Create tasks outside the lock. Canonical IDs are derived from
        // workflowId + nodeId so they can be reconstructed after crash.
        for (node in orderedNodes) {
            val canonicalId = "$workflowId#${node.id}"
            val depTaskIds = node.dependsOn.mapNotNull { depId ->
                nodeToTaskId[depId]
            }
            val task = taskEngine.create(
                goal = node.goal,
                role = node.role,
                riskClass = node.riskClass,
                dependsOn = depTaskIds,
                taskId = canonicalId
            )
            nodeToTaskId[node.id] = task.id
        }

        // Record RUNNING state and task mapping durably.
        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)

        eventBus.publish(TaskEvent(
            "workflow_started", System.currentTimeMillis(), workflowId,
            TaskState.QUEUED, TaskState.RUNNING
        ))

        for (node in workflowToPersist.nodes) {
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

    suspend fun cancel(workflowId: String): Boolean {
        awaitRecovery()

        synchronized(lock) {
            val workflow = workflows[workflowId] ?: return false
            if (workflow.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) return false
            workflow.state = TaskState.CANCELLED
        }

        val taskIdMap = synchronized(lock) { workflowTaskIds[workflowId] }
        val cancelWorkflow = synchronized(lock) { workflows[workflowId] }
        if (taskIdMap != null && cancelWorkflow != null) {
            for (node in cancelWorkflow.nodes) {
                val taskId = taskIdMap[node.id]
                if (taskId != null) {
                    taskEngine.cancel(taskId)
                }
            }
        }

        val cancelledWorkflow = synchronized(lock) { workflows[workflowId] }
        if (cancelledWorkflow != null) {
            persistWorkflow(cancelledWorkflow)
        }

        eventBus.publish(TaskEvent(
            "workflow_cancelled", System.currentTimeMillis(), workflowId,
            TaskState.RUNNING, TaskState.CANCELLED
        ))

        return true
    }

    private suspend fun monitorWorkflow(workflowId: String) {
        while (true) {
            delay(200)
            val workflowToPersist: Workflow? = synchronized(lock) {
                val workflow = workflows[workflowId] ?: return
                if (workflow.state != TaskState.RUNNING) return
                val nodes = workflow.nodes
                val taskIdMap = workflowTaskIds[workflowId]
                if (taskIdMap == null) return

                val allTerminal = nodes.all { node ->
                    val taskId = taskIdMap[node.id]
                    val task = taskId?.let { taskEngine.getTask(it) }
                    task?.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)
                }

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

                if (!allTerminal && !hasFailedDependency) {
                    null  // Not terminal yet — keep monitoring on next iteration
                } else {
                    val hasFailed = nodes.any { node ->
                        val taskId = taskIdMap[node.id]
                        val task = taskId?.let { taskEngine.getTask(it) }
                        task?.state == TaskState.FAILED || task?.state == TaskState.ERROR
                    }
                    val newState = if (hasFailed || hasFailedDependency) TaskState.FAILED else TaskState.COMPLETED
                    workflow.state = newState
                    workflow.completedAt = System.currentTimeMillis()

                    workflow
                }
            }

            if (workflowToPersist == null) continue

            persistWorkflow(workflowToPersist)

            eventBus.publish(TaskEvent(
                "workflow_${workflowToPersist.state.name.lowercase()}",
                System.currentTimeMillis(),
                workflowId,
                TaskState.RUNNING,
                workflowToPersist.state,
                mapOf("nodeCount" to workflowToPersist.nodes.size.toString())
            ))
            return
        }
    }

    private fun recoverWorkflows() {
        scope.launch {
            try {
                // Ensure TaskEngine has finished loading persisted tasks before reconciling.
                taskEngine.awaitRestored()

                val persistedWorkflows = workflowPersistence.loadAll()

                if (persistedWorkflows.isEmpty()) {
                    recoveryDeferred.complete(Unit)
                    return@launch
                }

                for (persisted in persistedWorkflows) {
                    val workflow = reconcileWorkflow(persisted)
                    if (workflow == null) continue

                    // Build resolved task ID map using canonical IDs for recovery tracking.
                    val resolvedTaskIdMap = mutableMapOf<String, String>()
                    for (node in persisted.nodes) {
                        val canonicalId = "${persisted.id}#${node.id}"
                        resolvedTaskIdMap[node.id] = node.actualTaskId ?: canonicalId
                    }

                    synchronized(lock) {
                        if (workflows.containsKey(workflow.id)) continue
                        workflows[workflow.id] = workflow
                        workflowTaskIds[workflow.id] = resolvedTaskIdMap.toMap()
                    }

                    // Persist recovered workflow with resolved mapping
                    persistWorkflow(workflow, persisted.executionAttemptId, resolvedTaskIdMap.toMap())

                    if (workflow.state == TaskState.RUNNING) {
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }
                }
                recoveryDeferred.complete(Unit)
            } catch (e: Exception) {
                recoveryDeferred.completeExceptionally(e)
            }
        }
    }

    private fun reconcileWorkflow(persisted: PersistedWorkflow): Workflow? {
        val nodes = persisted.nodes.map { node ->
            WorkflowNode(
                id = node.id,
                taskId = node.taskId,
                goal = node.goal,
                role = node.role,
                riskClass = RiskClass.valueOf(node.riskClass),
                dependsOn = node.dependsOn,
                state = TaskState.valueOf(node.state)
            )
        }

        validateWorkflowGraph(nodes)

        // Process nodes in topological order so dependencies exist before dependents.
        val orderedNodes = topologicalSort(nodes)
        val persistedNodeMap = persisted.nodes.associate { it.id to it }

        // Resolve/create canonical Task IDs for every node.
        val resolvedTaskIds = mutableMapOf<String, String>()
        for (node in orderedNodes) {
            val persistedNode = persistedNodeMap[node.id]!!
            val canonicalId = "${persisted.id}#${node.id}"
            val resolvedId: String

            if (persistedNode.actualTaskId != null) {
                val existingTask = taskEngine.getTask(persistedNode.actualTaskId)
                if (existingTask != null) {
                    // Case: actualTaskId exists and referenced Task exists
                    resolvedId = persistedNode.actualTaskId
                } else {
                    // Case: actualTaskId exists but referenced Task is missing
                    // Try canonical ID as fallback
                    val canonicalTask = taskEngine.getTask(canonicalId)
                    if (canonicalTask != null) {
                        // Case: mapping conflict — persisted ID differs from canonical
                        resolvedId = canonicalId
                    } else {
                        // Referenced task truly missing — create idempotently with canonical ID
                        val createdTask = taskEngine.create(
                            taskId = canonicalId,
                            goal = node.goal,
                            role = node.role,
                            riskClass = node.riskClass,
                            dependsOn = node.dependsOn.mapNotNull { depId ->
                                resolvedTaskIds[depId]
                            }
                        )
                        resolvedId = createdTask.id
                    }
                }
            } else {
                // Case: actualTaskId is null — look up canonical ID in TaskEngine
                val canonicalTask = taskEngine.getTask(canonicalId)
                if (canonicalTask != null) {
                    // Case: actualTaskId null but canonical Task exists — adopt it
                    resolvedId = canonicalId
                } else {
                    // Case: actualTaskId null and canonical Task does not exist
                    // Create idempotently — will be created during execute() if not now
                    val createdTask = taskEngine.create(
                        taskId = canonicalId,
                        goal = node.goal,
                        role = node.role,
                        riskClass = node.riskClass,
                        dependsOn = node.dependsOn.mapNotNull { depId ->
                            resolvedTaskIds[depId]
                        }
                    )
                    resolvedId = createdTask.id
                }
            }
            resolvedTaskIds[node.id] = resolvedId
        }

        val workflow = Workflow(
            id = persisted.id,
            name = persisted.name,
            nodes = nodes,
            state = TaskState.valueOf(persisted.state),
            createdAt = persisted.createdAt,
            completedAt = persisted.completedAt
        )

        when (workflow.state) {
            TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR -> {
                // Terminal — no changes needed
            }

            TaskState.QUEUED -> {
                // Not started — no changes needed
            }

            TaskState.RUNNING -> {
                val allTerminal = orderedNodes.all { node ->
                    val taskId = resolvedTaskIds.getValue(node.id)
                    val task = taskEngine.getTask(taskId)
                    task?.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)
                }

                val hasFailed = orderedNodes.any { node ->
                    val taskId = resolvedTaskIds.getValue(node.id)
                    val task = taskEngine.getTask(taskId)
                    task?.state == TaskState.FAILED || task?.state == TaskState.ERROR
                }

                val hasFailedDependency = orderedNodes.any { node ->
                    val taskId = resolvedTaskIds.getValue(node.id)
                    val task = taskEngine.getTask(taskId)
                    if (task == null || task.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) false
                    else {
                        node.dependsOn.any { depNodeId ->
                            val depTaskId = resolvedTaskIds.getValue(depNodeId)
                            val depTask = depTaskId?.let { taskEngine.getTask(it) }
                            depTask?.state == TaskState.FAILED || depTask?.state == TaskState.ERROR
                        }
                    }
                }

                if (allTerminal || hasFailedDependency) {
                    workflow.state = if (hasFailed || hasFailedDependency) TaskState.FAILED else TaskState.COMPLETED
                    workflow.completedAt = System.currentTimeMillis()
                }
            }

            else -> {
            }
        }

        return workflow
    }

    private fun toPersisted(
        workflow: Workflow,
        executionAttemptId: String? = null,
        taskIdMap: Map<String, String> = emptyMap()
    ): PersistedWorkflow {
        return PersistedWorkflow(
            id = workflow.id,
            name = workflow.name,
            state = workflow.state.name,
            createdAt = workflow.createdAt,
            completedAt = workflow.completedAt,
            executionAttemptId = executionAttemptId,
            nodes = workflow.nodes.map { node ->
                PersistedWorkflowNode(
                    id = node.id,
                    taskId = node.taskId,
                    actualTaskId = taskIdMap[node.id],
                    goal = node.goal,
                    role = node.role,
                    riskClass = node.riskClass.name,
                    dependsOn = node.dependsOn,
                    state = node.state.name
                )
            }
        )
    }

    private suspend fun persistWorkflow(
        workflow: Workflow,
        executionAttemptId: String? = null,
        taskIdMap: Map<String, String> = emptyMap()
    ) = persistenceMutex.withLock {
        val persisted = synchronized(lock) {
            toPersisted(workflow, executionAttemptId, taskIdMap)
        }
        val existing = workflowPersistence.loadAll()
        val updated = existing.filter { it.id != workflow.id } + persisted
        workflowPersistence.saveAll(updated)
    }

}
