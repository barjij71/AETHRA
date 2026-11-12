package org.aethra.core.policy

enum class RiskTier { TIER_0, TIER_1, TIER_2, TIER_3 }

data class Approval(
    val id: String,
    val actor: String,
    val action: String,
    val target: String,
    val normalizedPayload: String,
    val expiresAt: Long,
    val used: Boolean = false
)

interface PolicyEngine {
    fun allows(skillId: String, action: String, payload: Set<String>): Boolean
    fun requestApproval(actor: String, action: String, target: String, normalizedPayload: String, risk: RiskTier): Approval?
    fun consumeApproval(approvalId: String, actor: String, action: String, target: String, normalizedPayload: String): Boolean
}

class CorePolicyEngine : PolicyEngine {
    private val approvals = mutableMapOf<String, Approval>()

    override fun allows(skillId: String, action: String, payload: Set<String>): Boolean {
        return action == "execute" && payload.none { it.startsWith("sensitive:") }
    }

    @Synchronized
    override fun requestApproval(actor: String, action: String, target: String, normalizedPayload: String, risk: RiskTier): Approval? {
        if (risk < RiskTier.TIER_2) return null
        val approval = Approval(java.util.UUID.randomUUID().toString(), actor, action, target, normalizedPayload, System.currentTimeMillis() + 300_000)
        approvals[approval.id] = approval
        return approval
    }

    @Synchronized
    override fun consumeApproval(approvalId: String, actor: String, action: String, target: String, normalizedPayload: String): Boolean {
        val a = approvals[approvalId] ?: return false
        if (a.used || a.expiresAt < System.currentTimeMillis()) return false
        if (a.actor != actor || a.action != action || a.target != target || a.normalizedPayload != normalizedPayload) return false
        approvals[approvalId] = a.copy(used = true)
        return true
    }
}
