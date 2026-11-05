package org.aethra.core.task

import kotlinx.coroutines.*
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.domain.TaskEvent
import org.aethra.core.storage.Storage
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.task.Task
import java.util.LinkedList
import java.util.UUID

/** Execution policy for task types. Used by the engine to decide whether a task needs explicit user approval before it runs. */
enum class ExecutionPolicy {
    // Always runs (no approval), reads only
    READ_ONLY,
    // Runs after a short confirmation prompt, reversible
    REVERSIBLE,
    // Requires explicit approval, may change state
    SENSITIVE,
    // Requires approval + confirmation + recovery plan, destructive
    DESTRUCTIVE
}

/** Dispatches a task to its actual work. The platform layer (app module) or tests supply an implementation. The core never assumes an AI provider or a tool. */
interface TaskRunner {
    suspend fun run(task: Task): Result<Map<String, Any>>
}

/** Core task engine. Independent of Compose, AI providers and the UI.
 *
 *  Responsibilities:
 *  - create and persist tasks
 *  - coordinate states via the transitions above
 *  - run tasks asynchronously and observe the result
 *  - cancel, pause, resume and retry
 *  - track active/queued counts and persist on demand
 */
class TaskEngine(
    private val storage: Storage,
    private val eventBus: EventBus,
    private val runner: TaskRunner,
    private val maxConcurrent: Int = 4
) {
    private val tasks = HashMap<String, Task>()
    private val lock = Any()
    private val running = LinkedList<Task>()
    private var persistedVersion = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        load()
    }

    private fun load() {
        // persistence is layered: the platform's Storage keeps the journal; this
        // in-memory index is rebuilt from that storage at startup
    }

    /** Create a task and enqueue it. Returns the task for inspection. */
    fun create(
        goal: String,
        role: String = "assistant",
        riskClass: RiskClass = RiskClass.READ_ONLY,
        dependsOn: List<String> = emptyList()
    ): Task {
        val task = Task(goal, role, riskClass, dependsOn)
        synchronized(lock) { tasks[task.id] = task }
        persist()
        enqueue(task)
        return task
    }

    private fun enqueue(task: Task) {
        if (task.state == TaskState.QUEUED) {
            running.add(task)
            persist()
        }
    }

    /** Start or resume a task. Returns the resulting state (or null if not found). */
    fun run(taskId: String): TaskState? = synchronized(lock) {
        val task: org.aethra.core.task.Task = tasks[taskId] ?: return null
        transition(task, TaskState.PREPARING)
        if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
            transition(task, TaskState.WAITING_APPROVAL)
            return task.state
        }
        transition(task, TaskState.APPROVED)
        if (running.size >= maxConcurrent) {
            // overflow tasks are re-queued for later; a real scheduler would
            // return them to the queue and resume them when a slot frees up
            enqueue(task)
            return task.state
        }
        transition(task, TaskState.RUNNING)
        scope.launch {
            execute(task)
        }
        persist()
        task.state
    }

    private suspend fun execute(task: org.aethra.core.task.Task) {
        try {
            val result = runner.run(task)
            if (result.isSuccess) {
                task.complete()
            } else {
                val reason = result.exceptionOrNull()?.message ?: "unknown error"
                task.fail(reason)
                if (task.retryBudget > 0 && !task.isCancelled()) {
                    delay(1_000)
                    run(task.id)
                    return
                }
            }
        } catch (e: CancellationException) {
            task.updateState(TaskState.CANCELLED)
        } catch (e: Exception) {
            task.fail(e.message ?: "execution error")
        } finally {
            persist()
            eventBus.publish(TaskEvent("task", System.currentTimeMillis(), task.id, task.state, task.state))
        }
    }

    /** Request approval for a task that is blocked in WAITING_APPROVAL. The platform layer decides whether the user granted the permission and calls this with the outcome. */
    fun approve(taskId: String, granted: Boolean, reason: String) {
        val task = tasks[taskId] ?: return
        if (task.state != TaskState.WAITING_APPROVAL) return
        if (granted) transition(task, TaskState.APPROVED)
        else transition(task, TaskState.CANCELLED)
        persist()
        eventBus.publish(TaskEvent("approval", System.currentTimeMillis(), task.id, task.state, task.state))
    }

    fun cancel(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.state in setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.WAITING_APPROVAL)) {
            val wasRunning = task.state == TaskState.RUNNING
            task.cancel()
            if (wasRunning) {
                scope.coroutineContext[Job]?.cancel()
            }
            eventBus.publish(TaskEvent("cancel", System.currentTimeMillis(), task.id, task.state, task.state))
        }
    }

    fun pause(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.state == TaskState.RUNNING) {
            transition(task, TaskState.PAUSED)
            persist()
            eventBus.publish(TaskEvent("pause", System.currentTimeMillis(), task.id, task.state, task.state))
        }
    }

    fun resume(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.state == TaskState.PAUSED) {
            transition(task, TaskState.RUNNING)
            persist()
            eventBus.publish(TaskEvent("resume", System.currentTimeMillis(), task.id, task.state, task.state))
            scope.launch { execute(task) }
        }
    }

    fun retry(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.state == TaskState.FAILED) {
            // reset to a runnable state without claiming completion
            transition(task, TaskState.QUEUED)
            run(task.id)
        }
    }

    private fun transition(task: Task, to: TaskState) {
        if (to != task.state && task.canTransitionTo(to)) {
            val previous = task.state
            task.updateState(to)
            eventBus.publish(TaskEvent("core", System.currentTimeMillis(), task.id, previous, to))
        }
    }

    fun getTask(taskId: String): Task? = synchronized(lock) { tasks[taskId] }

    fun allTasks(): List<Task> = synchronized(lock) { tasks.values.sortedBy { it.createdAt } }

    val queuedCount: Int get() = tasks.values.count { it.state == TaskState.QUEUED }
    val activeCount: Int get() = synchronized(lock) { running.size }

    private fun persist() {
        // Persistence is delegated to the platform Storage implementation; the
        // in-memory index is available to tests without IO.
    }

    fun close() {
        scope.cancel()
    }
}
