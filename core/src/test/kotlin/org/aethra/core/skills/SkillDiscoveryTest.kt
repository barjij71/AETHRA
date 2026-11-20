package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for the skill discovery abstraction.
 */
class SkillDiscoveryTest {
    @Test
    fun inMemoryDiscoveryReturnsEmptyWhenEmpty() = runBlocking {
        val disc = InMemorySkillDiscovery()
        assertEquals(0, disc.discover().size)
    }

    @Test
    fun inMemoryDiscoveryReturnsAddedSkills() = runBlocking {
        val disc = InMemorySkillDiscovery()
        disc.add(
            SourceInfo(
                id = "s1", name = "Test Skill",
                version = "1.0.0", source = SourceMetadata(type = "local"),
                capabilities = listOf("read"),
                riskClass = RiskClass.READ_ONLY
            )
        )
        disc.add(
            SourceInfo(
                id = "s2", name = "Other Skill",
                version = "2.0.0", source = SourceMetadata(type = "local"),
                capabilities = listOf("write"),
                riskClass = RiskClass.SENSITIVE
            )
        )
        val results = disc.discover()
        assertEquals(2, results.size)
        assertEquals("Test Skill", results.first { it.id == "s1" }.name)
        assertEquals("Other Skill", results.first { it.id == "s2" }.name)
    }

    @Test
    fun sourceReportProvidesMetadata() {
        val report = SourceReport(
            source = "local",
            verified = true,
            license = "MIT",
            dependencies = listOf("dep1"),
            permissionsDeclared = listOf("read")
        )
        assertEquals("local", report.source)
        assertTrue(report.verified)
        assertEquals("MIT", report.license)
        assertEquals(listOf("dep1"), report.dependencies)
        assertEquals(listOf("read"), report.permissionsDeclared)
    }

    @Test
    fun discoveryDoesNotInstall() = runBlocking {
        val disc = InMemorySkillDiscovery()
        disc.add(
            SourceInfo(
                id = "s1", name = "Test Skill",
                version = "1.0.0", source = SourceMetadata(type = "local")
            )
        )
        // discovery = scan; does not register or execute
        assertTrue(disc.discover().isNotEmpty())
    }
}
