with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    new_lines.append(line)
    # Replace debug with file writing
    if 'java.lang.System.out.println("[DEBUG] init block started")' in line:
        new_lines.append('        java.io.File("/tmp/workflow_debug.log").appendText("[DEBUG] init block started\\n")\n')
    if 'java.lang.System.out.println("[DEBUG] subscribeToTaskEvents called")' in line:
        new_lines.append('        java.lang.System.out.println("[DEBUG] subscribeToTaskEvents called")\n')
        new_lines.append('        java.io.File("/tmp/workflow_debug.log").appendText("[DEBUG] subscribeToTaskEvents called\\n")\n')
    if 'java.lang.System.out.println("[DEBUG] onTaskTerminal called: taskId=${taskId}, eventSource=${eventSource}")' in line:
        new_lines.append('        java.io.File("/tmp/workflow_debug.log").appendText("[DEBUG] onTaskTerminal called: taskId=${taskId}, eventSource=${eventSource}\\n")\n')
    if 'java.lang.System.out.println("[DEBUG] releaseReadyNodes called for workflowId=${workflowId}")' in line:
        new_lines.append('        java.io.File("/tmp/workflow_debug.log").appendText("[DEBUG] releaseReadyNodes called for workflowId=${workflowId}\\n")\n')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.writelines(new_lines)
print('Done adding file debug')