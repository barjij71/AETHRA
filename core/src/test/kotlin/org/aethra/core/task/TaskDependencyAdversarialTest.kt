package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.domain.TaskEvent
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test

/**
 * Adversarial contract tests for Phase C Dependency Orchestration.
 *
 * These tests target edge cases and concurrent execution scenarios that were
 * not covered by the existing test suites. Each test proves a specific
 * contract invariant from the AETHRA PHASE C ADVERSARIAL CONTRACT REVIEW.
 */
class TaskDependencyAdversarialTest {

    private fun makeEngine(): TaskEngine {
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        return TaskEngine(
            testStorage(),
            EventBus(),
            runner,
            maxConcurrent = 4
        )
    }

    private fun testStorage(): Storage {
        return object : Storage {
            private val data = java.util.concurrent.ConcurrentHashMap<String, String>()
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
    }

    // -----------------------------------------------------------------------
    // Contract D: Dependency retry that eventually completes wakes dependent
    // -----------------------------------------------------------------------

    @Test
    fun dependentWakesAfterDependencyRetrySucceeds() = runBlocking {
        var depCallCount = 0
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                depCallCount++
                return if (depCallCount == 1) {
                    Result.failure(TaskExecutionException("first attempt fails"))
                } else {
                    Result.success(mapOf("ok" to "ok"))
                }
            }
        }
        val engine = TaskEngine(testStorage(), EventBus(), runner, maxConcurrent = 4)

        val dep = engine.create("dependency")
        dep.retryBudget = 2
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)

        engine.run(dep.id)
        val start = System.currentTimeMillis()
        while (!dependent.isTerminal() && System.currentTimeMillis() - start < 5000) {
            delay(50)
        }

        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    // -----------------------------------------------------------------------
    // Contract H: Persistence/restore preserves dependency semantics
    // -----------------------------------------------------------------------

    @Test
    fun persistenceRestorePreservesWaitingDependencyAndWake() = runBlocking {
        val storage = testStorage()

        // Engine 1: create dependency + dependent, persist (happens automatically in create)
        val engine1 = TaskEngine(storage, EventBus(), object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.success(mapOf("ok" to "ok"))
            }
        }, maxConcurrent = 4)

        val dep = engine1.create("dependency")
        val dependent = engine1.create("dependent", dependsOn = listOf(dep.id))

        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)

        // Wait for async persist() coroutine to write to storage
        val journalKey = "aethra.tasks.v1"
        val persistStart = System.currentTimeMillis()
        var encoded: String? = null
        while (encoded == null && System.currentTimeMillis() - persistStart < 5000) {
            encoded = storage.getString(journalKey, null)
            if (encoded == null) delay(50)
        }
        assertNotNull("Persist should have written to storage", encoded)

        // Engine 2: simulate restart with same storage
        val engine2 = TaskEngine(storage, EventBus(), object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.success(mapOf("ok" to "ok"))
            }
        }, maxConcurrent = 4)

        // Wait for engine2's async load() to populate its in-memory index
        // NOTE: load() auto-runs QUEUED tasks, so dep may already be COMPLETED
        val loadStart = System.currentTimeMillis()
        while (engine2.getTask(dep.id) == null && System.currentTimeMillis() - loadStart < 5000) {
            delay(50)
        }
        val restoredDep = engine2.getTask(dep.id)!!
        val restoredDependent = engine2.getTask(dependent.id)!!

        assertNotNull("Dependency should be restored from storage", restoredDep)
        assertNotNull("Dependent should be restored from storage", restoredDependent)

        // Dependency relationship must be preserved across restore
        assertEquals(listOf(dep.id), restoredDependent.dependsOn)

        // Both tasks should eventually complete: load() auto-runs QUEUED tasks,
        // and dependency completion wakes dependents via wakeReadyDependents()
        val start = System.currentTimeMillis()
        while (!restoredDep.isTerminal() || !restoredDependent.isTerminal()) {
            if (System.currentTimeMillis() - start > 10000) fail("Tasks did not complete after restore")
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, restoredDep.state)
        assertEquals(TaskState.COMPLETED, restoredDependent.state)
    }

    // -----------------------------------------------------------------------
    // Contract G: Concurrent dependency completion -> single wake-up
    // -----------------------------------------------------------------------

    @Test
    fun concurrentDependencyCompletionWakesDependentOnce() = runBlocking {
        val bus = EventBus()
        val events = mutableListOf<String>()
        bus.subscribe(TaskEvent::class.java) { event -> events += event.source }

        val engine = TaskEngine(testStorage(), bus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.success(mapOf("ok" to "ok"))
            }
        }, maxConcurrent = 4)

        val dep1 = engine.create("dep1")
        val dep2 = engine.create("dep2")
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id))

        engine.run(dep1.id)
        engine.run(dep2.id)

        val start = System.currentTimeMillis()
        while (!dep1.isTerminal() || !dep2.isTerminal()) {
            if (System.currentTimeMillis() - start > 5000) fail("Dependencies did not complete")
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep1.state)
        assertEquals(TaskState.COMPLETED, dep2.state)

        val start2 = System.currentTimeMillis()
        while (!dependent.isTerminal() && System.currentTimeMillis() - start2 < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dependent.state)

        val wakeCount = events.count { it == "dependency_wake" }
        assertEquals(1, wakeCount)
    }

    // -----------------------------------------------------------------------
    // Contract I: maxConcurrent enforced when multiple blocked tasks ready simultaneously
    // -----------------------------------------------------------------------

    @Test
    fun maxConcurrentEnforcedAfterMultipleDependentsWake() = runBlocking {
        val engine = TaskEngine(testStorage(), EventBus(), object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                delay(200)
                return Result.success(mapOf("ok" to "ok"))
            }
        }, maxConcurrent = 2)

        val dep = engine.create("dependency")
        val dep1 = engine.create("dependent1", dependsOn = listOf(dep.id))
        val dep2 = engine.create("dependent2", dependsOn = listOf(dep.id))
        val dep3 = engine.create("dependent3", dependsOn = listOf(dep.id))

        engine.run(dep.id)
        val start = System.currentTimeMillis()
        while (!dep.isTerminal() && System.currentTimeMillis() - start < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep.state)

        delay(100)

        val runningCount = engine.allTasks().count { it.state == TaskState.RUNNING }
        val queuedCount = engine.allTasks().count { it.state == TaskState.QUEUED }

        assertEquals(2, runningCount)
        assertEquals(1, queuedCount)

        val start2 = System.currentTimeMillis()
        while (!dep1.isTerminal() || !dep2.isTerminal() || !dep3.isTerminal()) {
            if (System.currentTimeMillis() - start2 > 10000) fail("Not all dependents completed")
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep1.state)
        assertEquals(TaskState.COMPLETED, dep2.state)
        assertEquals(TaskState.COMPLETED, dep3.state)
    }

}
