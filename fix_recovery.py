with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Fix recovery to initialize releasedTaskIds for ALL workflows
old_recovery = '''                for (persisted in persistedWorkflows) {
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
                }'''

new_recovery = '''                for (persisted in persistedWorkflows) {
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

                    if (workflow.state == TaskState.RUNNING) {
                        // Release ready-but-unreleased tasks for recovered RUNNING workflows
                        releaseReadyNodes(workflow.id)
                        scope.launch {
                            monitorWorkflow(workflow.id)
                        }
                    }
                }'''

content = content.replace(old_recovery, new_recovery)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')