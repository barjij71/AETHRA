package org.aethra.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.aethra.core.orchestration.*
import org.aethra.core.policy.CorePolicyEngine
import org.aethra.core.skills.SkillManifest
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.skills.TrustState
import java.nio.file.Files
import kotlin.test.*

class WorkflowReconciliationIntegrationTest {
    private val contract = Json.parseToJsonElement(
        """{"type":"object","required":["n"],"properties":{"n":{"type":"integer"}},"additionalProperties":false}"""
    ).jsonObject

    private fun manifest(
        id: String,
        idempotent: Boolean = true,
        inputSchema: JsonObject? = null,
        outputSchema: JsonObject? = null
    ) = SkillManifest(
        id = id,
        version = "1.0.0",
        name = id,
        source = "integration-test",
        license = "MIT",
        inputSchemaVersion = "1",
        outputSchemaVersion = "1",
        trusted = true,
        enabled = true,
        idempotent = idempotent,
        inputSchema = inputSchema,
        outputSchema = outputSchema
    )

    private fun trustedRegistry(vararg manifests: SkillManifest): SkillRegistry =
        SkillRegistry().apply {
            manifests.forEach { candidate ->
                register(candidate)
                setTrust(candidate.id, TrustState.TRUSTED)
                enable(candidate.id)
            }
        }

