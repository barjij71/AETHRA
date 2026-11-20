with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Add releaseReadyNodes and getReadyNodes functions after the execute function
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

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')