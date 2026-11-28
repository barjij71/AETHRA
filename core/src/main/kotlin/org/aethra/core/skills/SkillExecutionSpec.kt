package org.aethra.core.skills

import kotlinx.serialization.Serializable

/**
 * Durable Core-owned binding between a Task and one Skill entrypoint.
 *
 * The task engine remains the execution authority; this value only declares
 * which validated skill contract the TaskRunner should invoke.
 */
@Serializable
data class SkillExecutionSpec(
    val skillId: String,
    val skillVersion: String,
    val entrypointId: String,
    val inputArtifactIds: List<String> = emptyList(),
    val outputSchemaId: String,
    val outputSchemaVersion: String
) {
    fun toInvocation(): SkillInvocation = SkillInvocation(
        skillId = skillId,
        skillVersion = skillVersion,
        entrypointId = entrypointId,
        inputArtifactIds = inputArtifactIds,
        outputSchemaId = outputSchemaId,
        outputSchemaVersion = outputSchemaVersion
    )
}
