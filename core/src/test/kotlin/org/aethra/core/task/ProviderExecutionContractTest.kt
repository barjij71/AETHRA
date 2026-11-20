package org.aethra.core.task

import kotlinx.coroutines.runBlocking
import org.aethra.core.providers.FakeProviderFactory
import org.aethra.core.providers.ModelRegistry
import org.aethra.core.domain.RiskClass
import org.aethra.core.providers.ModelDefinition
import org.aethra.core.providers.ProviderDefinition
import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.providers.ProviderRegistry
import org.aethra.core.providers.LanguageModelProvider
import org.aethra.core.security.AuditLog
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test

class ProviderExecutionContractTest {
    private fun createTestStorage(): Storage = object : Storage {
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

    private fun createTestAuditLog(): AuditLog = AuditLog(createTestStorage())

    private fun buildRouterWithProvider(
        providerId: String = "fake",
        modelId: String = "fake-v1",
        isFallbackEligible: Boolean = false
    ): ProviderModelRouter {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        val providerDef = ProviderDefinition(
            providerId = providerId,
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://default",
            authenticationCapability = "none",
            supportedModels = listOf(modelId),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = isFallbackEligible
        )
        assertTrue(providerRegistry.register(providerDef))

        providerRegistry.registerProviderFactory(FakeProviderFactory())

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = modelId,
            displayName = "Fake Model",
            accessProvider = providerId,
            route = "local://fake/$modelId",
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

        return ProviderModelRouter(providerRegistry, modelRegistry)
    }

    // --- Success tests ---

    @Test
    fun `primary success returns result without fallback`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)
        val providerDef = ProviderDefinition(
            providerId = "default",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://default",
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
            accessProvider = "default",
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
        val runner = ProviderTaskRunner(router, providerRegistry, createTestAuditLog())
        val task = Task(goal = "hello", riskClass = RiskClass.READ_ONLY)

        val result = runner.run(task)

        assertTrue("Expected success", result.isSuccess)
        val reply = result.getOrThrow()
        assertEquals("Expected taskId in result", task.id, reply["taskId"])
        assertEquals("Expected taskGoal in result", task.goal, reply["taskGoal"])
        assertEquals("Expected providerId in result", "default", reply["providerId"])
        assertEquals("Expected modelId in result", "fake-v1", reply["modelId"])
        assertEquals("Expected attempt", "primary", reply["attempt"])
        assertEquals("Expected attempts", listOf("primary"), reply["attempts"])
    }

    // --- Fallback tests ---

    @Test
    fun `primary failure with fallback enabled and eligible succeeds via fallback`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        // Primary provider - fails
        val primaryDef = ProviderDefinition(
            providerId = "default",
            displayName = "Primary Tester",
            providerType = "local",
            endpoint = "local://primary",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(primaryDef))
        val fallbackPolicy = org.aethra.core.providers.FallbackPolicy(
            providerId = "default",
            enabled = true,
            fallbackTo = "fallback"
        )
        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        (router as org.aethra.core.providers.ProviderModelRouter).enableFallback(fallbackPolicy)

        // Single factory dispatching on providerDef.providerId: default fails, fallback succeeds
        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        if (providerDef.providerId == "default") {
                            Result.failure(RuntimeException("primary failed"))
                        } else {
                            Result.success(mapOf("fallback" to true, "content" to "fallback reply"))
                        }
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val fallbackDef = ProviderDefinition(
            providerId = "fallback",
            displayName = "Fallback Tester",
            providerType = "local",
            endpoint = "local://fallback",
            authenticationCapability = "none",
            supportedModels = listOf("fallback-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = true
        )
        assertTrue(providerRegistry.register(fallbackDef))

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "primary",
            route = "local://primary/fake-v1",
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

        val runner = ProviderTaskRunner(router, providerRegistry, auditLog)
        val task = Task(goal = "help", riskClass = RiskClass.READ_ONLY)

        val result = runner.run(task)

        assertTrue("Expected success", result.isSuccess)
        val reply = result.getOrThrow()
        assertEquals("Expected fallbackProviderId in result", "fallback", reply["fallbackProviderId"])
        assertEquals("Expected fallbackModelId in result", "fallback-v1", reply["fallbackModelId"])
        assertEquals("Expected attempt1", "primary", reply["attempt1"])
        assertEquals("Expected attempt2", "fallback", reply["attempt2"])
        assertEquals("Expected attempts count", 2, (reply["attempts"] as? List<*>)?.size)
    }

