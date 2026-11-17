package org.aethra.core.orchestration

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.aethra.core.policy.PolicyEngine
import org.aethra.core.policy.Approval
import org.aethra.core.policy.RiskTier
import org.aethra.core.skills.JsonSchemaValidator
import java.util.UUID
import java.security.MessageDigest
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

internal fun Any?.toArtifactJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Char -> JsonPrimitive(toString())
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { entry ->
        val key = entry.key as? String
            ?: throw IllegalArgumentException("Artifact object keys must be strings")
        key to entry.value.toArtifactJson()
    })
    is Iterable<*> -> JsonArray(map { it.toArtifactJson() })
    is Array<*> -> JsonArray(map { it.toArtifactJson() })
    else -> throw IllegalArgumentException(
        "Artifact payload is not JSON-compatible: ${this?.javaClass?.name ?: "null"}"
    )
}

class ArtifactStore {
    private val artifacts = LinkedHashMap<String, Artifact<*>>()

    @Synchronized
    fun put(artifact: Artifact<*>) {
        val existing = artifacts[artifact.id]
        if (existing != null) {
            require(existing == artifact) {
                "artifact id is immutable and already stores different content"
            }
            return
        }
        artifacts[artifact.id] = artifact
    }

    @Synchronized
    fun get(id: String): Artifact<*>? = artifacts[id]

    @Synchronized
    fun all(): List<Artifact<*>> = artifacts.values.toList()

    @Synchronized
    fun snapshot(taskId: String): Map<String, StoredArtifact> =
        artifacts.values
            .filter { it.taskId == taskId }
            .associate { artifact ->
                artifact.id to StoredArtifact(
                    id = artifact.id,
                    schemaVersion = artifact.schemaVersion,
                    producerSkillId = artifact.producerSkillId,
                    producerVersion = artifact.producerVersion,
                    taskId = artifact.taskId,
                    payload = artifact.value.toArtifactJson(),
                    provenance = artifact.provenance,
                    createdAt = artifact.createdAt
                )
            }

    @Synchronized
    fun restore(taskId: String, snapshot: Map<String, StoredArtifact>) {
        snapshot.forEach { (id, stored) ->
            require(id == stored.id) { "artifact snapshot key does not match artifact id" }
            require(stored.taskId == taskId) { "artifact belongs to a different task" }
            put(
                Artifact(
                    id = stored.id,
                    schemaVersion = stored.schemaVersion,
                    producerSkillId = stored.producerSkillId,
                    producerVersion = stored.producerVersion,
                    taskId = stored.taskId,
                    value = stored.payload,
                    provenance = stored.provenance,
                    createdAt = stored.createdAt
                )
            )
        }
    }
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
    private companion object {
        const val RECONCILIATION_ACTION = "workflow.reconcile.not_executed"
    }

    /**
     * Creates a one-shot Tier 2 approval for the exact evidence and step that would
     * be eligible for an explicit NOT_EXECUTED reconciliation.
     */
    fun requestReconciliationApproval(
        workflow: Workflow,
        taskId: String,
        stepId: String,
        evidence: ReconciliationEvidence,
        actor: String = "user"
    ): Approval? {
        val step = pendingReconciliationStep(workflow, taskId, stepId, evidence)
        requireReplayableStep(step)
        require(actor.isNotBlank()) { "reconciliation actor is required" }
        return policy.requestApproval(
            actor = actor,
            action = RECONCILIATION_ACTION,
            target = reconciliationTarget(workflow, taskId, step),
            normalizedPayload = reconciliationPayload(workflow, taskId, step, evidence),
            risk = RiskTier.TIER_2
        )
    }

