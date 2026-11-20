with open('core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt', 'r') as f:
    content = f.read()

# Find the execute function end
idx = content.find('return true\n    }\n\n    fun getWorkflow')
if idx >= 0:
    print(repr(content[idx:idx+200]))
else:
    print("Pattern not found")
    # Try alternative
    idx2 = content.find('return true\n    }')
    if idx2 >= 0:
        print(f"Found 'return true' at {idx2}: {repr(content[idx2:idx2+200])}")