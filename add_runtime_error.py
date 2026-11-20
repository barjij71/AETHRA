with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    new_lines.append(line)
    # Add a runtime error in init block to verify execution
    if 'init {' in line:
        new_lines.append('        throw RuntimeException("INIT BLOCK EXECUTED - DEBUG")\n')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.writelines(new_lines)
print('Done adding runtime error')