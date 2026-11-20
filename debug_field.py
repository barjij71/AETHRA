with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check for workflowTaskIds
idx = content.find('workflowTaskIds')
if idx >= 0:
    print(f"Found at {idx}: {content[idx:idx+200]}")
else:
    print("Not found")

# Check for releasedTaskIds
idx2 = content.find('releasedTaskIds')
if idx2 >= 0:
    print(f"releasedTaskIds found at {idx2}: {content[idx2:idx2+200]}")
else:
    print("releasedTaskIds not found")