    @Test
    fun `primary failure with fallback disabled does not trigger fallback`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        val primaryDef = ProviderDefinition(
            providerId = "default",
            displayName = "Primary Tester",
            providerType = "local",
            endpoint = "local://primary",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(primaryDef))
        val fallbackPolicy = org.aethra.core.providers.FallbackPolicy(
            providerId = "default",
            enabled = false,
            fallbackTo = "fallback"
        )
        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        (router as org.aethra.core.providers.ProviderModelRouter).enableFallback(fallbackPolicy)

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        Result.failure(RuntimeException("primary failed"))
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val fallbackDef = ProviderDefinition(
            providerId = "fallback",
            displayName = "Fallback Tester",
            providerType = "local",
            endpoint = "local://fallback",
            authenticationCapability = "none",
            supportedModels = listOf("fallback-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = true
        )
        assertTrue(providerRegistry.register(fallbackDef))

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        if (providerDef.providerId == "default") {
                            Result.failure(RuntimeException("primary failed"))
                        } else {
                            Result.success(mapOf("fallback" to true))
                        }
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "primary",
            route = "local://primary/fake-v1",
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

        val runner = ProviderTaskRunner(router, providerRegistry, auditLog)
        val task = Task(goal = "help", riskClass = RiskClass.READ_ONLY)

        val result = runner.run(task)

        assertFalse("Expected failure (fallback disabled)", result.isSuccess)
        val exception = result.exceptionOrNull()
        assertNotNull("Expected an exception", exception)
        assertTrue("Exception should be TaskExecutionException", exception is TaskExecutionException)
    }

    @Test
    fun `fallback provider not eligible does not trigger fallback`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        val primaryDef = ProviderDefinition(
            providerId = "default",
            displayName = "Primary Tester",
            providerType = "local",
            endpoint = "local://primary",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(primaryDef))
        val fallbackPolicy = org.aethra.core.providers.FallbackPolicy(
            providerId = "default",
            enabled = true,
            fallbackTo = "fallback"
        )
        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        (router as org.aethra.core.providers.ProviderModelRouter).enableFallback(fallbackPolicy)

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        Result.failure(RuntimeException("primary failed"))
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val fallbackDef = ProviderDefinition(
            providerId = "fallback",
            displayName = "Fallback Tester",
            providerType = "local",
            endpoint = "local://fallback",
            authenticationCapability = "none",
            supportedModels = listOf("fallback-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false  // Not eligible
        )
        assertTrue(providerRegistry.register(fallbackDef))

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        if (providerDef.providerId == "default") {
                            Result.failure(RuntimeException("primary failed"))
                        } else {
                            Result.success(mapOf("fallback" to true))
                        }
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "primary",
            route = "local://primary/fake-v1",
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

        val runner = ProviderTaskRunner(router, providerRegistry, auditLog)
        val task = Task(goal = "help", riskClass = RiskClass.READ_ONLY)

        val result = runner.run(task)

        assertFalse("Expected failure (fallback not eligible)", result.isSuccess)
        val exception = result.exceptionOrNull()
        assertNotNull("Expected an exception", exception)
        assertTrue("Exception should be TaskExecutionException", exception is TaskExecutionException)
    }

    @Test
    fun `destructive task blocks fallback`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        val primaryDef = ProviderDefinition(
            providerId = "default",
            displayName = "Primary Tester",
            providerType = "local",
            endpoint = "local://primary",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(primaryDef))
        val fallbackPolicy = org.aethra.core.providers.FallbackPolicy(
            providerId = "default",
            enabled = true,
            fallbackTo = "fallback"
        )
        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        (router as org.aethra.core.providers.ProviderModelRouter).enableFallback(fallbackPolicy)

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        Result.failure(RuntimeException("primary failed"))
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val fallbackDef = ProviderDefinition(
            providerId = "fallback",
            displayName = "Fallback Tester",
            providerType = "local",
            endpoint = "local://fallback",
            authenticationCapability = "none",
            supportedModels = listOf("fallback-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = true
        )
        assertTrue(providerRegistry.register(fallbackDef))

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        if (providerDef.providerId == "default") {
                            Result.failure(RuntimeException("primary failed"))
                        } else {
                            Result.success(mapOf("fallback" to true))
                        }
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "primary",
            route = "local://primary/fake-v1",
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

        val runner = ProviderTaskRunner(router, providerRegistry, auditLog)
        val task = Task(goal = "destroy", riskClass = RiskClass.DESTRUCTIVE)

        val result = runner.run(task)

        assertFalse("Expected failure (destructive task blocks fallback)", result.isSuccess)
        val exception = result.exceptionOrNull()
        assertNotNull("Expected an exception", exception)
        assertTrue("Exception should be TaskExecutionException", exception is TaskExecutionException)
    }

