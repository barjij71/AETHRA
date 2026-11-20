with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# 1. Add releasedTaskIds field after workflowTaskIds
old_field = '    private val workflowTaskIds = HashMap<String, Map<String, String>>()\n    private val lock = Any()'
new_field = '''    private val workflowTaskIds = HashMap<String, Map<String, String>>()
    private val releasedTaskIds = HashMap<String, MutableSet<String>>()
    private val lock = Any()'''

content = content.replace(old_field, new_field)

# 2. In execute function: 
# - Add releasedTaskIds initialization in the synchronized block
# - Remove the taskIds line and replace bulk release with releaseReadyNodes call

# Find the synchronized block in execute that sets workflowTaskIds
old_sync_block = '''        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)

        val taskIds = orderedNodes.map { nodeToTaskId[it.id]!! }
        taskEngine.releasePreparedTasks(taskIds)

        eventBus.publish(TaskEvent('''

new_sync_block = '''        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
            releasedTaskIds[workflowId] = mutableSetOf()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)
        eventBus.publish(TaskEvent('''

content = content.replace(old_sync_block, new_sync_block)

# 3. Add releaseReadyNodes and getReadyNodes functions after the execute function
# Find the end of execute function
old_execute_end = '''        scope.launch {
            monitorWorkflow(workflowId)
        }

        return true
    }

    fun getWorkflow'''

new_execute_end = '''        scope.launch {
            monitorWorkflow(workflowId)
        }

        return true
    }

    /**
     * Release all currently-ready nodes for a workflow.
     * A node is ready if all its dependencies are COMPLETED and it hasn't been released yet.
     */
    private fun releaseReadyNodes(workflowId: String) {
        val lock = this.lock

        synchronized(lock) {
            val workflow = workflows[workflowId] ?: return
            val nodeToTaskId = workflowTaskIds[workflowId] ?: return
            val released = releasedTaskIds[workflowId] ?: return

            // Get ready node IDs in deterministic order (topological rank, then nodeId)
            val readyNodeIds = getReadyNodes(workflow, nodeToTaskId, released)
                .sortedBy { it } // deterministic tie-break by nodeId
                .toList()

            if (readyNodeIds.isEmpty()) return

            val readyTaskIds = readyNodeIds.map { nodeToTaskId[it]!! }
            released.addAll(readyTaskIds)

            // Release via TaskEngine - this transitions tasks to QUEUED/APPROVED and starts execution
            readyTaskIds.forEach { taskEngine.releasePreparedTasks(listOf(it)) }
        }
    }

    /**
     * Get node IDs that are ready to be released (all deps COMPLETED, not yet released).
     */
    private fun getReadyNodes(
        workflow: Workflow,
        nodeToTaskId: Map<String, String>,
        released: Set<String>
    ): List<String> {
        val ready = mutableListOf<String>()

        for (node in workflow.nodes) {
            val taskId = nodeToTaskId[node.id] ?: continue
            if (released.contains(taskId)) continue

            var allDepsCompleted = true
            for (depNodeId in node.dependsOn) {
                val depTaskId = nodeToTaskId[depNodeId]
                if (depTaskId == null) {
                    allDepsCompleted = false
                    break
                }
                val depTask = taskEngine.getTask(depTaskId)
                if (depTask?.state != TaskState.COMPLETED) {
                    allDepsCompleted = false
                    break
                }
            }

            if (allDepsCompleted) {
                ready.add(node.id)
            }
        }

        return ready
    }

    fun getWorkflow'''

content = content.replace(old_execute_end, new_execute_end)

# 4. Fix recovery to rebuild releasedTaskIds from actual task states
# Find the recoverWorkflows function
old_recovery = '''                    if (workflow.state == TaskState.RUNNING) {
                        // Release ready-but-unreleased tasks for recovered RUNNING workflows
                        val taskIds = persisted.nodes
                            .mapNotNull { node -> node.actualTaskId }
                        if (taskIds.isNotEmpty()) {
                            taskEngine.releasePreparedTasks(taskIds)
                        }
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }'''

new_recovery = '''                    if (workflow.state == TaskState.RUNNING) {
                        // Rebuild releasedTaskIds from actual task states
                        val taskIdMap = persisted.nodes
                            .mapNotNull { node -> node.actualTaskId?.let { taskId -> node.id to taskId } }
                            .toMap()
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

                        // Release ready-but-unreleased tasks for recovered RUNNING workflows
                        releaseReadyNodes(workflow.id)

                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }'''

content = content.replace(old_recovery, new_recovery)

# 5. Fix monitorWorkflow to call releaseReadyNodes after checking for completed tasks
# Find the monitorWorkflow function
old_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    continue
                }'''

new_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    // Release newly-ready nodes
                    releaseReadyNodes(workflowId)
                    continue
                }'''

content = content.replace(old_monitor, new_monitor)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')