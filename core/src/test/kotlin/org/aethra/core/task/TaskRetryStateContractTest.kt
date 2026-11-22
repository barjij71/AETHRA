package org.aethra.core.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class TaskRetryStateContractTest {

    @Test
    fun oneRetryThenSuccessTracksExactlyOneRetry() = runBlocking {
        val storage = TestStorage()
        val eventBus = EventBus()
        val terminal = eventSignal(eventBus, "execution_completed")
        val observedRetryCounts = ConcurrentLinkedQueue<Int>()
        val retryEvents = ConcurrentLinkedQueue<TaskEvent>()
        eventBus.subscribe(TaskEvent::class.java) { event ->
            if (event.source == "retry_scheduled") retryEvents.add(event)
        }
        var executions = 0
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                observedRetryCounts.add(task.retriesUsed)
                executions++
                return if (executions == 1) {
                    Result.failure(IllegalStateException("retry once"))
                } else {
                    Result.success(mapOf("ok" to true))
                }
            }
        }
        val engine = TaskEngine(storage, eventBus, runner)
        val task = engine.create("one retry", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 1

        assertEquals(0, task.retriesUsed)
        engine.run(task.id)
        withTimeout(5_000) { terminal.await() }

        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals(2, executions)
        assertEquals(listOf(0, 1), observedRetryCounts.toList())
        assertEquals(1, task.retriesUsed)
        assertEquals(1, retryEvents.size)
        assertEquals("1", retryEvents.single().detail["retry"])
        assertEquals("2", retryEvents.single().detail["attempt"])
        engine.close()
    }

    @Test
    fun exhaustedBudgetTwoTracksTwoRetriesAndThreeExecutions() = runBlocking {
        val eventBus = EventBus()
        val terminal = eventSignal(eventBus, "execution_failed")
        val retryEvents = ConcurrentLinkedQueue<TaskEvent>()
        eventBus.subscribe(TaskEvent::class.java) { event ->
            if (event.source == "retry_scheduled") retryEvents.add(event)
        }
        var executions = 0
        val engine = TaskEngine(TestStorage(), eventBus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                assertEquals(executions, task.retriesUsed)
                executions++
                return Result.failure(IllegalStateException("always fails"))
            }
        })
        val task = engine.create("exhaust retries", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 2

        engine.run(task.id)
        withTimeout(5_000) { terminal.await() }

        assertEquals(TaskState.FAILED, task.state)
        assertEquals(3, executions)
        assertEquals(2, task.retriesUsed)
        assertEquals(listOf("1", "2"), retryEvents.map { it.detail["retry"] })
        assertEquals(listOf("2", "3"), retryEvents.map { it.detail["attempt"] })
        engine.close()
    }

    @Test
    fun retryCountSurvivesPersistenceRoundTripAfterExecution() = runBlocking {
        val storage = TestStorage()
        val eventBus = EventBus()
        val terminal = eventSignal(eventBus, "execution_completed")
        var executions = 0
        val engine = TaskEngine(storage, eventBus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                executions++
                return if (executions == 1) {
                    Result.failure(IllegalStateException("retry once"))
                } else {
                    Result.success(mapOf("ok" to true))
                }
            }
        })
        val task = engine.create("persist retry count", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 1

        engine.run(task.id)
        withTimeout(5_000) { terminal.await() }

        val restoredTask = TaskPersistence(storage).loadAll().single { it.id == task.id }
        assertEquals(1, task.retriesUsed)
        assertEquals(1, restoredTask.retriesUsed)
        engine.close()
    }

    @Test
    fun explicitNewExecutionCycleResetsRetryCountWithoutChangingAttemptIdentity() = runBlocking {
        val eventBus = EventBus()
        val firstTerminal = eventSignal(eventBus, "execution_failed")
        val retriedTerminal = eventSignal(eventBus, "execution_completed")
        var executions = 0
        val observedRetryCounts = ConcurrentLinkedQueue<Int>()
        val engine = TaskEngine(TestStorage(), eventBus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                executions++
                observedRetryCounts.add(task.retriesUsed)
                return when (executions) {
                    1, 2 -> Result.failure(IllegalStateException("fail first cycle"))
                    else -> Result.success(mapOf("ok" to true))
                }
            }
        })
        val task = engine.create("reset retry count", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 1

        engine.run(task.id)
        withTimeout(5_000) { firstTerminal.await() }
        assertEquals(1, task.retriesUsed)
        val executionAttemptId = task.executionAttemptId
        val attemptCounter = task.attemptCounter

        engine.retry(task.id)
        withTimeout(5_000) { retriedTerminal.await() }

        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals(3, executions)
        assertEquals(listOf(0, 1, 0), observedRetryCounts.toList())
        assertEquals(0, task.retriesUsed)
        assertEquals(executionAttemptId, task.executionAttemptId)
        assertEquals(attemptCounter, task.attemptCounter)
        engine.close()
    }

    private fun eventSignal(eventBus: EventBus, source: String): CompletableDeferred<TaskEvent> {
        val signal = CompletableDeferred<TaskEvent>()
        eventBus.subscribe(TaskEvent::class.java) { event ->
            if (event.source == source) signal.complete(event)
        }
        return signal
    }
}
