package org.aethra.core.security

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.Tool
import org.aethra.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ToolExecutionPolicyContractTest {
    @Test
    fun nativeToolDefinitionsClassifySensitiveMutationsWithoutFlaggingReadPaths() {
        val tools = ToolRegistry().all().associateBy { it.id }

        assertTrue(tools.getValue("file.write").requiresSensitiveApproval)
        assertTrue(tools.getValue("clipboard.write").requiresSensitiveApproval)
        assertFalse(tools.getValue("file.read").requiresSensitiveApproval)
        assertFalse(tools.getValue("clipboard.read").requiresSensitiveApproval)
        assertFalse(tools.getValue("app.query").requiresSensitiveApproval)
        assertTrue(tool("custom.sensitive", risk = RiskClass.SENSITIVE).requiresSensitiveApproval)
    }

    @Test
    fun laterToolRegistrationCannotDowngradeAnExistingSensitiveDefinition() {
        val registry = ToolRegistry()
        val original = registry.get("file.write")!!

        registry.register(original.copy(
            approvalRequired = false,
            requiredCapabilities = emptyList(),
            riskClass = RiskClass.READ_ONLY
        ))

        val registered = registry.get("file.write")!!
        assertTrue(registered.requiresSensitiveApproval)
        assertTrue(registered.requiredCapabilities.contains("file.write"))
        assertEquals(RiskClass.REVERSIBLE, registered.riskClass)
    }

    @Test
    fun executionPolicyDoesNotTrustCallerSuppliedWeakerToolMetadata() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("file.write /tmp/x")
        val forged = tool("file.write", risk = RiskClass.READ_ONLY)
        val policy = policy(broker, audit, sideEffects)

        val result = policy.execute(task, forged, null, emptyMap()) {
            sideEffects.incrementAndGet(); success(task)
        }

        assertTrue(result.isFailure)
        assertEquals(0, sideEffects.get())
        assertEquals(PermissionRequestState.REQUESTED, broker.getRequest(task.id)?.state)
    }

    @Test
    fun sensitiveToolDispatchWaitsForMatchingSingleUseApproval() = runBlocking {
        val storage = MemoryStorage()
        val audit = AuditLog(storage)
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val task = task("file.write /tmp/x")
        val tool = tool("file.write", "file.write", approval = true)
            .copy(requiredCapabilities = listOf("file.write", "device.info"))
        val registry = ToolRegistry().apply { register(tool) }
        val policy = policy(broker, audit, sideEffects, registry)

        val unresolved = policy.execute(task, tool, null, emptyMap())
        assertTrue(unresolved.isFailure)
        assertEquals(0, sideEffects.get())
        val requested = broker.getRequest(task.id)!!
        assertEquals(PermissionRequestState.REQUESTED, requested.state)
        assertEquals("file.write", requested.operation)
        assertEquals(setOf("device.info", "file.write"), requested.capabilities)
        assertEquals(task.id, requested.target)
        assertTrue(audit.all().any { it.operation == "permission.request" && it.target == task.id })
        assertTrue(audit.all().any { it.operation == "permission.execution.blocked" && it.target == task.id })
        assertTrue(policy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet(); success(task)
        }.isFailure)
        assertEquals(0, sideEffects.get())

        broker.decide(task.id, PermissionDecision.APPROVE, "user approved")
        val wrongTool = policy.execute(task, tool.copy(id = "clipboard.write"), null, emptyMap(), task.id) {
            sideEffects.incrementAndGet(); success(task)
        }
        assertTrue(wrongTool.isFailure)
        val otherTask = task("file.write /tmp/y")
        assertTrue(policy.execute(otherTask, tool, null, emptyMap(), task.id) {
            sideEffects.incrementAndGet(); success(otherTask)
        }.isFailure)
        assertEquals(0, sideEffects.get())
        assertEquals(PermissionRequestState.APPROVED, broker.getRequest(task.id)?.state)

        val allowed = policy.execute(task, tool, null, emptyMap(), task.id)
        assertTrue(allowed.isSuccess)
        assertEquals(1, sideEffects.get())
        assertEquals(PermissionRequestState.COMPLETED, broker.getRequest(task.id)?.state)
        assertTrue(audit.all().any { it.operation == "permission.execution.allowed" && it.target == task.id })
        assertTrue(audit.all().any { it.operation == "permission.execution.completed" && it.target == task.id })

        assertTrue(policy.execute(task, tool, null, emptyMap(), task.id) {
            sideEffects.incrementAndGet(); success(task)
        }.isFailure)
        assertEquals(1, sideEffects.get())

        val independentTask = task("file.write /tmp/z")
        assertTrue(policy.execute(independentTask, tool, null, emptyMap()) {
            sideEffects.incrementAndGet(); success(independentTask)
        }.isFailure)
        assertEquals(PermissionRequestState.REQUESTED, broker.getRequest(independentTask.id)?.state)
        assertEquals(1, sideEffects.get())
    }

    @Test
    fun denialCancellationAndUnknownRequestNeverReachProtectedOperation() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val broker = PermissionBroker(audit)
        val sideEffects = AtomicInteger()
        val policy = policy(broker, audit, sideEffects)
        val tool = tool("file.write", "file.write", approval = true)
        val operation: suspend () -> Result<Map<String, Any>> = { sideEffects.incrementAndGet(); Result.success(emptyMap()) }

        val deniedTask = task("file.write")
        assertTrue(policy.execute(deniedTask, tool, null, emptyMap(), operation = operation).isFailure)
        broker.decide(deniedTask.id, PermissionDecision.DENY, "denied")
        assertTrue(policy.execute(deniedTask, tool, null, emptyMap(), deniedTask.id, operation).isFailure)

        val cancelledTask = task("file.write")
        assertTrue(policy.execute(cancelledTask, tool, null, emptyMap(), operation = operation).isFailure)
        broker.decide(cancelledTask.id, PermissionDecision.CANCEL, "cancelled")
        assertTrue(policy.execute(cancelledTask, tool, null, emptyMap(), cancelledTask.id, operation).isFailure)

        assertTrue(policy.execute(deniedTask, tool, null, emptyMap(), "unknown-request", operation).isFailure)
        assertEquals(0, sideEffects.get())
        assertTrue(audit.all().any { it.operation == "permission.denied" && it.target == deniedTask.id })
        assertTrue(audit.all().any { it.operation == "permission.cancelled" && it.target == cancelledTask.id })
    }

    @Test
    fun normalToolUsesTrustedExecutionPathWithoutBrokerApproval() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val sideEffects = AtomicInteger()
        val task = task("app.query")
        val policy = policy(null, audit, sideEffects)

        val result = policy.execute(task, tool("app.query", "app.query"), null, emptyMap()) {
            sideEffects.incrementAndGet(); success(task)
        }

        assertTrue(result.isSuccess)
        assertEquals(1, sideEffects.get())
        assertFalse(audit.all().any { it.operation.startsWith("permission.") })
    }

    @Test
    fun missingBrokerAndAgentOrToolCallbacksCannotSelfApprove() = runBlocking {
        val audit = AuditLog(MemoryStorage())
        val sideEffects = AtomicInteger()
        val task = task("clipboard.write")
        val tool = tool("clipboard.write", "clipboard.write", approval = true)
        val noBrokerPolicy = policy(null, audit, sideEffects)

        assertTrue(noBrokerPolicy.execute(task, tool, null, emptyMap()) {
            sideEffects.incrementAndGet(); success(task)
        }.isFailure)
        assertEquals(0, sideEffects.get())
        assertTrue(audit.all().any { it.operation == "tool.permission.blocked" && it.outcome == "missing_broker" })

        val broker = PermissionBroker(audit)
        val brokerPolicy = policy(broker, audit, sideEffects)
        val selfApprovalCallback: suspend () -> Result<Map<String, Any>> = {
            broker.decide(task.id, PermissionDecision.APPROVE, "attempted from tool callback")
            sideEffects.incrementAndGet()
            success(task)
        }

        // The default TaskRunner (the agent/task path) cannot run early enough to decide.
        assertTrue(brokerPolicy.execute(task, tool, null, emptyMap()).isFailure)
        // Neither can a local Tool callback use its own attempted decision to get past the gate.
        assertTrue(brokerPolicy.execute(task, tool, null, emptyMap(), operation = selfApprovalCallback).isFailure)
        assertEquals(0, sideEffects.get())
        assertEquals(PermissionRequestState.REQUESTED, broker.getRequest(task.id)?.state)
    }

    private fun policy(
        broker: PermissionBroker?,
        audit: AuditLog,
        sideEffects: AtomicInteger,
        registry: ToolRegistry = ToolRegistry()
    ) = ExecutionPolicy(PolicyEngine(), broker, audit, object : TaskRunner {
        override suspend fun run(task: Task): Result<Map<String, Any>> {
            sideEffects.incrementAndGet()
            return success(task)
        }
    }, registry)

    private fun tool(
        id: String,
        capability: String = id,
        approval: Boolean = false,
        risk: RiskClass = RiskClass.READ_ONLY
    ) = Tool(
        id = id,
        description = id,
        category = "test",
        requiredCapabilities = listOf(capability),
        riskClass = risk,
        approvalRequired = approval
    )

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
}
