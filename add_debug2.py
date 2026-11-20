with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    new_lines.append(line)
    # Add debug to subscribeToTaskEvents
    if 'private fun subscribeToTaskEvents() {' in line:
        new_lines.append('        println("[DEBUG] subscribeToTaskEvents called")\n')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.writelines(new_lines)
print('Done adding debug')