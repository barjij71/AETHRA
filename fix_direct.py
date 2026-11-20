with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

# Print all lines with line numbers
for i, line in enumerate(lines):
    if any(keyword in line for keyword in ['workflowTaskIds', 'releasedTaskIds', 'releaseReadyNodes', 'getReadyNodes', 'execute', 'monitorWorkflow', 'recoverWorkflows', 'getWorkflow']):
        print(f"{i+1}: {line.rstrip()}")