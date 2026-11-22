package org.aethra.core.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3CTerminalDurabilityContractTest {
    private class ControlledStorage : Storage {
        private val values = mutableMapOf<String, String>()
        private var gatedState: String? = null
        private var failGatedWrite = false
        private var writeStarted = CompletableDeferred<Unit>()
        private var releaseWrite = CompletableDeferred<Unit>()
        private var writeFailed = CompletableDeferred<Throwable>()

        override suspend fun getString(key: String, defaultValue: String?): String? = synchronized(this) {
            values[key] ?: defaultValue
        }

        override suspend fun setString(key: String, value: String) {
            val target = synchronized(this) { gatedState?.takeIf { value.contains("\"state\":\"$it\"") } }
            if (target != null) {
                writeStarted.complete(Unit)
                if (failGatedWrite) {
                    val error = PersistenceException.IoFailure("terminal write failed", IllegalStateException("disk error"))
                    writeFailed.complete(error)
                    throw error
                }
                releaseWrite.await()
            }
            synchronized(this) { values[key] = value }
        }

        override suspend fun getLong(key: String, defaultValue: Long?): Long? = synchronized(this) { values[key]?.toLongOrNull() ?: defaultValue }
        override suspend fun setLong(key: String, value: Long) { synchronized(this) { values[key] = value.toString() } }
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = synchronized(this) { values[key]?.toBooleanStrictOrNull() ?: defaultValue }
        override suspend fun setBoolean(key: String, value: Boolean) { synchronized(this) { values[key] = value.toString() } }
        override suspend fun getAll(): Map<String, String> = synchronized(this) { values.toMap() }
        override suspend fun remove(key: String) { synchronized(this) { values.remove(key) } }
        override suspend fun clear() { synchronized(this) { values.clear() } }

        @Synchronized
        fun gate(state: TaskState, fail: Boolean = false) {
            gatedState = state.name
            failGatedWrite = fail
            writeStarted = CompletableDeferred()
            releaseWrite = CompletableDeferred()
            writeFailed = CompletableDeferred()
        }

        suspend fun awaitWriteStarted() = writeStarted.await()
        suspend fun awaitWriteFailure() = writeFailed.await()
        fun release() { releaseWrite.complete(Unit) }
    }

    private class RecordingEvents {
        val bus = EventBus()
        private val seen = mutableListOf<TaskEvent>()
        private val notifications = mutableMapOf<String, CompletableDeferred<TaskEvent>>()

        init {
            bus.subscribe(TaskEvent::class.java) { event -> synchronized(this) {
                seen.add(event)
                notifications.getOrPut(event.source) { CompletableDeferred() }.complete(event)
            } }
        }

        @Synchronized
        fun events(source: String): List<TaskEvent> = seen.filter { it.source == source }

        suspend fun awaitEvent(source: String): TaskEvent = synchronized(this) {
            notifications.getOrPut(source) { CompletableDeferred() }
        }.await()
    }

    private fun runner(block: suspend (Task) -> Result<Map<String, Any>>) = object : TaskRunner {
        override suspend fun run(task: Task) = block(task)
    }

    private fun makeRunningTask(engine: TaskEngine, goal: String): Task = engine.create(goal).also {
        it.updateState(TaskState.PREPARING)
        it.updateState(TaskState.APPROVED)
        it.updateState(TaskState.RUNNING)
    }

    private suspend fun assertOrdered(
        terminalState: TaskState,
        eventName: String,
        taskGoal: String,
        taskRunner: TaskRunner
    ) {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, taskRunner)
        val task = engine.create(taskGoal)
        task.retryBudget = 0
        storage.gate(terminalState)
        engine.run(task.id)
        storage.awaitWriteStarted()
        assertTrue("terminal event must wait for durable write", events.events(eventName).isEmpty())
        storage.release()
        val event = events.awaitEvent(eventName)
        assertEquals(task.id, event.taskId)
        assertEquals(terminalState, event.newState)
    }

    @Test
    fun completionPersistsBeforeEvent() = runBlocking {
        assertOrdered(TaskState.COMPLETED, "execution_completed", "success", runner {
            Result.success(mapOf("ok" to true))
        })
    }

    @Test
    fun failurePersistsBeforeEvent() = runBlocking {
        assertOrdered(TaskState.FAILED, "execution_failed", "failure", runner {
            Result.failure(IllegalStateException("failed"))
        })
    }

    @Test
    fun cancellationPersistsBeforeEvent() = runBlocking {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val runnerEntered = CompletableDeferred<Unit>()
        val engine = TaskEngine(storage, events.bus, runner {
            runnerEntered.complete(Unit)
            awaitCancellation()
        })
        val task = engine.create("cancel")
        engine.run(task.id)
        runnerEntered.await()
        storage.gate(TaskState.CANCELLED)
        val cancelJob = async { engine.cancel(task.id) }
        storage.awaitWriteStarted()
        assertTrue(events.events("cancel").isEmpty())
        storage.release()
        cancelJob.await()
        assertEquals(TaskState.CANCELLED, events.awaitEvent("cancel").newState)
    }

    @Test
    fun approvalDenialPersistsBeforeTerminalEvent() = runBlocking {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, runner {
            Result.success(emptyMap())
        })
        try {
            val task = engine.create("deny approval", riskClass = RiskClass.SENSITIVE)
            engine.run(task.id)
            assertEquals(TaskState.WAITING_APPROVAL, task.state)

            storage.gate(TaskState.CANCELLED)
            engine.approve(task.id, granted = false, reason = "denied")
            storage.awaitWriteStarted()

            assertTrue(
                "approval denial must not publish a terminal transition before persistence",
                events.events("core").none { it.taskId == task.id && it.newState == TaskState.CANCELLED }
            )
            assertTrue(events.events("approval").isEmpty())

            storage.release()
            val event = events.awaitEvent("approval")
            assertEquals(task.id, event.taskId)
            assertEquals(TaskState.CANCELLED, event.newState)
            assertTrue(task.isCancelled())
        } finally {
            storage.release()
            engine.close()
        }
    }

    @Test
    fun timeoutPersistsBeforeEvent() = runBlocking {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, runner { awaitCancellation() })
        val task = engine.create("timeout")
        task.setTimeoutMs(1)
        task.retryBudget = 0
        storage.gate(TaskState.FAILED)
        engine.run(task.id)
        storage.awaitWriteStarted()
        assertTrue(events.events("execution_timeout").isEmpty())
        storage.release()
        assertEquals(TaskState.FAILED, events.awaitEvent("execution_timeout").newState)
    }

    private suspend fun assertFailedWriteSuppressesEvent(
        state: TaskState,
        eventName: String,
        goal: String,
        taskRunner: TaskRunner,
        run: suspend (TaskEngine, Task) -> Unit
    ) {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, taskRunner)
        val task = engine.create(goal)
        task.retryBudget = 0
        storage.gate(state, fail = true)
        run(engine, task)
        storage.awaitWriteFailure()
        assertTrue("$eventName must not publish after persistence failure", events.events(eventName).isEmpty())
    }

    @Test
    fun completionPersistenceFailureSuppressesEvent() = runBlocking {
        assertFailedWriteSuppressesEvent(TaskState.COMPLETED, "execution_completed", "completion", runner {
            Result.success(mapOf("ok" to true))
        }) { engine, task -> engine.run(task.id) }
    }

    @Test
    fun failurePersistenceFailureSuppressesEvent() = runBlocking {
        assertFailedWriteSuppressesEvent(TaskState.FAILED, "execution_failed", "failure", runner {
            Result.failure(IllegalStateException("failed"))
        }) { engine, task -> engine.run(task.id) }
    }

    @Test
    fun cancellationPersistenceFailureSuppressesEvent() = runBlocking {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, runner { awaitCancellation() })
        val task = makeRunningTask(engine, "cancel-failure")
        storage.gate(TaskState.CANCELLED, fail = true)
        val error = runCatching { engine.cancel(task.id) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is PersistenceException)
        assertTrue(events.events("cancel").isEmpty())
    }

    @Test
    fun timeoutPersistenceFailureSuppressesEvent() = runBlocking {
        assertFailedWriteSuppressesEvent(TaskState.FAILED, "execution_timeout", "timeout", runner {
            awaitCancellation()
        }) { engine, task ->
            task.setTimeoutMs(1)
            engine.run(task.id)
        }
    }

    @Test
    fun persistenceExceptionPropagatesToCaller() = runBlocking {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, runner { awaitCancellation() })
        val task = makeRunningTask(engine, "exception")
        storage.gate(TaskState.CANCELLED, fail = true)
        val error = runCatching { engine.cancel(task.id) }.exceptionOrNull()
        assertTrue(error is PersistenceException)
        assertEquals("terminal write failed", error?.message)
        assertNull(events.events("cancel").firstOrNull())
    }

    @Test
    fun noTerminalEventAfterFailedPersistence() = runBlocking {
        val storage = ControlledStorage()
        val events = RecordingEvents()
        val engine = TaskEngine(storage, events.bus, runner { Result.success(mapOf("ok" to true)) })
        val task = engine.create("completion")
        storage.gate(TaskState.COMPLETED, fail = true)
        engine.run(task.id)
        storage.awaitWriteFailure()
        assertTrue(listOf("execution_completed", "execution_failed", "execution_cancelled", "execution_timeout", "cancel")
            .all { events.events(it).isEmpty() })
    }

    @Test
    fun restoredTerminalTaskDoesNotEmitDuplicateEvent() = runBlocking {
        val storage = ControlledStorage()
        val bus = RecordingEvents()
        val original = Task("terminal", riskClass = RiskClass.READ_ONLY)
        original.updateState(TaskState.PREPARING)
        original.updateState(TaskState.APPROVED)
        original.updateState(TaskState.RUNNING)
        original.complete()
        TaskPersistence(storage).saveAll(listOf(original))

        val engine = TaskEngine(storage, bus.bus, runner { Result.success(emptyMap()) })
        val restored = TaskPersistence(storage).loadAll().single()
        assertEquals(TaskState.COMPLETED, restored.state)
        assertEquals(original.executionAttemptId, restored.executionAttemptId)
        assertEquals(original.attemptCounter, restored.attemptCounter)
        assertFalse(bus.events("execution_completed").any { it.taskId == original.id })
        assertNotNull(engine)
    }
}
