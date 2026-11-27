package org.aethra.core.skills

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aethra.core.domain.RiskClass

class SkillExecutionEngineTest {
    private fun registry(): SkillRegistry {
        return SkillRegistry(DependencyTestStorage())
    }

    private fun activeSkill(registry: SkillRegistry, id: String, version: String = "1.0.0"): Skill {
        val skill = Skill(
            id = id,
            name = id,
            version = version,
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
    fun trustedActiveSkillCanProduceValidatedArtifact() = runBlocking {
        val registry = registry()
        activeSkill(registry, "skill-a")
        val schemas = ArtifactSchemaRegistry().apply {
            register(ArtifactSchema("text.v1", "1.0", requiredFields = listOf("value")))
        }
        val store = ArtifactStore(schemas)
        val engine = SkillExecutionEngine(registry, store)

        val result = engine.execute(
            SkillInvocation(
                skillId = "skill-a",
                skillVersion = "1.0.0",
                entrypointId = "run",
                outputSchemaId = "text.v1",
                outputSchemaVersion = "1.0"
            )
        ) {
            SkillArtifact(
                id = "artifact-a",
                schemaId = "text.v1",
                schemaVersion = "1.0",
                producerSkillId = "skill-a",
                producerSkillVersion = "1.0.0",
                payload = """{"value":"hello"}"""
            )
        }

        assertTrue(result.isSuccess)
        assertEquals("artifact-a", result.getOrThrow().id)
        assertEquals("artifact-a", store.get("artifact-a")?.id)
    }

    @Test
    fun untrustedSkillCannotExecuteEvenWhenAdapterIsAvailable() = runBlocking {
        val registry = registry()
        val skill = Skill(id = "untrusted", name = "untrusted", entrypoints = listOf("run"))
        assertTrue(registry.register(skill))
        val schemas = ArtifactSchemaRegistry().apply {
            register(ArtifactSchema("text.v1", "1.0", requiredFields = listOf("value")))
        }
        val engine = SkillExecutionEngine(registry, ArtifactStore(schemas))

        val result = engine.execute(
            SkillInvocation("untrusted", "1.0.0", "run", outputSchemaId = "text.v1", outputSchemaVersion = "1.0")
        ) {
            error("adapter must never be invoked for an untrusted skill")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not executable"))
    }

    @Test
    fun undeclaredEntrypointIsRejectedByCore() = runBlocking {
        val registry = registry()
        activeSkill(registry, "skill-a")
        val schemas = ArtifactSchemaRegistry().apply {
            register(ArtifactSchema("text.v1", "1.0", requiredFields = listOf("value")))
        }
        val engine = SkillExecutionEngine(registry, ArtifactStore(schemas))

        val result = engine.execute(
            SkillInvocation("skill-a", "1.0.0", "forged", outputSchemaId = "text.v1", outputSchemaVersion = "1.0")
        ) {
            error("adapter must not execute")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("entrypoint is not declared"))
    }

    @Test
    fun forgedProducerOrSchemaIsRejectedBeforeArtifactCommit() = runBlocking {
        val registry = registry()
        activeSkill(registry, "skill-a")
        val schemas = ArtifactSchemaRegistry().apply {
            register(ArtifactSchema("text.v1", "1.0", requiredFields = listOf("value")))
        }
        val store = ArtifactStore(schemas)
        val engine = SkillExecutionEngine(registry, store)

        val producerMismatch = engine.execute(
            SkillInvocation("skill-a", "1.0.0", "run", outputSchemaId = "text.v1", outputSchemaVersion = "1.0")
        ) {
            SkillArtifact(
                id = "forged",
                schemaId = "text.v1",
                schemaVersion = "1.0",
                producerSkillId = "attacker",
                producerSkillVersion = "1.0.0",
                payload = """{"value":"x"}"""
            )
        }

        assertTrue(producerMismatch.isFailure)
        assertEquals(null, store.get("forged"))

        val schemaMismatch = engine.execute(
            SkillInvocation("skill-a", "1.0.0", "run", outputSchemaId = "text.v1", outputSchemaVersion = "1.0")
        ) {
            SkillArtifact(
                id = "wrong-schema",
                schemaId = "other.v1",
                schemaVersion = "1.0",
                producerSkillId = "skill-a",
                producerSkillVersion = "1.0.0",
                payload = """{"value":"x"}"""
            )
        }

        assertTrue(schemaMismatch.isFailure)
        assertEquals(null, store.get("wrong-schema"))
    }

    @Test
    fun inputArtifactMustExistBeforeAdapterRuns() = runBlocking {
        val registry = registry()
        activeSkill(registry, "skill-b")
        val schemas = ArtifactSchemaRegistry().apply {
            register(ArtifactSchema("text.v1", "1.0", requiredFields = listOf("value")))
        }
        val engine = SkillExecutionEngine(registry, ArtifactStore(schemas))

        val result = engine.execute(
            SkillInvocation(
                skillId = "skill-b",
                skillVersion = "1.0.0",
                entrypointId = "run",
                inputArtifactIds = listOf("missing"),
                outputSchemaId = "text.v1",
                outputSchemaVersion = "1.0"
            )
        ) {
            error("adapter must not execute with an unresolved input")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("input artifact not found"))
    }
}
