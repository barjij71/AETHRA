import sys

with open("/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt", "r") as f:
    content = f.read()

old = """    suspend fun createWorkflow(name: String, nodes: List<WorkflowNode>): Workflow {
        awaitRecovery()

        validateWorkflowGraph(nodes)"""
new = """    suspend fun createWorkflow(name: String, nodes: List<WorkflowNode>): Workflow {
        validateWorkflowGraph(nodes)"""

if old not in content:
    print("ERROR: Could not find oldString")
    sys.exit(1)

content = content.replace(old, new)

with open("/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/workflow/WorkflowEngine.kt", "w") as f:
    f.write(content)

print("Successfully removed awaitRecovery from createWorkflow()")
