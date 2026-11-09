package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskConcurrencyContractTest {

    @Test
    fun maxConcurrentLimitsExecutionAndDrainsQueuedTasks() = runBlocking {
        val started = mutableListOf<String>()
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                started += task.goal
                delay(300)
                return Result.success(mapOf("ok" to true))
            }
        }
        val engine = TaskEngine(ConcurrencyTestStorage(), EventBus(), runner, maxConcurrent = 1)
        val first = engine.create("first")
        val second = engine.create("second")

        assertEquals(TaskState.RUNNING, engine.run(first.id))
        assertEquals(TaskState.QUEUED, engine.run(second.id))
        assertEquals(1, engine.activeCount)

        val firstDeadline = System.currentTimeMillis() + 3000
        while (first.state != TaskState.COMPLETED && System.currentTimeMillis() < firstDeadline) {
            delay(20)
        }
        assertEquals(TaskState.COMPLETED, first.state)

        val secondDeadline = System.currentTimeMillis() + 3000
        while (second.state != TaskState.COMPLETED && System.currentTimeMillis() < secondDeadline) {
            delay(20)
        }
        assertEquals(TaskState.COMPLETED, second.state)
        assertEquals(listOf("first", "second"), started)
        assertEquals(0, engine.activeCount)
    }

    @Test
    fun queuedTaskDoesNotExecuteBeforeCapacityIsReleased() = runBlocking {
        val calls = mutableListOf<String>()
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                calls += task.goal
                delay(if (task.goal == "first") 400 else 20)
                return Result.success(emptyMap())
            }
        }
        val engine = TaskEngine(ConcurrencyTestStorage(), EventBus(), runner, maxConcurrent = 1)
        val first = engine.create("first")
        val second = engine.create("second")

        engine.run(first.id)
        engine.run(second.id)
        delay(100)

        assertEquals(listOf("first"), calls)
        assertEquals(TaskState.QUEUED, second.state)

        val deadline = System.currentTimeMillis() + 3000
        while (second.state != TaskState.COMPLETED && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertEquals(TaskState.COMPLETED, second.state)
        assertTrue(calls.indexOf("second") > calls.indexOf("first"))
    }
    @Test
    fun cancellingRunningTaskReleasesSlotAndStartsQueuedTask() = runBlocking {
        val calls = mutableListOf<String>()
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                calls += task.goal
                delay(5000)
                return Result.success(emptyMap())
            }
        }
        val engine = TaskEngine(ConcurrencyTestStorage(), EventBus(), runner, maxConcurrent = 1)
        val first = engine.create("first")
        val second = engine.create("second")

        engine.run(first.id)
        engine.run(second.id)
        assertEquals(TaskState.QUEUED, second.state)

        engine.cancel(first.id)
        assertEquals(TaskState.CANCELLED, first.state)

        val deadline = System.currentTimeMillis() + 3000
        while (second.state != TaskState.RUNNING && System.currentTimeMillis() < deadline) {
            delay(20)
        }

        assertEquals(TaskState.RUNNING, second.state)
        assertEquals(1, calls.count { it == "second" })
        engine.cancel(second.id)
    }

}

private class ConcurrencyTestStorage : Storage {
    private val data = mutableMapOf<String, String>()
    override suspend fun getString(key: String, defaultValue: String?): String? = data[key] ?: defaultValue
    override suspend fun setString(key: String, value: String) { data[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull() ?: defaultValue
    override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = data[key]?.toBooleanStrictOrNull() ?: defaultValue
    override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
    override suspend fun getAll(): Map<String, String> = data.toMap()
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun clear() { data.clear() }
}
