with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Find all synchronized blocks
idx = 0
count = 0
while True:
    idx = content.find('synchronized(lock) {', idx)
    if idx < 0:
        break
    count += 1
    print(f"Occurrence {count} at {idx}:")
    # Print first 200 chars
    print(content[idx:idx+300])
    print("---")
    idx += 1