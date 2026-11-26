package org.aethra.core.skills

import java.util.concurrent.ConcurrentHashMap

class ArtifactStore(private val schemas: ArtifactSchemaRegistry) {
    private val artifacts = ConcurrentHashMap<String, SkillArtifact>()

    fun put(artifact: SkillArtifact): Result<SkillArtifact> = runCatching {
        artifact.validate().getOrThrow()
        schemas.requireCompatible(artifact).getOrThrow()
        require(artifacts.putIfAbsent(artifact.id, artifact) == null) {
            "artifact already exists: ${artifact.id}"
        }
        artifact
    }

    fun get(id: String): SkillArtifact? = artifacts[id]

    fun require(id: String, schemaId: String, schemaVersion: String): SkillArtifact {
        val artifact = artifacts[id] ?: throw NoSuchElementException("artifact not found: $id")
        require(artifact.schemaId == schemaId && artifact.schemaVersion == schemaVersion) {
            "artifact schema mismatch: expected $schemaId@$schemaVersion, got ${artifact.schemaId}@${artifact.schemaVersion}"
        }
        return artifact
    }

    fun all(): List<SkillArtifact> = artifacts.values.sortedBy { it.createdAt }
}
