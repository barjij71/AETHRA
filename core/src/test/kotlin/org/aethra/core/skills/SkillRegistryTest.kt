package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test

/** Tests for the skill registry. */
class SkillRegistryTest {
    private fun createRegistry(): SkillRegistry {
        return SkillRegistry(TestStorage())
    }

    @Test
    fun registerAcceptsNewSkill() {
        val reg = createRegistry()
        val s = Skill(id = "s1", name = "Test")
        assertTrue(reg.register(s))
        assertEquals(1, reg.all().size)
    }

    @Test
    fun registerRejectsDuplicateId() {
        val reg = createRegistry()
        val s1 = Skill(id = "s1", name = "Test 1")
        val s2 = Skill(id = "s1", name = "Test 2")
        assertTrue(reg.register(s1))
        assertFalse(reg.register(s2))
        assertEquals(1, reg.all().size)
    }

    @Test
    fun getReturnsNullForMissingId() {
        val reg = createRegistry()
        assertNull(reg.get("nonexistent"))
    }

    @Test
    fun findByNameIsCaseInsensitive() {
        val reg = createRegistry()
        val s = Skill(id = "s1", name = "Test Skill")
        reg.register(s)
        assertNotNull(reg.find("test skill"))
        assertNull(reg.find("nonexistent"))
    }

    @Test
    fun findByCapabilityReturnsMatchingSkills() {
        val reg = createRegistry()
        val s1 = Skill(id = "s1", name = "S1", capabilities = listOf("read"))
        val s2 = Skill(id = "s2", name = "S2", capabilities = listOf("write"))
        val s3 = Skill(id = "s3", name = "S3", capabilities = listOf("read", "write"))
        reg.register(s1)
        reg.register(s2)
        reg.register(s3)
        assertEquals(2, reg.findByCapability("read").size)
        assertEquals(2, reg.findByCapability("write").size)
    }

    @Test
    fun findByPlatformFiltersCorrectly() {
        val reg = createRegistry()
        val s1 = Skill(id = "s1", name = "S1", platforms = listOf("android"))
        val s2 = Skill(id = "s2", name = "S2", platforms = listOf("ios"))
        val s3 = Skill(id = "s3", name = "S3", platforms = emptyList()) // all platforms
        reg.register(s1)
        reg.register(s2)
        reg.register(s3)
        assertEquals(2, reg.findByPlatform("android").size)
        assertEquals(2, reg.findByPlatform("ios").size)
    }

    @Test
    fun enableAndDisableWork() {
        val reg = createRegistry()
        val s = Skill(id = "s1", name = "Test", enabled = true)
        reg.register(s)
        assertTrue(reg.disable("s1"))
        assertFalse(reg.get("s1")?.enabled == true)
        assertTrue(reg.enable("s1"))
        assertTrue(reg.get("s1")?.enabled == true)
    }

    @Test
    fun removeRemovesSkill() {
        val reg = createRegistry()
        val s = Skill(id = "s1", name = "Test")
        reg.register(s)
        assertTrue(reg.remove("s1"))
        assertNull(reg.get("s1"))
    }

    @Test
    fun removeReturnsFalseForMissing() {
        val reg = createRegistry()
        assertFalse(reg.remove("nonexistent"))
    }

    @Test
    fun highestRiskReturnsHighestRiskClass() {
        val reg = createRegistry()
        reg.register(Skill(id = "s1", name = "Low", riskClass = RiskClass.READ_ONLY))
        reg.register(Skill(id = "s2", name = "High", riskClass = RiskClass.DESTRUCTIVE))
        reg.register(Skill(id = "s3", name = "Medium", riskClass = RiskClass.SENSITIVE))
        // highest = CRITICAL (highest ordinal)
        assertEquals(RiskClass.DESTRUCTIVE, reg.highestRisk)
    }
}
