package org.aethra.core

import org.aethra.core.orchestration.Workflow
import org.aethra.core.orchestration.WorkflowStep
import org.aethra.core.orchestration.WorkflowValidator
import org.aethra.core.skills.SkillManifest
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.skills.TrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillRegistryTrustBoundaryTest {
    private fun manifest(trusted: Boolean, enabled: Boolean) = SkillManifest(
        id = "candidate",
        version = "1.0.0",
        name = "Candidate",
        source = "external-import",
        license = "MIT",
        inputSchemaVersion = "1",
        outputSchemaVersion = "1",
        trusted = trusted,
        enabled = enabled
    )

    @Test
    fun untrustedManifestCannotSelfEnableDuringRegistration() {
        val registry = SkillRegistry()
        registry.register(manifest(trusted = false, enabled = true))

        val registered = registry.get("candidate")
        assertEquals(TrustState.UNTRUSTED, registered?.trustState)
        assertFalse(registered?.manifest?.enabled ?: true)
        assertTrue(
            WorkflowValidator(registry).validate(
                Workflow("wf-untrusted", listOf(WorkflowStep("step", "candidate")))
            ).any { it.contains("not trusted and enabled") }
        )
    }

    @Test
    fun revokingTrustImmediatelyDisablesSkillAndBlocksWorkflow() {
        val registry = SkillRegistry()
        registry.register(manifest(trusted = true, enabled = true), TrustState.TRUSTED)
        assertTrue(registry.enable("candidate"))
        assertTrue(registry.get("candidate")?.manifest?.enabled == true)

        assertTrue(registry.setTrust("candidate", TrustState.REVOKED))
        assertFalse(registry.get("candidate")?.manifest?.enabled ?: true)

        val errors = WorkflowValidator(registry).validate(
            Workflow("wf-revoked", listOf(WorkflowStep("step", "candidate")))
        )
        assertTrue(errors.any { it.contains("not trusted and enabled") })
    }

    @Test
    fun registryRequiresExplicitEnableAfterTrustIsGranted() {
        val registry = SkillRegistry()
        registry.register(manifest(trusted = true, enabled = true), TrustState.TRUSTED)

        assertFalse(registry.get("candidate")?.manifest?.enabled ?: true)
        assertTrue(registry.enable("candidate"))
        assertEquals(TrustState.TRUSTED, registry.get("candidate")?.trustState)
        assertTrue(registry.get("candidate")?.manifest?.enabled == true)
    }
}
