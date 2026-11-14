package org.aethra.core

import org.aethra.core.orchestration.Workflow
import org.aethra.core.orchestration.WorkflowStep
import org.aethra.core.orchestration.WorkflowValidator
import org.aethra.core.policy.CorePolicyEngine
import org.aethra.core.policy.RiskTier
import org.aethra.core.skills.SkillManifest
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.skills.TrustState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyEngineRiskTierTest {
    @Test
    fun tierThreeCannotObtainAnApproval() {
        val policy = CorePolicyEngine()
        assertNull(
            policy.requestApproval(
                actor = "user",
                action = "execute",
                target = "device-1",
                normalizedPayload = "destructive:erase",
                risk = RiskTier.TIER_3
            )
        )
    }

    @Test
    fun tierThreeWorkflowIsRejectedEvenWhenGivenTierTwoApproval() {
        val registry = SkillRegistry()
        registry.register(
            SkillManifest(
                id = "critical",
                version = "1.0.0",
                name = "Critical",
                source = "test",
                license = "MIT",
                inputSchemaVersion = "1",
                outputSchemaVersion = "1",
                trusted = true
            )
        )
        assertTrue(registry.setTrust("critical", TrustState.TRUSTED))
        assertTrue(registry.enable("critical"))

        val policy = CorePolicyEngine()
        val approval = assertNotNull(
            policy.requestApproval(
                actor = "user",
                action = "execute",
                target = "device-1",
                normalizedPayload = "destructive:erase",
                risk = RiskTier.TIER_2
            )
        )
        val workflow = Workflow(
            "wf-tier-3",
            listOf(
                WorkflowStep(
                    id = "critical-step",
                    skillId = "critical",
                    riskTier = RiskTier.TIER_3,
                    approvalId = approval.id,
                    approvalTarget = "device-1",
                    approvalPayload = "destructive:erase"
                )
            )
        )

        val errors = WorkflowValidator(registry).validate(workflow)
        assertTrue(errors.any { it.contains("tier 3 action denied by default") })
    }
}
