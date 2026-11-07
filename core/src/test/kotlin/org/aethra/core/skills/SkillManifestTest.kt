package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import org.junit.Assert.*
import org.junit.Test

/** Tests for the skill manifest model. */
class SkillManifestTest {
    @Test
    fun validManifestHasAllRequiredFields() {
        val m = SkillManifest(
            id = "test.skill.example",
            name = "Example Skill",
            version = "1.0.0",
            source = SourceMetadata(type = "local"),
            description = "A test skill",
            entrypoints = listOf("example.exec"),
            capabilities = listOf("read"),
            requestedPermissions = emptyList(),
            dependencies = emptyList(),
            platforms = listOf("android"),
            riskClass = RiskClass.READ_ONLY,
            integrity = IntegrityInfo(algorithm = "sha256", digest = "abc123"),
            enabled = true,
            state = SkillState.VALID
        )
        assertEquals("test.skill.example", m.id)
        assertEquals("Example Skill", m.name)
        assertEquals("1.0.0", m.version)
        assertEquals(1, m.capabilities.size)
        assertEquals(RiskClass.READ_ONLY, m.riskClass)
        assertFalse(m.isActive)
    }

    @Test
    fun validManifestWithInstalledStateIsActive() {
        val m = SkillManifest(
            id = "test.skill.example",
            name = "Example Skill",
            version = "1.0.0",
            enabled = true,
            state = SkillState.INSTALLED
        )
        assertTrue(m.isActive)
    }

    @Test
    fun manifestRejectsBlankId() {
        val m = SkillManifest(
            id = "  ",
            name = "X",
            version = "1.0.0"
        )
        assertTrue(m.id.isBlank())
    }

    @Test
    fun manifestRejectsBlankName() {
        val m = SkillManifest(
            id = "test.skill",
            name = "  ",
            version = "1.0.0"
        )
        assertTrue(m.name.isBlank())
    }

    @Test
    fun isValidRejectsBlankFields() {
        val valid = SkillManifest(
            id = "test.skill",
            name = "X",
            version = "1.0.0"
        )
        assertTrue(valid.isValid)

        val missingVersion = SkillManifest(
            id = "test.skill",
            name = "X",
            version = ""
        )
        assertFalse(missingVersion.isValid)
    }

    @Test
    fun isActiveReflectsState() {
        val valid = SkillManifest(
            id = "t", name = "x", version = "1.0.0",
            state = SkillState.INSTALLED, enabled = true
        )
        assertTrue(valid.isActive)

        val disabled = SkillManifest(
            id = "t", name = "x", version = "1.0.0",
            state = SkillState.INSTALLED, enabled = false
        )
        assertFalse(disabled.isActive)
    }

    @Test
    fun toSkillPreservesFields() {
        val m = SkillManifest(
            id = "t", name = "x", version = "1.0.0",
            source = SourceMetadata(type = "local"),
            description = "desc",
            entrypoints = listOf("e"),
            capabilities = listOf("read"),
            requestedPermissions = listOf("read"),
            dependencies = emptyList(),
            platforms = listOf("android"),
            riskClass = RiskClass.READ_ONLY,
            integrity = IntegrityInfo(algorithm = "sha256", digest = "abc"),
            enabled = true,
            state = SkillState.VALID
        )
        val s = m.toSkill()
        assertEquals("t", s.id)
        assertEquals("x", s.name)
        assertEquals("desc", s.description)
        assertEquals(listOf("read"), s.capabilities)
        assertEquals(listOf("read"), s.requestedPermissions)
        assertEquals(listOf("android"), s.platforms)
        assertEquals(RiskClass.READ_ONLY, s.riskClass)
        assertEquals(SkillState.VALID, s.state)
        assertTrue(s.createdAt > 0)
        assertTrue(s.updatedAt > 0)
    }
}
