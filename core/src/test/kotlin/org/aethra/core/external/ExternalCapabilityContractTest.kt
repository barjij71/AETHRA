package org.aethra.core.external

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalCapabilityContractTest {
    private class FakeCapability(
        override val id: String,
        override val type: ExternalCapabilityType,
        override val displayName: String,
        override val capabilities: Set<String>
    ) : ExternalCapability {
        override suspend fun health(): CapabilityHealth = CapabilityHealth.READY
        override suspend fun execute(request: CapabilityRequest): Result<CapabilityResult> =
            Result.success(CapabilityResult(id, request.operation, true, request.arguments))
    }

    @Test
    fun registryStoresAndFiltersCapabilities() {
        val registry = ExternalCapabilityRegistry()
        val github = FakeCapability("github", ExternalCapabilityType.GITHUB, "GitHub", setOf("repo.read"))
        val mcp = FakeCapability("mcp.demo", ExternalCapabilityType.MCP_SERVER, "Demo MCP", setOf("tool.call"))
        registry.register(github)
        registry.register(mcp)
        assertEquals(github, registry.get("github"))
        assertEquals(listOf(github), registry.byType(ExternalCapabilityType.GITHUB))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun routerRejectsUnsupportedOperations() = runBlocking {
        val registry = ExternalCapabilityRegistry()
        registry.register(FakeCapability("github", ExternalCapabilityType.GITHUB, "GitHub", setOf("repo.read")))
        assertTrue(ExternalCapabilityRouter(registry).execute("github", CapabilityRequest("repo.write")).isFailure)
    }

    @Test
    fun routerRejectsBlockedRemoteOperations() = runBlocking {
        val registry = ExternalCapabilityRegistry()
        registry.register(FakeCapability("instabox", ExternalCapabilityType.REMOTE_COMPUTER, "InstaBox", setOf("shell.exec")))
        val result = ExternalCapabilityRouter(registry).execute(
            "instabox",
            CapabilityRequest("shell.exec", mapOf("command" to "port_scan localhost"))
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun routerDispatchesStructuredRequest() = runBlocking {
        val registry = ExternalCapabilityRegistry()
        registry.register(FakeCapability("instabox", ExternalCapabilityType.REMOTE_COMPUTER, "InstaBox", setOf("shell.exec")))
        val result = ExternalCapabilityRouter(registry).execute(
            "instabox", CapabilityRequest("shell.exec", mapOf("command" to "uname -a"))
        ).getOrThrow()
        assertTrue(result.success)
        assertEquals("uname -a", result.output["command"])
    }
}