with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# 1. Fix duplicated releasedTaskIds field
# The file has:
#     private val workflowTaskIds = HashMap<String, Map<String, String>>()
#     private val releasedTaskIds = HashMap<String, MutableSet<String>>()
#     private val releasedTaskIds = HashMap<String, MutableSet<String>>()
# We need to remove the duplicate

old_field_dup = '''    private val workflowTaskIds = HashMap<String, Map<String, String>>()
    private val releasedTaskIds = HashMap<String, MutableSet<String>>()
    private val releasedTaskIds = HashMap<String, MutableSet<String>>()
    private val lock = Any()'''

new_field = '''    private val workflowTaskIds = HashMap<String, Map<String, String>>()
    private val releasedTaskIds = HashMap<String, MutableSet<String>>()
    private val lock = Any()'''

content = content.replace(old_field_dup, new_field)

# 2. Fix the corrupted taskIds line in execute function
# The file has:
#         val taskIds = orderedNodes.map { nodeToTaskId[it.id]!! 
#             releasedTaskIds[workflowId] = mutableSetOf()
#         }
#                 releaseReadyNodes(workflowId)
# We need to remove the taskIds line and the duplicate releasedTaskIds init

old_corrupted = '''        val taskIds = orderedNodes.map { nodeToTaskId[it.id]!! 
            releasedTaskIds[workflowId] = mutableSetOf()
        }
                releaseReadyNodes(workflowId)'''

new_corrupted = '''        // Release ready tasks incrementally via TaskEngine's release boundary
        releaseReadyNodes(workflowId)'''

content = content.replace(old_corrupted, new_corrupted)

# 3. Fix recovery to rebuild releasedTaskIds and call releaseReadyNodes
# Find the recovery section
old_recovery = '''                    if (workflow.state == TaskState.RUNNING) {
                        // Release prepared tasks so they
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

# 4. Fix monitorWorkflow to call releaseReadyNodes when tasks complete
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