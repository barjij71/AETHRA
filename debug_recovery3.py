with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Find the recovery loop
idx = content.find('for (persisted in persistedWorkflows) {')
if idx >= 0:
    print(content[idx:idx+1500])