import sys

with open("/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt", "r") as f:
    content = f.read()

# Remove awaitRecovery() calls from execute() and cancel() - keep them only in createWorkflow
# Actually, let's remove awaitRecovery() from execute() only to test if that's the issue
old = '''    suspend fun execute(workflowId: String): Boolean {
        awaitRecovery()

        var workflow: Workflow? = null'''

new = '''    suspend fun execute(workflowId: String): Boolean {
        var workflow: Workflow? = null'''

if old not in content:
    print("ERROR: Could not find execute() start")
    sys.exit(1)

content = content.replace(old, new)

with open("/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt", "w") as f:
    f.write(content)

print("Successfully removed awaitRecovery from execute()")
