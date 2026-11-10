package org.aethra.core.task

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.Result
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.domain.TaskEvent
import org.aethra.core.task.TaskExecutionException
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import java.util.LinkedHashSet
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
    private val running = LinkedHashSet<String>()
    private val pending = LinkedHashSet<String>()
    private val jobs = HashMap<String, Job>()
    private val dependents = HashMap<String, MutableList<String>>()
    private val persistence = TaskPersistence(storage)
    private val restored = CompletableDeferred<Unit>()
    private val persistenceMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        require(maxConcurrent > 0) { "maxConcurrent must be > 0" }
        load()
    }

    private fun load() {
        scope.launch {
            try {
                val restoredTasks = persistence.loadAll()
                synchronized(lock) {
                    for (task in restoredTasks) {
                        if (!tasks.containsKey(task.id)) {
                            tasks[task.id] = task
                            registerDependents(task)
                        }
                    }
                }
                restored.complete(Unit)
                // Resume durable work that was queued or in-flight when the process stopped.
                for (task in restoredTasks) {
                    when (task.state) {
                        TaskState.QUEUED -> run(task.id)
                        TaskState.WAITING_DEPENDENCY -> wakeReadyDependents()
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                restored.completeExceptionally(t)
            }
        }
    }

    fun create(
        goal: String,
        role: String = "assistant",
        riskClass: RiskClass = RiskClass.READ_ONLY,
        dependsOn: List<String> = emptyList()
    ): Task {
        val task = Task(goal, role, riskClass, dependsOn)
        synchronized(lock) {
            require(dependsOn.all { tasks.containsKey(it) }) {
                "Task dependency not found"
            }
            require(!TaskScheduler.wouldCreateCycle(task.id, dependsOn) { tasks[it] }) {
                "Task dependency cycle detected"
            }
            tasks[task.id] = task
        }
        persist()
        registerDependents(task)
        // Tasks with dependencies are not auto-scheduled; they wait for dependencies
        // to be ready before transitioning to APPROVED. This avoids adding them
        // to the running queue prematurely.
        if (dependsOn.isEmpty()) {
            enqueue(task)
        } else {
            // WAITING_DEPENDENCY is entered through PREPARING in the state
            // machine. Keep dependency tasks blocked at creation time instead
            // of silently rejecting an illegal QUEUED -> WAITING transition.
            transition(task, TaskState.PREPARING)
            transition(task, TaskState.WAITING_DEPENDENCY)
            persist()
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
            pending.add(task.id)
            persist()
        }
    }

    private fun startTaskLocked(task: Task, reason: String): TaskState {
        transition(task, TaskState.RUNNING)
        running.add(task.id)
        eventBus.publish(TaskEvent(
            "execution_started", System.currentTimeMillis(), task.id,
            TaskState.APPROVED, TaskState.RUNNING,
            mapOf("reason" to reason)
        ))
        // The DEFAULT start dispatches the coroutine to the dispatcher so
        // run() preserves its RUNNING contract (returns before the coroutine
        // completes). The gate inside execute() refuses to run a fresh cycle
        // for a task that has since been cancelled or paused, and
        // claimExecution() guarantees exactly one racing duplicate proceeds.
        val job = scope.launch(start = CoroutineStart.DEFAULT) {
            try {
                execute(task)
            } finally {
                synchronized(lock) {
                    running.remove(task.id)
                    jobs.remove(task.id)
                }
                drainQueue()
            }
        }
        jobs[task.id] = job
        // Snapshot reported before the coroutine can outrun the caller and
        // complete an instantly-executable task: run() must report the state
        // at initiation (RUNNING), not whatever the task became meanwhile.
        return TaskState.RUNNING
    }

    private fun drainQueue() {
        synchronized(lock) {
            while (running.size < maxConcurrent && pending.isNotEmpty()) {
                val id = pending.iterator().next()
                pending.remove(id)
                val task = tasks[id] ?: continue
                if (task.state != TaskState.QUEUED || task.isExecuted) continue
                if (task.dependsOn.isNotEmpty() && !TaskScheduler.isReady(task.dependsOn, { tasks[it] })) {
                    transition(task, TaskState.WAITING_DEPENDENCY)
                    continue
                }
                if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
                    transition(task, TaskState.WAITING_APPROVAL)
                    continue
                }
                transition(task, TaskState.PREPARING)
                transition(task, TaskState.APPROVED)
                startTaskLocked(task, "queue_available")
            }
            persist()
        }
    }

    fun run(taskId: String): TaskState? = synchronized(lock) {
        val task = tasks[taskId] ?: return null
        if (task.isExecuted || task.state == TaskState.COMPLETED) return task.state

        // A dependency wake-up may already have moved this task into execution.
        // Treat an explicit run request as idempotent instead of starting a
        // second coroutine for the same task.
        if (task.state == TaskState.RUNNING || task.state == TaskState.APPROVED) {
            return task.state
        }

        if (task.state == TaskState.QUEUED || task.state == TaskState.PREPARING) {
            transition(task, TaskState.PREPARING)
        }
        if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
            transition(task, TaskState.WAITING_APPROVAL)
            persist()
            return task.state
        }
        if (task.dependsOn.isNotEmpty() && !TaskScheduler.isReady(task.dependsOn, { tasks[it] })) {
            transition(task, TaskState.WAITING_DEPENDENCY)
            persist()
            return task.state
        }

        if (running.size >= maxConcurrent) {
            transition(task, TaskState.APPROVED)
            transition(task, TaskState.QUEUED)
            enqueue(task)
            return task.state
        }

        transition(task, TaskState.APPROVED)
        val initiated = startTaskLocked(task, "started")
        persist()
        initiated
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

            // Dependency completion must never bypass the same risk/approval gate
            // used by normal task execution. A sensitive dependent becomes
            // WAITING_APPROVAL instead of being auto-started by the wake-up path.
            if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
                // A dependency wake-up still requires approval, but must enter
                // WAITING_APPROVAL directly because APPROVED is the post-approval
                // execution state and cannot transition back to an approval gate.
                transition(task, TaskState.WAITING_APPROVAL)
                persist()
                return
            }

            if (running.size >= maxConcurrent) {
                transition(task, TaskState.APPROVED)
                transition(task, TaskState.QUEUED)
                enqueue(task)
                return
            }
            transition(task, TaskState.APPROVED)
            startTaskLocked(task, "dependency_ready")
            persist()
        }
    }

    // --- Execution lifecycle hooks ---

    /** Re-evaluate dependents after a dependency changes state.
     * Dependents remain WAITING_DEPENDENCY unless every dependency completes.
     * This allows an explicit retry of a failed dependency to unlock the chain.
     */
    private fun onTaskTerminal(taskId: String) {
        wakeReadyDependents()
    }

    private suspend fun execute(task: org.aethra.core.task.Task) {
        // Startup gate: if the task was cancelled or paused between the start
        // dispatch and the first coroutine run, do not begin a fresh cycle.
        // A cancelled task must never execute work outside the approval chain.
        if (task.isCancelled() || task.state != TaskState.RUNNING) return
        // Atomic claim of the one-shot cycle: exactly one racing duplicate
        // proceeds; losers return and leave the guard false so a later pause/
        // resume or explicit retry can start a clean cycle.
        if (!task.claimExecution()) return

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
            val executionError = returnedFailure ?: failure

            if (executionError == null) {
                task.complete()
                eventBus.publish(TaskEvent(
                    "execution_completed", System.currentTimeMillis(), task.id,
                    TaskState.RUNNING, TaskState.COMPLETED,
                    mapOf("reason" to "success", "attempts" to (retriesUsed + 1).toString())
                ))
                onTaskTerminal(task.id)
                // Terminal state must be durable at once; otherwise a process
                // restart restores RUNNING, Task.restore() maps it back to
                // QUEUED and an already-finished task executes again.
                persist()
                return
            }

            if (returnedFailure is CancellationException) {
                // A runner may report cancellation through Result.failure().
                // That is a terminal outcome, not retryable work: leave the
                // task terminal instead of lingering as RUNNING with no job
                // attached (the UI would show a false [RUNNING] forever).
                val wasTerminal = task.isTerminal()
                if (!wasTerminal) task.cancel()
                persist()
                if (!wasTerminal) {
                    eventBus.publish(TaskEvent(
                        "execution_cancelled", System.currentTimeMillis(), task.id,
                        TaskState.RUNNING, TaskState.CANCELLED,
                        mapOf("reason" to (returnedFailure.message ?: "runner cancelled"))
                    ))
                    onTaskTerminal(task.id)
                }
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
            // Terminal FAILED state must be durable for the same reason as COMPLETED
            // (see above): restore() recovers RUNNING as a fresh QUEUED execution.
            persist()
            return
        }
    }

    fun approve(taskId: String, granted: Boolean, reason: String) {
        val shouldDrain = synchronized(lock) {
            val task = tasks[taskId] ?: return
            if (task.state != TaskState.WAITING_APPROVAL) return

            val previous = task.state
            if (!granted) {
                transition(task, TaskState.CANCELLED)
                persist()
                eventBus.publish(
                    TaskEvent(
                        "approval",
                        System.currentTimeMillis(),
                        task.id,
                        previous,
                        task.state,
                        mapOf("granted" to "false", "reason" to reason)
                    )
                )
                false
            } else {
                transition(task, TaskState.APPROVED)
                val canStart = running.size < maxConcurrent
                if (canStart) {
                    startTaskLocked(task, "approval_granted")
                } else {
                    transition(task, TaskState.QUEUED)
                    enqueue(task)
                }
                persist()
                eventBus.publish(
                    TaskEvent(
                        "approval",
                        System.currentTimeMillis(),
                        task.id,
                        previous,
                        task.state,
                        mapOf("granted" to "true", "reason" to reason)
                    )
                )
                !canStart
            }
        }

        if (shouldDrain) drainQueue()
    }

    fun cancel(taskId: String) {
        val wasRunning = synchronized(lock) {
            val task = tasks[taskId] ?: return
            if (task.state !in setOf(
                    TaskState.RUNNING,
                    TaskState.PAUSED,
                    TaskState.WAITING_APPROVAL,
                    TaskState.WAITING_DEPENDENCY,
                    TaskState.QUEUED
                )) {
                return
            }

            val previousState = task.state
            val runningNow = previousState == TaskState.RUNNING
            task.cancel()
            pending.remove(taskId)
            if (runningNow) {
                // Keep the job/running-slot update atomic with the task transition.
                // The coroutine completion handler also takes this lock, so it can
                // safely reconcile the slot after cancellation.
                jobs[taskId]?.cancel()
                running.remove(taskId)
            }
            persist()
            eventBus.publish(TaskEvent("cancel", System.currentTimeMillis(), task.id, previousState, task.state))
            runningNow
        }

        onTaskTerminal(taskId)
        if (wasRunning) drainQueue()
    }

    fun pause(taskId: String) {
        synchronized(lock) {
            val task = tasks[taskId] ?: return
            if (task.state != TaskState.RUNNING) return
            transition(task, TaskState.PAUSED)
            task.isExecuted = false
            jobs[taskId]?.cancel()
            jobs.remove(taskId)
            running.remove(taskId)
            persist()
            eventBus.publish(TaskEvent("pause", System.currentTimeMillis(), task.id, TaskState.RUNNING, TaskState.PAUSED))
        }
        drainQueue()
    }

    fun resume(taskId: String) {
        synchronized(lock) {
            val task = tasks[taskId] ?: return
            if (task.state != TaskState.PAUSED) return
            transition(task, TaskState.QUEUED)
            persist()
            eventBus.publish(TaskEvent("resume", System.currentTimeMillis(), task.id, TaskState.PAUSED, TaskState.QUEUED))
        }
        run(taskId)
    }

    fun retry(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.state == TaskState.FAILED) {
            task.resetForRetry()
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
        scope.launch {
            restored.await()
            persistenceMutex.withLock {
                persistence.saveAll(allTasks())
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}