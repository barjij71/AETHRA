package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test

class TaskDependencySchedulerContractTest {

    private fun makeEngine(runnerOverride: TaskRunner? = null): TaskEngine {
        val runner = runnerOverride ?: object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                if (task.goal == "dep2" || task.goal == "dep1_with_delay") {
                    delay(500)
                }
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        return TaskEngine(
            object : Storage {
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
            },
            EventBus(),
            runner,
            maxConcurrent = 4
        )
    }

    @Test
    fun dependentStartsInWaitingDependencyStateAtCreation() {
        val engine = makeEngine()
        val dependency = engine.create("dependency")
        val dependent = engine.create("dependent", dependsOn = listOf(dependency.id))

        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
    }

    @Test
    fun dependentWaitsForRunningDependency() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dependency")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        Thread.sleep(300)

        // Dependent should NOT be RUNNING while dependency is still RUNNING
        assertFalse(dependent.state == TaskState.RUNNING)
        assertFalse(dependent.isTerminal())
    }

    @Test
    fun dependencyCompletionWakesDependent() = runBlocking {
        val engine = makeEngine()
        val dep1 = engine.create("dep1")
        val dep2 = engine.create("dep2")
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id))

        // Run dependencies and dependent
        engine.run(dep1.id)
        engine.run(dep2.id)
        engine.run(dependent.id)

        // Ensure dependent is in WAITING_DEPENDENCY state (dep2 has 500ms delay, not completed yet)
        var stateFound = false
        val startWait = System.currentTimeMillis()
        do {
            if (dependent.state == TaskState.WAITING_DEPENDENCY) {
                stateFound = true
                break
            }
            kotlinx.coroutines.delay(50)
        } while (System.currentTimeMillis() - startWait < 5000)
        assertTrue("Dependent should have reached WAITING_DEPENDENCY state: ${dependent.state}", stateFound)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)

        // Complete dep1 (already completed)
        engine.getTask(dep1.id)?.complete()
        Thread.sleep(300)

        // Still waiting (dep2 has 500ms delay, not completed yet)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.isTerminal())

        // Complete dep2 - wait for it to complete naturally (500ms delay from runner)
        // This triggers onTaskTerminal() which wakes up dependent
        Thread.sleep(500)

        // Dependent should now be COMPLETED (woken up by dep2 completion)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun failedDependencyBlocksDependent() = runBlocking {
        val failingRunner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> =
                Result.failure(RuntimeException("dep failed"))
        }
        val engine = makeEngine(failingRunner)
        val dep = engine.create("dependency")
        dep.retryBudget = 0
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))
        engine.run(dependent.id)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)

        engine.run(dep.id)
        val started = System.currentTimeMillis()
        while (!dep.isTerminal() && System.currentTimeMillis() - started < 5000) delay(20)
        assertEquals(TaskState.FAILED, dep.state)

        // A failed dependency leaves its dependent blocked and recoverable.
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.isTerminal())
    }

    @Test
    fun cancelledDependencyBlocksDependent() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dependency")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        engine.cancel(dep.id)
        Thread.sleep(300)

        // Dependent should NOT have executed
        assertFalse(dependent.state == TaskState.RUNNING)
        assertFalse(dependent.isTerminal())
        assertTrue(dependent.state == TaskState.QUEUED || dependent.state == TaskState.WAITING_DEPENDENCY)
    }

    @Test
    fun independentTasksWorkNormally() = runBlocking {
        val engine = makeEngine()
        val independent = engine.create("independent")

        // Independent should start immediately (no dependencies)
        val initial = engine.run(independent.id)
        assertEquals(TaskState.RUNNING, initial)

        // Independent should complete quickly (no dependencies to wait for)
        val start = System.currentTimeMillis()
        while (independent.state != TaskState.COMPLETED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.COMPLETED, independent.state)
    }

    @Test
    fun idempotentWakeUpPreventsDoubleExecution() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dependency")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        Thread.sleep(300)

        // Complete dependency
        engine.getTask(dep.id)?.complete()
        Thread.sleep(300)

        // Run dependent
        engine.run(dependent.id)
        Thread.sleep(300)

        // Dependent should be COMPLETED (not executed twice)
        assertEquals(TaskState.COMPLETED, dependent.state)

        // Simulate event re-firing: complete dep again (should not cause re-run)
        engine.getTask(dep.id)?.complete()
        Thread.sleep(300)

        // Should still be COMPLETED (idempotent)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun dependentDoesNotRunWhenDependencyFailed() = runBlocking {
        val failingRunner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> =
                Result.failure(RuntimeException("dep failed"))
        }
        val engine = makeEngine(failingRunner)
        val dep = engine.create("dependency")
        dep.retryBudget = 0
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))
        engine.run(dependent.id)
        engine.run(dep.id)
        val started = System.currentTimeMillis()
        while (!dep.isTerminal() && System.currentTimeMillis() - started < 5000) delay(20)

        // The dependent remains blocked rather than being failed or executed.
        assertEquals(TaskState.FAILED, dep.state)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.state == TaskState.RUNNING)
        assertFalse(dependent.isTerminal())
    }

    @Test
    fun dependentDoesNotRunWhenDependencyCancelled() = runBlocking {
        val engine = makeEngine()
        val dep = engine.create("dependency")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        engine.cancel(dep.id)
        Thread.sleep(300)

        // Dependent should NOT be RUNNING
        assertFalse(dependent.state == TaskState.RUNNING)
        assertFalse(dependent.isTerminal())
    }
    @Test
    fun scheduleOrdersDependenciesBeforeDependents() {
        val first = Task("first")
        val second = Task("second", dependsOn = listOf(first.id))
        val third = Task("third", dependsOn = listOf(second.id))
        val ordered = TaskScheduler.schedule(listOf(third, first, second)) { id ->
            listOf(first, second, third).find { it.id == id }
        }
        assertEquals(listOf(first.id, second.id, third.id), ordered.map { it.id })
    }

    @Test
    fun schedulePreservesRelativeOrderOfIndependentTasks() {
        val first = Task("first")
        val second = Task("second")
        val third = Task("third", dependsOn = listOf(first.id))
        val ordered = TaskScheduler.schedule(listOf(third, second, first)) { id ->
            listOf(first, second, third).find { it.id == id }
        }
        assertEquals(listOf(second.id, first.id, third.id), ordered.map { it.id })
    }

    @Test
    fun selfDependencyIsRejectedAsCycle() {
        val task = Task("self")
        val tasks = mapOf(task.id to task)
        assertTrue(TaskScheduler.wouldCreateCycle(task.id, listOf(task.id)) { tasks[it] })
    }

    @Test
    fun indirectDependencyCycleIsDetected() {
        val a = Task("a")
        val b = Task("b", dependsOn = listOf(a.id))
        val c = Task("c", dependsOn = listOf(b.id))
        val tasks = mapOf(a.id to a, b.id to b, c.id to c)

        assertTrue(TaskScheduler.wouldCreateCycle(a.id, listOf(c.id)) { tasks[it] })
    }

    @Test
    fun acyclicDependencyGraphRemainsAllowed() {
        val a = Task("a")
        val b = Task("b", dependsOn = listOf(a.id))
        val c = Task("c", dependsOn = listOf(b.id))
        val tasks = mapOf(a.id to a, b.id to b, c.id to c)

        assertTrue(!TaskScheduler.wouldCreateCycle("new", listOf(c.id)) { tasks[it] })
    }

}
