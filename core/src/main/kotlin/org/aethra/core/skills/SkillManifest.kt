package org.aethra.core.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.aethra.core.domain.RiskClass

/** Versioned, Core-owned wire contract. Parsing never grants trust or activation. */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val source: SourceMetadata = SourceMetadata(),
    val description: String = "",
    val entrypoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val integrity: IntegrityInfo? = null,
    val enabled: Boolean = false,
    val state: SkillState = SkillState.DISCOVERED,
    val contractVersion: String = CURRENT_CONTRACT_VERSION
) {
    val isValid: Boolean
        get() = id.isNotBlank() && name.isNotBlank() && version.isNotBlank() &&
            contractVersion == CURRENT_CONTRACT_VERSION

    val isActive: Boolean
        get() = enabled && (state == SkillState.INSTALLED || state == SkillState.UPDATING)

    fun toUntrustedSkill(): Skill = Skill(
        id = id, name = name, version = version, source = source,
        description = description, entrypoints = entrypoints,
        capabilities = capabilities, requestedPermissions = requestedPermissions,
        dependencies = dependencies, platforms = platforms, riskClass = riskClass,
        integrity = integrity, enabled = false,
        trustState = SkillTrustState.UNTRUSTED, state = SkillState.DISCOVERED
    )

    companion object {
        const val CURRENT_CONTRACT_VERSION = "1.0"
        private val json = Json { ignoreUnknownKeys = false }

        const val JSON_SCHEMA = """{
  "type":"object","additionalProperties":false,
  "required":["contractVersion","id","name","version"],
  "properties":{
    "contractVersion":{"const":"1.0"},
    "id":{"type":"string","minLength":1},
    "name":{"type":"string","minLength":1},
    "version":{"type":"string","minLength":1},
    "entrypoints":{"type":"array","items":{"type":"string","minLength":1}},
    "capabilities":{"type":"array","items":{"type":"string","minLength":1}},
    "requestedPermissions":{"type":"array","items":{"type":"string","minLength":1}},
    "dependencies":{"type":"array","items":{"type":"string","minLength":1}},
    "platforms":{"type":"array","items":{"type":"string","minLength":1}},
    "riskClass":{"type":"string","enum":["READ_ONLY","SAFE","LOW","SENSITIVE","DESTRUCTIVE"]}
  }
}"""

        fun parse(raw: String): Result<SkillManifest> = runCatching {
            val root = json.parseToJsonElement(raw)
            require(root is JsonObject) { "manifest root must be a JSON object" }
            val allowed = setOf("contractVersion","id","name","version","entrypoints",
                "capabilities","requestedPermissions","dependencies","platforms","riskClass")
            require(root.keys.all { it in allowed }) {
                "unknown manifest field: ${root.keys.first { it !in allowed }}"
            }
            val contract = required(root, "contractVersion")
            require(contract == CURRENT_CONTRACT_VERSION) { "unsupported contractVersion: $contract" }
            val result = SkillManifest(
                id = required(root, "id"),
                name = required(root, "name"),
                version = required(root, "version"),
                entrypoints = strings(root, "entrypoints"),
                capabilities = strings(root, "capabilities"),
                requestedPermissions = strings(root, "requestedPermissions"),
                dependencies = strings(root, "dependencies"),
                platforms = strings(root, "platforms"),
                riskClass = risk(root)
            )
            require(result.isValid) { "manifest is invalid" }
            result
        }

        private fun required(root: JsonObject, key: String): String {
            val v = root[key]
            require(v is JsonPrimitive && v.isString && v.content.isNotBlank()) { "missing/invalid $key" }
            return v.content
        }

        private fun strings(root: JsonObject, key: String): List<String> {
            val v = root[key] ?: return emptyList()
            require(v is kotlinx.serialization.json.JsonArray) { "$key must be an array" }
            return v.mapIndexed { i, e ->
                require(e is JsonPrimitive && e.isString && e.content.isNotBlank()) { "$key[$i] invalid" }
                e.content
            }
        }

        private fun risk(root: JsonObject): RiskClass {
            val value = root["riskClass"]?.let { require(it is JsonPrimitive && it.isString) { "riskClass must be string" }; it.content }
                ?: "READ_ONLY"
            return runCatching { RiskClass.valueOf(value) }
                .getOrElse { throw IllegalArgumentException("unsupported riskClass: $value") }
        }
    }
}

/** Legacy conversion retained for callers that already validated the manifest. */
fun SkillManifest.toSkill(): Skill = toUntrustedSkill()
