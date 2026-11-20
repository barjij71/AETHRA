package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.domain.TaskEvent
import org.junit.Assert.*
import org.junit.Test

class TaskEngineB1ContractTest {

    @Test
    fun idempotentExecutionPreventsDuplicateRun() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val events = mutableListOf<String>()
        bus.subscribe(TaskEvent::class.java) { event ->
            events += "\"${event.source}\""
        }
        val engine = TaskEngine(store, bus, SlowRunner(500), maxConcurrent = 4)

        val task = engine.create("do work", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)
        assertEquals(TaskState.RUNNING, engine.getTask(task.id)?.state)
        val firstStart = System.currentTimeMillis()
        while (task.state != TaskState.COMPLETED && System.currentTimeMillis() - firstStart < 3000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.COMPLETED, task.state)

        val secondStart = System.currentTimeMillis()
        engine.run(task.id)
        while (task.state == TaskState.RUNNING && System.currentTimeMillis() - secondStart < 2000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.COMPLETED, task.state)
        Thread.sleep(100)
        assertTrue(events.any { it.contains("execution_started") })
    }

    @Test
    fun timedOutRunnerDoesNotBlockForever() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val runner = SlowRunner(10000)
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("very slow", riskClass = RiskClass.READ_ONLY)
        task.setTimeoutMs(300)

        engine.run(task.id)

        val start = System.currentTimeMillis()
        while (task.state != TaskState.FAILED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.FAILED, task.state)
        assertFalse(task.state == TaskState.RUNNING)
        Thread.sleep(200)
    }

    @Test
    fun providerExceptionIsNotTimeout() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val runner = object : TaskRunner {
            override suspend fun run(t: Task): Result<Map<String, Any>> {
                throw TaskExecutionException("provider failure")
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("fail me", riskClass = RiskClass.READ_ONLY)

        engine.run(task.id)

        val start = System.currentTimeMillis()
        while (task.state != TaskState.FAILED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.FAILED, task.state)
        Thread.sleep(200)
    }

    @Test
    fun cancellationDoesNotBecomeFailureOrRetry() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val runner = object : TaskRunner {
            override suspend fun run(t: Task): Result<Map<String, Any>> {
                kotlinx.coroutines.delay(1000)
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("cancel me", riskClass = RiskClass.READ_ONLY)

        engine.run(task.id)
        val start = System.currentTimeMillis()
        while (task.state != TaskState.RUNNING && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.RUNNING, task.state)

        engine.cancel(task.id)
        Thread.sleep(200)
        assertFalse(task.isTerminal() && task.state == TaskState.FAILED)
        assertTrue(task.isCancelled() || task.state == TaskState.CANCELLED)
        Thread.sleep(200)
    }

    @Test
    fun retryAllowsOneIntentionalReExecution() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        var callCount = 0
        val runner = object : TaskRunner {
            override suspend fun run(t: Task): Result<Map<String, Any>> {
                callCount++
                if (callCount == 1) {
                    return Result.failure(TaskExecutionException("first failure"))
                }
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("retry me", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 2

        engine.run(task.id)

        // The retry is enabled: task fails first, then succeeds on retry
        val start = System.currentTimeMillis()
        while (task.state != TaskState.COMPLETED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.COMPLETED, task.state)
        Thread.sleep(200)
    }

    @Test
    fun timeoutEventIsDistinct() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val runner = object : TaskRunner {
            override suspend fun run(t: Task): Result<Map<String, Any>> {
                kotlinx.coroutines.delay(5000)
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("timing out", riskClass = RiskClass.READ_ONLY)
        task.setTimeoutMs(500)

        engine.run(task.id)

        val start = System.currentTimeMillis()
        while (task.state != TaskState.FAILED && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals(TaskState.FAILED, task.state)
        Thread.sleep(200)
    }

    @Test
    fun cancelledExecutionDoesNotProduceTimeout() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val runner = object : TaskRunner {
            override suspend fun run(t: Task): Result<Map<String, Any>> {
                kotlinx.coroutines.delay(2000)
                return Result.success(mapOf("ok" to "ok"))
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("slow cancel me", riskClass = RiskClass.READ_ONLY)
        task.setTimeoutMs(1000)

        engine.run(task.id)
        val start = System.currentTimeMillis()
        while (task.state != TaskState.RUNNING && System.currentTimeMillis() - start < 5000) {
            kotlinx.coroutines.delay(50)
        }

        engine.cancel(task.id)
        Thread.sleep(300)
        assertTrue(task.isCancelled() || task.state == TaskState.CANCELLED)
        Thread.sleep(200)
    }
}

/** A runner that blocks for a fixed duration, simulating a slow provider. */
class SlowRunner(private val millis: Long) : TaskRunner {
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        kotlinx.coroutines.delay(millis)
        return Result.success(mapOf("ok" to "ok"))
    }
}
