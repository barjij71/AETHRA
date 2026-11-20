with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
i = 0
while i < len(lines):
    line = lines[i]
    
    # 1. Add releasedTaskIds field after workflowTaskIds
    if 'private val workflowTaskIds = HashMap<String, Map<String, String>>()' in line:
        new_lines.append(line)
        new_lines.append('    private val releasedTaskIds = HashMap<String, MutableSet<String>>()\n')
        i += 1
        continue
    
    # 2. In execute function: 
    # - Add releasedTaskIds initialization in synchronized block
    # - Replace the bulk run loop with releaseReadyNodes call
    if 'workflowTaskIds[workflowId] = nodeToTaskId.toMap()' in line:
        # Check if this is in execute function (look for the pattern)
        # We need to add releasedTaskIds initialization and replace the for loop below
        pass
    
    # 3. Replace the execute function's task running loop with releaseReadyNodes
    # The original has:
    #        for (node in workflowToPersist.nodes) {
    #            val taskId = nodeToTaskId[node.id]!!
    #            val task = taskEngine.getTask(taskId)
    #            if (task?.state == TaskState.CANCELLED) continue
    #            taskEngine.run(taskId)
    #        }
    # We want to replace with:
    #        releaseReadyNodes(workflowId)
    
    new_lines.append(line)
    i += 1

# The above approach is too complex for line-by-line. Let me use string replacement instead.
content = ''.join(new_lines)

# Actually, let me re-read the file and do string replacements
with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# 1. Add releasedTaskIds field
old_field = '''    private val workflows = HashMap<String, Workflow>()
    private val workflowTaskIds = HashMap<String, Map<String, String>>()
    private val lock = Any()'''

new_field = '''    private val workflows = HashMap<String, Workflow>()
    private val workflowTaskIds = HashMap<String, Map<String, String>>()
    private val releasedTaskIds = HashMap<String, MutableSet<String>>()
    private val lock = Any()'''

content = content.replace(old_field, new_field)

# 2. Fix execute function - add releasedTaskIds init and replace bulk run with releaseReadyNodes
old_execute_sync = '''        synchronized(lock) {
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
        }'''

new_execute_sync = '''        synchronized(lock) {
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
        releaseReadyNodes(workflowId)'''

content = content.replace(old_execute_sync, new_execute_sync)

# 3. Add releaseReadyNodes and getReadyNodes functions after execute function
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

# 4. Fix recoverWorkflows to rebuild releasedTaskIds and call releaseReadyNodes
old_recovery = '''                    if (workflow.state == TaskState.RUNNING) {
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }'''

new_recovery = '''                    if (workflow.state == TaskState.RUNNING) {
                        // Rebuild releasedTaskIds from actual task states
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

# 5. Fix monitorWorkflow to call releaseReadyNodes when tasks complete
old_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    return
                }'''

new_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    // Release newly-ready nodes
                    releaseReadyNodes(workflowId)
                    return
                }'''

content = content.replace(old_monitor, new_monitor)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')