    @Test
    fun notExecutedReconciliationRequiresExactApprovalAndResumesThroughOrchestratorAfterRestart() = runTest {
        val taskId = "task-reconcile-replay"
        val workflow = Workflow(
            "workflow-reconcile-replay",
            listOf(WorkflowStep("once-step", "once"))
        )
        val evidence = ReconciliationEvidence(
            reference = "executor-query-17",
            summary = "the external executor confirmed that the action was not submitted",
            idempotencyKey = "$taskId::once-step"
        )
        val file = Files.createTempFile("aethra-reconcile-replay-", ".json")
        val registry = trustedRegistry(manifest("once", idempotent = false))
        var executions = 0

        try {
            val started = CompletableDeferred<Unit>()
            val firstOrchestrator = Orchestrator(
                registry = registry,
                artifactStore = ArtifactStore(),
                policy = CorePolicyEngine(),
                executor = SkillExecutor { _, _ ->
                    executions += 1
                    started.complete(Unit)
                    awaitCancellation()
                },
                workflowStore = JsonFileWorkflowStore(file)
            )
            val firstRun = launch { firstOrchestrator.run(workflow, taskId) }
            started.await()
            firstRun.cancelAndJoin()

            val interrupted = JsonFileWorkflowStore(file).load(workflow.id, taskId)
            assertEquals(StepResult.Status.RUNNING, interrupted?.steps?.get("once-step")?.status)
            assertEquals(1, executions)

            val restarted = Orchestrator(
                registry = registry,
                artifactStore = ArtifactStore(),
                policy = CorePolicyEngine(),
                executor = SkillExecutor { _, _ ->
                    executions += 1
                    StepResult(StepResult.Status.SUCCEEDED)
                },
                workflowStore = JsonFileWorkflowStore(file)
            )
            val recovered = restarted.run(workflow, taskId)
            assertEquals(StepResult.Status.UNKNOWN_OUTCOME, recovered["once-step"]?.status)
            assertEquals(1, executions, "recovery must not replay a non-idempotent step")

            assertFailsWith<IllegalArgumentException> {
                restarted.reconcileStep(
                    workflow, taskId, "once-step",
                    ReconciliationResolution.NOT_EXECUTED, evidence
                )
            }
            assertEquals(
                StepResult.Status.UNKNOWN_OUTCOME,
                JsonFileWorkflowStore(file).load(workflow.id, taskId)?.steps?.get("once-step")?.status
            )

            val approval = assertNotNull(
                restarted.requestReconciliationApproval(workflow, taskId, "once-step", evidence)
            )
            assertFailsWith<IllegalArgumentException> {
                restarted.reconcileStep(
                    workflow, taskId, "once-step",
                    ReconciliationResolution.NOT_EXECUTED,
                    evidence.copy(summary = "changed evidence"),
                    approvalId = approval.id
                )
            }

            val reconciled = restarted.reconcileStep(
                workflow = workflow,
                taskId = taskId,
                stepId = "once-step",
                resolution = ReconciliationResolution.NOT_EXECUTED,
                evidence = evidence,
                approvalId = approval.id
            )
            val reconciliationEvent = reconciled.audit.last {
                it.event == "recovery.reconciled_not_executed"
            }
            assertEquals(evidence.reference, reconciliationEvent.evidenceReference)
            assertEquals(evidence.idempotencyKey, reconciliationEvent.idempotencyKey)
            assertEquals(approval.id, reconciliationEvent.approvalId)
            assertFalse("once-step" in reconciled.steps)

            val resumed = restarted.run(workflow, taskId)
            assertEquals(StepResult.Status.SUCCEEDED, resumed["once-step"]?.status)
            assertEquals(2, executions, "the action may run again only after the approved NOT_EXECUTED decision")
            val finalRecord = JsonFileWorkflowStore(file).load(workflow.id, taskId)
            assertEquals(
                approval.id,
                finalRecord?.audit?.last { it.event == "recovery.reconciled_not_executed" }?.approvalId
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun reconciledSuccessPersistsValidatedArtifactForDownstreamAfterRestart() = runTest {
        val taskId = "task-reconcile-success"
        val workflow = Workflow(
            "workflow-reconcile-success",
            listOf(
                WorkflowStep("produce", "producer"),
                WorkflowStep("consume", "consumer", dependsOn = setOf("produce"))
            )
        )
        val registry = trustedRegistry(
            manifest("producer", outputSchema = contract),
            manifest("consumer", inputSchema = contract)
        )
        val artifactStore = ArtifactStore()
        val artifact = Artifact(
            id = "reconciled-artifact",
            schemaVersion = "1",
            producerSkillId = "producer",
            producerVersion = "1.0.0",
            taskId = taskId,
            value = mapOf("n" to 7),
            provenance = "verified-executor-record"
        )
        artifactStore.put(artifact)
        val file = Files.createTempFile("aethra-reconcile-success-", ".json")

        try {
            JsonFileWorkflowStore(file).save(
                WorkflowExecutionRecord(
                    workflowId = workflow.id,
                    taskId = taskId,
                    steps = mapOf(
                        "produce" to StepExecutionRecord(
                            stepId = "produce",
                            status = StepResult.Status.UNKNOWN_OUTCOME,
                            idempotencyKey = "$taskId::produce"
                        )
                    )
                )
            )

            val reconciler = Orchestrator(
                registry = registry,
                artifactStore = artifactStore,
                policy = CorePolicyEngine(),
                executor = SkillExecutor { _, _ ->
                    error("the reconciled producer must not be executed again")
                },
                workflowStore = JsonFileWorkflowStore(file)
            )
            val reconciled = reconciler.reconcileStep(
                workflow = workflow,
                taskId = taskId,
                stepId = "produce",
                resolution = ReconciliationResolution.SUCCEEDED,
                evidence = ReconciliationEvidence(
                    reference = "executor-result-88",
                    summary = "the executor result and artifact were independently retrieved",
                    idempotencyKey = "$taskId::produce"
                ),
                artifactId = artifact.id
            )

            assertEquals(StepResult.Status.SUCCEEDED, reconciled.steps["produce"]?.status)
            assertTrue(artifact.id in reconciled.artifacts)

            var consumerReceivedRestoredArtifact = false
            val restarted = Orchestrator(
                registry = registry,
                artifactStore = ArtifactStore(),
                policy = CorePolicyEngine(),
                executor = SkillExecutor { step, inputs ->
                    assertEquals("consume", step.id)
                    assertEquals(1, inputs.size)
                    val payload = inputs.single().value as? JsonObject
                    assertNotNull(payload)
                    assertEquals("7", payload["n"]?.toString())
                    consumerReceivedRestoredArtifact = true
                    StepResult(StepResult.Status.SUCCEEDED)
                },
                workflowStore = JsonFileWorkflowStore(file)
            )

            val results = restarted.run(workflow, taskId)
            assertEquals(StepResult.Status.SUCCEEDED, results["produce"]?.status)
            assertEquals(StepResult.Status.SUCCEEDED, results["consume"]?.status)
            assertTrue(consumerReceivedRestoredArtifact)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun reconciledSuccessRejectsArtifactFromAnotherSkillBeforeChangingState() = runTest {
        val taskId = "task-reconcile-invalid-artifact"
        val workflow = Workflow(
            "workflow-reconcile-invalid-artifact",
            listOf(WorkflowStep("produce", "producer"))
        )
        val registry = trustedRegistry(
            manifest("producer", outputSchema = contract),
            manifest("other", outputSchema = contract)
        )
        val artifacts = ArtifactStore()
        artifacts.put(
            Artifact(
                id = "wrong-producer",
                schemaVersion = "1",
                producerSkillId = "other",
                producerVersion = "1.0.0",
                taskId = taskId,
                value = mapOf("n" to 1),
                provenance = "wrong-producer"
            )
        )
        val store = InMemoryWorkflowStore()
        store.save(
            WorkflowExecutionRecord(
                workflowId = workflow.id,
                taskId = taskId,
                steps = mapOf(
                    "produce" to StepExecutionRecord(
                        "produce",
                        StepResult.Status.UNKNOWN_OUTCOME,
                        idempotencyKey = "$taskId::produce"
                    )
                )
            )
        )
        val orchestrator = Orchestrator(
            registry, artifacts, CorePolicyEngine(),
            SkillExecutor { _, _ -> error("executor must not run") },
            workflowStore = store
        )

        assertFailsWith<IllegalArgumentException> {
            orchestrator.reconcileStep(
                workflow, taskId, "produce",
                ReconciliationResolution.SUCCEEDED,
                ReconciliationEvidence(
                    "wrong-result",
                    "claimed result from another skill",
                    "$taskId::produce"
                ),
                artifactId = "wrong-producer"
            )
        }
        assertEquals(
            StepResult.Status.UNKNOWN_OUTCOME,
            store.load(workflow.id, taskId)?.steps?.get("produce")?.status
        )
    }
}
