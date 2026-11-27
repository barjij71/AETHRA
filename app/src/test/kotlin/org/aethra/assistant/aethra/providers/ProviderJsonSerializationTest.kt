package org.aethra.assistant.aethra.providers

import org.aethra.core.providers.ProviderRequest
import org.aethra.core.providers.ProviderResponse
import org.aethra.core.providers.ProviderToolCall
import org.aethra.core.providers.ProviderToolResult
import kotlinx.serialization.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromString
import kotlinx.serialization.json.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests JSON serialization/deserialization of [ProviderRequest] and [ProviderResponse].
 *
 * These are pure Kotlin serialization tests — no Android SDK required.
 */
class ProviderJsonSerializationTest {

    @Test
    fun providerRequestSerializesAndDeserializes() {
        val request = ProviderRequest(
            messages = listOf(
                mapOf("role" to "user", "content" to "Hello"),
                mapOf("role" to "assistant", "content" to "Hi there")
            ),
            availableTools = listOf(
                mapOf("name" to "file.read", "description" to "Read a file")
            ),
            toolResults = listOf(
                ProviderToolResult(invocationId = "inv-1", output = "file content"),
                ProviderToolResult(invocationId = "inv-2", error = "not found")
            )
        )

        val json = Json { ignoreUnknownKeys = true }.encodeToString(request)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderRequest>(json)

        assertNotNull(decoded)
        assertEquals(2, decoded.messages.size)
        assertEquals("user", decoded.messages[0]["role"])
        assertEquals("Hello", decoded.messages[0]["content"])
        assertEquals("assistant", decoded.messages[1]["role"])
        assertEquals("Hi there", decoded.messages[1]["content"])
        assertEquals(1, decoded.availableTools?.size)
        assertEquals("file.read", decoded.availableTools!![0]["name"])
        assertEquals(2, decoded.toolResults.size)
        assertEquals("inv-1", decoded.toolResults[0].invocationId)
        assertEquals("file content", decoded.toolResults[0].output)
        assertEquals("inv-2", decoded.toolResults[1].invocationId)
        assertEquals("not found", decoded.toolResults[1].error)
    }

    @Test
    fun providerResponseSerializesAndDeserializes() {
        val response = ProviderResponse(
            content = "Hello, world!",
            toolCalls = listOf(
                ProviderToolCall(invocationId = "call-1", toolId = "file.read", arguments = mapOf("path" to "/tmp/test.txt")),
                ProviderToolCall(invocationId = "call-2", toolId = "file.write", arguments = mapOf("path" to "/tmp/out.txt", "content" to "data"))
            ),
            metadata = mapOf("provider" to "android-provider", "model" to "default-model")
        )

        val json = Json { ignoreUnknownKeys = true }.encodeToString(response)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderResponse>(json)

        assertNotNull(decoded)
        assertEquals("Hello, world!", decoded.content)
        assertEquals(2, decoded.toolCalls.size)
        assertEquals("call-1", decoded.toolCalls[0].invocationId)
        assertEquals("file.read", decoded.toolCalls[0].toolId)
        assertEquals("/tmp/test.txt", decoded.toolCalls[0].arguments["path"])
        assertEquals("call-2", decoded.toolCalls[1].invocationId)
        assertEquals("file.write", decoded.toolCalls[1].toolId)
        assertEquals("android-provider", decoded.metadata["provider"])
        assertEquals("default-model", decoded.metadata["model"])
    }

    @Test
    fun providerRequestWithEmptyValuesSerializesCorrectly() {
        val request = ProviderRequest(
            messages = listOf(mapOf("role" to "user", "content" to "test"))
        )

        val json = Json { ignoreUnknownKeys = true }.encodeToString(request)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderRequest>(json)

        assertNotNull(decoded)
        assertEquals(1, decoded.messages.size)
        assertEquals("user", decoded.messages[0]["role"])
        assertEquals("test", decoded.messages[0]["content"])
        assertNull(decoded.availableTools)
        assertTrue(decoded.toolResults.isEmpty())
    }

    @Test
    fun providerResponseWithNullContentSerializesCorrectly() {
        val response = ProviderResponse(
            content = null,
            toolCalls = emptyList(),
            metadata = emptyMap()
        )

        val json = Json { ignoreUnknownKeys = true }.encodeToString(response)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderResponse>(json)

        assertNotNull(decoded)
        assertNull(decoded.content)
        assertTrue(decoded.toolCalls.isEmpty())
        assertTrue(decoded.metadata.isEmpty())
    }
}
