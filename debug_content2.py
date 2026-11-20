with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Find the pattern
idx = content.find(b'synchronized(lock) {')
if idx >= 0:
    # Find the second occurrence (the one in execute)
    idx2 = content.find(b'synchronized(lock) {', idx + 1)
    if idx2 >= 0:
        print(content[idx2:idx2+600])
    else:
        print("Second not found")
else:
    print("Not found")