package org.aethra.core.agents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.aethra.core.task.PersistenceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AgentRegistryContractTest {
    @Test
    fun registerThenRetrievePreservesAgentDefinition() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val agent = agent("agent-b", role = "reviewer", tools = listOf("file.read", "memory.search"))

        assertTrue(engine.register(agent))
        assertEquals(agent, engine.get(agent.id))
    }

    @Test
    fun persistedAgentLoadsIntoRecreatedEngine() = runBlocking {
        val storage = MemoryStorage()
        val original = AgentEngine(AgentPersistence(storage))
        val agent = agent("stable-agent", role = "planner", tools = listOf("task.plan"))
        original.register(agent)

        val restored = AgentEngine(AgentPersistence(storage))
        restored.load()

        assertEquals(agent, restored.get("stable-agent"))
        assertEquals("stable-agent", restored.get("stable-agent")?.id)
    }

    @Test
    fun roleAndToolAssignmentsSurviveRestoration() = runBlocking {
        val storage = MemoryStorage()
        val agent = agent("assignments", role = "security-reviewer", tools = listOf("repo.read", "test.run"))
        AgentEngine(AgentPersistence(storage)).apply { register(agent) }

        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }.get(agent.id)

        assertEquals("security-reviewer", restored?.role)
        assertEquals(listOf("repo.read", "test.run"), restored?.tools)
        assertEquals(agent.name, restored?.name)
        assertEquals(agent.description, restored?.description)
        assertEquals(agent.provider, restored?.provider)
        assertEquals(agent.model, restored?.model)
        assertEquals(agent.memoryScope, restored?.memoryScope)
        assertEquals(agent.retryBudget, restored?.retryBudget)
        assertEquals(agent.createdAt, restored?.createdAt)
        assertEquals(agent.updatedAt, restored?.updatedAt)
        assertEquals(agent.enabled, restored?.enabled)
        assertEquals(agent.status, restored?.status)
    }

    @Test
    fun duplicateRegistrationIsRejectedWithoutReplacingExistingAgent() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        val first = agent("duplicate", role = "first")
        val second = agent("duplicate", role = "second")
        assertTrue(engine.register(first))

        assertFalse(engine.register(second))
        assertEquals(first, engine.get("duplicate"))
    }

    @Test
    fun listingIsSortedByStableId() = runBlocking {
        val engine = AgentEngine(AgentPersistence(MemoryStorage()))
        engine.register(agent("z"))
        engine.register(agent("a"))
        engine.register(agent("m"))

        assertEquals(listOf("a", "m", "z"), engine.all().map { it.id })
    }

    @Test
    fun deleteRemovesAndPersistsAgent() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage))
        engine.register(agent("remove"))

        assertTrue(engine.delete("remove"))
        assertNull(engine.get("remove"))
        assertFalse(engine.delete("remove"))

        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }
        assertNull(restored.get("remove"))
    }

    @Test
    fun persistenceWriteFailureIsPropagatedAndRegistryIsUnchanged() = runBlocking {
        val storage = MemoryStorage().apply { failWrites = true }
        val engine = AgentEngine(AgentPersistence(storage))

        val failure = runCatching { engine.register(agent("write-fails")) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is PersistenceException.IoFailure || failure is StorageException.IoFailure)
        assertNull(engine.get("write-fails"))
    }

    @Test
    fun corruptAgentDataRaisesTypedPersistenceError() = runBlocking {
        val storage = MemoryStorage().apply { values["aethra.agents.v1"] = "{broken" }

        val failure = runCatching { AgentEngine(AgentPersistence(storage)).load() }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Corrupt)
    }

    @Test
    fun incompatibleStorageDataRaisesTypedPersistenceError() = runBlocking {
        val storage = MemoryStorage().apply { failReadsWith = StorageException.Incompatible("old schema") }

        val failure = runCatching { AgentEngine(AgentPersistence(storage)).load() }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Incompatible)
    }

    @Test
    fun unsupportedRegistrySchemaRaisesTypedPersistenceError() = runBlocking {
        val storage = MemoryStorage().apply {
            values["aethra.agents.v1"] = """{"schemaVersion":99,"agents":[]}"""
        }

        val failure = runCatching { AgentEngine(AgentPersistence(storage)).load() }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Incompatible)
    }

    @Test
    fun readFailureIsSurfacedInsteadOfBecomingAnEmptyRegistry() = runBlocking {
        val storage = MemoryStorage().apply { failReadsWith = StorageException.IoFailure("read failed") }

        val failure = runCatching { AgentEngine(AgentPersistence(storage)).load() }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
    }

    @Test
    fun concurrentRegistrationsAreSerializedAndRetainBothAgents() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage))
        val ready = CompletableDeferred<Unit>()
        val start = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger()
        val jobs = listOf("concurrent-b", "concurrent-a").map { id ->
            async(Dispatchers.Default) {
                if (readyCount.incrementAndGet() == 2) ready.complete(Unit)
                start.await()
                engine.register(agent(id))
            }
        }
        ready.await()
        start.complete(Unit)

        assertEquals(listOf(true, true), jobs.awaitAll())
        assertEquals(listOf("concurrent-a", "concurrent-b"), engine.all().map { it.id })
        assertEquals(listOf("concurrent-a", "concurrent-b"), AgentEngine(AgentPersistence(storage)).apply { load() }.all().map { it.id })
    }

    @Test
    fun concurrentUpdateAndRegistrationAreSerializedWithoutLostChanges() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage))
        val original = agent("updated", role = "before")
        engine.register(original)
        val ready = CompletableDeferred<Unit>()
        val start = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger()
        val updateJob = async(Dispatchers.Default) {
            if (readyCount.incrementAndGet() == 2) ready.complete(Unit)
            start.await()
            engine.update(original.copy(role = "after"))
        }
        val registerJob = async(Dispatchers.Default) {
            if (readyCount.incrementAndGet() == 2) ready.complete(Unit)
            start.await()
            engine.register(agent("added"))
        }
        ready.await()
        start.complete(Unit)

        assertEquals("after", updateJob.await().role)
        assertTrue(registerJob.await())
        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }
        assertEquals("after", restored.get("updated")?.role)
        assertNotNull(restored.get("added"))
    }

    @Test
    fun registryOperationsNeedNoProviderOrNetwork() = runBlocking {
        val storage = MemoryStorage()
        val engine = AgentEngine(AgentPersistence(storage))

        assertTrue(engine.register(agent("offline")))
        val restored = AgentEngine(AgentPersistence(storage)).apply { load() }

        assertEquals("offline", restored.get("offline")?.id)
    }

    private fun agent(
        id: String,
        role: String = "assistant",
        tools: List<String> = emptyList()
    ) = Agent(
        id = id,
        name = "name-$id",
        role = role,
        description = "description-$id",
        provider = "local",
        model = "model-$id",
        enabled = false,
        tools = tools,
        memoryScope = "scope-$id",
        retryBudget = 3,
        createdAt = 100L,
        updatedAt = 200L
    )

    private class MemoryStorage : Storage {
        val values = ConcurrentHashMap<String, String>()
        @Volatile var failWrites = false
        @Volatile var failReadsWith: StorageException? = null

        override suspend fun getString(key: String, defaultValue: String?): String? {
            failReadsWith?.let { throw it }
            return values[key] ?: defaultValue
        }
        override suspend fun setString(key: String, value: String) {
            if (failWrites) throw StorageException.IoFailure("write failed")
            values[key] = value
        }
        override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }
}
