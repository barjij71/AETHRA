package org.aethra.core.task

import kotlinx.coroutines.*
import kotlin.Result
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.domain.TaskEvent
import org.aethra.core.task.TaskExecutionException
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import java.util.LinkedList
import java.util.UUID

enum class ExecutionPolicy {
    READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE
}

interface TaskRunner {
    suspend fun run(task: Task): Result<Map<String, Any>>
}

class TaskEngine(
    private val storage: Storage,
    private val eventBus: EventBus,
    private val runner: TaskRunner,
    private val maxConcurrent: Int = 4
) {
    private val tasks = HashMap<String, Task>()
    private val lock = Any()
    private val running = LinkedList<Task>()
    private val dependents = HashMap<String, MutableList<String>>()
    private var persistedVersion = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        load()
    }

    private fun load() {
        // persistence is layered
    }

    fun create(
        goal: String,
        role: String = "assistant",
        riskClass: RiskClass = RiskClass.READ_ONLY,
        dependsOn: List<String> = emptyList()
    ): Task {
        val task = Task(goal, role, riskClass, dependsOn)
        synchronized(lock) { tasks[task.id] = task }
        persist()
        registerDependents(task)
        // Tasks with dependencies are not auto-scheduled; they wait for dependencies
        // to be ready before transitioning to APPROVED. This avoids adding them
        // to the running queue prematurely.
        if (dependsOn.isEmpty()) {
            enqueue(task)
        }
        return task
    }

    private fun registerDependents(task: Task) {
        for (depId in task.dependsOn) {
            dependents.getOrPut(depId) { mutableListOf() }.add(task.id)
        }
    }

    private fun enqueue(task: Task) {
        if (task.state == TaskState.QUEUED) {
            running.add(task)
            persist()
        }
    }

    fun run(taskId: String): TaskState? = synchronized(lock) {
        println("[C2-DEBUG] run called for taskId=$taskId")
        val task: org.aethra.core.task.Task = tasks[taskId] ?: return null
        println("[C2-DEBUG] run: task found, state=${task.state}")
        // Idempotent execution guard: already completed/executed, no re-run
        if (task.isExecuted) {
            return task.state
        }
        // All tasks pass through PREPARING first. Tasks with unresolved dependencies
        // then move to WAITING_DEPENDENCY; otherwise they proceed to APPROVED.
        if (task.state == TaskState.QUEUED || task.state == TaskState.PREPARING) {
            transition(task, TaskState.PREPARING)
        }
        // Risk-aware approval: SENSITIVE tasks wait for explicit approval
        if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
            transition(task, TaskState.WAITING_APPROVAL)
            return task.state
        }
        if (task.dependsOn.isNotEmpty() && !TaskScheduler.isReady(task.dependsOn, { tasks[it] })) {
            if (task.state == TaskState.WAITING_DEPENDENCY) {
                return task.state
            }
            transition(task, TaskState.WAITING_DEPENDENCY)
            return task.state
        }
        transition(task, TaskState.APPROVED)
        if (running.size >= maxConcurrent) {
            enqueue(task)
            return task.state
        }
        transition(task, TaskState.RUNNING)
        eventBus.publish(TaskEvent(
            "execution_started", System.currentTimeMillis(), task.id, TaskState.PREPARING, TaskState.RUNNING,
            mapOf<String, String>("reason" to "started")
        ))
        scope.launch {
            execute(task)
        }
        persist()
        task.state
    }

    // --- Dependency scheduling integration ---

    /**
     * Wake up any dependent tasks whose dependencies are now all COMPLETED.
     * Only tasks in WAITING_DEPENDENCY can be woken; each is transitioned to
     * APPROVED then RUNNING and scheduled for execution (with maxConcurrent gating).
     */
    private fun wakeReadyDependents() {
        val ready = synchronized(lock) {
            val readyTasks = mutableListOf<Task>()
            // dependents: depId -> list of tasks that depend on that dep
            for ((_, childIds) in dependents) {
                for (childId in childIds) {
                    val child = tasks[childId] ?: continue
                    // Only tasks currently WAITING_DEPENDENCY are candidates
                    if (child.state != TaskState.WAITING_DEPENDENCY) continue
                    if (TaskScheduler.isReady(child.dependsOn, { tasks[it] })) {
                        readyTasks.add(child)
                    }
                }
            }
            readyTasks
        }
        for (task in ready) {
            wakeTask(task)
        }
    }

    private fun wakeTask(task: Task) {
        synchronized(lock) {
            if (task.state != TaskState.WAITING_DEPENDENCY) return
            if (running.size >= maxConcurrent) {
                // Not enough capacity now; it will be woken later when a slot frees
                return
            }
            transition(task, TaskState.APPROVED)
            // Transition APPROVED -> RUNNING so we can launch execute() immediately
            transition(task, TaskState.RUNNING)
            eventBus.publish(TaskEvent(
                "execution_started", System.currentTimeMillis(), task.id, TaskState.APPROVED, TaskState.RUNNING,
                mapOf<String, String>("reason" to "dependency_ready")
            ))
            scope.launch {
                execute(task)
            }
            persist()
        }
    }

    // --- Execution lifecycle hooks ---

    /** Called when a task transitions to a terminal state; re-evaluates dependents. */
    private fun onTaskTerminal(taskId: String) {
        println("[C2-DEBUG] onTaskTerminal called for taskId=$taskId")
        val children = synchronized(lock) { dependents[taskId]?.toMutableList() }
        println("[C2-DEBUG] onTaskTerminal children count=${children?.size}")
        children?.forEach { childId ->
            val child = tasks[childId] ?: return@forEach
            println("[C2-DEBUG] onTaskTerminal: childId=$childId, child.state=${child.state}")
            // Find the terminal state of this dependency
            val depState = tasks[taskId]?.state ?: return@forEach
            println("[C2-DEBUG] onTaskTerminal: depState=$depState, depId=$taskId")
            // Failed and errored dependencies fail their dependents.
            // A cancelled dependency leaves dependents blocked in their current
            // non-terminal state; it must never make them runnable.
            // COMPLETED is handled by wakeReadyDependents() via isReady().
            if (depState !in setOf(TaskState.FAILED, TaskState.ERROR)) {
                return@forEach
            }
            val childState = when (depState) {
                TaskState.FAILED -> TaskState.FAILED
                TaskState.ERROR -> TaskState.ERROR
                else -> return@forEach
            }
            // Dependent is in WAITING_DEPENDENCY: already blocked, just propagate failure
            if (child.state == TaskState.WAITING_DEPENDENCY) {
                transition(child, childState)
                eventBus.publish(TaskEvent(
                    "dependency_failed", System.currentTimeMillis(), child.id,
                    TaskState.WAITING_DEPENDENCY, childState,
                    mapOf("dependency_id" to taskId, "reason" to depState.name.lowercase())
                ))
            } else if (child.state == TaskState.QUEUED) {
                // Dependent not yet started: transition directly to failure state
                transition(child, childState)
                eventBus.publish(TaskEvent(
                    "dependency_failed", System.currentTimeMillis(), child.id,
                    child.state, childState,
                    mapOf("dependency_id" to taskId, "reason" to depState.name.lowercase())
                ))
            }
            // Dependents in RUNNING/PAUSED are not blocked by dependency failure in C2 (C4 policy)
        }
        // COMPLETED dependencies may wake up WAITING_DEPENDENCY dependents
        wakeReadyDependents()
    }

    private suspend fun execute(task: org.aethra.core.task.Task) {
        // Idempotency guard: prevent duplicate execution from race/resume/retry.
        if (task.isExecuted) return
        task.isExecuted = true

        var retriesUsed = 0
        var nextBackoffMs = task.backoffMs.coerceAtLeast(0L)

        while (true) {
            var failure: Throwable? = null
            var timedOut = false

            val result = try {
                withTimeout<Result<Map<String, Any>>>(task.timeoutMs) {
                    runner.run(task)
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                failure = TaskExecutionException("execution timeout after ${task.timeoutMs}ms")
                null
            } catch (e: CancellationException) {
                // External cancellation must not be converted into failure or retry.
                throw e
            } catch (e: Exception) {
                // Provider exceptions are retryable failures, unlike cancellation/timeouts.
                failure = e
                null
            }

            val returnedFailure = result?.exceptionOrNull()
            if (returnedFailure is CancellationException) {
                // A runner may also report cancellation through Result.failure().
                return
            }
            val executionError = returnedFailure ?: failure

            if (executionError == null) {
                task.complete()
                eventBus.publish(TaskEvent(
                    "execution_completed", System.currentTimeMillis(), task.id,
                    TaskState.RUNNING, TaskState.COMPLETED,
                    mapOf("reason" to "success", "attempts" to (retriesUsed + 1).toString())
                ))
                onTaskTerminal(task.id)
                return
            }

            // Timeouts and cancellations are deliberately not retried. Ordinary
            // provider failures consume the task's configured retry budget.
            if (!timedOut && retriesUsed < task.retryBudget.coerceAtLeast(0)) {
                retriesUsed++
                val waitMs = nextBackoffMs.coerceAtMost(30_000L)
                eventBus.publish(TaskEvent(
                    "retry_scheduled", System.currentTimeMillis(), task.id,
                    TaskState.RUNNING, TaskState.RUNNING,
                    mapOf(
                        "attempt" to (retriesUsed + 1).toString(),
                        "retry" to retriesUsed.toString(),
                        "delay_ms" to waitMs.toString(),
                        "reason" to (executionError.message ?: "provider error")
                    )
                ))
                if (waitMs > 0) delay(waitMs)
                // Do not restart work if the user cancelled the task during backoff.
                if (task.state != TaskState.RUNNING || task.isCancelled()) return
                nextBackoffMs = if (nextBackoffMs >= 15_000L) {
                    30_000L
                } else {
                    nextBackoffMs * 2
                }
                continue
            }

            task.fail(executionError.message ?: "execution error")
            val terminalEvent = if (timedOut) "execution_timeout" else "execution_failed"
            eventBus.publish(TaskEvent(
                terminalEvent, System.currentTimeMillis(), task.id,
                TaskState.RUNNING, TaskState.FAILED,
                mapOf(
                    "reason" to if (timedOut) "execution timeout" else "provider error",
                    "attempts" to (retriesUsed + 1).toString()
                )
            ))
            onTaskTerminal(task.id)
            return
        }
    }

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
        if (task.state in setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.WAITING_APPROVAL, TaskState.QUEUED)) {
            val wasRunning = task.state == TaskState.RUNNING
            task.cancel()
            // NOTE: Do NOT cancel the whole scope here — a single task cancellation
            // must not kill independent running tasks. The timeout wrapper in
            // execute() relies on CancellationException to propagate a natural
            // cancellation without terminating the engine's coroutine scope.
            eventBus.publish(TaskEvent("cancel", System.currentTimeMillis(), task.id, task.state, task.state))
            // Re-evaluate dependents when this task is cancelled
            onTaskTerminal(taskId)
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
            task.isExecuted = false
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
        // Persistence is delegated to the platform Storage implementation
    }

    fun close() {
        scope.cancel()
    }
}