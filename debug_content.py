with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Find the pattern
idx = content.find(b'workflow!!.state = TaskState.RUNNING')
if idx >= 0:
    print(content[idx:idx+500])
else:
    print("Not found")