package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import org.junit.Assert.*
import org.junit.Test

/** Contract tests for the Core-owned skill trust boundary. */
class SkillRegistryTest {
    private fun createRegistry(): SkillRegistry = SkillRegistry(TestStorage())

    @Test
    fun registerAcceptsNewSkillButStartsUntrustedAndDisabled() {
        val reg = createRegistry()
        val candidate = Skill(id = "s1", name = "Test", enabled = true)
        assertTrue(reg.register(candidate))
        val stored = reg.get("s1")!!
        assertEquals(SkillTrustState.UNTRUSTED, stored.trustState)
        assertFalse(stored.enabled)
        assertFalse(stored.isActive)
    }

    @Test
    fun registerManifestValidatesAndForcesUntrustedDisabledState() {
        val reg = createRegistry()
        val result = reg.registerManifest(
            """{"contractVersion":"1.0","id":"manifest.skill","name":"Manifest Skill","version":"2.0.0","capabilities":["demo.read"],"riskClass":"REVERSIBLE"}"""
        )
        assertTrue(result.isSuccess)
        val stored = reg.get("manifest.skill")!!
        assertEquals("2.0.0", stored.version)
        assertEquals(SkillTrustState.UNTRUSTED, stored.trustState)
        assertFalse(stored.enabled)
        assertFalse(stored.isActive)
    }

    @Test
    fun registerManifestRejectsInvalidManifestWithoutMutation() {
        val reg = createRegistry()
        val result = reg.registerManifest(
            """{"contractVersion":"1.0","id":"bad","name":"Bad","version":"1.0.0","unexpected":true}"""
        )
        assertTrue(result.isFailure)
        assertNull(reg.get("bad"))
    }

    @Test
    fun registerRejectsDuplicateId() {
        val reg = createRegistry()
        assertTrue(reg.register(Skill(id = "s1", name = "Test 1")))
        assertFalse(reg.register(Skill(id = "s1", name = "Test 2")))
        assertEquals(1, reg.all().size)
    }

    @Test
    fun untrustedSkillCannotBeEnabled() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", enabled = true, state = SkillState.INSTALLED))
        assertFalse(reg.enable("s1"))
        assertFalse(reg.get("s1")!!.enabled)
    }

    @Test
    fun trustedInstalledSkillCanBeEnabled() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        assertTrue(reg.trust("s1"))
        assertFalse(reg.get("s1")!!.enabled)
        assertTrue(reg.enable("s1"))
        assertTrue(reg.get("s1")!!.isActive)
    }

    @Test
    fun trustRevocationDisablesImmediately() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        assertTrue(reg.trust("s1"))
        assertTrue(reg.enable("s1"))
        assertTrue(reg.revokeTrust("s1"))
        assertEquals(SkillTrustState.REVOKED, reg.get("s1")!!.trustState)
        assertFalse(reg.get("s1")!!.enabled)
        assertFalse(reg.get("s1")!!.isActive)
    }

    @Test
    fun highestRiskIgnoresUntrustedSkills() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Low", riskClass = RiskClass.READ_ONLY, state = SkillState.INSTALLED))
        reg.register(Skill(id = "s2", name = "High", riskClass = RiskClass.DESTRUCTIVE, state = SkillState.INSTALLED))
        assertEquals(RiskClass.READ_ONLY, reg.highestRisk)
        reg.trust("s1")
        reg.enable("s1")
        assertEquals(RiskClass.READ_ONLY, reg.highestRisk)
        reg.trust("s2")
        reg.enable("s2")
        assertEquals(RiskClass.DESTRUCTIVE, reg.highestRisk)
    }

    @Test
    fun removeRemovesSkill() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test"))
        assertTrue(reg.remove("s1"))
        assertNull(reg.get("s1"))
    }

    @Test
    fun removeReturnsFalseForMissing() {
        val reg = createRegistry()
        assertFalse(reg.remove("nonexistent"))
    }

    @Test
    fun persistenceFailureDoesNotCommitEnablement() {
        val storage = FailingStorage()
        val reg = SkillRegistry(storage)
        reg.register(Skill(id = "persist", name = "Persist", state = SkillState.INSTALLED))
        assertTrue(reg.trust("persist"))
        storage.failWrites = true

        assertThrows(SkillPersistenceException::class.java) {
            reg.enable("persist")
        }
        assertFalse(reg.get("persist")!!.enabled)
    }

    @Test
    fun corruptPersistedJournalFailsClosed() {
        val storage = FailingStorage()
        storage.raw = "{not-json"
        assertThrows(SkillPersistenceException::class.java) {
            SkillRegistry(storage)
        }
    }

    private class FailingStorage : org.aethra.core.storage.Storage {
        var raw: String? = null
        var failWrites: Boolean = false

        override suspend fun getString(key: String, defaultValue: String?): String? =
            raw ?: defaultValue

        override suspend fun setString(key: String, value: String) {
            if (failWrites) throw IllegalStateException("write failed")
            raw = value
        }

        override suspend fun getLong(key: String, defaultValue: Long?): Long? = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = emptyMap()
        override suspend fun remove(key: String) = Unit
        override suspend fun clear() { raw = null }
    }

}
