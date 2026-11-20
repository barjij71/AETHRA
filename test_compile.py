with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

# Add a compile error to init block
new_lines = []
for line in lines:
    new_lines.append(line)
    if 'init {' in line:
        new_lines.append('        thisIsNotAValidFunction()\n')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.writelines(new_lines)
print('Done adding compile error')