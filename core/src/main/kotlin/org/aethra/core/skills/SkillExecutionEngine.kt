package org.aethra.core.skills

/**
 * Core-owned execution contract for a skill invocation.
 *
 * The implementation is supplied by an adapter/fake provider, but it cannot
 * bypass Core validation: the registry gates trust/activation, input artifacts
 * are read through the Core artifact store, and the returned artifact must
 * match the invocation's producer and output schema before it is committed.
 */
data class SkillInvocation(
    val skillId: String,
    val skillVersion: String,
    val entrypointId: String,
    val inputArtifactIds: List<String> = emptyList(),
    val outputSchemaId: String,
    val outputSchemaVersion: String
)

data class SkillExecutionContext(
    val invocation: SkillInvocation,
    val inputArtifacts: List<SkillArtifact>
)

fun interface SkillImplementation {
    suspend fun execute(context: SkillExecutionContext): SkillArtifact
}

class SkillExecutionEngine(
    private val registry: SkillRegistry,
    private val artifactStore: ArtifactStore
) {
    suspend fun execute(
        invocation: SkillInvocation,
        implementation: SkillImplementation
    ): Result<SkillArtifact> = runCatching {
        val skill = registry.get(invocation.skillId)
            ?: throw IllegalArgumentException("unknown skill: ${invocation.skillId}")

        require(skill.isActive) {
            "skill is not executable: ${skill.id} (${skill.trustState}/${skill.state})"
        }
        require(skill.version == invocation.skillVersion) {
            "skill version mismatch: expected ${skill.version}, got ${invocation.skillVersion}"
        }
        require(invocation.entrypointId in skill.entrypoints) {
            "entrypoint is not declared by skill: ${invocation.entrypointId}"
        }

        val inputs = invocation.inputArtifactIds.map { id ->
            artifactStore.get(id)
                ?: throw IllegalArgumentException("input artifact not found: $id")
        }

        val output = implementation.execute(
            SkillExecutionContext(invocation = invocation, inputArtifacts = inputs)
        )

        require(output.producerSkillId == skill.id) {
            "artifact producer does not match executing skill"
        }
        require(output.producerSkillVersion == skill.version) {
            "artifact producer version does not match executing skill"
        }
        require(output.schemaId == invocation.outputSchemaId) {
            "artifact output schema mismatch"
        }
        require(output.schemaVersion == invocation.outputSchemaVersion) {
            "artifact output schema version mismatch"
        }

        artifactStore.put(output).getOrThrow()
    }
}
