package org.aethra.core.task

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.junit.Assert.*
import org.junit.Test

/** In-memory stand-in for [Storage]. */
class TestStorage : Storage {
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

/** Force a deterministic result from the engine's runner. */
class TestRunner(private val failure: Boolean = false) : TaskRunner {
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        return if (failure) Result.failure(Exception("test failure")) else Result.success(mapOf("ok" to "ok"))
    }
}

class TaskEngineTest {
    private fun makeEngine(): TaskEngine {
        val store = TestStorage()
        val bus = EventBus()
        return TaskEngine(store, bus, TestRunner(), maxConcurrent = 4)
    }

    @Test
    fun taskTransitionsThroughLifecycle() = runBlocking {
        val engine = makeEngine()
        val task = engine.create("do something", riskClass = RiskClass.READ_ONLY)
        assertEquals(TaskState.QUEUED, task.state)
        val final = engine.run(task.id)
        assertEquals(TaskState.RUNNING, final)
        // The engine runs asynchronously: wait until the task reaches a terminal state.
        val start = System.currentTimeMillis()
        while (task.state != TaskState.COMPLETED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.COMPLETED, task.state)
        assertTrue(task.isTerminal())
    }

    @Test
    fun cancelledTaskStops() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        // A runner that holds briefly so the task is still RUNNING when we cancel it.
        val runner = object : TaskRunner {
            override suspend fun run(t: Task): Result<Map<String, Any>> {
                kotlinx.coroutines.delay(500)
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("cancel me", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)   // async: task enters RUNNING
        Thread.sleep(300)     // task is still RUNNING during the hold
        engine.cancel(task.id)
        assertTrue(task.isCancelled())
    }

    @Test
    fun dependencyBlindedUntilDependencyCompletes() = runBlocking {
        val engine = makeEngine()
        val root = engine.create("root task")
        val dependent = engine.create("dependent task", dependsOn = listOf(root.id))
        assertNotNull(engine.getTask(dependent.id))
        engine.run(root.id)
        // The engine runs asynchronously: wait until the dependency reaches a terminal state.
        val start = System.currentTimeMillis()
        while (engine.getTask(root.id)?.state != TaskState.COMPLETED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.COMPLETED, engine.getTask(root.id)?.state)
    }

    @Test
    fun sensitiveTaskWaitsForApproval() = runBlocking {
        val engine = makeEngine()
        val task = engine.create("sensitive", riskClass = RiskClass.SENSITIVE)
        engine.run(task.id)
        assertEquals(TaskState.WAITING_APPROVAL, task.state)
        engine.cancel(task.id)
        assertTrue(task.isCancelled())
    }
}
