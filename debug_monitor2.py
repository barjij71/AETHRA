with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Find the exact pattern
idx = content.find('if (!allTerminal && !hasFailedDependency) {')
if idx >= 0:
    print(repr(content[idx:idx+100]))