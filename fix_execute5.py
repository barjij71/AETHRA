with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'rb') as f:
    content = f.read()

# Find the exact location to insert releasedTaskIds initialization
# We need to find "workflowTaskIds[workflowId] = nodeToTaskId.toMap()" and add after it
idx = content.find(b'workflowTaskIds[workflowId] = nodeToTaskId.toMap()')
if idx >= 0:
    # Find the end of this line
    end_idx = content.find(b'\r\n', idx)
    if end_idx >= 0:
        # Insert after this line
        insert_pos = end_idx + 2  # skip \r\n
        # Check if we're inside a synchronized block - need to insert before the closing }
        # The pattern is:
        #         synchronized(lock) {
        #             workflow!!.state = TaskState.RUNNING
        #             workflowTaskIds[workflowId] = nodeToTaskId.toMap()
        #         }
        # We want to add "releasedTaskIds[workflowId] = mutableSetOf()" before the closing }
        
        # Find the closing } of this synchronized block
        brace_count = 0
        search_idx = idx
        # Find the opening {
        open_brace = content.find(b'{', search_idx)
        if open_brace >= 0:
            search_idx = open_brace + 1
            brace_count = 1
            while search_idx < len(content) and brace_count > 0:
                if content[search_idx] == ord('{'):
                    brace_count += 1
                elif content[search_idx] == ord('}'):
                    brace_count -= 1
                if brace_count == 0:
                    # Found the closing }
                    # Insert before it
                    insert_pos = search_idx
                    # Need to add proper indentation and newline
                    new_line = b'\r\n            releasedTaskIds[workflowId] = mutableSetOf()\r\n        '
                    content = content[:insert_pos] + new_line + content[insert_pos:]
                    print('Inserted releasedTaskIds initialization')
                    break
                search_idx += 1

# Now find and replace the release call
# Find "taskEngine.releasePreparedTasks(taskIds)" and replace with "releaseReadyNodes(workflowId)"
idx2 = content.find(b'taskEngine.releasePreparedTasks(taskIds)')
if idx2 >= 0:
    # Find the line end
    end_idx2 = content.find(b'\r\n', idx2)
    if end_idx2 >= 0:
        # Replace the line
        old_line = content[idx2:end_idx2+2]
        new_line = b'        releaseReadyNodes(workflowId)\r\n'
        content = content[:idx2] + new_line + content[end_idx2+2:]
        print('Replaced releasePreparedTasks call')

with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'wb') as f:
    f.write(content)
print('Done')