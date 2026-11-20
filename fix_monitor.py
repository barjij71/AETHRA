with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Fix monitorWorkflow to call releaseReadyNodes when tasks complete
old_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    return
                }'''

new_monitor = '''                if (!allTerminal && !hasFailedDependency) {
                    // Release newly-ready nodes
                    releaseReadyNodes(workflowId)
                    return
                }'''

content = content.replace(old_monitor, new_monitor)

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'w') as f:
    f.write(content)
print('Done')