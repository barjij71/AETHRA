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

class TaskDependencyOrchestrationTest {

    private fun makeEngine(): TaskEngine {
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                if (task.goal == "slow") {
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
    fun getDependencyStatusReturnsStatesOfAllDependencies() {
        val engine = makeEngine()
        val dep1 = engine.create("dep1")
        val dep2 = engine.create("dep2")
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id))

        val status = engine.getDependencyStatus(dependent.id)
        assertEquals(2, status.size)
        assertEquals(TaskState.QUEUED, status[dep1.id])
        assertEquals(TaskState.QUEUED, status[dep2.id])
    }

    @Test
    fun getBlockedByReturnsIncompleteDependencies() {
        val engine = makeEngine()
        val dep1 = engine.create("dep1")
        val dep2 = engine.create("dep2")
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id))

        val blockedBy = engine.getBlockedBy(dependent.id)
        assertEquals(2, blockedBy.size)
        assertTrue(blockedBy.contains(dep1.id))
        assertTrue(blockedBy.contains(dep2.id))
    }

    @Test
    fun getBlockedByReturnsEmptyWhenAllDependenciesCompleted() = runBlocking {
        val engine = makeEngine()
        val dep1 = engine.create("dep1")
        val dep2 = engine.create("dep2")
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id))

        engine.run(dep1.id)
        engine.run(dep2.id)
        val start = System.currentTimeMillis()
        while (!dep1.isTerminal() || !dep2.isTerminal()) {
            if (System.currentTimeMillis() - start > 5000) fail("dependencies did not complete")
            delay(50)
        }

        val blockedBy = engine.getBlockedBy(dependent.id)
        assertTrue(blockedBy.isEmpty())
    }

    @Test
    fun getBlockedByReturnsEmptyForTaskWithoutDependencies() {
        val engine = makeEngine()
        val independent = engine.create("independent")

        assertTrue(engine.getBlockedBy(independent.id).isEmpty())
        assertTrue(engine.getDependencyStatus(independent.id).isEmpty())
    }

    @Test
    fun dependencyBlockedEventPublishedWhenDependentCreated() {
        val engine = makeEngine()
        val bus = EventBus()
        // Recreate engine with bus to capture events
        val engine2 = TaskEngine(
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
            bus,
            object : TaskRunner {
                override suspend fun run(task: Task): Result<Map<String, Any>> {
                    return Result.success(mapOf("ok" to "ok"))
                }
            },
            maxConcurrent = 4
        )
        val dep = engine2.create("dep")
        val events = mutableListOf<String>()
        bus.subscribe(TaskEvent::class.java) { event ->
            events += event.source
        }
        engine2.create("dependent", dependsOn = listOf(dep.id))

        assertTrue("Expected dependency_blocked event, got: $events",
            events.any { it == "dependency_blocked" })
    }

    @Test
    fun dependencyWakeEventPublishedWhenDependencyCompletes() = runBlocking {
        val bus = EventBus()
        val engine = TaskEngine(
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
            bus,
            object : TaskRunner {
                override suspend fun run(task: Task): Result<Map<String, Any>> {
                    return Result.success(mapOf("ok" to "ok"))
                }
            },
            maxConcurrent = 4
        )
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        val events = mutableListOf<String>()
        bus.subscribe(TaskEvent::class.java) { event ->
            events += event.source
        }

        engine.run(dep.id)
        val start = System.currentTimeMillis()
        while (!dep.isTerminal() && System.currentTimeMillis() - start < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep.state)

        // Wait for dependency_wake event to be published
        val wakeStart = System.currentTimeMillis()
        while (!events.any { it == "dependency_wake" } && System.currentTimeMillis() - wakeStart < 5000) {
            delay(50)
        }
        assertTrue("Expected dependency_wake event, got: $events",
            events.any { it == "dependency_wake" })
    }

    @Test
    fun dependentDoesNotExecuteEarlyWithMultipleDependencies() = runBlocking {
        val engine = makeEngine()
        val dep1 = engine.create("dep1")
        val dep2 = engine.create("slow") // slow runner, 500ms delay
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id))

        engine.run(dep1.id)
        engine.run(dep2.id)
        engine.run(dependent.id)

        // Wait for dep1 to complete (fast)
        val start1 = System.currentTimeMillis()
        while (!dep1.isTerminal() && System.currentTimeMillis() - start1 < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep1.state)

        // Dependent should still be WAITING_DEPENDENCY because dep2 is not complete
        Thread.sleep(300)
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.state == TaskState.RUNNING)
        assertFalse(dependent.isTerminal())
    }

    @Test
    fun multipleDependenciesAllMustCompleteBeforeDependent() = runBlocking {
        val engine = makeEngine()
        val dep1 = engine.create("dep1")
        val dep2 = engine.create("slow")
        val dep3 = engine.create("dep3")
        val dependent = engine.create("dependent", dependsOn = listOf(dep1.id, dep2.id, dep3.id))

        engine.run(dep1.id)
        engine.run(dep2.id)
        engine.run(dep3.id)
        engine.run(dependent.id)

        // Complete dep1 and dep3 immediately
        engine.getTask(dep1.id)?.complete()
        engine.getTask(dep3.id)?.complete()
        Thread.sleep(300)

        // Still waiting for dep2
        assertEquals(TaskState.WAITING_DEPENDENCY, dependent.state)
        assertFalse(dependent.isTerminal())

        // Wait for dep2 to complete naturally (500ms delay)
        val start = System.currentTimeMillis()
        while (!dep2.isTerminal() && System.currentTimeMillis() - start < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep2.state)

        // Now dependent should complete
        val start2 = System.currentTimeMillis()
        while (!dependent.isTerminal() && System.currentTimeMillis() - start2 < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

    @Test
    fun getDependencyStatusReturnsEmptyForTaskWithoutDependencies() {
        val engine = makeEngine()
        val independent = engine.create("independent")

        val status = engine.getDependencyStatus(independent.id)
        assertTrue(status.isEmpty())
    }

    @Test
    fun dependencyBlockedAndWakeAreIdempotent() = runBlocking {
        val bus = EventBus()
        val engine = TaskEngine(
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
            bus,
            object : TaskRunner {
                override suspend fun run(task: Task): Result<Map<String, Any>> {
                    return Result.success(mapOf("ok" to "ok"))
                }
            },
            maxConcurrent = 4
        )
        val dep = engine.create("dep")
        val dependent = engine.create("dependent", dependsOn = listOf(dep.id))

        val events = mutableListOf<String>()
        bus.subscribe(TaskEvent::class.java) { event ->
            events += event.source
        }

        // Complete dep through the engine (triggers onTaskTerminal → wakeReadyDependents → wakeTask)
        engine.run(dep.id)
        val startDep = System.currentTimeMillis()
        while (!dep.isTerminal() && System.currentTimeMillis() - startDep < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dep.state)

        // Wait for dependent to complete (woken by dep completion via dependency_wake)
        val startDep2 = System.currentTimeMillis()
        while (!dependent.isTerminal() && System.currentTimeMillis() - startDep2 < 5000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, dependent.state)

        // Run dep again (idempotent: already COMPLETED, should not re-wake dependent)
        engine.run(dep.id)
        Thread.sleep(300)

        // dependency_wake should appear exactly once
        val wakeCount = events.count { it == "dependency_wake" }
        assertEquals(1, wakeCount)
        assertEquals(TaskState.COMPLETED, dependent.state)
    }

}
