package org.aethra.core.skills

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aethra.core.domain.RiskClass
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.runtime.events.EventBus

class SkillTaskRunnerTest {
    private fun registry(): SkillRegistry {
        return SkillRegistry(DependencyTestStorage())
    }

    private fun activeSkill(registry: SkillRegistry, id: String): Skill {
        val skill = Skill(
            id = id,
            name = id,
            version = "1.0.0",
            entrypoints = listOf("run"),
            riskClass = RiskClass.READ_ONLY
        )
        assertTrue(registry.register(skill))
        assertTrue(registry.install(id))
        assertTrue(registry.trust(id))
        assertTrue(registry.enable(id))
        return registry.get(id)!!
    }

    @Test
    fun taskEngineCanExecuteSkillThroughNormalTaskRunnerBoundary() = runBlocking {
        val registry = registry()
        activeSkill(registry, "skill-a")
        val schemas = ArtifactSchemaRegistry().apply {
            register(ArtifactSchema("text.v1", "1.0", requiredFields = listOf("value")))
        }
        val store = ArtifactStore(schemas)
        val skillEngine = SkillExecutionEngine(registry, store)
        val runner = SkillTaskRunner(
            skillEngine = skillEngine,
            implementations = mapOf(
                "run" to SkillImplementation {
                    SkillArtifact(
                        id = "artifact-a",
                        schemaId = "text.v1",
                        schemaVersion = "1.0",
                        producerSkillId = "skill-a",
                        producerSkillVersion = "1.0.0",
                        payload = """{"value":"hello"}"""
                    )
                }
            )
        )
        val taskEngine = TaskEngine(
            storage = DependencyTestStorage(),
            eventBus = EventBus(),
            runner = runner,
            maxConcurrent = 1
        )

        val task = Task(
            goal = "execute skill-a",
            riskClass = RiskClass.READ_ONLY,
            skillExecutionSpec = SkillExecutionSpec(
                skillId = "skill-a",
                skillVersion = "1.0.0",
                entrypointId = "run",
                outputSchemaId = "text.v1",
                outputSchemaVersion = "1.0"
            )
        )

        taskEngine.register(task)
        taskEngine.run(task.id)

        while (taskEngine.getTask(task.id)?.state !in setOf(
            org.aethra.core.domain.TaskState.COMPLETED,
            org.aethra.core.domain.TaskState.FAILED
        )) {
            kotlinx.coroutines.delay(10)
        }

        assertEquals(org.aethra.core.domain.TaskState.COMPLETED, taskEngine.getTask(task.id)?.state)
        assertEquals("artifact-a", store.get("artifact-a")?.id)
    }
}
