package org.aethra.core.memory

import kotlinx.coroutines.runBlocking
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test

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

    @Test
    fun addPersistsAndReloadsAcrossEngineInstances() = runBlocking {
        val st = TestStorage()

        val first = MemoryEngine(st)
        first.initialize()
        val entry = first.add(
            MemoryCategory.KNOWLEDGE,
            "AETHRA memory survives restart",
            source = "user"
        )

        val second = MemoryEngine(st)
        second.initialize()

        val restored = second.all()
        assertEquals(1, restored.size)
        assertEquals(entry.id, restored.first().id)
        assertEquals(entry.text, restored.first().text)
        assertEquals(entry.category, restored.first().category)
        assertEquals(entry.source, restored.first().source)

        val hits = second.search("survives restart")
        assertEquals(1, hits.size)
        assertEquals(entry.id, hits.first().entry.id)
    }

}

class MemoryEngineTest {
    @Test
    fun addAndRetrieve() = runBlocking {
        val st = TestStorage()
        val engine = MemoryEngine(st)
        engine.initialize()
        val entry = engine.add(MemoryCategory.PREFERENCES, "Always charge overnight", source = "user")
        assertNotNull(entry.id)
        assertEquals("PREFERENCES", entry.category)
        assertEquals(1, engine.all().size)
    }

    @Test
    fun searchFindsByKeyword() = runBlocking {
        val st = TestStorage()
        val engine = MemoryEngine(st)
        engine.initialize()
        engine.add(MemoryCategory.PREFERENCES, "Always charge overnight")
        engine.add(MemoryCategory.PROJECT, "Use Kotlin coroutines")
        val hits = engine.search("charge")
        assertEquals(1, hits.size)
        assertEquals("PREFERENCES", hits.first().entry.category)
    }

    @Test
    fun deleteRemovesSingleEntry() = runBlocking {
        val st = TestStorage()
        val engine = MemoryEngine(st)
        engine.initialize()
        val e = engine.add(MemoryCategory.KNOWLEDGE, "test fact")
        engine.delete(e.id)
        assertEquals(0, engine.all().size)
    }

    @Test
    fun byCategoryFiltersCorrectly() = runBlocking {
        val st = TestStorage()
        val engine = MemoryEngine(st)
        engine.initialize()
        engine.add(MemoryCategory.PREFERENCES, "a")
        engine.add(MemoryCategory.PROJECT, "b")
        assertEquals(1, engine.byCategory(MemoryCategory.PREFERENCES).size)
        assertEquals(1, engine.byCategory(MemoryCategory.PROJECT).size)
    }
}
