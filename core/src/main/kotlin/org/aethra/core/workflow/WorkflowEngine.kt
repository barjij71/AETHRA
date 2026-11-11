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

    fun createWorkflow(name: String, nodes: List<WorkflowNode>): Workflow {
        val nodeIds = nodes.map { it.id }.toSet()
        for (node in nodes) {
            for (depId in node.dependsOn) {
                require(depId in nodeIds) {
                    "Workflow node ${node.id} depends on unknown node $depId"
                }
            }
        }

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

    fun execute(workflowId: String): Boolean {
        var workflow: Workflow? = null
        synchronized(lock) {
            workflow = workflows[workflowId] ?: return false
            if (workflow.state != TaskState.QUEUED) return false
            workflow.state = TaskState.RUNNING
        }

        eventBus.publish(TaskEvent(
            "workflow_started", System.currentTimeMillis(), workflowId,
            TaskState.QUEUED, TaskState.RUNNING
        ))

        val nodeToTaskId = mutableMapOf<String, String>()
        for (node in workflow!!.nodes) {
            val task = taskEngine.create(
                goal = node.goal,
                role = node.role,
                riskClass = node.riskClass,
                dependsOn = node.dependsOn.map { depId -> nodeToTaskId[depId] ?: "" }.filter { depId -> depId.isNotEmpty() }
            )
            nodeToTaskId[node.id] = task.id
        }

        synchronized(lock) {
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        for (node in workflow!!.nodes) {
            val taskId = nodeToTaskId[node.id]!!
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
