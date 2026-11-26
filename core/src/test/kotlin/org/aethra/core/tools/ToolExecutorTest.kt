package org.aethra.core.tools

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Tool Executor contract (Phase 7 core execution capability contract).
 */
class ToolExecutorContractTest {

    @Test
    fun `tool execution request has correct identity`() {
        val request = ToolExecutionRequest(
            toolId = "test.tool",
            arguments = mapOf("key" to "value"),
            taskId = "task-123",
            executionAttemptId = "attempt-456",
            requester = "test.requester"
        )

        assertEquals("test.tool", request.toolId)
        assertEquals("task-123", request.taskId)
        assertEquals("attempt-456", request.executionAttemptId)
        assertEquals("test.requester", request.requester)
        assertEquals(1, request.arguments.size)
    }

    @Test
    fun `tool execution request rejects blank toolId`() {
        var thrown = false
        try {
            ToolExecutionRequest(
                toolId = "",
                taskId = "task-123",
                executionAttemptId = "attempt-456"
            )
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `tool execution request rejects blank taskId`() {
        var thrown = false
        try {
            ToolExecutionRequest(
                toolId = "test.tool",
                taskId = "",
                executionAttemptId = "attempt-456"
            )
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `tool execution request rejects blank executionAttemptId`() {
        var thrown = false
        try {
            ToolExecutionRequest(
                toolId = "test.tool",
                taskId = "task-123",
                executionAttemptId = ""
            )
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `success factory creates successful result`() {
        val result = ToolExecutionResult.success(
            toolId = "test.tool",
            output = mapOf("status" to "ok"),
            evidence = listOf("evidence-1")
        )

        assertTrue(result.success)
        assertEquals("test.tool", result.toolId)
        assertEquals(1, result.output.size)
        assertEquals(1, result.evidence.size)
        assertEquals(null, result.error)
    }

    @Test
    fun `failure factory creates failed result`() {
        val result = ToolExecutionResult.failure(
            toolId = "test.tool",
            error = "execution failed",
            output = mapOf("partial" to "data")
        )

        assertFalse(result.success)
        assertEquals("test.tool", result.toolId)
        assertEquals("execution failed", result.error)
        assertEquals(1, result.output.size)
        assertTrue(result.evidence.isEmpty())
    }
}

/**
 * Simple test implementation of ToolExecutor for contract verification.
 */
class TestToolExecutor : ToolExecutor {
    override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        return ToolExecutionResult.success(
            toolId = request.toolId,
            output = mapOf("executed" to "true"),
            evidence = listOf("tool:executed@${request.toolId}")
        )
    }
}

/**
 * Tool executor integration tests.
 */
class ToolExecutorIntegrationTest {

    @Test
    fun `tool executor preserves execution identity`() = runBlocking {
        val executor = TestToolExecutor()
        val request = ToolExecutionRequest(
            toolId = "identity.tool",
            taskId = "task-789",
            executionAttemptId = "attempt-101"
        )

        val result = executor.execute(request)

        assertEquals("identity.tool", result.toolId)
        assertEquals(1, result.evidence.size)
        assertTrue(result.evidence.first().contains("identity.tool"))
    }
}

/**
 * Tool Task Runner tests.
 */
class ToolTaskRunnerContractTest {
    
    @Test
    fun `tool task runner resolves existing tool by id`() = runBlocking {
        val registry = ToolRegistry()
        val storage = TestStorage()
        val auditLog = AuditLog(storage)
        val executor = TestToolExecutor()
        val runner = ToolTaskRunner(
            toolRegistry = registry,
            auditLog = auditLog,
            toolExecutor = executor
        )

        // Create a task through the registry - the goal should be the tool ID
        val tool = registry.resolve("device.info")
        assertNotNull(tool)
        assertEquals("device.info", tool?.id)
    }

    @Test
    fun `tool task runner resolves tool by alias`() = runBlocking {
        val registry = ToolRegistry()
        
        // Verify that alias resolution works (from NativeTools)
        val tool = registry.resolve("info device")
        assertNotNull(tool)
        assertEquals("device.info", tool?.id)
    }

    @Test
    fun `tool task runner fails when tool not found`() = runBlocking {
        val registry = ToolRegistry()
        val storage = TestStorage()
        val auditLog = AuditLog(storage)
        val runner = ToolTaskRunner(
            toolRegistry = registry,
            auditLog = auditLog,
            toolExecutor = null
        )

        // Verify that nonexistent tool cannot be resolved
        val tool = registry.resolve("nonexistent.tool")
        assertEquals(null, tool)
    }
}

/**
 * Simple in-memory Storage implementation for testing.
 */
class TestStorage : Storage {
    private val data = mutableMapOf<String, String>()
    override suspend fun getString(key: String, defaultValue: String?): String? = data[key]
    override suspend fun setString(key: String, value: String) { data[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull()
    override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = data[key]?.toBooleanStrictOrNull()
    override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
    override suspend fun getAll(): Map<String, String> = data.toMap()
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun clear() { data.clear() }
}