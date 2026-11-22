package org.aethra.core.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState
import org.aethra.core.providers.*
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProviderPrimaryFallbackIntegrationTest {
    @Test
    fun `primary success is registry routed and does not invoke fallback`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.success(ProviderResponse("primary answer")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("backup answer")) },
            fallbackEnabled = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY)).getOrThrow()

        assertEquals("primary answer", result["content"])
        assertEquals("default", result["primaryProviderId"])
        assertEquals("default", result["providerId"])
        assertEquals(false, result["usedFallback"])
        assertEquals(1, h.primary.invocationCount.get())
        assertEquals(0, h.fallback!!.invocationCount.get())
    }

    @Test
    fun `primary invocation failure uses explicitly eligible fallback`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.failure(IllegalStateException("offline")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("backup answer")) },
            fallbackEnabled = true,
            fallbackEligible = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY)).getOrThrow()

        assertEquals("backup answer", result["content"])
        assertEquals(true, result["usedFallback"])
        assertEquals("default", result["primaryProviderId"])
        assertEquals("backup", result["fallbackProviderId"])
        assertEquals("backup", result["providerId"])
        assertEquals(1, h.fallback!!.invocationCount.get())
    }

    @Test
    fun `primary failure without registered eligible candidate returns failure`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.failure(IllegalStateException("primary failed")) },
            fallback = null,
            fallbackEnabled = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY))

        assertTrue(result.isFailure)
        assertEquals(ProviderFailureKind.INVOCATION, (result.exceptionOrNull() as TaskExecutionException).providerFailureKind)
        assertEquals(1, h.primary.invocationCount.get())
    }

    @Test
    fun `unavailable primary is classified and does not invoke fallback`() = runBlocking {
        val storage = MemoryStorage()
        val audit = AuditLog(storage)
        val providers = ProviderRegistry(storage, audit)
        val models = ModelRegistry(storage, audit)
        val runner = ProviderTaskRunner(ProviderModelRouter(providers, models), providers, audit)

        val result = runner.run(Task("missing provider", riskClass = RiskClass.READ_ONLY))

        assertTrue(result.isFailure)
        assertEquals(ProviderFailureKind.PROVIDER_UNAVAILABLE,
            (result.exceptionOrNull() as TaskExecutionException).providerFailureKind)
        assertTrue(audit.all().any { it.operation == "provider_select" && it.outcome == "PROVIDER_UNAVAILABLE" })
    }

    @Test
    fun `fallback policy rejection prevents fallback execution`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.failure(IllegalStateException("primary failed")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("must not run")) },
            fallbackEnabled = false,
            fallbackEligible = true
        )

        assertTrue(h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY)).isFailure)
        assertEquals(0, h.fallback!!.invocationCount.get())
        assertTrue(h.audit.all().any { it.operation == "provider_fallback" && it.decision == "DENIED" })
    }

    @Test
    fun `local only adaptive selection cannot use a remote policy fallback`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.failure(IllegalStateException("primary failed")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("must not run")) },
            fallbackEnabled = true,
            fallbackEligible = true,
            fallbackLocal = false,
            selectionContext = ProviderSelectionContext(
                privacyRequirement = PrivacyRequirement.LOCAL_ONLY
            )
        )

        val result = h.runner.run(Task("private answer", riskClass = RiskClass.READ_ONLY))

        assertTrue(result.isFailure)
        assertEquals(0, h.fallback!!.invocationCount.get())
        assertTrue(h.audit.all().any {
            it.operation == "provider_fallback" &&
                it.decision == "DENIED" &&
                it.outcome == "POLICY_REJECTED:privacy_requirement"
        })
    }

    @Test
    fun `ineligible fallback is never invoked even when policy names it`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.failure(IllegalStateException("primary failed")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("must not run")) },
            fallbackEnabled = true,
            fallbackEligible = false
        )

        assertTrue(h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY)).isFailure)
        assertEquals(0, h.fallback!!.invocationCount.get())
    }

    @Test
    fun `cancellation propagates without consulting or invoking fallback`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { throw CancellationException("caller cancelled") },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("must not run")) },
            fallbackEnabled = true,
            fallbackEligible = true
        )

        var cancelled = false
        try {
            h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, h.fallback!!.invocationCount.get())
        val entries = h.audit.all()
        assertFalse(entries.any { it.operation == "provider_fallback" })
        assertTrue(entries.any { it.operation == "provider_execute" && it.outcome == "CANCELLED" })
        assertTrue(entries.any { it.operation == "provider_result" && it.outcome == "CANCELLED" })
    }

    @Test
    fun `streaming failure may use the same explicitly allowed fallback`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider(
                id = "default",
                supportsStreaming = true,
                streamResult = { flow { emit(ProviderStreamEvent.Failed(ProviderFailure(ProviderFailureKind.STREAMING, "broken stream"))) } }
            ) { Result.success(ProviderResponse("unused")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("fallback after stream error")) },
            fallbackEnabled = true,
            fallbackEligible = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY)).getOrThrow()

        assertEquals("fallback after stream error", result["content"])
        assertEquals(1, h.primary.streamCount.get())
        assertEquals(1, h.fallback!!.invocationCount.get())
    }

    @Test
    fun `streaming failure with fallback denied remains a streaming failure`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider(
                id = "default",
                supportsStreaming = true,
                streamResult = { flow { emit(ProviderStreamEvent.Failed(ProviderFailure(ProviderFailureKind.STREAMING, "broken stream"))) } }
            ) { Result.success(ProviderResponse("unused")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("must not run")) },
            fallbackEnabled = false,
            fallbackEligible = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY))

        assertTrue(result.isFailure)
        assertEquals(ProviderFailureKind.STREAMING, (result.exceptionOrNull() as TaskExecutionException).providerFailureKind)
        assertEquals(0, h.fallback!!.invocationCount.get())
    }

    @Test
    fun `tool protocol failure is classified and does not trigger fallback`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") {
                Result.failure(ProviderInvocationException(ProviderFailureKind.TOOL_PROTOCOL, "invalid tool call"))
            },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("must not run")) },
            fallbackEnabled = true,
            fallbackEligible = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY))

        assertTrue(result.isFailure)
        assertEquals(ProviderFailureKind.TOOL_PROTOCOL, (result.exceptionOrNull() as TaskExecutionException).providerFailureKind)
        assertEquals(0, h.fallback!!.invocationCount.get())
    }

    @Test
    fun `provider identifiers and final outcome are traceable in result and audit`() = runBlocking {
        val h = harness(
            primary = ScriptedProvider("default") { Result.failure(IllegalStateException("primary failed")) },
            fallback = ScriptedProvider("backup") { Result.success(ProviderResponse("done")) },
            fallbackEnabled = true,
            fallbackEligible = true
        )

        val result = h.runner.run(Task("answer", riskClass = RiskClass.READ_ONLY)).getOrThrow()
        val entries = h.audit.all()

        assertEquals("default", result["primaryProviderId"])
        assertEquals("backup", result["fallbackProviderId"])
        assertTrue(entries.any { it.operation == "provider_select" && it.target == "default/primary-model" })
        assertTrue(entries.any { it.operation == "provider_execute" && it.target == "default/primary-model" && it.outcome == "FAILED_DURING_EXECUTION" })
        assertTrue(entries.any { it.operation == "provider_fallback" && it.target == "backup/fallback-model" && it.decision == "GRANTED" })
        assertTrue(entries.any { it.operation == "provider_execute" && it.target == "backup/fallback-model" && it.outcome == "SUCCESS" })
        assertTrue(entries.any { it.operation == "provider_result" && it.outcome == "SUCCESS_FALLBACK" })
    }

    @Test
    fun `TaskEngine remains execution authority and owns retry counter`() = runBlocking {
        val secondInvocation = CompletableDeferred<Unit>()
        val callCount = AtomicInteger()
        val primary = ScriptedProvider("default") {
            if (callCount.incrementAndGet() == 1) Result.failure(IllegalStateException("retryable"))
            else {
                secondInvocation.complete(Unit)
                Result.success(ProviderResponse("completed"))
            }
        }
        val h = harness(primary, fallback = null)
        val eventBus = EventBus()
        val completed = CompletableDeferred<Unit>()
        eventBus.subscribe(TaskEvent::class.java) { event ->
            if (event.source == "execution_completed") completed.complete(Unit)
        }
        val engine = TaskEngine(h.storage, eventBus, h.runner)
        try {
            val task = engine.create("through engine", riskClass = RiskClass.READ_ONLY)
            task.retryBudget = 1
            engine.run(task.id)
            withTimeout(5_000) { secondInvocation.await() }
            withTimeout(5_000) { completed.await() }

            assertEquals(TaskState.COMPLETED, task.state)
            assertEquals(2, callCount.get())
            assertEquals(1, task.retriesUsed)
        } finally {
            engine.close()
        }
    }

    private fun harness(
        primary: ScriptedProvider,
        fallback: ScriptedProvider?,
        fallbackEnabled: Boolean = false,
        fallbackEligible: Boolean = false,
        fallbackLocal: Boolean = true,
        selectionContext: ProviderSelectionContext = ProviderSelectionContext()
    ): Harness {
        val storage = MemoryStorage()
        val audit = AuditLog(storage)
        val providers = ProviderRegistry(storage, audit)
        val models = ModelRegistry(storage, audit)
        val runtime = mutableMapOf(primary.id to primary)
        if (fallback != null) runtime[fallback.id] = fallback
        val primaryDefinition = definition(primary.id, "primary-model", fallbackEligible = false, isLocal = true)
        check(providers.register(primaryDefinition))
        check(models.register(model(primary.id, "primary-model", primary.supportsStreaming, isLocal = true)))
        if (fallback != null) {
            check(providers.register(definition(fallback.id, "fallback-model", fallbackEligible, fallbackLocal)))
            check(models.register(model(fallback.id, "fallback-model", fallback.supportsStreaming, fallbackLocal)))
        }
        providers.registerProviderFactory(object : ProviderFactory {
            override fun create(providerDef: ProviderDefinition): LanguageModelProvider =
                runtime[providerDef.providerId] ?: error("Unexpected provider lookup: ${providerDef.providerId}")
        })
        val router = ProviderModelRouter(providers, models)
        if (fallback != null) {
            router.enableFallback(FallbackPolicy(primary.id, fallbackEnabled, fallback.id))
        }
        return Harness(
            storage,
            audit,
            providers,
            primary,
            fallback,
            ProviderTaskRunner(router, providers, audit, selectionContext = selectionContext)
        )
    }

    private fun definition(
        id: String,
        modelId: String,
        fallbackEligible: Boolean,
        isLocal: Boolean
    ) = ProviderDefinition(
        providerId = id,
        displayName = id,
        providerType = "test",
        endpoint = "test://$id",
        authenticationCapability = "none",
        supportedModels = listOf(modelId),
        capabilities = emptySet(),
        availability = "available",
        health = "healthy",
        isLocal = isLocal,
        isFallbackEligible = fallbackEligible
    )

    private fun model(
        providerId: String,
        modelId: String,
        streaming: Boolean,
        isLocal: Boolean
    ) = ModelDefinition(
        creator = "test",
        modelId = modelId,
        displayName = modelId,
        accessProvider = providerId,
        route = "test://$providerId/$modelId",
        capabilities = emptySet(),
        contextLimit = null,
        isLocal = isLocal,
        availability = "available",
        health = "healthy",
        inputOutput = "text",
        supportsTools = false,
        supportsVision = false,
        supportsAudio = false,
        supportsStreaming = streaming,
        supportsReasoning = false
    )

    private data class Harness(
        val storage: MemoryStorage,
        val audit: AuditLog,
        val registry: ProviderRegistry,
        val primary: ScriptedProvider,
        val fallback: ScriptedProvider?,
        val runner: ProviderTaskRunner
    )

    private class MemoryStorage : Storage {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?) = values[key]?.toLongOrNull() ?: defaultValue
        override suspend fun setLong(key: String, value: Long) { values[key] = value.toString() }
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = values[key]?.toBooleanStrictOrNull() ?: defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }

    private class ScriptedProvider(
        override val id: String,
        override val supportsStreaming: Boolean = false,
        private val streamResult: ((ProviderRequest) -> Flow<ProviderStreamEvent>)? = null,
        private val result: suspend (ProviderRequest) -> Result<ProviderResponse>
    ) : LanguageModelProvider {
        override val displayName = id
        override val supportsToolCalling = true
        override val models: List<String> = listOf("${id}-model")
        val invocationCount = AtomicInteger()
        val streamCount = AtomicInteger()
        override fun capabilities(): Map<String, Any> = emptyMap()
        override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
            result(ProviderRequest(messages, availableTools)).map { response ->
                buildMap {
                    response.content?.let { put("content", it) }
                    putAll(response.metadata)
                    if (response.toolCalls.isNotEmpty()) put("toolCalls", response.toolCalls)
                }
            }
        override suspend fun invoke(request: ProviderRequest): Result<ProviderResponse> {
            invocationCount.incrementAndGet()
            return result(request)
        }
        override fun stream(request: ProviderRequest): Flow<ProviderStreamEvent> {
            val scripted = streamResult ?: return super<LanguageModelProvider>.stream(request)
            return flow {
                streamCount.incrementAndGet()
                scripted(request).collect { emit(it) }
            }
        }
        override suspend fun health(): Result<Boolean> = Result.success(true)
    }
}
