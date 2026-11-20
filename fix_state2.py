with open('/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/task/Task.kt', 'r') as f:
    content = f.read()

# Replace the state field with @Volatile annotation
old = '    var state: TaskState = TaskState.QUEUED\n        private set'

new = '''    @Volatile
    var state: TaskState = TaskState.QUEUED
        private set'''

content = content.replace(old, new)

with open('/data/workspace/AETHRA-worktrees/opencode/core/src/main/kotlin/org/aethra/core/task/Task.kt', 'w') as f:
    f.write(content)
print('Done')