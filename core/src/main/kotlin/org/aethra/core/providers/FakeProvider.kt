package org.aethra.core.providers

import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.aethra.core.runtime.events.EventBus
import java.util.UUID

/**
 * Deterministic fake provider used for testing and for core-level demos.
 *
 * IMPORTANT: this is a stand-in, not a real intelligence service. It never
 * performs inference, never calls external APIs and never stores private data.
 * Features where a real provider is required (voice, streaming, tool-running) are
 * documented as unimplemented when this provider is in use.
 */
class FakeProvider(
    id: String = "fake",
    displayName: String = "Fake Tester",
    models: List<String> = listOf("fake-v1"),
    storage: Storage,
    eventBus: EventBus
) : LanguageModelProvider {
    override val id: String = id
    override val displayName: String = displayName
    override val supportsToolCalling: Boolean = false
    override val supportsStreaming: Boolean = false
    override val models: List<String> = models

    private val build = "open"

    override fun capabilities(): Map<String, Any> = mapOf(
        "supportsToolCalling" to supportsToolCalling,
        "supportsStreaming" to supportsStreaming,
        "modelCapabilities" to models
    )

    override suspend fun chat(
        messages: List<Map<String, Any>>,
        availableTools: List<Map<String, Any>>?
    ): Result<Map<String, Any>> = try {
        val last = messages.lastOrNull() ?: throw NoSuchElementException("no messages")
        val text = last["content"]?.toString()?.lowercase() ?: ""
        val reply = when {
            text.contains("hello") || text.contains("hi") || text.contains("hey") ->
                "Hello! This is the Fake Provider. Nothing was inferred here — the reply is generated deterministically. Ask about memory, tasks or tools and I will answer from this stand-in."
            text.contains("memory") -> "Fake memory: this is not persistent. Store a real fact via the MemoryEngine to have it remain after a restart."
            text.contains("task") || text.contains("work") || text.contains("run") ->
                "Task stand-in: a queued task is represented in the TaskEngine. No job ran — the fake provider cannot execute code."
            text.contains("tool") || text.contains("call") ->
                "Tool stand-in: tool calls are claimed here but never executed by the fake provider."
            text.contains("fallback") || text.contains("provider") ->
                "Provider routing: new providers are not auto-fallbacks; you must enable fallback per provider."
            else -> "This is a deterministic stand-in response. No model inference ran. Ask me for tasks, memory or tools and I will say so clearly."
        }
        val replyMap = mapOf(
            "id" to UUID.randomUUID().toString(),
            "model" to models.first(),
            "content" to reply,
            "provider" to id,
            "usage" to mapOf("promptTokens" to 1, "completionTokens" to 2),
            "finishReason" to "stop"
        )
        Result.success(replyMap)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    override suspend fun health(): Result<Boolean> = Result.success(true)
}
