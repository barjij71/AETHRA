with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Find the init block and add subscription
old_init = '''    init {
        recoverWorkflows()
    }'''

new_init = '''    init {
        recoverWorkflows()
        subscribeToTaskEvents()
    }

    private val terminalSources = setOf(
        "execution_completed",
        "execution_failed",
        "execution_cancelled",
        "execution_timeout"
    )

    private fun subscribeToTaskEvents() {
        scope.launch {
            eventBus.subscribe(TaskEvent::class.java) { event ->
                if (event.source in terminalSources) {
                    onTaskTerminal(event.taskId, event.source)
                }
            }
        }
    }

    private fun onTaskTerminal(taskId: String, eventSource: String) {
        // Find which workflow this task belongs to
        val workflowId = findWorkflowForTask(taskId) ?: return

        // For failed/cancelled/timeout dependencies: do NOT release dependents
        // They remain WAITING_DEPENDENCY in TaskEngine (enforced by TaskScheduler.isReady)
        // Only COMPLETED dependencies allow dependents to proceed
        val isCompleted = eventSource == "execution_completed"

        if (!isCompleted) {
            // Dependency failed/cancelled/timed out - dependents stay blocked
            // TaskEngine's wakeReadyDependents will check isReady and find deps not COMPLETED
            return
        }

        // Dependency completed - release newly ready nodes
        releaseReadyNodes(workflowId)
    }

    private fun findWorkflowForTask(taskId: String): String? {
        for ((wfId, nodeToTaskId) in workflowTaskIds) {
            if (taskId in nodeToTaskId.values) return wfId
        }
        return null
    }'''

content = content.replace(old_init, new_init)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done adding event subscription')