    /**
     * Resolves an interrupted step through the same stores and policy instance
     * used by this orchestrator. NOT_EXECUTED is a replay authorization and
     * therefore consumes a one-shot approval bound to the exact step and evidence.
     */
    fun reconcileStep(
        workflow: Workflow,
        taskId: String,
        stepId: String,
        resolution: ReconciliationResolution,
        evidence: ReconciliationEvidence,
        artifactId: String? = null,
        reason: String? = null,
        approvalId: String? = null,
        actor: String = "user"
    ): WorkflowExecutionRecord {
        val step = pendingReconciliationStep(workflow, taskId, stepId, evidence)
        val target = reconciliationTarget(workflow, taskId, step)
        when (resolution) {
            ReconciliationResolution.NOT_EXECUTED -> {
                requireReplayableStep(step)
                require(!approvalId.isNullOrBlank()) {
                    "explicit one-shot approval is required before a non-executed step can be replayed"
                }
                require(
                    policy.consumeApproval(
                        approvalId,
                        actor,
                        RECONCILIATION_ACTION,
                        target,
                        reconciliationPayload(workflow, taskId, step, evidence)
                    )
                ) { "reconciliation approval is invalid, expired, mismatched, or already used" }
            }
            ReconciliationResolution.SUCCEEDED -> {
                require(approvalId == null) { "approval is only accepted for NOT_EXECUTED reconciliation" }
                require(!artifactId.isNullOrBlank()) { "reconciled success requires an artifact id" }
                val artifact = artifactStore.get(artifactId)
                    ?: error("reconciled success artifact is not present in the artifact store")
                val validationError = validateOutput(step, artifact, taskId)
                require(validationError == null) {
                    "reconciled success artifact is invalid: $validationError"
                }
            }
            ReconciliationResolution.FAILED -> {
                require(approvalId == null) { "approval is only accepted for NOT_EXECUTED reconciliation" }
                require(artifactId == null) { "failed reconciliation cannot attach a success artifact" }
            }
        }
        return WorkflowReconciler(workflowStore, artifactStore).reconcile(
            workflowId = workflow.id,
            taskId = taskId,
            stepId = stepId,
            resolution = resolution,
            evidence = evidence,
            artifactId = artifactId,
            reason = reason,
            approvalId = approvalId
        )
    }

    private fun pendingReconciliationStep(
        workflow: Workflow,
        taskId: String,
        stepId: String,
        evidence: ReconciliationEvidence
    ): WorkflowStep {
        require(workflow.id.isNotBlank()) { "workflow id is required" }
        require(taskId.isNotBlank()) { "task id is required" }
        require(evidence.reference.isNotBlank()) { "reconciliation evidence reference is required" }
        require(evidence.summary.isNotBlank()) { "reconciliation evidence summary is required" }
        require(evidence.idempotencyKey.isNotBlank()) { "reconciliation idempotency key is required" }
        val step = workflow.steps.filter { it.id == stepId }.singleOrNull()
            ?: error("workflow must contain exactly one step with id: $stepId")
        val record = workflowStore.load(workflow.id, taskId)
            ?: error("workflow execution record not found")
        val persistedStep = record.steps[stepId]
            ?: error("workflow step is not awaiting reconciliation: $stepId")
        require(
            persistedStep.status == StepResult.Status.RUNNING ||
                persistedStep.status == StepResult.Status.UNKNOWN_OUTCOME
        ) { "workflow step is not awaiting reconciliation: $stepId" }
        val expectedKey = step.idempotencyKey(taskId)
        require(persistedStep.idempotencyKey.isNotBlank() && persistedStep.idempotencyKey == expectedKey) {
            "persisted step idempotency key does not match the workflow step"
        }
        require(evidence.idempotencyKey == expectedKey) {
            "reconciliation evidence is bound to a different idempotency key"
        }
        return step
    }

    private fun requireReplayableStep(step: WorkflowStep) {
        val registered = registry.get(step.skillId)
            ?: error("replay skill is no longer registered")
        require(
            registered.trustState == org.aethra.core.skills.TrustState.TRUSTED &&
                registered.manifest.trusted &&
                registered.manifest.enabled
        ) { "replay skill is not trusted and enabled" }
        require(!registered.manifest.idempotent) {
            "explicit NOT_EXECUTED reconciliation is reserved for non-idempotent steps"
        }
    }

