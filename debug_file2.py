with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check for releaseReadyNodes
idx = content.find('releaseReadyNodes')
if idx >= 0:
    print(f"releaseReadyNodes found at {idx}")
    print(content[idx:idx+500])
else:
    print("releaseReadyNodes not found")

# Check for getReadyNodes
idx2 = content.find('getReadyNodes')
if idx2 >= 0:
    print(f"getReadyNodes found at {idx2}")
else:
    print("getReadyNodes not found")