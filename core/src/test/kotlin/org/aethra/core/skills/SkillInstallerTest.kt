package org.aethra.core.skills

import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.skills.DependencyTestStorage
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * In-memory test storage for skill tests.
 */
class TestStorage : Storage {
    private val data = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun getString(key: String, defaultValue: String?): String? {
        return data[key]
    }

    override suspend fun setString(key: String, value: String) {
        data[key] = value
    }

    override suspend fun getLong(key: String, defaultValue: Long?): Long? {
        return data[key]?.toLongOrNull()
    }

    override suspend fun setLong(key: String, value: Long) {
        data[key] = value.toString()
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? {
        return data[key]?.toBooleanStrictOrNull()
    }

    override suspend fun setBoolean(key: String, value: Boolean) {
        data[key] = value.toString()
    }

    override suspend fun getAll(): Map<String, String> {
        return data.toMap()
    }

    override suspend fun remove(key: String) {
        data.remove(key)
    }

    override suspend fun clear() {
        data.clear()
    }
}

/**
 * Test helper that uses the real AuditLog with test storage.
 */
fun createTestAuditLog(): org.aethra.core.security.AuditLog {
    return org.aethra.core.security.AuditLog(TestStorage())
}

/**
 * Tests for the skill installer.
 */
class SkillInstallerTest {

    @Test
    fun installRejectsBlankId() = runBlocking {
        val store = SkillRegistry(TestStorage())
        val audit = createTestAuditLog()
        val broker = PermissionBroker(audit)
        val installer = SkillInstaller(store, audit, broker, TestStorage())
        val skill = Skill(id = "", name = "Test", version = "1.0.0")
        val result = installer.install(SourceReport("local", verified = true), skill)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun installRejectsMissingName() = runBlocking {
        val store = SkillRegistry(TestStorage())
        val audit = createTestAuditLog()
        val broker = PermissionBroker(audit)
        val installer = SkillInstaller(store, audit, broker, TestStorage())
        val skill = Skill(id = "s1", name = "", version = "1.0.0")
        val result = installer.install(SourceReport("local", verified = true), skill)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun installAcceptsValidSkill() = runBlocking {
        val store = SkillRegistry(TestStorage())
        val audit = createTestAuditLog()
        val broker = PermissionBroker(audit)
        val installer = SkillInstaller(store, audit, broker, TestStorage())
        val skill = Skill(id = "s1", name = "Test Skill", version = "1.0.0", riskClass = RiskClass.READ_ONLY)
        val result = installer.install(SourceReport("local", verified = true), skill)
        assertTrue(result.isSuccess)
        assertEquals("Test Skill", result.getOrDefault(null)!!.name)
        assertEquals(1, store.all().size)
    }

    @Test
    fun installGeneratesAuditEntry() = runBlocking {
        val store = SkillRegistry(TestStorage())
        val audit = createTestAuditLog()
        val broker = PermissionBroker(audit)
        val installer = SkillInstaller(store, audit, broker, TestStorage())
        val skill = Skill(id = "s1", name = "Test", version = "1.0.0")
        installer.install(SourceReport("local", verified = true), skill)
        val entries = audit.all()
        assertTrue(entries.any { it.operation == "skill.install" && it.decision == "GRANTED" && it.outcome == "installed" })
    }
}
