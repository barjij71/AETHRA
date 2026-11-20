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
    
    # 2. Fix execute function - add releasedTaskIds initialization and releaseReadyNodes call
    if 'workflowTaskIds[workflowId] = nodeToTaskId.toMap()' in line:
        new_lines.append(line)
        new_lines.append('            releasedTaskIds[workflowId] = mutableSetOf()\n')
        i += 1
        continue
    
    # 3. Replace the for loop that runs all tasks with releaseReadyNodes call
    if 'for (node in workflowToPersist.nodes) {' in line:
        # Skip this line and the for loop block
        # Find the end of the for loop (matching brace)
        brace_count = 0
        while i < len(lines):
            curr = lines[i]
            if '{' in curr:
                brace_count += curr.count('{')
            if '}' in curr:
                brace_count -= curr.count('}')
                if brace_count <= 0:
                    # This is the closing brace of the for loop
                    i += 1
                    break
            i += 1
        # Now add the releaseReadyNodes call
        new_lines.append('        // Release ready nodes via TaskEngine release boundary\n')
        new_lines.append('        releaseReadyNodes(workflowId)\n')
        continue
    
    # 4. Add releaseReadyNodes and getReadyNodes functions after execute function
    # Look for "return true\n    }\n\n    fun getWorkflow"
    if line.strip() == 'return true' and i + 2 < len(lines):
        next_line1 = lines[i + 1].strip()
        next_line2 = lines[i + 2].strip() if i + 2 < len(lines) else ''
        if next_line1 == '}' and next_line2.startswith('fun getWorkflow'):
            # Insert the new functions before getWorkflow
            new_lines.append(line)
            new_lines.append(lines[i + 1])
            new_lines.append('\n')
            new_lines.append('    /**\n')
            new_lines.append('     * Release all currently-ready nodes for a workflow.\n')
            new_lines.append('     * A node is ready if all its dependencies are COMPLETED and it hasn\'t been released yet.\n')
            new_lines.append('     */\n')
            new_lines.append('    private fun releaseReadyNodes(workflowId: String) {\n')
            new_lines.append('        val lock = this.lock\n')
            new_lines.append('\n')
            new_lines.append('        synchronized(lock) {\n')
            new_lines.append('            val workflow = workflows[workflowId] ?: return\n')
            new_lines.append('            val nodeToTaskId = workflowTaskIds[workflowId] ?: return\n')
            new_lines.append('            val released = releasedTaskIds[workflowId] ?: return\n')
            new_lines.append('\n')
            new_lines.append('            // Get ready node IDs in deterministic order (topological rank, then nodeId)\n')
            new_lines.append('            val readyNodeIds = getReadyNodes(workflow, nodeToTaskId, released)\n')
            new_lines.append('                .sortedBy { it } // deterministic tie-break by nodeId\n')
            new_lines.append('                .toList()\n')
            new_lines.append('\n')
            new_lines.append('            if (readyNodeIds.isEmpty()) return\n')
            new_lines.append('\n')
            new_lines.append('            val readyTaskIds = readyNodeIds.map { nodeToTaskId[it]!! }\n')
            new_lines.append('            released.addAll(readyTaskIds)\n')
            new_lines.append('\n')
            new_lines.append('            // Release via TaskEngine - this transitions tasks to QUEUED/APPROVED and starts execution\n')
            new_lines.append('            readyTaskIds.forEach { taskEngine.releasePreparedTasks(listOf(it)) }\n')
            new_lines.append('        }\n')
            new_lines.append('    }\n')
            new_lines.append('\n')
            new_lines.append('    /**\n')
            new_lines.append('     * Get node IDs that are ready to be released (all deps COMPLETED, not yet released).\n')
            new_lines.append('     */\n')
            new_lines.append('    private fun getReadyNodes(\n')
            new_lines.append('        workflow: Workflow,\n')
            new_lines.append('        nodeToTaskId: Map<String, String>,\n')
            new_lines.append('        released: Set<String>\n')
            new_lines.append('    ): List<String> {\n')
            new_lines.append('        val ready = mutableListOf<String>()\n')
            new_lines.append('\n')
            new_lines.append('        for (node in workflow.nodes) {\n')
            new_lines.append('            val taskId = nodeToTaskId[node.id] ?: continue\n')
            new_lines.append('            if (released.contains(taskId)) continue\n')
            new_lines.append('\n')
            new_lines.append('            var allDepsCompleted = true\n')
            new_lines.append('            for (depNodeId in node.dependsOn) {\n')
            new_lines.append('                val depTaskId = nodeToTaskId[depNodeId]\n')
            new_lines.append('                if (depTaskId == null) {\n')
            new_lines.append('                    allDepsCompleted = false\n')
            new_lines.append('                    break\n')
            new_lines.append('                }\n')
            new_lines.append('                val depTask = taskEngine.getTask(depTaskId)\n')
            new_lines.append('                if (depTask?.state != TaskState.COMPLETED) {\n')
            new_lines.append('                    allDepsCompleted = false\n')
            new_lines.append('                    break\n')
            new_lines.append('                }\n')
            new_lines.append('            }\n')
            new_lines.append('\n')
            new_lines.append('            if (allDepsCompleted) {\n')
            new_lines.append('                ready.add(node.id)\n')
            new_lines.append('            }\n')
            new_lines.append('        }\n')
            new_lines.append('\n')
            new_lines.append('        return ready\n')
            new_lines.append('    }\n')
            new_lines.append('\n')
            i += 1
            continue
    
    # 5. Fix monitorWorkflow - add releaseReadyNodes call before return
    if 'if (!allTerminal && !hasFailedDependency) {' in line:
        new_lines.append(line)
        i += 1
        # Check if next line is "return" or "return@synchronized"
        if i < len(lines):
            next_line = lines[i].strip()
            if next_line.startswith('return'):
                new_lines.append('                    releaseReadyNodes(workflowId)\n')
                new_lines.append(lines[i])
                i += 1
            else:
                new_lines.append(lines[i])
                i += 1
        continue
    
    # 6. Fix recovery - initialize releasedTaskIds for all workflows
    # This is more complex - let's do it with string replacement after building the content
    
    new_lines.append(line)
    i += 1

# Build content and do string replacements for the complex parts
content = ''.join(new_lines)

# Fix recovery - add releasedTaskIds initialization for all workflows
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

                    // Initialize releasedTaskIds for ALL recovered workflows
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

# Fix monitorWorkflow - add releaseReadyNodes call before return
old_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    return
                }'''

new_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    // Release newly-ready nodes before continuing to monitor
                    releaseReadyNodes(workflowId)
                    return
                }'''

content = content.replace(old_monitor, new_monitor)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')