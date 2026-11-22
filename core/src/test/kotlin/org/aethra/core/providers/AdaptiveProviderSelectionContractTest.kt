package org.aethra.core.providers

import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AdaptiveProviderSelectionContractTest {
    @Test
    fun `required capability excludes a cheaper unsupported model`() {
        val harness = Harness()
        harness.addCandidate("cheap", capabilities = emptySet(), costTier = 0)
        harness.addCandidate("capable", capabilities = setOf("vision"), costTier = 5)

        val decision = harness.select(
            ProviderSelectionContext(requiredCapabilities = setOf("vision"))
        )

        assertSelected("capable", decision)
        val rejected = evidence(decision).candidate("cheap")
        assertEquals(SelectionCandidateStatus.INELIGIBLE, rejected.status)
        assertTrue(SelectionReason.MISSING_REQUIRED_CAPABILITY in rejected.reasons)
        assertEquals(setOf("vision"), rejected.missingCapabilities)
    }

    @Test
    fun `tool requirement excludes a candidate whose runtime cannot call tools`() {
        val harness = Harness()
        harness.addCandidate(
            "no-tools",
            capabilities = setOf("tool_calling"),
            supportsTools = true,
            runtimeSupportsTools = false,
            costTier = 0
        )
        harness.addCandidate(
            "tools",
            capabilities = setOf("tool_calling"),
            supportsTools = true,
            runtimeSupportsTools = true,
            costTier = 9
        )

        val decision = harness.select(ProviderSelectionContext(requiresTools = true))

        assertSelected("tools", decision)
        assertTrue(SelectionReason.TOOL_SUPPORT_MISSING in evidence(decision).candidate("no-tools").reasons)
    }

    @Test
    fun `streaming requirement excludes a candidate without end to end streaming support`() {
        val harness = Harness()
        harness.addCandidate("no-stream", supportsStreaming = false, costTier = 0)
        harness.addCandidate(
            "stream",
            capabilities = setOf("streaming"),
            supportsStreaming = true,
            runtimeSupportsStreaming = true,
            costTier = 4
        )

        val decision = harness.select(ProviderSelectionContext(requiresStreaming = true))

        assertSelected("stream", decision)
        assertTrue(SelectionReason.STREAMING_SUPPORT_MISSING in evidence(decision).candidate("no-stream").reasons)
    }

    @Test
    fun `local only privacy excludes remote provider and model`() {
        val harness = Harness()
        harness.addCandidate("remote", isLocal = false, costTier = 0)
        harness.addCandidate("local", isLocal = true, costTier = 7)

        val decision = harness.select(
            ProviderSelectionContext(privacyRequirement = PrivacyRequirement.LOCAL_ONLY)
        )

        assertSelected("local", decision)
        assertTrue(SelectionReason.LOCAL_PRIVACY_REQUIRED in evidence(decision).candidate("remote").reasons)
    }

    @Test
    fun `unavailable and unhealthy candidates cannot become primary`() {
        val harness = Harness()
        harness.addCandidate("provider-offline", providerAvailability = "unavailable", costTier = 0)
        harness.addCandidate("model-unhealthy", modelHealth = "unhealthy", costTier = 1)
        harness.addCandidate("ready", costTier = 8)

        val decision = harness.select()

        assertSelected("ready", decision)
        assertTrue(SelectionReason.PROVIDER_UNAVAILABLE in evidence(decision).candidate("provider-offline").reasons)
        assertTrue(SelectionReason.MODEL_UNHEALTHY in evidence(decision).candidate("model-unhealthy").reasons)
    }

    @Test
    fun `model owned by another provider is rejected`() {
        val harness = Harness()
        harness.addCandidate("mismatch", modelAccessProvider = "someone-else", costTier = 0)
        harness.addCandidate("valid", costTier = 3)

        val decision = harness.select()

        assertSelected("valid", decision)
        assertTrue(SelectionReason.MODEL_PROVIDER_MISMATCH in evidence(decision).candidate("mismatch").reasons)
    }

    @Test
    fun `candidate without runtime implementation is rejected with evidence`() {
        val harness = Harness()
        harness.addCandidate("metadata-only", registerRuntime = false, costTier = 0)
        harness.addCandidate("runtime", costTier = 6)

        val decision = harness.select()

        assertSelected("runtime", decision)
        assertTrue(
            SelectionReason.RUNTIME_PROVIDER_UNAVAILABLE in
                evidence(decision).candidate("metadata-only").reasons
        )
    }

    @Test
    fun `lower known cost wins after all eligibility dimensions are equal`() {
        val harness = Harness()
        harness.addCandidate("higher", costTier = 3)
        harness.addCandidate("lower", costTier = 1)

        val decision = harness.select()

        assertSelected("lower", decision)
        assertTrue(SelectionReason.LOWEST_KNOWN_COST in evidence(decision).candidate("lower").reasons)
        assertTrue(SelectionReason.HIGHER_COST in evidence(decision).candidate("higher").reasons)
    }

    @Test
    fun `known cost deterministically ranks before unknown cost`() {
        val harness = Harness()
        harness.addCandidate("unknown", costTier = null)
        harness.addCandidate("known", costTier = 9)

        val decision = harness.select()

        assertSelected("known", decision)
        assertTrue(
            SelectionReason.COST_UNKNOWN_RANKED_AFTER_KNOWN in
                evidence(decision).candidate("unknown").reasons
        )
    }

    @Test
    fun `negative cost tier is rejected instead of becoming an artificial cheapest value`() {
        val result = runCatching {
            ModelDefinition(
                creator = "test",
                modelId = "invalid-cost",
                displayName = "invalid-cost",
                accessProvider = "provider",
                route = "test://provider/invalid-cost",
                capabilities = emptySet(),
                contextLimit = null,
                isLocal = true,
                availability = "available",
                health = "healthy",
                inputOutput = "text",
                supportsTools = false,
                supportsVision = false,
                supportsAudio = false,
                supportsStreaming = false,
                supportsReasoning = false,
                costTier = -1
            )
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `provider then model identifiers break equal cost ties regardless of registration order`() {
        val harness = Harness()
        harness.addCandidate("zeta", modelId = "a-model", costTier = 2)
        harness.addCandidate("alpha", modelId = "z-model", costTier = 2)

        val decision = harness.select()

        assertSelected("alpha", decision)
        assertTrue(SelectionReason.DETERMINISTIC_TIE_BREAK in evidence(decision).candidate("alpha").reasons)
        assertTrue(SelectionReason.DETERMINISTIC_TIE_BREAK in evidence(decision).candidate("zeta").reasons)
    }

    @Test
    fun `selection exposes structured evidence without provider endpoint data`() {
        val harness = Harness()
        harness.addCandidate("selected", costTier = 1)
        harness.addCandidate("ranked-lower", costTier = 4)

        val decision = harness.select()
        val evidence = evidence(decision)

        assertEquals(
            SelectionRankingRule.ELIGIBILITY_THEN_KNOWN_COST_THEN_DEFAULT_THEN_PROVIDER_MODEL_ID,
            evidence.rankingRule
        )
        assertEquals(2, evidence.candidates.size)
        assertEquals(SelectionCandidateStatus.SELECTED, evidence.candidate("selected").status)
        assertEquals(SelectionCandidateStatus.LOWER_RANKED, evidence.candidate("ranked-lower").status)
        assertTrue(evidence.toString().contains("test://").not())
    }

    @Test
    fun `selection is metadata only and never performs a provider health call`() {
        val harness = Harness()
        val provider = harness.addCandidate("local-metadata", costTier = 1)

        val decision = harness.select()

        assertSelected("local-metadata", decision)
        assertEquals(0, provider.healthCalls.get())
    }

    @Test
    fun `resolve uses adaptive selection when no explicit provider or model is configured`() {
        val harness = Harness()
        harness.addCandidate("default", costTier = 8)
        harness.addCandidate("lower-cost", costTier = 1)

        val decision = harness.resolve(
            ConversationProviderConfig(
                conversationId = "adaptive",
                preferredProviderId = null,
                preferredModelId = null,
                hasCustomSelection = false,
                selectionContext = ProviderSelectionContext()
            )
        )

        assertSelected("lower-cost", decision)
        assertNotNull((decision as RoutingDecision.Success).selectionEvidence)
    }

    @Test
    fun `explicit provider and model remain authoritative even when custom flag is false`() {
        val harness = Harness()
        harness.addCandidate("explicit", costTier = 9)
        harness.addCandidate("cheaper", costTier = 1)

        val decision = harness.resolve(
            ConversationProviderConfig(
                conversationId = "legacy-explicit",
                preferredProviderId = "explicit",
                preferredModelId = "explicit-model",
                hasCustomSelection = false,
                selectionContext = ProviderSelectionContext()
            )
        )

        assertSelected("explicit", decision)
    }

    @Test
    fun `explicit remote selection cannot bypass a local only privacy requirement`() {
        val harness = Harness()
        harness.addCandidate("explicit-remote", isLocal = false, costTier = 1)

        val decision = harness.resolve(
            ConversationProviderConfig(
                conversationId = "private-explicit",
                preferredProviderId = "explicit-remote",
                preferredModelId = "explicit-remote-model",
                hasCustomSelection = true,
                selectionContext = ProviderSelectionContext(
                    privacyRequirement = PrivacyRequirement.LOCAL_ONLY
                )
            )
        )

        assertTrue(decision is RoutingDecision.UnableToRoute)
        val unable = decision as RoutingDecision.UnableToRoute
        assertTrue(
            SelectionReason.LOCAL_PRIVACY_REQUIRED in
                unable.selectionEvidence!!.candidate("explicit-remote").reasons
        )
    }

    @Test
    fun `legacy default provider wins an otherwise equal adaptive tie`() {
        val harness = Harness()
        harness.addCandidate("backup", costTier = null)
        harness.addCandidate("default", costTier = null)

        val decision = harness.select()

        assertSelected("default", decision)
        assertTrue(SelectionReason.DETERMINISTIC_TIE_BREAK in evidence(decision).candidate("default").reasons)
    }

    private fun assertSelected(providerId: String, decision: RoutingDecision) {
        assertTrue("Expected RoutingDecision.Success but got $decision", decision is RoutingDecision.Success)
        assertEquals(providerId, (decision as RoutingDecision.Success).providerId)
    }

    private fun evidence(decision: RoutingDecision): ProviderSelectionEvidence {
        assertTrue(decision is RoutingDecision.Success)
        val evidence = (decision as RoutingDecision.Success).selectionEvidence
        assertNotNull(evidence)
        return evidence!!
    }

    private fun ProviderSelectionEvidence.candidate(providerId: String): CandidateSelectionEvidence =
        candidates.single { it.providerId == providerId }

    private class Harness {
        private val storage = MemoryStorage()
        private val audit = AuditLog(storage)
        private val providers = ProviderRegistry(storage, audit)
        private val models = ModelRegistry(storage, audit)
        private val runtimes = ConcurrentHashMap<String, TestProvider>()
        private val router = ProviderModelRouter(providers, models)

        init {
            providers.registerProviderFactory(object : ProviderFactory {
                override fun create(providerDef: ProviderDefinition): LanguageModelProvider =
                    runtimes[providerDef.providerId]
                        ?: throw IllegalStateException("No runtime for ${providerDef.providerId}")
            })
        }

        fun addCandidate(
            providerId: String,
            modelId: String = "$providerId-model",
            capabilities: Set<String> = emptySet(),
            supportsTools: Boolean = false,
            supportsStreaming: Boolean = false,
            runtimeSupportsTools: Boolean = supportsTools,
            runtimeSupportsStreaming: Boolean = supportsStreaming,
            isLocal: Boolean = true,
            providerAvailability: String = "available",
            providerHealth: String = "healthy",
            modelAvailability: String = "available",
            modelHealth: String = "healthy",
            modelAccessProvider: String = providerId,
            costTier: Int? = null,
            registerRuntime: Boolean = true
        ): TestProvider {
            check(providers.register(
                ProviderDefinition(
                    providerId = providerId,
                    displayName = providerId,
                    providerType = "test",
                    endpoint = "test://$providerId",
                    authenticationCapability = "none",
                    supportedModels = listOf(modelId),
                    capabilities = capabilities,
                    availability = providerAvailability,
                    health = providerHealth,
                    isLocal = isLocal,
                    isFallbackEligible = false
                )
            ))
            check(models.register(
                ModelDefinition(
                    creator = "test",
                    modelId = modelId,
                    displayName = modelId,
                    accessProvider = modelAccessProvider,
                    route = "test://$providerId/$modelId",
                    capabilities = capabilities,
                    contextLimit = null,
                    isLocal = isLocal,
                    availability = modelAvailability,
                    health = modelHealth,
                    inputOutput = "text",
                    supportsTools = supportsTools,
                    supportsVision = "vision" in capabilities,
                    supportsAudio = "audio" in capabilities,
                    supportsStreaming = supportsStreaming,
                    supportsReasoning = "reasoning" in capabilities,
                    costTier = costTier
                )
            ))
            val runtime = TestProvider(
                id = providerId,
                modelId = modelId,
                supportsToolCalling = runtimeSupportsTools,
                supportsStreaming = runtimeSupportsStreaming
            )
            if (registerRuntime) runtimes[providerId] = runtime
            return runtime
        }

        fun select(context: ProviderSelectionContext = ProviderSelectionContext()): RoutingDecision =
            router.select(context, Task("select", riskClass = RiskClass.READ_ONLY))

        fun resolve(config: ConversationProviderConfig): RoutingDecision =
            router.resolve(config, Task("resolve", riskClass = RiskClass.READ_ONLY))
    }

    private class TestProvider(
        override val id: String,
        modelId: String,
        override val supportsToolCalling: Boolean,
        override val supportsStreaming: Boolean
    ) : LanguageModelProvider {
        override val displayName: String = id
        override val models: List<String> = listOf(modelId)
        val healthCalls = AtomicInteger()

        override fun capabilities(): Map<String, Any> = emptyMap()

        override suspend fun chat(
            messages: List<Map<String, Any>>,
            availableTools: List<Map<String, Any>>?
        ): Result<Map<String, Any>> = Result.success(mapOf("content" to "unused"))

        override suspend fun health(): Result<Boolean> {
            healthCalls.incrementAndGet()
            error("Selection must not call provider health")
        }
    }

    private class MemoryStorage : Storage {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?) = values[key]?.toLongOrNull() ?: defaultValue
        override suspend fun setLong(key: String, value: Long) { values[key] = value.toString() }
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) =
            values[key]?.toBooleanStrictOrNull() ?: defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }
}
