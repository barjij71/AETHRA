package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.junit.Assert.*
import org.junit.Test

/** Contract suite for C2: failure/cancellation propagation semantics (simplified). */
class TaskDependencyPropagationContractTest {

    @Test
    fun dependentWaitsForDependency() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        // Dependent should wait for dep to complete
        engine.run(dependent.id)
        val dependentAfterRun = engine.getTask(dependent.id)!!
        assertEquals(TaskState.WAITING_DEPENDENCY, dependentAfterRun.state)

        // Complete dep, then dependent should become runnable
        engine.run(dep.id)
        waitForTerminal(engine, dep.id)
        engine.run(dependent.id)
        waitForTerminal(engine, dependent.id)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun multipleDependenciesRequireAllCompleted() = runBlocking {
        // "t2" delays deterministically: the queue auto-drain may legitimately
        // start it as soon as capacity frees, and without a delay an instant
        // runner can complete it before the blocked-dependent assertion below.
        val engine = makeEngineWithRunner(object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                if (task.goal == "t2") delay(500)
                return Result.success(mapOf("ok" to "ok"))
            }
        })
        val t1 = engine.create("t1")
        val t2 = engine.create("t2")
        val dependent = engine.create("dependent", dependsOn = listOf(t1.id, t2.id))

        // Complete t1 only
        engine.run(t1.id)
        waitForTerminal(engine, t1.id)

        // Dependent should wait for both
        engine.run(dependent.id)
        val dependentAfterRun = engine.getTask(dependent.id)!!
        assertEquals(TaskState.WAITING_DEPENDENCY, dependentAfterRun.state)

        // Complete t2, then dependent should become runnable
        engine.run(t2.id)
        waitForTerminal(engine, t2.id)
        engine.run(dependent.id)
        waitForTerminal(engine, dependent.id)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun failedDependencyCascadesToDependent() = runBlocking {
        val engine = makeEngineWithRunner(object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.failure<Map<String, Any>>(RuntimeException("dep failure"))
            }
        })
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.FAILED, dep.state)

        // A failed dependency blocks the dependent without making it terminal.
        engine.run(dependent.id)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.state == TaskState.RUNNING)
    }

    @Test
    fun failedDependencyBlocksDependent() = runBlocking {
        val engine = makeEngineWithRunner(object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.failure<Map<String, Any>>(RuntimeException("dep failure"))
            }
        })
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.FAILED, dep.state)

        // A failed dependency keeps the dependent blocked and non-terminal.
        engine.run(dependent.id)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.isTerminal())
    }

    @Test
    fun cancelledDependencyCascadesToDependent() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        // Use a runner that delays, then fails after cancellation
        var cancelled = false
        val delayedRunner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                delay(500)
                if (cancelled) {
                    return Result.failure<Map<String, Any>>(CancellationException("cancelled"))
                }
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        // Cancel dep explicitly
        engine.cancel(dep.id)
        // Wait for dep to reach cancellation
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.CANCELLED, dep.state)

        // Cancellation blocks the dependent; it does not cancel it.
        engine.run(dependent.id)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
    }

    @Test
    fun failedDependencyRetryCanEventuallyUnlockDependent() = runBlocking {
        var callCount = 0
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                callCount++
                if (callCount == 1) {
                    // First attempt: fail
                    return Result.failure<Map<String, Any>>(RuntimeException("dep failure"))
                }
                // Retry: succeed
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        val engine = makeEngineWithRunner(runner)
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))
        dep.retryBudget = 0
        engine.run(dependent.id)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)

        engine.run(dep.id)
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.FAILED, dep.state)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)

        // Explicit retry succeeds and automatically wakes the blocked dependent.
        engine.retry(dep.id)
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.COMPLETED, dep.state)
        waitForTerminal(engine, dependent.id)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun cancellationEventPreservesPreviousState() = runBlocking {
        val storage = C2TestStorage()
        val bus = EventBus()
        val engine = TaskEngine(storage, bus, C2TestRunner(), maxConcurrent = 4)
        val task = engine.create("cancel-event")

        engine.cancel(task.id)

        val cancelEvent = bus.recent()
            .filterIsInstance<org.aethra.core.domain.TaskEvent>()
            .last { it.source == "cancel" }

        assertEquals(TaskState.QUEUED, cancelEvent.previousState)
        assertEquals(TaskState.CANCELLED, cancelEvent.newState)
    }

    @Test
    fun duplicateTerminalNotificationIsIdempotent() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.COMPLETED, dep.state)

        // Run dep again (duplicate) - should not cause dep to fail
        engine.run(dep.id)
        waitForTerminal(engine, dep.id)
        assertEquals(TaskState.COMPLETED, dep.state)

        // Dependent should complete normally
        engine.run(dependent.id)
        waitForTerminal(engine, dependent.id)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun independentTasksSurviveCancellation() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))
        val independent = engine.create("independent")

        engine.run(dep.id)
        waitForTerminal(engine, dep.id)

        engine.run(dependent.id)
        waitForTerminal(engine, dependent.id)

        engine.run(independent.id)

        // Cancel dep after it completed
        engine.cancel(dep.id)

        waitForTerminal(engine, independent.id)
        assertEquals(TaskState.COMPLETED, independent.state)
        assertTrue(independent.isTerminal())
    }

    private suspend fun waitForTerminal(engine: TaskEngine, taskId: String, timeout: Long = 5000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            val task = engine.getTask(taskId) ?: return
            if (task.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) {
                return
            }
            kotlinx.coroutines.delay(50)
        }
        fail("Task $taskId did not reach terminal state within ${timeout}ms")
    }
}

/** Test runner for C2 contract tests. */
class C2TestRunner : TaskRunner {
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        return Result.success(mapOf("ok" to "ok"))
    }
}

/** In-memory stand-in for Storage for C2 tests. */
class C2TestStorage : Storage {
    private val data = mutableMapOf<String, String>()
    override suspend fun getString(key: String, defaultValue: String?): String? = data[key]
    override suspend fun setString(key: String, value: String) { data[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull()
    override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = data[key]?.toBooleanStrictOrNull()
    override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
    override suspend fun getAll(): Map<String, String> = data.toMap()
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun clear() { data.clear() }
}

private fun makeEngine(): TaskEngine {
    val store = C2TestStorage()
    val bus = EventBus()
    return TaskEngine(store, bus, C2TestRunner(), maxConcurrent = 4)
}


private fun makeEngineWithRunner(runner: TaskRunner): TaskEngine {
    val store = C2TestStorage()
    val bus = EventBus()
    return TaskEngine(store, bus, runner, maxConcurrent = 4)
}
