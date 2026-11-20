with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

old = b'''        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)

        val taskIds = orderedNodes.map { nodeToTaskId[it.id]!! }
        taskEngine.releasePreparedTasks(taskIds)

        eventBus.publish(TaskEvent('''

new = b'''        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
            releasedTaskIds[workflowId] = mutableSetOf()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)
        eventBus.publish(TaskEvent('''

if old in content:
    content = content.replace(old, new)
    print('Replaced successfully')
else:
    print('Pattern not found')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'wb') as f:
    f.write(content)
print('Done')