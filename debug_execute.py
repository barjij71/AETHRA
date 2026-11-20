with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check around the execute function
idx = content.find('workflowTaskIds[workflowId] = nodeToTaskId.toMap()')
if idx >= 0:
    print(content[idx:idx+800])