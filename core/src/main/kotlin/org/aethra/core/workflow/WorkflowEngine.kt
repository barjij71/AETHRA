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
        var workflow: Workflow? = null
        val nodeToTaskId = mutableMapOf<String, String>()
        synchronized(lock) {
            workflow = workflows[workflowId] ?: return false
            if (workflow.state != TaskState.QUEUED) return false
            workflow.state = TaskState.RUNNING

            for (node in topologicalSort(workflow.nodes)) {
                val task = taskEngine.create(
                    goal = node.goal,
                    role = node.role,
                    riskClass = node.riskClass,
                    dependsOn = node.dependsOn.mapNotNull { depId -> nodeToTaskId[depId] }
                )
                nodeToTaskId[node.id] = task.id
            }
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist)

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
        if (taskIdMap != null) {
            for (node in workflows[workflowId]!!.nodes) {
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
            val result = synchronized(lock) {
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
                    return
                }

                val hasFailed = nodes.any { node ->
                    val taskId = taskIdMap[node.id]
                    val task = taskId?.let { taskEngine.getTask(it) }
                    task?.state == TaskState.FAILED || task?.state == TaskState.ERROR
                }
                val newState = if (hasFailed || hasFailedDependency) TaskState.FAILED else TaskState.COMPLETED
                workflow.state = newState
                workflow.completedAt = System.currentTimeMillis()

                PersistData(workflow, toPersisted(workflow), newState)
            }

            if (result == null) continue

            persistWorkflow(result.workflow)

            eventBus.publish(TaskEvent(
                "workflow_${result.workflow.state.name.lowercase()}",
                System.currentTimeMillis(),
                workflowId,
                TaskState.RUNNING,
                result.newState,
                mapOf("nodeCount" to result.workflow.nodes.size.toString())
            ))
            return
        }
    }

    private fun recoverWorkflows() {
        scope.launch {
            try {
                val persistedWorkflows = workflowPersistence.loadAll()

                if (persistedWorkflows.isEmpty()) return@launch

                val allTaskIds = persistedWorkflows
                    .flatMap { it.nodes }
                    .mapNotNull { it.actualTaskId }
                    .toSet()

                val waitStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - waitStart < 10000) {
                    val allLoaded = allTaskIds.all { taskId ->
                        taskEngine.getTask(taskId) != null
                    }
                    if (allLoaded) break
                    delay(50)
                }

                for (persisted in persistedWorkflows) {
                    val workflow = reconcileWorkflow(persisted)
                    if (workflow == null) continue
                    synchronized(lock) {
                        if (workflows.containsKey(workflow.id)) continue
                        workflows[workflow.id] = workflow
                        val taskIdMap = persisted.nodes
                            .mapNotNull { node -> node.actualTaskId?.let { taskId -> node.id to taskId } }
                            .toMap()
                        workflowTaskIds[workflow.id] = taskIdMap
                    }
                    if (workflow.state == TaskState.RUNNING) {
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    recoveryDeferred.complete(Unit)
                } catch (e: IllegalStateException) {
                }
            }
        }
    }

    private fun reconcileWorkflow(persisted: PersistedWorkflow): Workflow? {
        val taskIdMap = persisted.nodes.associate { node ->
            node.id to node.actualTaskId
        }

        for (node in persisted.nodes) {
            val taskId = node.actualTaskId
            if (taskId != null) {
                val task = taskEngine.getTask(taskId)
                if (task == null) {
                    return null
                }
            }
        }

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
            }

            TaskState.QUEUED -> {
            }

            TaskState.RUNNING -> {
                val allTerminal = persisted.nodes.all { node ->
                    val taskId = node.actualTaskId
                    if (taskId == null) false
                    else {
                        val task = taskEngine.getTask(taskId)
                        task?.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)
                    }
                }

                val hasFailed = persisted.nodes.any { node ->
                    val taskId = node.actualTaskId
                    if (taskId == null) false
                    else {
                        val task = taskEngine.getTask(taskId)
                        task?.state == TaskState.FAILED || task?.state == TaskState.ERROR
                    }
                }

                val hasFailedDependency = persisted.nodes.any { node ->
                    val taskId = node.actualTaskId
                    if (taskId == null) false
                    else {
                        val task = taskEngine.getTask(taskId)
                        if (task == null || task.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) false
                        else {
                            node.dependsOn.any { depNodeId ->
                                val depTaskId = taskIdMap[depNodeId]
                                if (depTaskId == null) false
                                else {
                                    val depTask = taskEngine.getTask(depTaskId)
                                    depTask?.state == TaskState.FAILED || depTask?.state == TaskState.ERROR
                                }
                            }
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

    private fun toPersisted(workflow: Workflow): PersistedWorkflow {
        val taskIdMap = workflowTaskIds[workflow.id] ?: emptyMap()
        return PersistedWorkflow(
            id = workflow.id,
            name = workflow.name,
            state = workflow.state.name,
            createdAt = workflow.createdAt,
            completedAt = workflow.completedAt,
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

    private suspend fun persistWorkflow(workflow: Workflow) = persistenceMutex.withLock {
        val persisted = synchronized(lock) {
            toPersisted(workflow)
        }
        val existing = workflowPersistence.loadAll()
        val updated = existing.filter { it.id != workflow.id } + persisted
        workflowPersistence.saveAll(updated)
    }

    private data class PersistData(val workflow: Workflow, val persisted: PersistedWorkflow, val newState: TaskState)
}
