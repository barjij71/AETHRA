package org.aethra.core.task

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3BExecutionIdentityContractTest {

    private class MemoryStorage : Storage {
        private val data = mutableMapOf<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = data[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { data[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?): Long? = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = data.toMap()
        override suspend fun remove(key: String) { data.remove(key) }
        override suspend fun clear() { data.clear() }
    }

    @Test
    fun recoveringOneTaskDoesNotAlterAnother() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)

        val taskA = Task("task A")
        taskA.updateState(TaskState.PREPARING)
        taskA.updateState(TaskState.APPROVED)
        taskA.updateState(TaskState.RUNNING)

        val taskB = Task("task B")
        taskB.updateState(TaskState.PREPARING)
        taskB.updateState(TaskState.APPROVED)
        taskB.updateState(TaskState.RUNNING)
        taskB.recordRetry()

        val bId = taskB.id
        val bAttemptId = taskB.executionAttemptId
        val bAttemptCounter = taskB.attemptCounter
        val bRetries = taskB.retriesUsed

        persistence.saveAll(listOf(taskA, taskB))
        val restored = persistence.loadAll()
        val restoredA = restored.single { it.id == taskA.id }
        val restoredB = restored.single { it.id == bId }

        assertEquals(taskA.id, restoredA.id)
        assertEquals(bId, restoredB.id)
        assertNotEquals(taskA.executionAttemptId, restoredA.executionAttemptId)
        assertNotEquals(bAttemptId, restoredB.executionAttemptId)
        assertEquals(taskA.attemptCounter + 1, restoredA.attemptCounter)
        assertEquals(bAttemptCounter + 1, restoredB.attemptCounter)
        assertEquals(0, restoredA.retriesUsed)
        assertEquals(0, restoredB.retriesUsed)
        assertEquals(TaskState.QUEUED, restoredA.state)
        assertEquals(TaskState.QUEUED, restoredB.state)
        assertTrue(restoredA.executionAttemptId != restoredB.executionAttemptId)
    }

    @Test
    fun preparingRecoveryCreatesNewAttempt() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = Task("preparing recovery")
        task.updateState(TaskState.PREPARING)
        val oldAttemptId = task.executionAttemptId
        val oldCounter = task.attemptCounter

        persistence.saveAll(listOf(task))
        val restored = persistence.loadAll().single()

        assertEquals(task.id, restored.id)
        assertNotEquals(oldAttemptId, restored.executionAttemptId)
        assertEquals(oldCounter + 1, restored.attemptCounter)
        assertEquals(0, restored.retriesUsed)
        assertEquals(TaskState.QUEUED, restored.state)
    }

    @Test
    fun runningTaskRecoveryIncrementsAttemptCounterExactlyOnce() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = Task("running recovery")
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        task.updateState(TaskState.RUNNING)
        task.recordRetry()
        val oldAttemptId = task.executionAttemptId
        val oldCounter = task.attemptCounter

        persistence.saveAll(listOf(task))
        val restored = persistence.loadAll().single()

        assertNotEquals(oldAttemptId, restored.executionAttemptId)
        assertEquals(oldCounter + 1, restored.attemptCounter)
        assertEquals(0, restored.retriesUsed)
        assertEquals(TaskState.QUEUED, restored.state)
    }

    @Test
    fun approvedRecoveryCreatesNewAttempt() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = Task("approved recovery")
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        val oldAttemptId = task.executionAttemptId
        val oldCounter = task.attemptCounter

        persistence.saveAll(listOf(task))
        val restored = persistence.loadAll().single()

        assertNotEquals(oldAttemptId, restored.executionAttemptId)
        assertEquals(oldCounter + 1, restored.attemptCounter)
        assertEquals(0, restored.retriesUsed)
        assertEquals(TaskState.QUEUED, restored.state)
    }
}
