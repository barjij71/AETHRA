with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check the execute function area
idx = content.find('synchronized(lock) {')
# Find the third occurrence (the one in execute)
count = 0
while idx >= 0 and count < 3:
    idx = content.find('synchronized(lock) {', idx + 1)
    count += 1

if idx >= 0:
    print(content[idx:idx+800])