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
    enum class Status { SUCCEEDED, FAILED, BLOCKED, CANCELLED, UNKNOWN_OUTCOME, RUNNING }
}

fun interface SkillExecutor {
    suspend fun execute(step: WorkflowStep, inputs: List<Artifact<*>>): StepResult
}

class Orchestrator(
    private val registry: org.aethra.core.skills.SkillRegistry,
    private val artifactStore: ArtifactStore,
    private val policy: PolicyEngine,
    private val executor: SkillExecutor,
    private val maxParallelism: Int = 2,
    private val workflowStore: WorkflowStore = InMemoryWorkflowStore()
) {
    suspend fun run(workflow: Workflow, taskId: String): Map<String, StepResult> {
        require(maxParallelism > 0) { "maxParallelism must be positive" }
        val validation = WorkflowValidator(registry).validate(workflow)
        require(validation.isEmpty()) { validation.joinToString("; ") }

        val persisted = workflowStore.load(workflow.id, taskId)
        val results = LinkedHashMap<String, StepResult>()
        val auditEvents = (persisted?.audit ?: emptyList()).toMutableList()

        fun audit(
            step: WorkflowStep? = null,
            event: String,
            status: StepResult.Status? = null,
            reason: String? = null,
            artifactIds: List<String> = emptyList(),
            approvalId: String? = null
        ) {
            auditEvents += AuditEvent(
                eventId = UUID.randomUUID().toString(),
                workflowId = workflow.id,
                taskId = taskId,
                stepId = step?.id,
                skillId = step?.skillId,
                skillVersion = step?.let { registry.get(it.skillId)?.manifest?.version },
                event = event,
                status = status,
                reason = reason,
                artifactIds = artifactIds,
                approvalId = approvalId
            )
        }

        persisted?.steps?.forEach { (id, record) ->
            val step = workflow.steps.firstOrNull { it.id == id } ?: return@forEach
            when (record.status) {
                StepResult.Status.SUCCEEDED ->
                    results[id] = StepResult(StepResult.Status.SUCCEEDED)
                StepResult.Status.RUNNING -> {
                    val idempotent = registry.get(step.skillId)?.manifest?.idempotent == true
                    if (!idempotent) {
                        val reason = "recovered non-idempotent step; reconciliation required"
                        results[id] = StepResult(StepResult.Status.UNKNOWN_OUTCOME, reason = reason)
                        audit(step, "recovery.unknown_outcome", StepResult.Status.UNKNOWN_OUTCOME, reason)
                    }
                }
                StepResult.Status.FAILED, StepResult.Status.BLOCKED,
                StepResult.Status.CANCELLED, StepResult.Status.UNKNOWN_OUTCOME ->
                    results[id] = StepResult(record.status, reason = record.reason)
            }
        }

        val remaining = workflow.steps.filter { it.id !in results }.toMutableList()
        val active = mutableMapOf<String, Deferred<StepResult>>()

        fun persistState() {
            workflowStore.save(
                WorkflowExecutionRecord(
                    workflowId = workflow.id,
                    taskId = taskId,
                    steps = results.mapValues { (id, result) ->
                        val step = workflow.steps.first { it.id == id }
                        StepExecutionRecord(
                            stepId = id,
                            status = result.status,
                            reason = result.reason,
                            artifactId = result.output?.id,
                            idempotencyKey = step.idempotencyKey(taskId)
                        )
                    },
                    audit = auditEvents.toList()
                )
            )
        }

        audit(event = "workflow.started")
        persistState()

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
                    val reason = "dependency failed"
                    results[step.id] = StepResult(StepResult.Status.BLOCKED, reason = reason)
                    audit(step, "step.blocked", StepResult.Status.BLOCKED, reason)
                    remaining.remove(step)
                }
                persistState()

                while (active.size < maxParallelism) {
                    val step = remaining.firstOrNull { candidate ->
                        candidate.dependsOn.all { results[it]?.status == StepResult.Status.SUCCEEDED }
                    } ?: break
                    remaining.remove(step)

                    results[step.id] = StepResult(StepResult.Status.RUNNING)
                    audit(step, "step.started", StepResult.Status.RUNNING)
                    persistState()

                    active[step.id] = async {
                        if (step.riskTier >= RiskTier.TIER_2) {
                            val approvalId = step.approvalId
                                ?: run {
                                    val reason = "approval required"
                                    audit(step, "approval.required", StepResult.Status.BLOCKED, reason)
                                    return@async StepResult(StepResult.Status.BLOCKED, reason = reason)
                                }
                            if (!policy.consumeApproval(
                                    approvalId,
                                    step.approvalActor,
                                    "execute",
                                    step.approvalTarget,
                                    step.approvalPayload
                                )
                            ) {
                                val reason = "approval invalid or already used"
                                audit(step, "approval.rejected", StepResult.Status.BLOCKED, reason, approvalId = approvalId)
                                return@async StepResult(StepResult.Status.BLOCKED, reason = reason)
                            }
                            audit(step, "approval.consumed", approvalId = approvalId)
                        }

                        if (!policy.allows(step.skillId, "execute", step.inputArtifactIds)) {
                            val reason = "blocked by core policy"
                            audit(step, "policy.blocked", StepResult.Status.BLOCKED, reason)
                            return@async StepResult(StepResult.Status.BLOCKED, reason = reason)
                        }

                        val dependencyInputs = step.dependsOn.mapNotNull { dep -> results[dep]?.output }
                        val explicitInputs = step.inputArtifactIds.mapNotNull(artifactStore::get)
                        if (explicitInputs.size != step.inputArtifactIds.size) {
                            val reason = "missing required artifact"
                            audit(step, "artifact.missing", StepResult.Status.BLOCKED, reason)
                            return@async StepResult(StepResult.Status.BLOCKED, reason = reason)
                        }
                        val inputs = (dependencyInputs + explicitInputs).distinctBy { it.id }
                        val result = runCatching {
                            withTimeout(30.seconds) { executor.execute(step, inputs) }
                        }.getOrElse {
                            StepResult(StepResult.Status.FAILED, reason = it.message ?: "execution error")
                        }
                        audit(
                            step,
                            "step.finished",
                            result.status,
                            result.reason,
                            artifactIds = listOfNotNull(result.output?.id)
                        )
                        result
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
                        persistState()
                    } else {
                        delay(5)
                    }
                } else if (remaining.isNotEmpty()) {
                    remaining.forEach {
                        val reason = "dependency graph cannot make progress"
                        results[it.id] = StepResult(StepResult.Status.BLOCKED, reason = reason)
                        audit(it, "step.blocked", StepResult.Status.BLOCKED, reason)
                    }
                    remaining.clear()
                    persistState()
                }
            }
        }
        audit(event = "workflow.finished")
        persistState()
        return results
    }
}
