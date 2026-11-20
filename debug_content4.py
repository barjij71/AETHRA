with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Get occurrence 3
idx = 13860
print(content[idx:idx+800])