    private fun reconciliationTarget(workflow: Workflow, taskId: String, step: WorkflowStep): String {
        val version = registry.get(step.skillId)?.manifest?.version ?: "unknown"
        return "workflow:${workflow.id}/task:$taskId/step:${step.id}/skill:${step.skillId}@$version"
    }

    private fun reconciliationPayload(
        workflow: Workflow,
        taskId: String,
        step: WorkflowStep,
        evidence: ReconciliationEvidence
    ): String {
        val version = registry.get(step.skillId)?.manifest?.version ?: "unknown"
        val record = workflowStore.load(workflow.id, taskId)
            ?: error("workflow execution record not found while binding reconciliation approval")
        val inputIds = (step.inputArtifactIds + step.dependsOn.mapNotNull { dependency ->
            record.steps[dependency]?.artifactId
        }).toSortedSet()
        val inputDigests = inputIds.map { id ->
            val artifact = requireNotNull(record.artifacts[id]) {
                "cannot approve replay because input artifact is not durably stored: $id"
            }
            val canonicalArtifact = buildJsonObject {
                put("id", artifact.id)
                put("schemaVersion", artifact.schemaVersion)
                put("producerSkillId", artifact.producerSkillId)
                put("producerVersion", artifact.producerVersion)
                put("taskId", artifact.taskId)
                put("payload", artifact.payload)
                put("provenance", artifact.provenance)
                put("createdAt", artifact.createdAt)
            }.toString()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalArtifact.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            buildJsonObject {
                put("artifactId", id)
                put("sha256", digest)
            }
        }
        return buildJsonObject {
            put("resolution", ReconciliationResolution.NOT_EXECUTED.name)
            put("stepId", step.id)
            put("skillId", step.skillId)
            put("skillVersion", version)
            put("dependsOn", JsonArray(step.dependsOn.sorted().map { JsonPrimitive(it) }))
            put("inputArtifactIds", JsonArray(step.inputArtifactIds.sorted().map { JsonPrimitive(it) }))
            put("inputArtifactDigests", JsonArray(inputDigests))
            put("riskTier", step.riskTier.name)
            put("idempotencyKey", evidence.idempotencyKey)
            put("evidenceReference", evidence.reference)
            put("evidenceSummary", evidence.summary)
        }.toString()
    }

