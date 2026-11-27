package org.aethra.core.skills

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.Tool
import org.aethra.core.tools.ToolRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SkillOrchestrator Phase 9 implementation.
 * Verifies skill-to-task bridging with proper entrypoint validation,
 * risk propagation, capability boundaries, and provenance tracking.
 */
class SkillOrchestratorTest {

    private fun createOrchestrator(): SkillOrchestrator {
        val skillRegistry = SkillRegistry(TestStorage())
        val toolRegistry = ToolRegistry()
        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )
        return SkillOrchestrator(skillRegistry, toolRegistry, taskEngine)
    }

    @Test
    fun `execute succeeds with valid skill and entrypoint`() = runBlocking {
        val skill = Skill(
            id = "test.skill",
            name = "Test Skill",
            enabled = true,
            state = SkillState.INSTALLED,
            entrypoints = listOf("test.tool.read")
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("test.skill")
        registry.enable("test.skill")

        val toolRegistry = ToolRegistry()
        val tool = Tool(
            id = "test.tool.read",
            description = "A test tool",
            category = "test",
            riskClass = RiskClass.READ_ONLY
        )
        toolRegistry.register(tool)

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "test.skill",
                entrypointId = "test.tool.read"
            )
        )

        assertTrue("Should succeed with valid skill and entrypoint", result is SkillOrchestrator.Result.Success)
        if (result is SkillOrchestrator.Result.Success) {
            val task = result.task
            assertEquals("createdAtBy should be the skill ID for provenance", "test.skill", task.createdAtBy)
            assertEquals("task goal should be the tool ID", "test.tool.read", task.goal)
            assertEquals("default role should be assistant", "assistant", task.role)
        }
    }

    @Test
    fun `execute fails for non-existent skill`() = runBlocking {
        val orchestrator = createOrchestrator()

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "nonexistent.skill",
                entrypointId = "test.tool.read"
            )
        )

        assertTrue("Should fail for non-existent skill", result is SkillOrchestrator.Result.Failure)
        if (result is SkillOrchestrator.Result.Failure) {
            assertTrue("Error message should mention skill not found",
                result.reason.contains("skill not found", ignoreCase = true))
        }
    }

    @Test
    fun `execute fails for disabled skill`() = runBlocking {
        val skill = Skill(
            id = "disabled.skill",
            name = "Disabled Skill",
            enabled = false, // disabled
            state = SkillState.INSTALLED,
            entrypoints = listOf("test.tool.read")
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("disabled.skill")
        // NOT enabling the skill

        val toolRegistry = ToolRegistry()
        val tool = Tool(
            id = "test.tool.read",
            description = "A test tool",
            category = "test",
            riskClass = RiskClass.READ_ONLY
        )
        toolRegistry.register(tool)

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "disabled.skill",
                entrypointId = "test.tool.read"
            )
        )

        assertTrue("Should fail for disabled skill", result is SkillOrchestrator.Result.Failure)
        if (result is SkillOrchestrator.Result.Failure) {
            assertTrue("Error message should mention skill not enabled",
                result.reason.contains("not enabled", ignoreCase = true))
        }
    }

    @Test
    fun `execute fails for entrypoint not in skill entrypoints`() = runBlocking {
        val skill = Skill(
            id = "limited.skill",
            name = "Limited Skill",
            enabled = true,
            state = SkillState.INSTALLED,
            entrypoints = listOf("only.this.tool") // doesn't include test.tool.read
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("limited.skill")
        registry.enable("limited.skill")

        val toolRegistry = ToolRegistry()
        val tool = Tool(
            id = "test.tool.read",
            description = "A test tool",
            category = "test",
            riskClass = RiskClass.READ_ONLY
        )
        toolRegistry.register(tool)

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "limited.skill",
                entrypointId = "test.tool.read"
            )
        )

        assertTrue("Should fail for entrypoint not in skill", result is SkillOrchestrator.Result.Failure)
        if (result is SkillOrchestrator.Result.Failure) {
            assertTrue("Error message should mention entrypoint not found",
                result.reason.contains("entrypoint not found", ignoreCase = true))
        }
    }

    @Test
    fun `execute fails for unresolved tool`() = runBlocking {
        val skill = Skill(
            id = "test.skill",
            name = "Test Skill",
            enabled = true,
            state = SkillState.INSTALLED,
            entrypoints = listOf("nonexistent.tool")
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("test.skill")
        registry.enable("test.skill")

        // Tool registry is empty - tool doesn't exist
        val toolRegistry = ToolRegistry()

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "test.skill",
                entrypointId = "nonexistent.tool"
            )
        )

        assertTrue("Should fail for unresolved tool", result is SkillOrchestrator.Result.Failure)
        if (result is SkillOrchestrator.Result.Failure) {
            assertTrue("Error message should mention tool resolution",
                result.reason.contains("does not resolve to a registered tool", ignoreCase = true))
        }
    }

    @Test
    fun `execute propagates risk class correctly`() = runBlocking {
        val skill = Skill(
            id = "risk.skill",
            name = "Risk Skill",
            enabled = true,
            state = SkillState.INSTALLED,
            entrypoints = listOf("test.tool.read"),
            riskClass = RiskClass.READ_ONLY // skill is READ_ONLY
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("risk.skill")
        registry.enable("risk.skill")

        val toolRegistry = ToolRegistry()
        val tool = Tool(
            id = "test.tool.read",
            description = "A test tool",
            category = "test",
            riskClass = RiskClass.READ_ONLY
        )
        toolRegistry.register(tool)

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        // Request with higher risk than skill
        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "risk.skill",
                entrypointId = "test.tool.read",
                requestedRiskClass = RiskClass.DESTRUCTIVE
            )
        )

        assertTrue("Should succeed", result is SkillOrchestrator.Result.Success)
        if (result is SkillOrchestrator.Result.Success) {
            assertEquals("Effective risk should be the requested (higher) risk", RiskClass.DESTRUCTIVE, result.task.riskClass)
        }
    }

    @Test
    fun `execute preserves skill provenance via createdAtBy`() = runBlocking {
        val skill = Skill(
            id = "provenance.skill",
            name = "Provenance Skill",
            enabled = true,
            state = SkillState.INSTALLED,
            entrypoints = listOf("test.tool.read")
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("provenance.skill")
        registry.enable("provenance.skill")

        val toolRegistry = ToolRegistry()
        val tool = Tool(
            id = "test.tool.read",
            description = "A test tool",
            category = "test",
            riskClass = RiskClass.READ_ONLY
        )
        toolRegistry.register(tool)

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "provenance.skill",
                entrypointId = "test.tool.read"
            )
        )

        assertTrue("Should succeed", result is SkillOrchestrator.Result.Success)
        if (result is SkillOrchestrator.Result.Success) {
            assertEquals("createdAtBy should preserve skill ID for provenance tracking", "provenance.skill", result.task.createdAtBy)
        }
    }

    @Test
    fun `execute passes detail map to task`() = runBlocking {
        val skill = Skill(
            id = "detail.skill",
            name = "Detail Skill",
            enabled = true,
            state = SkillState.INSTALLED,
            entrypoints = listOf("test.tool.read")
        )
        val registry = SkillRegistry(TestStorage())
        registry.register(skill)
        registry.trust("detail.skill")
        registry.enable("detail.skill")

        val toolRegistry = ToolRegistry()
        val tool = Tool(
            id = "test.tool.read",
            description = "A test tool",
            category = "test",
            riskClass = RiskClass.READ_ONLY
        )
        toolRegistry.register(tool)

        val taskEngine = TaskEngine(
            storage = TestStorage(),
            eventBus = EventBus(),
            runner = TestRunner(),
            maxConcurrent = 4
        )

        val orchestrator = SkillOrchestrator(registry, toolRegistry, taskEngine)

        val detailMap = mapOf("key1" to "value1", "key2" to "value2")

        val result = orchestrator.execute(
            SkillOrchestrator.Request(
                skillId = "detail.skill",
                entrypointId = "test.tool.read",
                detail = detailMap
            )
        )

        assertTrue("Should succeed", result is SkillOrchestrator.Result.Success)
        if (result is SkillOrchestrator.Result.Success) {
            assertNotNull("Task detail should not be null", result.task.detail)
        }
    }

    // ==================== Test Infrastructure ====================

    private class TestStorage : Storage {
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
    }

    private class TestRunner(private val failure: Boolean = false) : TaskRunner {
        override suspend fun run(task: Task): kotlin.Result<Map<String, Any>> {
            return if (failure) kotlin.Result.failure(Exception("test failure")) 
            else kotlin.Result.success(mapOf("ok" to "ok"))
        }
    }
}