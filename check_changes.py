with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

idx = content.find(b'workflowTaskIds[workflowId] = nodeToTaskId.toMap()')
if idx >= 0:
    print(content[idx:idx+500])
else:
    print("Not found")