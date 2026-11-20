with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# 1. Fix execute function to use releaseReadyNodes instead of the old loop
old_execute = '''    suspend fun execute(workflowId: String): Boolean {
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
    }'''

new_execute = '''    suspend fun execute(workflowId: String): Boolean {
        awaitRecovery()
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
            releasedTaskIds[workflowId] = mutableSetOf()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist)

        eventBus.publish(TaskEvent(
            "workflow_started", System.currentTimeMillis(), workflowId,
            TaskState.QUEUED, TaskState.RUNNING
        ))

        // Release ready tasks incrementally via TaskEngine's release boundary
        releaseReadyNodes(workflowId)

        scope.launch {
            monitorWorkflow(workflowId)
        }

        return true
    }'''

content = content.replace(old_execute, new_execute)

# 2. Fix recovery to initialize releasedTaskIds for ALL workflows and fix the releasePreparedTasks call
old_recovery = '''                for (persisted in persistedWorkflows) {
                    val reconciled = reconcileWorkflow(persisted)
                    if (reconciled == null) continue
                    val workflow = reconciled.workflow

                    synchronized(lock) {
                        if (workflows.containsKey(workflow.id)) continue
                        workflows[workflow.id] = workflow
                        workflowTaskIds[workflow.id] = reconciled.taskIdMap
                    }

                    persistWorkflow(workflow, persisted.executionAttemptId, reconciled.taskIdMap)

                    if (workflow.state == TaskState.RUNNING) {
                        // Release prepared tasks so they can execute
                        val taskIds = reconciled.taskIdMap.values.toList()
                        taskEngine.releasePreparedTasks(workflow.id, taskIds)
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }
                }'''

new_recovery = '''                for (persisted in persistedWorkflows) {
                    val reconciled = reconcileWorkflow(persisted)
                    if (reconciled == null) continue
                    val workflow = reconciled.workflow

                    synchronized(lock) {
                        if (workflows.containsKey(workflow.id)) continue
                        workflows[workflow.id] = workflow
                        workflowTaskIds[workflow.id] = reconciled.taskIdMap
                    }

                    // Initialize releasedTaskIds for all recovered workflows
                    val released = mutableSetOf<String>()
                    for (node in persisted.nodes) {
                        val taskId = node.actualTaskId ?: continue
                        val task = taskEngine.getTask(taskId)
                        if (task != null && task.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) {
                            released.add(taskId)
                        }
                    }
                    synchronized(lock) {
                        releasedTaskIds[workflow.id] = released
                    }

                    persistWorkflow(workflow, persisted.executionAttemptId, reconciled.taskIdMap)

                    if (workflow.state == TaskState.RUNNING) {
                        // Release ready-but-unreleased tasks for recovered RUNNING workflows
                        releaseReadyNodes(workflow.id)
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }
                }'''

content = content.replace(old_recovery, new_recovery)

# 3. Fix monitorWorkflow to call releaseReadyNodes when tasks complete
old_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    return@synchronized MonitorResult.Continue
                }'''

new_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    // Release newly-ready nodes
                    releaseReadyNodes(workflowId)
                    return@synchronized MonitorResult.Continue
                }'''

content = content.replace(old_monitor, new_monitor)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')