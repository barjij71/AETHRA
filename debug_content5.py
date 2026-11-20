with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Get occurrence 3
idx = 13860
# Print with hex
print(' '.join(f'{b:02x}' for b in content[idx:idx+300]))