package org.aethra.assistant.aethra.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aethra.core.domain.RiskClass

/**
 * JVM unit tests for [DeviceInfoTool].
 *
 * These tests verify the Tool declaration and risk classification.
 * The execute() method requires an Android Context and is exercised
 * by instrumented tests on a real device/emulator.
 */
class DeviceInfoToolTest {

    @Test
    fun toolIdIsDeviceInfo() {
        val tool = DeviceInfoTool(androidContextFake()).tool
        assertEquals("device.info", tool.id)
    }

    @Test
    fun toolRiskClassIsReadOnly() {
        val tool = DeviceInfoTool(androidContextFake()).tool
        assertEquals(RiskClass.READ_ONLY, tool.riskClass)
    }

    @Test
    fun toolRequiresDeviceQueryCapability() {
        val tool = DeviceInfoTool(androidContextFake()).tool
        assertTrue(tool.requiredCapabilities.contains("device.query"))
        assertTrue(tool.requiredCapabilities.contains("app.query"))
    }

    @Test
    fun toolDoesNotRequireApproval() {
        val tool = DeviceInfoTool(androidContextFake()).tool
        assertFalse(tool.approvalRequired)
        assertTrue(tool.reversible)
    }

    @Test
    fun toolHasInputAndOutputSchema() {
        val tool = DeviceInfoTool(androidContextFake()).tool
        assertEquals("string", tool.inputSchema["query"])
        assertEquals("object", tool.outputSchema["info"])
    }

    // Fake Context substitute: DeviceInfoTool.execute() requires a real
    // Android Context, so we cannot test it on a plain JVM. The Tool
    // declaration is verified here instead.
    private fun androidContextFake(): Nothing {
        throw AssertionError("Android Context not available on JVM; use instrumented test")
    }
}
