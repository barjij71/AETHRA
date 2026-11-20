package org.aethra.core.providers

import org.aethra.core.security.AuditLog
import org.aethra.core.storage.Storage

/**
 * Model Registry.
 *
 * Purpose: Register and manage AI models independently from providers.
 *
 * Key principles:
 *  - Model and provider are distinct concepts.
 *  - Model capabilities include: context limit, vision, audio, tool support,
 *    streaming support, local/cloud, etc.
 *  - Unknown capabilities are represented as "unknown" rather than being
 *    invented or assumed.
 *  - The registry does NOT make routing decisions; the router does that.
 */

data class ModelDefinition(
    /** Model creator/organization (e.g., "Upstage", "OpenAI", "Anthropic") */
    val creator: String,
    /** Model ID as known by the system (e.g., "solar-mini-4") */
    val modelId: String,
    /** Display name shown in UI (e.g., "Solar Mini 4") */
    val displayName: String,
    /** Access provider (the provider that hosts this model, e.g., "openrouter", "ollama") */
    val accessProvider: String,
    /** Model route/endpoint */
    val route: String,
    /** Capabilities: "tool_calling", "vision", "audio", "streaming", "reasoning" */
    val capabilities: Set<String>,
    /** Context limit if known; null if unknown */
    val contextLimit: Long?,
    /** Whether this model is local or cloud */
    val isLocal: Boolean,
    /** Availability status */
    val availability: String,
    /** Health status */
    val health: String,
    /** Input/output support */
    val inputOutput: String,
    /** Whether this model supports tools */
    val supportsTools: Boolean,
    /** Whether this model supports vision */
    val supportsVision: Boolean,
    /** Whether this model supports audio */
    val supportsAudio: Boolean,
    /** Whether this model supports streaming */
    val supportsStreaming: Boolean,
    /** Whether this model supports reasoning */
    val supportsReasoning: Boolean
)

class ModelRegistry(
    private val storage: Storage,
    private val auditLog: AuditLog
) {
    private val models = LinkedHashMap<String, ModelDefinition>()

    fun register(model: ModelDefinition): Boolean {
        if (models.containsKey(model.modelId)) {
            return false
        }
        models[model.modelId] = model
        persist()
        return true
    }

    fun unregister(modelId: String): Boolean {
        if (!models.containsKey(modelId)) {
            return false
        }
        models.remove(modelId)
        persist()
        return true
    }

    fun get(modelId: String): ModelDefinition? = models[modelId]

    fun list(): List<ModelDefinition> = models.values.sortedBy { it.modelId }.toList()

    fun findByCreator(creator: String): List<ModelDefinition> {
        return models.values.filter { it.creator == creator }.toList()
    }

    fun findByAccessProvider(provider: String): List<ModelDefinition> {
        return models.values.filter { it.accessProvider == provider }.toList()
    }

    fun findByCapability(capability: String): List<ModelDefinition> {
        return models.values.filter { it.capabilities.contains(capability) }.toList()
    }

    fun findByModelId(modelId: String): ModelDefinition? = models[modelId]

    private fun persist() {
        // TODO: Persist to storage when storage implementation is available
    }
}
