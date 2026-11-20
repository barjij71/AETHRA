with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Find the functions
for name in ['releaseReadyNodes', 'getReadyNodes']:
    idx = content.find(f'fun {name}')
    if idx >= 0:
        print(f"Found '{name}' at {idx}: {repr(content[idx:idx+100])}")
    else:
        print(f"NOT FOUND: '{name}'")