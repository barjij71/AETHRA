with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Find all occurrences
idx = 0
count = 0
while True:
    idx = content.find(b'synchronized(lock) {', idx)
    if idx < 0:
        break
    count += 1
    print(f"Occurrence {count} at {idx}:")
    print(content[idx:idx+200])
    print("---")
    idx += 1