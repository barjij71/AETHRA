with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check each pattern
patterns = [
    'private val workflowTaskIds = HashMap<String, Map<String, String>>()',
    'workflowTaskIds[workflowId] = nodeToTaskId.toMap()',
    'for (node in workflowToPersist.nodes) {',
    'releaseReadyNodes(workflowId)',
    'if (workflow.state == TaskState.RUNNING) {',
    'if (!allTerminal && !hasFailedDependency) {'
]

for p in patterns:
    idx = content.find(p)
    if idx >= 0:
        print(f"FOUND: '{p}' at {idx}")
    else:
        print(f"NOT FOUND: '{p}'")