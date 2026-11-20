with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Search for releasePreparedTasks
idx = content.find(b'releasePreparedTasks')
while idx >= 0:
    print(f'Found at {idx}: {content[idx:idx+100]}')
    idx = content.find(b'releasePreparedTasks', idx + 1)