package org.aethra.assistant.aethra.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aethra.core.domain.RiskClass

/**
 * JVM unit tests for [AppQueryTool].
 *
 * Verifies the Tool declaration: app.query is READ_ONLY, reversible,
 * does not require approval, and lists installed applications.
 * The execute() method requires an Android Context (PackageManager);
 * it is exercised by instrumented tests on a real device/emulator.
 */
class AppQueryToolTest {

    @Test
    fun toolIdIsAppQuery() {
        val tool = AppQueryTool(androidContextFake()).tool
        assertEquals("app.query", tool.id)
    }

    @Test
    fun toolRiskClassIsReadOnly() {
        val tool = AppQueryTool(androidContextFake()).tool
        assertEquals(RiskClass.READ_ONLY, tool.riskClass)
    }

    @Test
    fun toolRequiresAppQueryCapability() {
        val tool = AppQueryTool(androidContextFake()).tool
        assertTrue(tool.requiredCapabilities.contains("app.query"))
    }

    @Test
    fun toolDoesNotRequireApproval() {
        val tool = AppQueryTool(androidContextFake()).tool
        assertFalse(tool.approvalRequired)
        assertTrue(tool.reversible)
    }

    @Test
    fun toolIsNative() {
        val tool = AppQueryTool(androidContextFake()).tool
        assertTrue(tool.native)
    }

    @Test
    fun toolHasInputAndOutputSchema() {
        val tool = AppQueryTool(androidContextFake()).tool
        assertEquals("optional package or application name filter", tool.inputSchema["query"])
        assertEquals("list", tool.outputSchema["results"])
    }

    private fun androidContextFake(): Nothing {
        throw AssertionError("Android Context not available on JVM; use instrumented test")
    }
}
