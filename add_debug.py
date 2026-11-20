with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    new_lines.append(line)
    # Add debug to onTaskTerminal
    if 'private fun onTaskTerminal(taskId: String, eventSource: String) {' in line:
        new_lines.append('        println("[DEBUG] onTaskTerminal called: taskId=${taskId}, eventSource=${eventSource}")\n')
    # Add debug to releaseReadyNodes
    if 'private fun releaseReadyNodes(workflowId: String) {' in line:
        new_lines.append('        println("[DEBUG] releaseReadyNodes called for workflowId=${workflowId}")\n')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.writelines(new_lines)
print('Done adding debug')