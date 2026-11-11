package org.aethra.assistant.aethra.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aethra.core.domain.RiskClass

/**
 * JVM unit tests for [AppLaunchTool].
 *
 * Verifies the Tool declaration: app.launch is REVERSIBLE, does not
 * require approval by default, and targets Android package names.
 * The execute() method requires an Android Context (PackageManager);
 * it is exercised by instrumented tests on a real device/emulator.
 */
class AppLaunchToolTest {

    @Test
    fun toolIdIsAppLaunch() {
        val tool = AppLaunchTool(androidContextFake()).tool
        assertEquals("app.launch", tool.id)
    }

    @Test
    fun toolRiskClassIsReversible() {
        val tool = AppLaunchTool(androidContextFake()).tool
        assertEquals(RiskClass.REVERSIBLE, tool.riskClass)
    }

    @Test
    fun toolRequiresAppLaunchCapability() {
        val tool = AppLaunchTool(androidContextFake()).tool
        assertTrue(tool.requiredCapabilities.contains("app.launch"))
    }

    @Test
    fun toolDoesNotRequireApproval() {
        val tool = AppLaunchTool(androidContextFake()).tool
        assertFalse(tool.approvalRequired)
        assertTrue(tool.reversible)
    }

    @Test
    fun toolIsNative() {
        val tool = AppLaunchTool(androidContextFake()).tool
        assertTrue(tool.native)
    }

    @Test
    fun toolHasInputAndOutputSchema() {
        val tool = AppLaunchTool(androidContextFake()).tool
        assertEquals("android package name", tool.inputSchema["package"])
        assertEquals("boolean", tool.outputSchema["ok"])
        assertEquals("string", tool.outputSchema["package"])
    }

    private fun androidContextFake(): Nothing {
        throw AssertionError("Android Context not available on JVM; use instrumented test")
    }
}
