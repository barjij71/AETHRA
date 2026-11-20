with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    new_lines.append(line)
    # Replace println with System.out.println to force output
    if 'println("[DEBUG] init block started")' in line:
        new_lines.append('        java.lang.System.out.println("[DEBUG] init block started")\n')
    if 'println("[DEBUG] subscribeToTaskEvents called")' in line:
        new_lines.append('        java.lang.System.out.println("[DEBUG] subscribeToTaskEvents called")\n')
    if 'println("[DEBUG] onTaskTerminal called: taskId=${taskId}, eventSource=${eventSource}")' in line:
        new_lines.append('        java.lang.System.out.println("[DEBUG] onTaskTerminal called: taskId=${taskId}, eventSource=${eventSource}")\n')
    if 'println("[DEBUG] releaseReadyNodes called for workflowId=${workflowId}")' in line:
        new_lines.append('        java.lang.System.out.println("[DEBUG] releaseReadyNodes called for workflowId=${workflowId}")\n')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.writelines(new_lines)
print('Done forcing System.out.println')