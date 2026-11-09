package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract: a task that reached a terminal state (COMPLETED / FAILED /
 * CANCELLED) must never be resurrected by persistence restore, and must never
 * linger in a transient state such as RUNNING in the observable task list.
 */
class TaskTerminalStateContractTest {

    private suspend fun storageContains(store: TestStorage, snippet: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val raw = runBlocking { store.getString("aethra.tasks.v1") } ?: ""
            if (raw.contains(snippet)) return true
            delay(50)
        }
        return false
    }

    @Test
    fun completedStateIsPersistedAndNotRequeuedOnRestart() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val engine = TaskEngine(store, bus, SlowRunner(10), maxConcurrent = 4)
        val task = engine.create("finish me", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)

        val start = System.currentTimeMillis()
        while (task.state != TaskState.COMPLETED && System.currentTimeMillis() - start < 3000) {
            delay(50)
        }
        assertEquals(TaskState.COMPLETED, task.state)

        // Terminal state must be durable: the journal must eventually contain COMPLETED.
        assertTrue(storageContains(store, "\"state\":\"COMPLETED\"", 5000))
        engine.close()

        // A fresh engine over the same storage must restore the terminal state
        // instead of resurrecting the task as QUEUED and re-running it.
        val engine2 = TaskEngine(store, bus, SlowRunner(10), maxConcurrent = 4)
        var restored: Task? = null
        val restoreStart = System.currentTimeMillis()
        while (restored == null && System.currentTimeMillis() - restoreStart < 3000) {
            restored = engine2.getTask(task.id)
            if (restored == null) delay(50)
        }
        assertEquals("an already-finished task must be restored, not lost", TaskState.COMPLETED, restored?.state)
        assertEquals("restored task must not be re-run", TaskState.COMPLETED, restored?.state)
        engine2.close()
    }

    @Test
    fun runnerReportedCancellationIsTerminalNotRunning() = runBlocking {
        val store = TestStorage()
        val bus = EventBus()
        val events = mutableListOf<TaskEvent>()
        bus.subscribe(TaskEvent::class.java) { events += it }
        val runner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                return Result.failure(kotlinx.coroutines.CancellationException("downstream cancelled"))
            }
        }
        val engine = TaskEngine(store, bus, runner, maxConcurrent = 4)
        val task = engine.create("cancel via runner", riskClass = RiskClass.READ_ONLY)

        engine.run(task.id)

        val start = System.currentTimeMillis()
        while (!task.isTerminal() && System.currentTimeMillis() - start < 3000) {
            delay(50)
        }
        assertEquals(
            "a runner-reported cancellation must end in a terminal state",
            TaskState.CANCELLED,
            task.state
        )
        assertTrue(task.isCancelled())
        var cancelEvent: TaskEvent? = null
        val eventsStart = System.currentTimeMillis()
        while (cancelEvent == null && System.currentTimeMillis() - eventsStart < 3000) {
            cancelEvent = events.firstOrNull { it.taskId == task.id && it.newState == TaskState.CANCELLED && it.source == "execution_cancelled" }
            if (cancelEvent == null) delay(50)
        }
        assertTrue("an explicit terminal event must be observable", cancelEvent != null)
        engine.close()
    }
}
