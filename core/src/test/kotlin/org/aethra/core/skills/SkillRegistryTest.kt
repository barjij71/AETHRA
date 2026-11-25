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

    // ==================== INSTALL CONTRACT TESTS ====================

    @Test
    fun installDISCOVEREDToINSTALLEDUntrustedDisabled() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test"))
        assertTrue(reg.install("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.INSTALLED, stored.state)
        assertEquals(SkillTrustState.UNTRUSTED, stored.trustState)
        assertFalse(stored.enabled)
        assertFalse(stored.isActive)
    }

    @Test
    fun installVALIDToINSTALLED() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.VALID))
        assertTrue(reg.install("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.INSTALLED, stored.state)
        assertEquals(SkillTrustState.UNTRUSTED, stored.trustState)
        assertFalse(stored.enabled)
    }

    @Test
    fun installVALIDATINGToINSTALLED() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.VALIDATING))
        assertTrue(reg.install("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.INSTALLED, stored.state)
        assertEquals(SkillTrustState.UNTRUSTED, stored.trustState)
        assertFalse(stored.enabled)
    }

    @Test
    fun installIdempotentOnINSTALLED() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test"))
        assertTrue(reg.install("s1"))
        assertTrue(reg.install("s1")) // second call succeeds, no-op
        val stored = reg.get("s1")!!
        assertEquals(SkillState.INSTALLED, stored.state)
    }

    @Test
    fun installRejectsREMOVED() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test"))
        assertTrue(reg.remove("s1"))
        assertFalse(reg.install("s1"))
    }

    @Test
    fun installPersistenceFailureRollback() {
        val storage = FailingStorage()
        val reg = SkillRegistry(storage)
        reg.register(Skill(id = "persist", name = "Persist"))
        storage.failWrites = true

        assertThrows(SkillPersistenceException::class.java) {
            reg.install("persist")
        }
        // After failed persist, skill should remain in original state
        val stored = reg.get("persist")!!
        assertEquals(SkillState.DISCOVERED, stored.state)
    }

    // ==================== MARK UPDATE AVAILABLE CONTRACT TESTS ====================

    @Test
    fun markUpdateAvailableInstalledToUpdateAvailable() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        assertTrue(reg.trust("s1"))
        assertTrue(reg.enable("s1"))
        assertTrue(reg.get("s1")!!.isActive)

        assertTrue(reg.markUpdateAvailable("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.UPDATE_AVAILABLE, stored.state)
        assertFalse(stored.enabled)
        assertFalse(stored.isActive)
        assertEquals(SkillTrustState.TRUSTED, stored.trustState) // identity trust preserved
    }

    @Test
    fun markUpdateAvailableRejectsNonInstalled() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test")) // DISCOVERED

        assertFalse(reg.markUpdateAvailable("s1"))
        assertEquals(SkillState.DISCOVERED, reg.get("s1")!!.state)

        // DISABLED
        reg.register(Skill(id = "s2", name = "Test2", state = SkillState.INSTALLED))
        reg.trust("s2")
        reg.enable("s2")
        reg.disable("s2")
        assertFalse(reg.markUpdateAvailable("s2"))

        // UPDATE_AVAILABLE (idempotent rejection)
        reg.register(Skill(id = "s3", name = "Test3", state = SkillState.INSTALLED))
        reg.trust("s3")
        reg.enable("s3")
        reg.markUpdateAvailable("s3")
        assertFalse(reg.markUpdateAvailable("s3"))

        // FAILED
        reg.register(Skill(id = "s4", name = "Test4", state = SkillState.FAILED))
        assertFalse(reg.markUpdateAvailable("s4"))

        // REMOVED
        reg.register(Skill(id = "s5", name = "Test5"))
        reg.remove("s5")
        assertFalse(reg.markUpdateAvailable("s5"))
    }

    @Test
    fun markUpdateAvailablePreservesIdentityTrust() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")

        reg.markUpdateAvailable("s1")
        val stored = reg.get("s1")!!
        assertEquals(SkillTrustState.TRUSTED, stored.trustState)
    }

    @Test
    fun markUpdateAvailablePersistenceFailureRollback() {
        val storage = FailingStorage()
        val reg = SkillRegistry(storage)
        reg.register(Skill(id = "persist", name = "Persist", state = SkillState.INSTALLED))
        storage.failWrites = true

        assertThrows(SkillPersistenceException::class.java) {
            reg.markUpdateAvailable("persist")
        }
        // Should remain in INSTALLED state
        val stored = reg.get("persist")!!
        assertEquals(SkillState.INSTALLED, stored.state)
    }

    // ==================== ENABLE CONTRACT TESTS ====================

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
    fun enableDisabledTrustedSkill() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")
        reg.disable("s1")
        assertEquals(SkillState.DISABLED, reg.get("s1")!!.state)

        // Re-enable from DISABLED
        assertTrue(reg.enable("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.INSTALLED, stored.state)
        assertTrue(stored.enabled)
        assertTrue(stored.isActive)
    }

    @Test
    fun enableRejectsUntrusted() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        assertFalse(reg.enable("s1"))
    }

    @Test
    fun enableRejectsRevoked() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.revokeTrust("s1")
        assertFalse(reg.enable("s1"))
    }

    @Test
    fun enableRejectsUpdateAvailable() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")
        reg.markUpdateAvailable("s1")
        assertFalse(reg.enable("s1"))
    }

    @Test
    fun enableRejectsUpdating() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.UPDATING))
        reg.trust("s1")
        assertFalse(reg.enable("s1"))
    }

    @Test
    fun enableRejectsRemoved() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.remove("s1")
        assertFalse(reg.enable("s1"))
    }

    @Test
    fun enableRejectsNonInstalledStates() {
        val reg = createRegistry()
        // DISCOVERED
        reg.register(Skill(id = "s1", name = "Test1"))
        reg.trust("s1")
        assertFalse(reg.enable("s1"))

        // VALID
        reg.register(Skill(id = "s2", name = "Test2", state = SkillState.VALID))
        reg.trust("s2")
        assertFalse(reg.enable("s2"))

        // VALIDATING
        reg.register(Skill(id = "s3", name = "Test3", state = SkillState.VALIDATING))
        reg.trust("s3")
        assertFalse(reg.enable("s3"))

        // FAILED
        reg.register(Skill(id = "s4", name = "Test4", state = SkillState.FAILED))
        reg.trust("s4")
        assertFalse(reg.enable("s4"))
    }

    @Test
    fun enablePersistenceFailureRollback() {
        val storage = FailingStorage()
        val reg = SkillRegistry(storage)
        reg.register(Skill(id = "persist", name = "Persist", state = SkillState.INSTALLED))
        reg.trust("persist")
        storage.failWrites = true

        assertThrows(SkillPersistenceException::class.java) {
            reg.enable("persist")
        }
        // Should remain disabled
        val stored = reg.get("persist")!!
        assertFalse(stored.enabled)
    }

    // ==================== DISABLE CONTRACT TESTS ====================

    @Test
    fun disableInstalledToDisabled() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")
        assertTrue(reg.get("s1")!!.isActive)

        assertTrue(reg.disable("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.DISABLED, stored.state)
        assertFalse(stored.enabled)
        assertFalse(stored.isActive)
    }

    @Test
    fun disableIdempotentOnDisabled() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")
        reg.disable("s1")
        assertTrue(reg.disable("s1")) // second call succeeds (idempotent)
        val stored = reg.get("s1")!!
        assertEquals(SkillState.DISABLED, stored.state)
        assertFalse(stored.enabled)
    }

    @Test
    fun disableRejectsUpdateAvailable() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")
        reg.markUpdateAvailable("s1")
        assertEquals(SkillState.UPDATE_AVAILABLE, reg.get("s1")!!.state)

        // disable should reject UPDATE_AVAILABLE (preserve semantic)
        assertFalse(reg.disable("s1"))
        assertEquals(SkillState.UPDATE_AVAILABLE, reg.get("s1")!!.state)
    }

    @Test
    fun disableRejectsUpdating() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.UPDATING))
        assertFalse(reg.disable("s1"))
        assertEquals(SkillState.UPDATING, reg.get("s1")!!.state)
    }

    @Test
    fun disableRejectsRemoved() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.remove("s1")
        assertFalse(reg.disable("s1"))
    }

    @Test
    fun disablePersistenceFailureRollback() {
        val storage = FailingStorage()
        val reg = SkillRegistry(storage)
        reg.register(Skill(id = "persist", name = "Persist", state = SkillState.INSTALLED))
        reg.trust("persist")
        reg.enable("persist")
        storage.failWrites = true

        assertThrows(SkillPersistenceException::class.java) {
            reg.disable("persist")
        }
        // Should remain enabled
        val stored = reg.get("persist")!!
        assertTrue(stored.enabled)
    }

    // ==================== TRUST INTERACTION TESTS ====================

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
    fun installWithoutTrustNotExecutable() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test"))
        assertTrue(reg.install("s1"))
        assertFalse(reg.enable("s1")) // no trust -> reject
        assertFalse(reg.get("s1")!!.isActive)
    }

    @Test
    fun markUpdateAvailableThenInstallResetsTrust() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))
        reg.trust("s1")
        reg.enable("s1")
        assertTrue(reg.get("s1")!!.isActive)

        reg.markUpdateAvailable("s1")
        assertEquals(SkillTrustState.TRUSTED, reg.get("s1")!!.trustState)

        // install() resets trust to UNTRUSTED
        assertTrue(reg.install("s1"))
        val stored = reg.get("s1")!!
        assertEquals(SkillState.INSTALLED, stored.state)
        assertEquals(SkillTrustState.UNTRUSTED, stored.trustState)
        assertFalse(stored.enabled)
        assertFalse(stored.isActive)
    }

    @Test
    fun trustThenEnableThenRevokeThenDisable() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Test", state = SkillState.INSTALLED))

        // trust -> enable
        assertTrue(reg.trust("s1"))
        assertTrue(reg.enable("s1"))
        assertTrue(reg.get("s1")!!.isActive)

        // revoke -> disables immediately
        assertTrue(reg.revokeTrust("s1"))
        assertEquals(SkillTrustState.REVOKED, reg.get("s1")!!.trustState)
        assertFalse(reg.get("s1")!!.enabled)
        assertFalse(reg.get("s1")!!.isActive)

        // disable -> idempotent on already disabled
        assertTrue(reg.disable("s1"))
        assertEquals(SkillState.DISABLED, reg.get("s1")!!.state)
        assertFalse(reg.get("s1")!!.enabled)
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