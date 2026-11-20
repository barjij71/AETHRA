with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check occurrence 3 area (execute function)
idx = 13554
print(content[idx:idx+800])