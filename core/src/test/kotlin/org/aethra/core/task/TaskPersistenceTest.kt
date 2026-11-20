package org.aethra.core.task

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPersistenceTest {

    private class MemoryStorage : Storage {
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

    @Test
    fun roundTripPreservesTaskIdentityStateAndDependencies() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)

        val dependency = Task("prepare")
        dependency.complete()

        val task = Task(
            goal = "continue work",
            role = "assistant",
            riskClass = RiskClass.READ_ONLY,
            dependsOn = listOf(dependency.id)
        )
        task.retryBudget = 4
        task.setTimeoutMs(12_345)
        task.updateState(TaskState.PREPARING, mapOf("phase" to "persist-test"))
        task.updateState(TaskState.WAITING_DEPENDENCY)

        persistence.saveAll(listOf(dependency, task))
        val restored = persistence.loadAll()

        val restoredTask = restored.first { it.id == task.id }
        assertEquals(task.id, restoredTask.id)
        assertEquals(task.goal, restoredTask.goal)
        assertEquals(task.role, restoredTask.role)
        assertEquals(task.riskClass, restoredTask.riskClass)
        assertEquals(task.dependsOn, restoredTask.dependsOn)
        assertEquals(TaskState.WAITING_DEPENDENCY, restoredTask.state)
        assertEquals(4, restoredTask.retryBudget)
        assertEquals(12_345L, restoredTask.timeoutMs)
        assertEquals(mapOf("phase" to "persist-test"), restoredTask.detail)
    }

    @Test
    fun inFlightStateRecoversToQueuedForSafeRestart() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)

        val task = Task("in flight")
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        task.updateState(TaskState.RUNNING)
        task.isExecuted = true

        persistence.saveAll(listOf(task))
        val restored = persistence.loadAll().single()

        assertEquals(task.id, restored.id)
        assertEquals(TaskState.QUEUED, restored.state)
        assertTrue(!restored.isExecuted)
    }
}
