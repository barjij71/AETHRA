with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check if the patterns exist
patterns = [
    'private val workflowTaskIds = HashMap<String, Map<String, String>>()',
    'suspend fun execute(workflowId: String): Boolean {',
    'for (node in workflowToPersist.nodes) {',
    'if (!allTerminal && !hasFailedDependency) {',
    'for (persisted in persistedWorkflows) {',
    'fun getWorkflow(workflowId: String): Workflow?'
]

for p in patterns:
    idx = content.find(p)
    if idx >= 0:
        print(f"FOUND: '{p}' at {idx}")
    else:
        print(f"NOT FOUND: '{p}'")