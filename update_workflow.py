with open('/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# 1. Add awaitRecovery() at the start of execute function
content = content.replace(
    '    suspend fun execute(workflowId: String): Boolean {\n        var workflow: Workflow? = null',
    '    suspend fun execute(workflowId: String): Boolean {\n        awaitRecovery()\n        var workflow: Workflow? = null')

# 2. Add orderedNodes variable after nodeToTaskId
content = content.replace(
    '        val nodeToTaskId = mutableMapOf<String, String>()\n        synchronized(lock) {',
    '        val nodeToTaskId = mutableMapOf<String, String>()\n        var orderedNodes: List<WorkflowNode> = emptyList()\n        synchronized(lock) {')

# 3. Store orderedNodes in the synchronized block
content = content.replace(
    '            for (node in topologicalSort(workflow.nodes)) {\n',
    '            orderedNodes = topologicalSort(workflow.nodes)\n            for (node in orderedNodes) {\n')

# 4. Add executionAttemptId logic after the synchronized block
old_block = '''            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist)'''

new_block = '''            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        // Check if this is a recovery of an execution that crashed after intent persistence
        // but before task creation. If workflow already has executionAttemptId, reuse it.
        val executionAttemptId: String
        val isRecovery = workflow!!.executionAttemptId != null
        if (isRecovery) {
            // Reuse existing execution attempt - do not increment counter
            executionAttemptId = workflow!!.executionAttemptId!!
        } else {
            // Fresh execution - generate new attempt
            val attemptNumber = workflow!!.attemptCounter + 1
            workflow!!.attemptCounter = attemptNumber
            executionAttemptId = "exec-${workflowId}#${attemptNumber}"
            workflow!!.executionAttemptId = executionAttemptId
        }
        persistWorkflow(workflow!!, executionAttemptId, emptyMap())

        // Test seam: allow deterministic crash injection after intent persistence
        testAfterIntentPersistence?.invoke()

        for (node in orderedNodes) {
            val depTaskIds = node.dependsOn.mapNotNull { depId ->
                nodeToTaskId[depId]
            }
            val task = taskEngine.create(
                goal = node.goal,
                role = node.role,
                riskClass = node.riskClass,
                dependsOn = depTaskIds
            )
            // Set workflow integration fields
            task.workflowId = workflowId
            task.executionAttemptId = executionAttemptId
            nodeToTaskId[node.id] = task.id
        }

        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)'''

content = content.replace(
    '''            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist)''',
    '''            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        // Check if this is a recovery of an execution that crashed after intent persistence
        // but before task creation. If workflow already has executionAttemptId, reuse it.
        val executionAttemptId: String
        val isRecovery = workflow!!.executionAttemptId != null
        if (isRecovery) {
            // Reuse existing execution attempt - do not increment counter
            executionAttemptId = workflow!!.executionAttemptId!!
        } else {
            // Fresh execution - generate new attempt
            val attemptNumber = workflow!!.attemptCounter + 1
            workflow!!.attemptCounter = attemptNumber
            executionAttemptId = "exec-${workflowId}#${attemptNumber}"
            workflow!!.executionAttemptId = executionAttemptId
        }
        persistWorkflow(workflow!!, executionAttemptId, emptyMap())

        // Test seam: allow deterministic crash injection after intent persistence
        testAfterIntentPersistence?.invoke()

        for (node in orderedNodes) {
            val depTaskIds = node.dependsOn.mapNotNull { depId ->
                nodeToTaskId[depId]
            }
            val task = taskEngine.create(
                goal = node.goal,
                role = node.role,
                riskClass = node.riskClass,
                dependsOn = depTaskIds
            )
            // Set workflow integration fields
            task.workflowId = workflowId
            task.executionAttemptId = executionAttemptId
            nodeToTaskId[node.id] = task.id
        }

        synchronized(lock) {
            workflow!!.state = TaskState.RUNNING
            workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        }

        val workflowToPersist = workflow!!
        persistWorkflow(workflowToPersist, executionAttemptId, nodeToTaskId)''')

with open('/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)

print('All replacements done')