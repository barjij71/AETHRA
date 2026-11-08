package org.aethra.core.orchestration

import kotlinx.coroutines.*
import org.aethra.core.policy.PolicyEngine
import org.aethra.core.policy.RiskTier
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class Artifact<T>(
    val id: String = UUID.randomUUID().toString(),
    val schemaVersion: String,
    val producerSkillId: String,
    val producerVersion: String,
    val taskId: String,
    val value: T,
    val provenance: String,
    val createdAt: Long = System.currentTimeMillis()
)

class ArtifactStore {
    private val artifacts = LinkedHashMap<String, Artifact<*>>()
    @Synchronized fun put(artifact: Artifact<*>) { artifacts[artifact.id] = artifact }
    @Synchronized fun get(id: String): Artifact<*>? = artifacts[id]
    @Synchronized fun all(): List<Artifact<*>> = artifacts.values.toList()
}

data class StepResult(
    val status: Status,
    val output: Artifact<*>? = null,
    val reason: String? = null
) {
    enum class Status { SUCCEEDED, FAILED, BLOCKED, CANCELLED, UNKNOWN_OUTCOME }
}

fun interface SkillExecutor {
    suspend fun execute(step: WorkflowStep, inputs: List<Artifact<*>>): StepResult
}

class Orchestrator(
    private val registry: org.aethra.core.skills.SkillRegistry,
    private val artifactStore: ArtifactStore,
    private val policy: PolicyEngine,
    private val executor: SkillExecutor,
    private val maxParallelism: Int = 2
) {
    suspend fun run(workflow: Workflow, taskId: String): Map<String, StepResult> {
        require(maxParallelism > 0) { "maxParallelism must be positive" }
        val validation = WorkflowValidator(registry).validate(workflow)
        require(validation.isEmpty()) { validation.joinToString("; ") }

        val results = LinkedHashMap<String, StepResult>()
        val remaining = workflow.steps.toMutableList()
        val active = mutableMapOf<String, Deferred<StepResult>>()

        coroutineScope {
            while (remaining.isNotEmpty() || active.isNotEmpty()) {
                remaining.filter { step ->
                    step.dependsOn.any { dep ->
                        results[dep]?.status in setOf(
                            StepResult.Status.FAILED,
                            StepResult.Status.BLOCKED,
                            StepResult.Status.CANCELLED,
                            StepResult.Status.UNKNOWN_OUTCOME
                        )
                    }
                }.forEach { step ->
                    results[step.id] = StepResult(StepResult.Status.BLOCKED, reason = "dependency failed")
                    remaining.remove(step)
                }

                while (active.size < maxParallelism) {
                    val step = remaining.firstOrNull { candidate ->
                        candidate.dependsOn.all { results[it]?.status == StepResult.Status.SUCCEEDED }
                    } ?: break
                    remaining.remove(step)

                    active[step.id] = async {
                        if (step.riskTier >= RiskTier.TIER_2) {
                            val approvalId = step.approvalId
                                ?: return@async StepResult(StepResult.Status.BLOCKED, reason = "approval required")
                            if (!policy.consumeApproval(
                                    approvalId,
                                    step.approvalActor,
                                    "execute",
                                    step.approvalTarget,
                                    step.approvalPayload
                                )
                            ) {
                                return@async StepResult(StepResult.Status.BLOCKED, reason = "approval invalid or already used")
                            }
                        }

                        if (!policy.allows(step.skillId, "execute", step.inputArtifactIds)) {
                            return@async StepResult(StepResult.Status.BLOCKED, reason = "blocked by core policy")
                        }

                        val dependencyInputs = step.dependsOn.mapNotNull { dep -> results[dep]?.output }
                        val explicitInputs = step.inputArtifactIds.mapNotNull(artifactStore::get)
                        if (explicitInputs.size != step.inputArtifactIds.size) {
                            return@async StepResult(StepResult.Status.BLOCKED, reason = "missing required artifact")
                        }
                        val inputs = (dependencyInputs + explicitInputs).distinctBy { it.id }
                        runCatching {
                            withTimeout(30.seconds) { executor.execute(step, inputs) }
                        }.getOrElse {
                            StepResult(StepResult.Status.FAILED, reason = it.message ?: "execution error")
                        }
                    }
                }

                if (active.isNotEmpty()) {
                    val completed = active.entries.firstOrNull { it.value.isCompleted }
                    if (completed != null) {
                        val result = active.remove(completed.key)!!.await()
                        if (result.status == StepResult.Status.SUCCEEDED && result.output != null) {
                            artifactStore.put(result.output)
                        }
                        results[completed.key] = result
                    } else {
                        delay(5)
                    }
                } else if (remaining.isNotEmpty()) {
                    remaining.forEach {
                        results[it.id] = StepResult(
                            StepResult.Status.BLOCKED,
                            reason = "dependency graph cannot make progress"
                        )
                    }
                    remaining.clear()
                }
            }
        }
        return results
    }
}
