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
    val timeoutMs: Long = 30_000
)

data class ManifestValidation(val valid: Boolean, val errors: List<String>)

object SkillManifestValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
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
        }
        return ManifestValidation(errors.isEmpty(), errors)
    }

    fun parseAndValidate(json: String): Result<SkillManifest> = runCatching {
        val manifest = Json { ignoreUnknownKeys = false }.decodeFromString<SkillManifest>(json)
        require(validate(manifest).valid) { validate(manifest).errors.joinToString("; ") }
        manifest
    }

    fun schema(): JsonObject = Json.parseToJsonElement(
        """{"type":"object","required":["id","version","name","source","license","inputSchemaVersion","outputSchemaVersion"],"properties":{"id":{"type":"string"},"version":{"type":"string"},"name":{"type":"string"},"source":{"type":"string"},"license":{"type":"string"},"inputSchemaVersion":{"type":"string"},"outputSchemaVersion":{"type":"string"}}}"""
    ).jsonObject
}
