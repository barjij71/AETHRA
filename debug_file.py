with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Check for workflowTaskIds
idx = content.find('workflowTaskIds')
if idx >= 0:
    print(f"Found at {idx}: {content[idx:idx+100]}")
else:
    print("workflowTaskIds not found")

# Check for the execute function return
idx2 = content.find('return true\n    }\n\n    fun getWorkflow')
if idx2 >= 0:
    print(f"Found marker at {idx2}")
else:
    print("Marker not found")
    # Try with different line endings
    idx3 = content.find('return true\r\n    }\r\n\r\n    fun getWorkflow')
    if idx3 >= 0:
        print(f"Found marker with CRLF at {idx3}")
    else:
        # Print around return true
        idx4 = content.find('return true')
        if idx4 >= 0:
            print(f"Found 'return true' at {idx4}: {content[idx4:idx4+200]}")