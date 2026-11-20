package org.aethra.core.providers

import org.aethra.core.domain.RiskClass
import org.aethra.core.task.Task
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Contract tests for [ProviderModelRouter].
 *
 * These tests verify the wiring between [ProviderRegistry] (metadata/config),
 * [ModelRegistry] (metadata/config) and the runtime [LanguageModelProvider]
 * implementation, without running any real inference or network calls.
 *
 * The tests use [FakeProvider] as a stand-in implementation only.
 */
class ProviderModelRouterContractTest {
    private fun createTestStorage(): Storage {
        return object : Storage {
            private val data = java.util.concurrent.ConcurrentHashMap<String, String>()
            override suspend fun getString(key: String, defaultValue: String?) = data[key]
            override suspend fun setString(key: String, value: String) { data[key] = value }
            override suspend fun getLong(key: String, defaultValue: Long?) = data[key]?.toLongOrNull()
            override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
            override suspend fun getBoolean(key: String, defaultValue: Boolean?) = data[key]?.toBooleanStrictOrNull()
            override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
            override suspend fun getAll(): Map<String, String> = data.toMap()
            override suspend fun remove(key: String) { data.remove(key) }
            override suspend fun clear() { data.clear() }
        }
    }

    private fun createTestAuditLog(): AuditLog {
        return AuditLog(createTestStorage())
    }

