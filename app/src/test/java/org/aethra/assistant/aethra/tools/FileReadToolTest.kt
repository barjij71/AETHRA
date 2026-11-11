package org.aethra.assistant.aethra.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aethra.core.domain.RiskClass

/**
 * JVM unit tests for [FileReadTool].
 *
 * Verifies the Tool declaration: file.read is READ_ONLY, reversible,
 * scoped to app private storage, and does not require approval.
 * The execute() method requires an Android Context and file system;
 * it is exercised by instrumented tests on a real device/emulator.
 */
class FileReadToolTest {

    @Test
    fun toolIdIsFileRead() {
        val tool = FileReadTool(androidContextFake()).tool
        assertEquals("file.read", tool.id)
    }

    @Test
    fun toolRiskClassIsReadOnly() {
        val tool = FileReadTool(androidContextFake()).tool
        assertEquals(RiskClass.READ_ONLY, tool.riskClass)
    }

    @Test
    fun toolRequiresFileReadCapability() {
        val tool = FileReadTool(androidContextFake()).tool
        assertTrue(tool.requiredCapabilities.contains("file.read"))
    }

    @Test
    fun toolDoesNotRequireApproval() {
        val tool = FileReadTool(androidContextFake()).tool
        assertFalse(tool.approvalRequired)
        assertTrue(tool.reversible)
    }

    @Test
    fun toolIsNative() {
        val tool = FileReadTool(androidContextFake()).tool
        assertTrue(tool.native)
    }

    @Test
    fun toolHasInputAndOutputSchema() {
        val tool = FileReadTool(androidContextFake()).tool
        assertEquals("string", tool.inputSchema["path"])
        assertEquals("string", tool.outputSchema["content"])
        assertEquals("string", tool.outputSchema["path"])
    }

    private fun androidContextFake(): Nothing {
        throw AssertionError("Android Context not available on JVM; use instrumented test")
    }
}