    suspend fun run(workflow: Workflow, taskId: String): Map<String, StepResult> {
        require(maxParallelism > 0) { "maxParallelism must be positive" }
        val validation = WorkflowValidator(registry).validate(workflow)
        require(validation.isEmpty()) { validation.joinToString("; ") }

        val persisted = workflowStore.load(workflow.id, taskId)
        persisted?.artifacts?.let { artifactStore.restore(taskId, it) }
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
                StepResult.Status.SUCCEEDED -> {
                    val output = record.artifactId?.let(artifactStore::get)
                    if (record.artifactId != null && output == null) {
                        val reason = "recovered successful step artifact is missing; reconciliation required"
                        results[id] = StepResult(StepResult.Status.UNKNOWN_OUTCOME, reason = reason)
                        audit(
                            step,
                            "recovery.artifact_missing",
                            StepResult.Status.UNKNOWN_OUTCOME,
                            reason,
                            artifactIds = listOf(record.artifactId)
                        )
                    } else {
                        results[id] = StepResult(StepResult.Status.SUCCEEDED, output = output)
                    }
                }
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
                    audit = auditEvents.toList(),
                    artifacts = artifactStore.snapshot(taskId)
                )
            )
        }

        audit(event = "workflow.started")
        persistState()

        try {
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
                        val inputValidation = validateInputs(step, inputs, taskId)
                        if (inputValidation != null) {
                            audit(step, "artifact.validation_failed", StepResult.Status.BLOCKED, inputValidation)
                            return@async StepResult(StepResult.Status.BLOCKED, reason = inputValidation)
                        }

                        val rawResult = try {
                            withTimeout(30.seconds) { executor.execute(step, inputs) }
                        } catch (timeout: TimeoutCancellationException) {
                            StepResult(
                                StepResult.Status.FAILED,
                                reason = timeout.message ?: "execution timed out"
                            )
                        } catch (cancelled: CancellationException) {
                            if (!currentCoroutineContext().isActive) throw cancelled
                            StepResult(
                                StepResult.Status.CANCELLED,
                                reason = cancelled.message ?: "execution cancelled"
                            )
                        } catch (failure: Exception) {
                            StepResult(
                                StepResult.Status.FAILED,
                                reason = failure.message ?: "execution error"
                            )
                        }
                        val outputValidation = if (
                            rawResult.status == StepResult.Status.SUCCEEDED && rawResult.output != null
                        ) {
                            validateOutput(step, rawResult.output, taskId)
                        } else {
                            null
                        }
                        val result = if (outputValidation != null) {
                            audit(
                                step,
                                "artifact.validation_failed",
                                StepResult.Status.FAILED,
                                outputValidation,
                                artifactIds = listOfNotNull(rawResult.output?.id)
                            )
                            StepResult(
                                StepResult.Status.FAILED,
                                reason = "output contract invalid: " + outputValidation
                            )
                        } else {
                            rawResult
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
        } catch (cancelled: CancellationException) {
            audit(
                event = "workflow.cancelled",
                status = StepResult.Status.CANCELLED,
                reason = cancelled.message ?: "workflow cancelled"
            )
            try {
                persistState()
            } catch (persistenceFailure: Exception) {
                cancelled.addSuppressed(persistenceFailure)
            }
            throw cancelled
        }
        audit(event = "workflow.finished")
        persistState()
        return results
    }

    private fun validateInputs(step: WorkflowStep, inputs: List<Artifact<*>>, taskId: String): String? {
        val consumer = registry.get(step.skillId)?.manifest
            ?: return "consumer skill is not registered"
        val schema = consumer.inputSchema
        if (schema == null) {
            return if (inputs.isEmpty()) null else "skill does not declare an input schema"
        }
        if (inputs.isEmpty()) return "required input artifact is missing"

        inputs.forEach { artifact ->
            val producerError = validateArtifactForProducer(artifact, taskId)
            if (producerError != null) return "input artifact invalid: " + producerError
            if (artifact.schemaVersion != consumer.inputSchemaVersion) {
                return "input schema version mismatch for skill " + step.skillId
            }
            val payload = runCatching { artifact.value.toArtifactJson() }.getOrElse {
                return "input artifact is not JSON-compatible"
            }
            val errors = JsonSchemaValidator.validate(payload, schema)
            if (errors.isNotEmpty()) {
                return "input artifact does not match consumer schema: " + errors.joinToString("; ")
            }
        }
        return null
    }

    private fun validateOutput(step: WorkflowStep, artifact: Artifact<*>, taskId: String): String? =
        validateArtifactForProducer(artifact, taskId, expectedSkillId = step.skillId)

    private fun validateArtifactForProducer(
        artifact: Artifact<*>,
        taskId: String,
        expectedSkillId: String? = null
    ): String? {
        if (expectedSkillId != null && artifact.producerSkillId != expectedSkillId) {
            return "artifact producer does not match executing skill"
        }
        if (artifact.taskId != taskId) return "artifact belongs to a different task"
        if (artifact.provenance.isBlank()) return "artifact provenance is missing"

        val producer = registry.get(artifact.producerSkillId)
            ?: return "artifact producer is not registered"
        val manifest = producer.manifest
        if (
            producer.trustState != org.aethra.core.skills.TrustState.TRUSTED ||
            !manifest.trusted ||
            !manifest.enabled
        ) {
            return "artifact producer is not trusted and enabled"
        }
        if (artifact.producerVersion != manifest.version) return "artifact producer version mismatch"
        if (artifact.schemaVersion != manifest.outputSchemaVersion) return "artifact output schema version mismatch"

        val schema = manifest.outputSchema ?: return "artifact producer has no output schema"
        val payload = runCatching { artifact.value.toArtifactJson() }.getOrElse {
            return "artifact payload is not JSON-compatible"
        }
        val errors = JsonSchemaValidator.validate(payload, schema)
        if (errors.isNotEmpty()) return errors.joinToString("; ")
        return null
    }

}