    @Test
    fun `unavailable provider returns failure without fallback`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        val providerDef = ProviderDefinition(
            providerId = "default",
            displayName = "Fake Tester",
            providerType = "local",
            endpoint = "local://default",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(providerDef))

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "default",
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
        val runner = ProviderTaskRunner(router, providerRegistry, auditLog)
        val task = Task(goal = "hello", riskClass = RiskClass.READ_ONLY)

        val result = runner.run(task)

        assertFalse("Expected failure (provider unavailable)", result.isSuccess)
        val exception = result.exceptionOrNull()
        assertNotNull("Expected an exception", exception)
        assertTrue("Exception should be TaskExecutionException", exception is TaskExecutionException)
    }

    @Test
    fun `audit log contains primary and fallback provider and model and outcome`() = runBlocking {
        val storage = createTestStorage()
        val auditLog = createTestAuditLog()
        val providerRegistry = ProviderRegistry(storage, auditLog)
        val modelRegistry = ModelRegistry(storage, auditLog)

        val primaryDef = ProviderDefinition(
            providerId = "default",
            displayName = "Primary Tester",
            providerType = "local",
            endpoint = "local://primary",
            authenticationCapability = "none",
            supportedModels = listOf("fake-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = false
        )
        assertTrue(providerRegistry.register(primaryDef))
        val fallbackPolicy = org.aethra.core.providers.FallbackPolicy(
            providerId = "default",
            enabled = true,
            fallbackTo = "fallback"
        )
        val router = ProviderModelRouter(providerRegistry, modelRegistry)
        (router as org.aethra.core.providers.ProviderModelRouter).enableFallback(fallbackPolicy)

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        Result.failure(RuntimeException("primary failed"))
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val fallbackDef = ProviderDefinition(
            providerId = "fallback",
            displayName = "Fallback Tester",
            providerType = "local",
            endpoint = "local://fallback",
            authenticationCapability = "none",
            supportedModels = listOf("fallback-v1"),
            capabilities = setOf("tool_calling"),
            availability = "available",
            health = "healthy",
            isLocal = true,
            isFallbackEligible = true
        )
        assertTrue(providerRegistry.register(fallbackDef))

        providerRegistry.registerProviderFactory(object : org.aethra.core.providers.ProviderFactory {
            override fun create(providerDef: org.aethra.core.providers.ProviderDefinition): LanguageModelProvider {
                return object : LanguageModelProvider {
                    override val id: String = providerDef.providerId
                    override val displayName: String = providerDef.displayName
                    override val supportsToolCalling: Boolean = false
                    override val supportsStreaming: Boolean = false
                    override val models: List<String> = providerDef.supportedModels
                    override fun capabilities(): Map<String, Any> = emptyMap()
                    override suspend fun chat(messages: List<Map<String, Any>>, availableTools: List<Map<String, Any>>?): Result<Map<String, Any>> =
                        if (providerDef.providerId == "default") {
                            Result.failure(RuntimeException("primary failed"))
                        } else {
                            Result.success(mapOf("fallback" to true))
                        }
                    override suspend fun health(): Result<Boolean> = Result.success(true)
                }
            }
        })

        val modelDef = ModelDefinition(
            creator = "Fake",
            modelId = "fake-v1",
            displayName = "Fake Model",
            accessProvider = "primary",
            route = "local://primary/fake-v1",
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

        val runner = ProviderTaskRunner(router, providerRegistry, auditLog)
        val task = Task(goal = "help", riskClass = RiskClass.READ_ONLY)

        val result = runner.run(task)

        assertTrue("Expected success", result.isSuccess)
        val auditEntries = auditLog.all()
        assertEquals("Expected audit entries", 2, auditEntries.size)
        val primaryEntry = auditEntries[0]
        val fallbackEntry = auditEntries[1]
        assertEquals("Expected primary operation", "provider_execute", primaryEntry.operation)
        assertEquals("Expected primary outcome", "FAILED_DURING_EXECUTION", primaryEntry.outcome)
        assertEquals("Expected fallback operation", "provider_fallback", fallbackEntry.operation)
        assertEquals("Expected fallback outcome", "SUCCESS", fallbackEntry.outcome)
        assertTrue("Expected fallback target contains fallback provider", fallbackEntry.target.contains("fallback"))
    }
}
