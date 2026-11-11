package org.aethra.assistant.aethra.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aethra.core.domain.RiskClass

/**
 * JVM unit tests for [ClipboardReadTool].
 *
 * Verifies the Tool declaration: clipboard.read is READ_ONLY, reversible,
 * non-privileged, and does not require approval.
 * The execute() method requires an Android Context (ClipboardManager);
 * it is exercised by instrumented tests on a real device/emulator.
 */
class ClipboardReadToolTest {

    @Test
    fun toolIdIsClipboardRead() {
        val tool = ClipboardReadTool(androidContextFake()).tool
        assertEquals("clipboard.read", tool.id)
    }

    @Test
    fun toolRiskClassIsReadOnly() {
        val tool = ClipboardReadTool(androidContextFake()).tool
        assertEquals(RiskClass.READ_ONLY, tool.riskClass)
    }

    @Test
    fun toolRequiresClipboardReadCapability() {
        val tool = ClipboardReadTool(androidContextFake()).tool
        assertTrue(tool.requiredCapabilities.contains("clipboard.read"))
    }

    @Test
    fun toolDoesNotRequireApproval() {
        val tool = ClipboardReadTool(androidContextFake()).tool
        assertFalse(tool.approvalRequired)
        assertTrue(tool.reversible)
    }

    @Test
    fun toolIsNative() {
        val tool = ClipboardReadTool(androidContextFake()).tool
        assertTrue(tool.native)
    }

    @Test
    fun toolHasOutputSchema() {
        val tool = ClipboardReadTool(androidContextFake()).tool
        assertEquals("string", tool.outputSchema["text"])
        assertEquals("string", tool.outputSchema["has_text"])
    }

    private fun androidContextFake(): Nothing {
        throw AssertionError("Android Context not available on JVM; use instrumented test")
    }
}
