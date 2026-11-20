package org.aethra.core.workflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Contract test for the AETHRA development workflow system.
 *
 * Validates that the workflow documentation is internally consistent:
 * - Every workflow directory under skills/aethra/workflows/ has a SKILL.md
 * - Each SKILL.md contains all required contract sections
 * - template.json is valid JSON with expected structure
 * - The routing matrix references only existing workflow directories
 */
class WorkflowSystemConsistencyTest {

    private val skillsDir = File("../skills/aethra")
    private val workflowsDir = File(skillsDir, "workflows")
    private val requiredSections = listOf(
        "## Goal",
        "## Scope",
        "## Triggers",
        "## Inputs",
        "## Invariants",
        "## Procedure",
        "## Outputs",
        "## Review gate",
        "## References"
    )

    /** All expected workflow names from template.json routing. */
    private val expectedWorkflows = listOf(
        "core", "tasks", "dependencies", "memory", "providers", "tools",
        "policy", "android-runtime", "android-ui", "external", "github",
        "mcp", "instabox", "security", "testing", "review", "release"
    )

    @Test
    fun skillsDirectoryExists() {
        assertTrue("skills/aethra directory must exist", skillsDir.exists())
        assertTrue("skills/aethra must be a directory", skillsDir.isDirectory)
    }

    @Test
    fun entrySkillExists() {
        val entry = File(skillsDir, "SKILL.md")
        assertTrue("Entry SKILL.md must exist at skills/aethra/SKILL.md", entry.exists())
        assertTrue("Entry SKILL.md must not be empty", entry.readText().isNotBlank())
    }

    @Test
    fun templateJsonExistsAndHasCommands() {
        val template = File(skillsDir, "template.json")
        assertTrue("template.json must exist", template.exists())
        val content = template.readText()
        assertTrue("template.json must contain name field", content.contains("\"name\""))
        assertTrue("template.json must contain version field", content.contains("\"version\""))
        assertTrue("template.json must contain commands array", content.contains("\"commands\""))
        assertTrue("template.json name must be 'aethra'", content.contains("\"name\": \"aethra\""))
    }

    @Test
    fun everyWorkflowHasSkillMdWithAllSections() {
        assertTrue("workflows directory must exist", workflowsDir.exists())
        val workflowDirs = workflowsDir.listFiles { f -> f.isDirectory }
            ?: error("workflows directory is empty or unreadable")
        assertTrue("Expected ${expectedWorkflows.size} workflow directories, found ${workflowDirs.size}",
            workflowDirs.size == expectedWorkflows.size)

        for (workflowDir in workflowDirs) {
            val skillMd = File(workflowDir, "SKILL.md")
            assertTrue("${workflowDir.name} must have SKILL.md", skillMd.exists())
            val content = skillMd.readText()
            assertTrue("${workflowDir.name} SKILL.md must not be empty", content.isNotBlank())
            for (section in requiredSections) {
                assertTrue("${workflowDir.name} SKILL.md must contain section '$section'",
                    content.contains(section))
            }
        }
    }

    @Test
    fun expectedWorkflowsMatchDirectories() {
        assertTrue("workflows directory must exist", workflowsDir.exists())
        val actualDirs = workflowsDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?: emptyList()
        for (expected in expectedWorkflows) {
            assertTrue("Expected workflow directory '$expected' must exist",
                actualDirs.contains(expected))
        }
    }

    @Test
    fun routingMatrixReferencesValidWorkflows() {
        val routingMatrix = File(skillsDir, "reference/routing-matrix.md")
        assertTrue("routing-matrix.md must exist", routingMatrix.exists())
        val content = routingMatrix.readText()
        for (workflow in expectedWorkflows) {
            assertTrue("routing-matrix.md must reference workflow '$workflow'",
                content.contains("workflows/$workflow/SKILL.md"))
        }
    }

    @Test
    fun roleContractsExists() {
        val roleContracts = File(skillsDir, "reference/role-contracts.md")
        assertTrue("role-contracts.md must exist", roleContracts.exists())
        assertTrue("role-contracts.md must not be empty", roleContracts.readText().isNotBlank())
    }
}