    @Test
    fun `provider registered with matching model returns Success`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())

        val providerDef = ProviderDefinition(
            providerId = "fake",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://fake",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(providerDef))

        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "fake",
            route = "local://fake/fake-v1",
            capabilities = setOf("tool_calling"),
            contextLimit = null,
            isLocal = true,
            availability = "available",
            health = "healthy",
            inputOutput = "text",
            supportsTools = false,
            supportsVision = false,
            supportsAudio = false,
            supportsStreaming = false,
            supportsReasoning = false
        )
        assertTrue(modelRegistry.register(modelDef))

        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        val config = ConversationProviderConfig(
            conversationId = "test",
            preferredProviderId = "fake",
            preferredModelId = "fake-v1",
            hasCustomSelection = false
        )
        val task = Task(goal = "test", riskClass = RiskClass.READ_ONLY)

        // Act
        val decision = router.resolve(config, task)

        // Assert
        assertTrue("Expected RoutingDecision.Success", decision is RoutingDecision.Success)
        val success = decision as RoutingDecision.Success
        assertEquals("fake", success.providerId)
        assertEquals("fake-v1", success.modelId)
        assertNotNull("Expected a runtime provider implementation to be attached", success.provider)
        assertTrue("Expected provider implementation", success.provider is FakeProvider)
    }

    @Test
    fun `provider not found returns UnableToRoute`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())
        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        val config = ConversationProviderConfig(
            conversationId = "test",
            preferredProviderId = "nonexistent",
            preferredModelId = "fake-v1",
            hasCustomSelection = false
        )
        val task = Task(goal = "test", riskClass = RiskClass.READ_ONLY)

        // Act
        val decision = router.resolve(config, task)

        // Assert
        assertTrue("Expected RoutingDecision.UnableToRoute", decision is RoutingDecision.UnableToRoute)
    }

    @Test
    fun `model not found returns UnableToRoute`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())

        val providerDef = ProviderDefinition(
            providerId = "fake",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://fake",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(providerDef))
        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "fake",
            route = "local://fake/fake-v1",
            capabilities = setOf("tool_calling"),
            contextLimit = null,
            isLocal = true,
            availability = "available",
            health = "healthy",
            inputOutput = "text",
            supportsTools = false,
            supportsVision = false,
            supportsAudio = false,
            supportsStreaming = false,
            supportsReasoning = false
        )
        assertTrue(modelRegistry.register(modelDef))

        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        val config = ConversationProviderConfig(
            conversationId = "test",
            preferredProviderId = "fake",
            preferredModelId = "nonexistent",
            hasCustomSelection = false
        )
        val task = Task(goal = "test", riskClass = RiskClass.READ_ONLY)

        // Act
        val decision = router.resolve(config, task)

        // Assert
        assertTrue("Expected RoutingDecision.UnableToRoute", decision is RoutingDecision.UnableToRoute)
    }

    @Test
    fun `model belongs to different provider returns UnableToRoute`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())

        val providerDef = ProviderDefinition(
            providerId = "fake",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://fake",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(providerDef))
        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "different-provider", // model belongs to another provider
            route = "local://fake/fake-v1",
            capabilities = setOf("tool_calling"),
            contextLimit = null,
            isLocal = true,
            availability = "available",
            health = "healthy",
            inputOutput = "text",
            supportsTools = false,
            supportsVision = false,
            supportsAudio = false,
            supportsStreaming = false,
            supportsReasoning = false
        )
        assertTrue(modelRegistry.register(modelDef))

        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        val config = ConversationProviderConfig(
            conversationId = "test",
            preferredProviderId = "fake",
            preferredModelId = "fake-v1",
            hasCustomSelection = false
        )
        val task = Task(goal = "test", riskClass = RiskClass.READ_ONLY)

        // Act
        val decision = router.resolve(config, task)

        // Assert
        assertTrue("Expected RoutingDecision.UnableToRoute", decision is RoutingDecision.UnableToRoute)
        val unableToRoute = decision as RoutingDecision.UnableToRoute
        assertTrue("Expected a clear diagnostic message about provider mismatch",
            unableToRoute.reason.contains("belongs to provider", ignoreCase = true))
    }

    @Test
    fun `destructive risk without approval returns Rejected`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())

        val providerDef = ProviderDefinition(
            providerId = "fake",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://fake",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(providerDef))
        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "fake",
            route = "local://fake/fake-v1",
            capabilities = setOf("tool_calling"),
            contextLimit = null,
            isLocal = true,
            availability = "available",
            health = "healthy",
            inputOutput = "text",
            supportsTools = false,
            supportsVision = false,
            supportsAudio = false,
            supportsStreaming = false,
            supportsReasoning = false
        )
        assertTrue(modelRegistry.register(modelDef))

        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        val config = ConversationProviderConfig(
            conversationId = "test",
            preferredProviderId = "fake",
            preferredModelId = "fake-v1",
            hasCustomSelection = false
        )
        val task = Task(goal = "test", riskClass = RiskClass.DESTRUCTIVE)

        // Act
        val decision = router.resolve(config, task)

        // Assert
        assertTrue("Expected RoutingDecision.Rejected", decision is RoutingDecision.Rejected)
        val rejected = decision as RoutingDecision.Rejected
        assertEquals(RiskClass.DESTRUCTIVE, rejected.policy)
        assertTrue(rejected.reason.contains("Destructive", ignoreCase = true))
    }

    @Test
    fun `fallback eligibility does not auto-enable for non-eligible provider`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())

        // Register a provider that is NOT fallback-eligible
        val providerDef = ProviderDefinition(
            providerId = "fake",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://fake",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false  // explicitly not fallback-eligible
        )
        assertTrue(providerRegistry.register(providerDef))
        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "fake",
            route = "local://fake/fake-v1",
            capabilities = setOf("tool_calling"),
            contextLimit = null,
            isLocal = true,
            availability = "available",
            health = "healthy",
            inputOutput = "text",
            supportsTools = false,
            supportsVision = false,
            supportsAudio = false,
            supportsStreaming = false,
            supportsReasoning = false
        )
        assertTrue(modelRegistry.register(modelDef))

        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        val eligibility = router.checkFallbackEligibility("fake")

        // Assert: fallback is not auto-enabled, and isFallbackEligible is false
        assertFalse("Fallback must not be auto-enabled", eligibility.fallbackEnabled)
        assertFalse("Provider must not be fallback-eligible", eligibility.isFallbackEligible)
    }

    @Test
    fun `new provider is not automatically fallback-eligible`() {
        // Arrange
        val providerRegistry = ProviderRegistry(createTestStorage(), createTestAuditLog())
        val modelRegistry = ModelRegistry(createTestStorage(), createTestAuditLog())

        // Register a provider without marking it as fallback-eligible
        val providerDef = ProviderDefinition(
            providerId = "fake-new",
            displayName = "Fake New",
            providerType = "local",
            endpoint = "local://fake-new",
            authenticationCapability = "none",
            supportedModels = listOf("fake-new-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false  // freshly registered, not fallback-eligible
        )
        assertTrue(providerRegistry.register(providerDef))
        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val router = ProviderModelRouter(providerRegistry, modelRegistry)

        // Assert: isFallbackEligible reflects the provider definition
        assertFalse(providerRegistry.isFallbackEligible("fake-new"))
    }
}
