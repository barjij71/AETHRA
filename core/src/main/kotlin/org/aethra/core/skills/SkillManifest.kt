package org.aethra.core.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class SkillManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String = "",
    val source: String,
    val license: String,
    val inputSchemaVersion: String,
    val outputSchemaVersion: String,
    val requestedCapabilities: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val trusted: Boolean = false,
    val enabled: Boolean = false,
    val idempotent: Boolean = true,
    val timeoutMs: Long = 30_000,
    val inputSchema: JsonObject? = null,
    val outputSchema: JsonObject? = null
)

data class ManifestValidation(val valid: Boolean, val errors: List<String>)

object SkillManifestValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")

    fun validate(manifest: SkillManifest): ManifestValidation {
        val errors = buildList {
            if (!idPattern.matches(manifest.id)) add("invalid id")
            if (!versionPattern.matches(manifest.version)) add("version must be semver")
            if (manifest.name.isBlank()) add("name must not be blank")
            if (manifest.source.isBlank()) add("source must not be blank")
            if (manifest.license.isBlank()) add("license must not be blank")
            if (manifest.inputSchemaVersion.isBlank()) add("input schema version required")
            if (manifest.outputSchemaVersion.isBlank()) add("output schema version required")
            if (manifest.timeoutMs !in 1..300_000) add("timeoutMs must be 1..300000")
            if (manifest.requestedCapabilities.any { it.isBlank() }) add("blank capability")
            manifest.inputSchema?.let { schema ->
                if ("type" !in schema) add("input schema must declare a root type")
                JsonSchemaValidator.validateSchema(schema).forEach { add("invalid input schema: " + it) }
            }
            manifest.outputSchema?.let { schema ->
                if ("type" !in schema) add("output schema must declare a root type")
                JsonSchemaValidator.validateSchema(schema).forEach { add("invalid output schema: " + it) }
            }
        }
        return ManifestValidation(errors.isEmpty(), errors)
    }

    fun parseAndValidate(json: String): Result<SkillManifest> = runCatching {
        val manifest = Json { ignoreUnknownKeys = false }.decodeFromString<SkillManifest>(json)
        val validation = validate(manifest)
        require(validation.valid) { validation.errors.joinToString("; ") }
        manifest
    }

    fun schema(): JsonObject = Json.parseToJsonElement(
        """{"type":"object","additionalProperties":false,"required":["id","version","name","source","license","inputSchemaVersion","outputSchemaVersion"],"properties":{"id":{"type":"string","pattern":"^[a-z0-9][a-z0-9._-]{0,63}$"},"version":{"type":"string","pattern":"^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$"},"name":{"type":"string","minLength":1},"description":{"type":"string"},"source":{"type":"string","minLength":1},"license":{"type":"string","minLength":1},"inputSchemaVersion":{"type":"string","minLength":1},"outputSchemaVersion":{"type":"string","minLength":1},"requestedCapabilities":{"type":"array","items":{"type":"string","minLength":1},"uniqueItems":true},"dependencies":{"type":"array","items":{"type":"string","minLength":1},"uniqueItems":true},"trusted":{"type":"boolean"},"enabled":{"type":"boolean"},"idempotent":{"type":"boolean"},"timeoutMs":{"type":"integer","minimum":1,"maximum":300000},"inputSchema":{"type":"object"},"outputSchema":{"type":"object"}}}"""
    ).jsonObject
}
