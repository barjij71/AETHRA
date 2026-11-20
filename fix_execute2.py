with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

old = b'''        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)
        eventBus.publish(TaskEvent(
            "workflow_started", System.currentTimeMillis(), workflowId,
            TaskState.QUEUED, TaskState.RUNNING
        ))

        // Release ready tasks via TaskEngine's release boundary
        val taskIds = orderedNodes.map { nodeToTaskId[it.id]!! }
        taskEngine.releasePreparedTasks(taskIds)'''

new = b'''        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
            releasedTaskIds[workflowId] = mutableSetOf()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)
        eventBus.publish(TaskEvent(
            "workflow_started", System.currentTimeMillis(), workflowId,
            TaskState.QUEUED, TaskState.RUNNING
        ))

        // Release ready tasks incrementally via TaskEngine's release boundary
        releaseReadyNodes(workflowId)'''

if old in content:
    content = content.replace(old, new)
    print('Replaced successfully')
else:
    print('Pattern not found')
    # Try with CRLF
    old_crlf = old.replace(b'\n', b'\r\n')
    if old_crlf in content:
        content = content.replace(old_crlf, new.replace(b'\n', b'\r\n'))
        print('Replaced with CRLF')
    else:
        print('Still not found')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'wb') as f:
    f.write(content)
print('Done')