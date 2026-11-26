package org.aethra.core.security

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.CapabilityAvailability
import org.aethra.core.tools.Tool
import org.aethra.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 8 tests: CapabilityAvailability validation before authorization.
 * These tests verify that capabilities are validated before policy/permission checks.
 */
class CapabilityAvailabilityTest {

    /**
     * Test 1 — required capability available
     * Tool requires file.read
     * CapabilityAvailability provides file.read
     * → authorization may continue
     * → ToolExecutor can be reached
     */
    @Test
    fun requiredCapabilityAvailableAllowsExecution() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("file.read /path")
        val tool = toolWithCapabilities("file.read")
        val registry = ToolRegistry().apply { register(tool) }
        val capabilityProvider = TestCapabilityProvider(setOf("file.read"))
        val policy = policy(broker, audit, sideEffects, registry, capabilityProvider)

        val result = policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("Execution should succeed when capability is available", result.isSuccess)
        assertEquals(1, sideEffects.get())
    }

    /**
     * Test 2 — required capability missing
     * Tool requires file.write
     * CapabilityAvailability does NOT provide file.write
     * → denied
     * → ToolExecutor NOT CALLED
     */
    @Test
    fun requiredCapabilityMissingDeniesExecution() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("file.write /path")
        val tool = toolWithCapabilities("file.write")
        val registry = ToolRegistry().apply { register(tool) }
        val capabilityProvider = TestCapabilityProvider(emptySet()) // No capabilities
        val policy = policy(broker, audit, sideEffects, registry, capabilityProvider)

        val result = policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("Execution should fail when capability is missing", result.isFailure)
        assertEquals(0, sideEffects.get())
        assertTrue(
            "Should contain missing_capability error",
            result.exceptionOrNull()?.message?.contains("missing_capability:file.write") == true
        )
    }

    /**
     * Test 3 — multiple capabilities
     * Tool requires:
     * file.read
     * network
     * clipboard.read
     * Only some are available (file.read)
     * → denied
     * → identify missing capability
     * → ToolExecutor NOT CALLED
     */
    @Test
    fun multipleCapabilitiesPartialAvailabilityDenies() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("multi.tool")
        val tool = toolWithCapabilities("file.read", "network", "clipboard.read")
        val registry = ToolRegistry().apply { register(tool) }
        val capabilityProvider = TestCapabilityProvider(setOf("file.read")) // Only one capability
        val policy = policy(broker, audit, sideEffects, registry, capabilityProvider)

        val result = policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("Execution should fail with partial capability availability", result.isFailure)
        assertEquals(0, sideEffects.get())
    }

    /**
     * Test 4 — FULL_ACCESS cannot bypass capability
     * ExecutionMode = FULL_ACCESS
     * required capability missing
     * → DENIED
     * → ToolExecutor NOT CALLED
     */
    @Test
    fun fullAccessDoesNotBypassCapabilityValidation() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("file.write /path")
        val tool = toolWithCapabilities("file.write")
        val registry = ToolRegistry().apply { register(tool) }
        val capabilityProvider = TestCapabilityProvider(emptySet()) // No capabilities
        val policy = policy(broker, audit, sideEffects, registry, capabilityProvider)

        // Execute with an explicit approval request ID (simulating FULL_ACCESS mode behavior)
        // Even with approval request ID, missing capability should still deny
        val result = policy.execute(task, tool, null, emptyMap(), approvalRequestId = "approved-123") {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("FULL_ACCESS should NOT bypass capability validation", result.isFailure)
        assertEquals(0, sideEffects.get())
    }

    /**
     * Test 5 — no required capabilities
     * Tool.requiredCapabilities = emptyList()
     * → capability validation passes
     * → normal authorization flow continues
     */
    @Test
    fun noRequiredCapabilitiesPassesValidation() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("simple.tool")
        val tool = toolWithCapabilities() // No required capabilities
        val registry = ToolRegistry().apply { register(tool) }
        val capabilityProvider = TestCapabilityProvider(emptySet()) // No capabilities available
        val policy = policy(broker, audit, sideEffects, registry, capabilityProvider)

        val result = policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("Execution should succeed when no capabilities are required", result.isSuccess)
        assertEquals(1, sideEffects.get())
    }

    /**
     * Test 6 — capability validation occurs before executor
     * Prove behaviorally that a missing capability prevents executor invocation.
     */
    @Test
    fun capabilityValidationOccursBeforeExecutorInvocation() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("file.delete /important")
        val tool = toolWithCapabilities("file.delete")
        val registry = ToolRegistry().apply { register(tool) }
        val capabilityProvider = TestCapabilityProvider(emptySet())

        // Create a custom executor that would cause catastrophic side effects
        // If capability validation runs first, this executor should NEVER be called
        val catastrophicExecutor = {
            sideEffects.incrementAndGet()
            // Simulate data loss
            throw RuntimeException("DATA CORRUPTED - EXECUTOR SHOULD NOT HAVE BEEN CALLED!")
        }

        val policy = policy(broker, audit, sideEffects, registry, capabilityProvider)

        val result = policy.execute(task, tool, null, emptyMap()) {
            catastrophicExecutor()
        }

        assertTrue("Execution should fail due to missing capability", result.isFailure)
        assertEquals(0, sideEffects.get())
        assertTrue(
            result.exceptionOrNull()?.message?.contains("missing_capability:file.delete") == true
        )
    }

    /**
     * Test 7 — null CapabilityAvailability allows backward compatibility
     * When capabilityAvailability is null, validation should pass (backward compatible with tests)
     */
    @Test
    fun nullCapabilityAvailabilityAllowsBackwardCompatibility() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val task = task("app.query")
        val tool = toolWithCapabilities("app.query")
        val registry = ToolRegistry().apply { register(tool) }
        val sideEffects = AtomicInteger()
        val policy = policy(broker = PermissionBroker(audit), audit = audit, sideEffects = sideEffects, registry = registry, capabilityProvider = null)

        val result = policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("When capabilityAvailability is null, execution should proceed", result.isSuccess)
        assertEquals(1, sideEffects.get())
    }

    /**
     * Test 8 — empty requiredCapabilities passes validation
     */
    @Test
    fun emptyRequiredCapabilitiesAlwaysPassesValidation() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val task = task("simple.tool")
        val tool = toolWithCapabilities() // Empty capabilities
        val registry = ToolRegistry().apply { register(tool) }
        val sideEffects = AtomicInteger()
        val capabilityProvider = TestCapabilityProvider(emptySet())
        val policy = policy(
            broker = PermissionBroker(audit),
            audit = audit,
            sideEffects = sideEffects,
            registry = registry,
            capabilityProvider = capabilityProvider
        )

        val result = policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet()
            success(task)
        }

        assertTrue("Empty required capabilities should always pass validation", result.isSuccess)
        assertEquals(1, sideEffects.get())
    }

    private fun policy(
        broker: PermissionBroker?,
        audit: AuditLog,
        sideEffects: AtomicInteger,
        registry: ToolRegistry = ToolRegistry(),
        capabilityProvider: CapabilityAvailability?
    ) = ExecutionPolicy(
        PolicyEngine(),
        broker,
        audit,
        object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                sideEffects.incrementAndGet()
                return success(task)
            }
        },
        registry,
        capabilityProvider
    )

    private fun toolWithCapabilities(vararg capabilities: String): Tool {
        return Tool(
            id = when {
                capabilities.isEmpty() -> "simple.tool"
                capabilities.size == 1 -> capabilities[0]
                else -> "multi.tool"
            },
            description = "Test tool",
            category = "test",
            requiredCapabilities = capabilities.toList(),
            riskClass = RiskClass.READ_ONLY,
            approvalRequired = false
        )
    }

    private fun success(task: Task): Result<Map<String, Any>> = Result.success(mapOf("taskId" to task.id))

    private fun task(goal: String): Task = Task(goal)

    private class MemoryStorage : Storage {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }

    private class TestCapabilityProvider(
        private val availableCapabilities: Set<String>
    ) : CapabilityAvailability {
        override fun hasCapability(name: String): Boolean = name in availableCapabilities
    }
}