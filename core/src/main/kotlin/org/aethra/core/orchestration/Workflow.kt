package org.aethra.core.orchestration

import org.aethra.core.policy.RiskTier
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.skills.TrustState

data class WorkflowStep(
    val id: String,
    val skillId: String,
    val dependsOn: Set<String> = emptySet(),
    val inputArtifactIds: Set<String> = emptySet(),
    val riskTier: RiskTier = RiskTier.TIER_0,
    val approvalId: String? = null,
    val approvalActor: String = "user",
    val approvalTarget: String = "",
    val approvalPayload: String = ""
) {
    fun idempotencyKey(taskId: String): String = "$taskId::$id"
}

data class Workflow(val id: String, val steps: List<WorkflowStep>)

class WorkflowValidator(private val registry: SkillRegistry) {
    fun validate(workflow: Workflow): List<String> {
        val errors = mutableListOf<String>()
        val ids = workflow.steps.map { it.id }
        if (ids.size != ids.toSet().size) errors += "duplicate step id"
        val stepIds = ids.toSet()
        workflow.steps.forEach { step ->
            val registered = registry.get(step.skillId)
            if (
                registered?.trustState != TrustState.TRUSTED ||
                registered.manifest.trusted != true ||
                registered.manifest.enabled != true
            ) {
                errors += "skill " + step.skillId + " is not trusted and enabled"
            }
            if (step.dependsOn.any { it !in stepIds }) errors += "step " + step.id + " has missing dependency"
            if (step.dependsOn.contains(step.id)) errors += "step " + step.id + " depends on itself"
            if (step.inputArtifactIds.any { it.isBlank() }) errors += "step " + step.id + " has blank artifact reference"
            if (step.riskTier >= RiskTier.TIER_2 && step.approvalId == null) errors += "step " + step.id + " requires explicit approval"
            if (step.riskTier >= RiskTier.TIER_2 && step.approvalTarget.isBlank()) errors += "step " + step.id + " requires approval target"
            if (step.riskTier >= RiskTier.TIER_2 && step.approvalPayload.isBlank()) errors += "step " + step.id + " requires approval payload"
        }
        if (hasCycle(workflow.steps)) errors += "workflow contains a dependency cycle"
        return errors
    }

    private fun hasCycle(steps: List<WorkflowStep>): Boolean {
        val graph = steps.associate { it.id to it.dependsOn }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun dfs(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            graph[id].orEmpty().forEach { if (dfs(it)) return true }
            visiting -= id
            visited += id
            return false
        }
        return graph.keys.any(::dfs)
    }
}
