package org.aethra.core.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class SkillArtifact(
    val id: String = UUID.randomUUID().toString(),
    val schemaId: String,
    val schemaVersion: String,
    val producerSkillId: String,
    val producerSkillVersion: String,
    val payload: String,
    val evidence: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun validate(): Result<Unit> = runCatching {
        require(schemaId.isNotBlank()) { "artifact schemaId is blank" }
        require(schemaVersion.isNotBlank()) { "artifact schemaVersion is blank" }
        require(producerSkillId.isNotBlank()) { "artifact producerSkillId is blank" }
        require(producerSkillVersion.isNotBlank()) { "artifact producerSkillVersion is blank" }
        val element = Json.parseToJsonElement(payload)
        require(element is JsonObject) { "artifact payload must be a JSON object" }
        require(element.keys.all { it.isNotBlank() }) { "artifact payload contains a blank field name" }
    }

    fun payloadObject(): JsonObject {
        validate().getOrThrow()
        return Json.parseToJsonElement(payload) as JsonObject
    }
}

class ArtifactSchemaRegistry {
    private val schemas = LinkedHashMap<String, ArtifactSchema>()

    fun register(schema: ArtifactSchema): Boolean {
        require(schema.id.isNotBlank()) { "schema id is blank" }
        require(schema.version.isNotBlank()) { "schema version is blank" }
        if (schemas.containsKey(key(schema.id, schema.version))) return false
        schemas[key(schema.id, schema.version)] = schema
        return true
    }

    fun requireCompatible(artifact: SkillArtifact): Result<Unit> = runCatching {
        artifact.validate().getOrThrow()
        val schema = schemas[key(artifact.schemaId, artifact.schemaVersion)]
            ?: throw IllegalArgumentException("unknown artifact schema ${artifact.schemaId}@${artifact.schemaVersion}")
        val payload = artifact.payloadObject()
        require(schema.requiredFields.all { payload.containsKey(it) }) {
            "artifact is missing required field(s): ${schema.requiredFields.joinToString()}"
        }
        require(schema.payloadType == "object") { "unsupported schema payload type: ${schema.payloadType}" }
    }

    private fun key(id: String, version: String) = "$id@$version"
}

@Serializable
data class ArtifactSchema(
    val id: String,
    val version: String,
    val payloadType: String = "object",
    val requiredFields: List<String> = emptyList()
)
