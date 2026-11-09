package org.aethra.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ToolRegistryResolutionTest {
    @Test
    fun resolvesExplicitAliasCaseInsensitively() {
        val tool = ToolRegistry().resolve("INFO DEVICE")
        assertNotNull(tool)
        assertEquals("device.info", tool?.id)
    }

    @Test
    fun resolvesIdPrefixedArguments() {
        val tool = ToolRegistry().resolve("file.read some/path")
        assertNotNull(tool)
        assertEquals("file.read", tool?.id)
    }

    @Test
    fun unknownCommandDoesNotResolve() {
        assertEquals(null, ToolRegistry().resolve("unknown.command"))
    }
}
