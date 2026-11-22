package org.aethra.core.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderInvocationContractTest {
    @Test
    fun `legacy chat adapts to normal provider response`() = runBlocking {
        val provider = ContractProvider(chatResult = Result.success(mapOf("content" to "answer", "model" to "m1")))

        val response = provider.invoke(ProviderRequest(messages = listOf(userMessage("question")))).getOrThrow()

        assertEquals("answer", response.content)
        assertEquals("m1", response.metadata["model"])
    }

    @Test
    fun `stream and tool-result continuation form deterministic exchange sequence`() = runBlocking {
        val call = ProviderToolCall(invocationId = "invoke-1", toolId = "lookup", arguments = mapOf("key" to "x"))
        val result = ProviderToolResult(invocationId = "invoke-1", output = "found")
        val provider = ContractProvider(streams = listOf(
            listOf(ProviderStreamEvent.TextChunk("one"), ProviderStreamEvent.TextChunk("two"), ProviderStreamEvent.ToolCallRequested(call)),
            listOf(ProviderStreamEvent.TextChunk("three"), ProviderStreamEvent.Completed(ProviderResponse("onetwothree")))
        ))
        val request = ProviderRequest(messages = listOf(userMessage("question")))

        val first = provider.stream(request).toList()
        val second = provider.stream(request.copy(toolResults = listOf(result))).toList()

        assertEquals(listOf("one", "two", call), listOf(
            (first[0] as ProviderStreamEvent.TextChunk).text,
            (first[1] as ProviderStreamEvent.TextChunk).text,
            (first[2] as ProviderStreamEvent.ToolCallRequested).call
        ))
        assertEquals("three", (second[0] as ProviderStreamEvent.TextChunk).text)
        assertTrue(second.last() is ProviderStreamEvent.Completed)
        assertEquals(result, provider.requests.last().toolResults.single())
        val exchange = listOf(
            "chunk:${(first[0] as ProviderStreamEvent.TextChunk).text}",
            "chunk:${(first[1] as ProviderStreamEvent.TextChunk).text}",
            "tool_call:${(first[2] as ProviderStreamEvent.ToolCallRequested).call.invocationId}",
            "tool_result:${provider.requests.last().toolResults.single().invocationId}",
            "chunk:${(second[0] as ProviderStreamEvent.TextChunk).text}",
            "completed"
        )
        assertEquals(listOf("chunk:one", "chunk:two", "tool_call:invoke-1", "tool_result:invoke-1", "chunk:three", "completed"), exchange)
    }

    @Test
    fun `tool call is structured and retains invocation identifier`() = runBlocking {
        val call = ProviderToolCall("call-7", "search", mapOf("query" to "aethra"))
        val provider = ContractProvider(invokeResponses = listOf(ProviderResponse(toolCalls = listOf(call))))

        val response = provider.invoke(ProviderRequest(messages = emptyList())).getOrThrow()

        assertEquals("call-7", response.toolCalls.single().invocationId)
        assertEquals("search", response.toolCalls.single().toolId)
        assertEquals("aethra", response.toolCalls.single().arguments["query"])
    }

    @Test
    fun `tool result is supplied on continuation request`() = runBlocking {
        val call = ProviderToolCall("call-9", "lookup", mapOf("id" to 9))
        val result = ProviderToolResult("call-9", output = mapOf("value" to "ready"))
        val provider = ContractProvider(invokeResponses = listOf(
            ProviderResponse(toolCalls = listOf(call)),
            ProviderResponse(content = "continued")
        ))

        val initial = provider.invoke(ProviderRequest(messages = emptyList())).getOrThrow()
        val continued = provider.invoke(ProviderRequest(messages = emptyList(), toolResults = listOf(result))).getOrThrow()

        assertEquals(call, initial.toolCalls.single())
        assertEquals("continued", continued.content)
        assertEquals(result, provider.requests.last().toolResults.single())
    }

    @Test
    fun `legacy invocation failure is categorized`() = runBlocking {
        val provider = ContractProvider(chatResult = Result.failure(IllegalStateException("offline")))

        val failure = provider.invoke(ProviderRequest(emptyList())).exceptionOrNull()

        assertTrue(failure is ProviderInvocationException)
        assertEquals(ProviderFailureKind.INVOCATION, (failure as ProviderInvocationException).kind)
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun `legacy provider rejects unsupported tool-result continuation as protocol failure`() = runBlocking {
        val provider = ContractProvider()
        val request = ProviderRequest(
            messages = emptyList(),
            toolResults = listOf(ProviderToolResult(invocationId = "call-1", output = "done"))
        )

        val failure = provider.invoke(request).exceptionOrNull()

        assertTrue(failure is ProviderInvocationException)
        assertEquals(ProviderFailureKind.TOOL_PROTOCOL, (failure as ProviderInvocationException).kind)
    }

    @Test
    fun `streaming failure remains distinct from invocation failure`() = runBlocking {
        val failure = ProviderFailure(ProviderFailureKind.STREAMING, "stream ended unexpectedly")
        val provider = ContractProvider(streams = listOf(listOf(ProviderStreamEvent.Failed(failure))))

        val event = provider.stream(ProviderRequest(emptyList())).toList().single() as ProviderStreamEvent.Failed

        assertEquals(ProviderFailureKind.STREAMING, event.failure.kind)
    }

    @Test
    fun `cancellation is representable and coroutine cancellation propagates`() = runBlocking {
        val provider = ContractProvider(streams = listOf(listOf(ProviderStreamEvent.Cancelled)))
        val cancellationEvent = provider.stream(ProviderRequest(emptyList())).toList().single()
        assertEquals(ProviderStreamEvent.Cancelled, cancellationEvent)

        val throwingProvider = ContractProvider(streamFailure = CancellationException("cancelled"))
        var propagated = false
        try {
            throwingProvider.stream(ProviderRequest(emptyList())).toList()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)

        val returnedCancellationProvider = ContractProvider(chatResult = Result.failure(CancellationException("cancelled")))
        propagated = false
        try {
            returnedCancellationProvider.stream(ProviderRequest(emptyList())).toList()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun `contract tests use no network transport`() = runBlocking {
        val provider = ContractProvider()

        provider.invoke(ProviderRequest(listOf(userMessage("local")))).getOrThrow()

        assertFalse(provider.networkUsed)
    }

    @Test
    fun `fallback policy remains opt in`() {
        assertFalse(FallbackPolicy(providerId = "primary").enabled)
        assertFalse(FallbackPolicy(providerId = "primary").fallbackTo != null)
    }

    private fun userMessage(content: String) = mapOf("role" to "user", "content" to content)

    private class ContractProvider(
        private val chatResult: Result<Map<String, Any>> = Result.success(mapOf("content" to "legacy")),
        private val invokeResponses: List<ProviderResponse>? = null,
        private val streams: List<List<ProviderStreamEvent>> = emptyList(),
        private val streamFailure: Throwable? = null
    ) : LanguageModelProvider {
        override val id = "contract-test"
        override val displayName = "Contract Test"
        override val supportsToolCalling = true
        override val supportsStreaming = true
        override val models = listOf("test-model")
        val requests = mutableListOf<ProviderRequest>()
        var networkUsed = false
        private var responseIndex = 0
        private var streamIndex = 0

        override fun capabilities() = emptyMap<String, Any>()

        override suspend fun chat(
            messages: List<Map<String, Any>>,
            availableTools: List<Map<String, Any>>?
        ): Result<Map<String, Any>> = chatResult

        override suspend fun invoke(request: ProviderRequest): Result<ProviderResponse> {
            requests += request
            val scriptedResponses = invokeResponses ?: return super<LanguageModelProvider>.invoke(request)
            return Result.success(scriptedResponses.getOrNull(responseIndex++) ?: ProviderResponse())
        }

        override fun stream(request: ProviderRequest): Flow<ProviderStreamEvent> {
            if (streams.isEmpty() && streamFailure == null) {
                return super<LanguageModelProvider>.stream(request)
            }
            return flow {
                requests += request
                streamFailure?.let { throw it }
                streams.getOrNull(streamIndex++)?.forEach { emit(it) }
                    ?: emit(ProviderStreamEvent.Completed(ProviderResponse(content = "response")))
            }
        }

        override suspend fun health(): Result<Boolean> = Result.success(true)
    }
}
