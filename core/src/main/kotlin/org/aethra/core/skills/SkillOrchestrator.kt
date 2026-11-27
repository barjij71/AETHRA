package org.aethra.core.skills

import org.aethra.core.domain.RiskClass
import org.aethra.core.tools.ToolRegistry
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine

/**
 * Orchestrates skill-to-task bridging while preserving TaskEngine as the sole
 * execution authority.
 *
 * This component:
 * 1. Validates skill exists and is enabled
 * 2. Validates entrypointId exists in skill.entrypoints
 * 3. Validates entrypointId resolves to a Tool ID via ToolRegistry
 * 4. Verifies the Tool is registered
 * 5. Constructs Task with goal=toolId, riskClass=max(requested risk, skill.riskClass)
 * 6. Preserves skill provenance via createdAtBy=skill.id
 * 7. Delegates to TaskEngine for execution
 *
 * Does NOT:
 * - Execute tools directly
 * - Call ToolExecutor directly
 * - Mutate authoritative Task terminal state
 * - Perform verification
 * - Persist authoritative execution state independently of TaskEngine
 * - Bypass ExecutionPolicy, PermissionBroker, or CapabilityAvailability
 */
class SkillOrchestrator(
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val taskEngine: TaskEngine
) {

    /**
     * Request to execute a skill's entrypoint tool.
     */
    data class Request(
        val skillId: String,
        val entrypointId: String,
        val role: String = "assistant",
        val requestedRiskClass: RiskClass = RiskClass.READ_ONLY,
        val detail: Map<String, String> = emptyMap(),
        val dependsOn: List<String> = emptyList()
    )

    /**
     * Result of executing a skill orchestrator request.
     */
    sealed class Result {
        data class Success(val task: Task) : Result()
        data class Failure(val reason: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Orchestrates the execution of a skill's tool entrypoint.
     *
     * This validates the skill, validates the entrypoint resolves to a tool,
     * derives the effective risk class, and delegates to TaskEngine.
     *
     * @param request the skill execution request
     * @return Result.Success with the created Task, or Result.Failure with the reason
     */
    fun execute(request: Request): Result {
        // Step 1: Validate skill exists
        val skill = skillRegistry.get(request.skillId)
            ?: return Result.Failure("skill not found: ${request.skillId}")

        // Step 2: Validate skill is enabled
        if (!skill.enabled) {
            return Result.Failure("skill not enabled: ${request.skillId}")
        }

        // Step 3: Validate entrypointId exists in skill.entrypoints
        if (request.entrypointId !in skill.entrypoints) {
            return Result.Failure(
                "entrypoint not found in skill: ${request.entrypointId} not in ${skill.entrypoints}"
            )
        }

        // Step 4: Validate entrypointId resolves to a Tool ID
        val tool = toolRegistry.resolve(request.entrypointId)
            ?: return Result.Failure(
                "entrypoint does not resolve to a registered tool: ${request.entrypointId}"
            )

        // Step 5: Verify tool is registered (it should be since we just resolved it)
        val registeredTool = toolRegistry.get(tool.id)
            ?: return Result.Failure(
                "resolved tool not registered: ${tool.id}"
            )

        // Step 6: Derive effective risk class (max of requested risk and skill risk)
        val effectiveRisk = if (request.requestedRiskClass.level > skill.riskClass.level) {
            request.requestedRiskClass
        } else {
            skill.riskClass
        }

        // Step 7: Construct Task with provenance and delegate to TaskEngine
        val task = taskEngine.create(
            goal = tool.id,
            role = request.role,
            riskClass = effectiveRisk,
            dependsOn = request.dependsOn,
            createdAtBy = skill.id,
            detail = request.detail
        )

        return Result.Success(task)
    }

    /**
     * Convenience method to execute a tool by skill ID and entrypoint ID.
     * Does not check if the skill is enabled - use [execute] for full validation.
     */
    fun delegate(
        skillId: String,
        entrypointId: String,
        role: String = "assistant",
        requestedRiskClass: RiskClass = RiskClass.READ_ONLY,
        detail: Map<String, String> = emptyMap(),
        dependsOn: List<String> = emptyList()
    ): Result = execute(
        Request(
            skillId = skillId,
            entrypointId = entrypointId,
            role = role,
            requestedRiskClass = requestedRiskClass,
            detail = detail,
            dependsOn = dependsOn
        )
    )
}