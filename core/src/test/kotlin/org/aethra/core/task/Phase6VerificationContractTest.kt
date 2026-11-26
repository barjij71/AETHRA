package org.aethra.core.task

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 contract: Core owns the verification authority.
 *
 * These tests pin the trust boundary:
 * - A Runner may only contribute *evidence*; it may never declare a verdict.
 * - Only a Core-owned [VerificationOutcome.VERIFIED] may complete a task.
 * - Evidence and the verdict are durably persisted with the task.
 */
class Phase6VerificationContractTest {

    private class MemoryStorage : Storage {
        private val data = mutableMapOf<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = data[key]
        override suspend fun setString(key: String, value: String) { data[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull()
        override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = data[key]?.toBooleanStrictOrNull()
        override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
        override suspend fun getAll(): Map<String, String> = data.toMap()
        override suspend fun remove(key: String) { data.remove(key) }
        override suspend fun clear() { data.clear() }
    }

    private fun runnerOf(result: Result<Map<String, Any>>) = object : TaskRunner {
        override suspend fun run(task: Task): Result<Map<String, Any>> = result
    }

    private suspend fun awaitTerminal(engine: TaskEngine, task: Task, timeoutMs: Long = 5_000) {
        val start = System.currentTimeMillis()
        while (!task.isTerminal() && System.currentTimeMillis() - start < timeoutMs) {
            kotlinx.coroutines.delay(20)
        }
    }

    /**
     * Awaits the terminal event for [taskId].
     *
     * The Phase 3 contract publishes the terminal event only AFTER the durable
     * write completes, so awaiting the event is the correct way to observe that
     * the journal already carries the terminal state.
     */
    private suspend fun awaitTerminalEvent(
        bus: EventBus,
        taskId: String,
        timeoutMs: Long = 5_000
    ) {
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
        bus.subscribe(org.aethra.core.domain.TaskEvent::class.java) { event ->
            if (event.taskId == taskId &&
                event.source in setOf("execution_completed", "execution_failed", "execution_timeout", "execution_cancelled")
            ) {
                signal.complete(Unit)
            }
        }
        kotlinx.coroutines.withTimeout(timeoutMs) { signal.await() }
    }

    // -----------------------------------------------------------------------
    // Core owns the verdict
    // -----------------------------------------------------------------------

    @Test
    fun runnerCannotDeclareTheVerdictItself() {
        val verifier = TaskVerifier()
        // A runner tries to smuggle a verdict through the result map.
        val smuggled = Result.success<Map<String, Any>>(
            mapOf(
                "verified" to true,
                "verificationOutcome" to "VERIFIED",
                "realWorldObjectiveVerified" to true,
                "status" to "success"
            )
        )

        val verdict = verifier.verify(
            Task("goal"),
            smuggled,
            executionAttemptId = "attempt-1"
        )

        // The verdict is produced by Core. The runner's self-declared fields are
        // not evidence types the policy understands, so they cannot grant VERIFIED
        // on their own -- but the execution itself completed normally, so Core
        // still reaches VERIFIED through its own baseline policy.
        assertEquals(VerificationOutcome.VERIFIED, verdict.outcome)
        assertTrue(
            "runner-declared verdict fields must not become evidence",
            verdict.evidence.none { it.type in setOf("verified", "verificationOutcome", "realWorldObjectiveVerified") }
        )
    }

    @Test
    fun runnerCannotOverrideAFailedExecutionWithAClaimedSuccess() {
        val verifier = TaskVerifier()
        val verdict = verifier.verify(
            Task("goal"),
            Result.failure<Map<String, Any>>(IllegalStateException("boom")),
            executionAttemptId = "attempt-1"
        )

        assertEquals(VerificationOutcome.FAILED, verdict.outcome)
        assertEquals("boom", verdict.details["reason"])
    }

    @Test
    fun runnerReportedFailureIsTreatedAsFailedNotVerified() {
        val verifier = TaskVerifier()
        val verdict = verifier.verify(
            Task("goal"),
            Result.success(mapOf("success" to false)),
            executionAttemptId = "attempt-1"
        )

        assertEquals(VerificationOutcome.FAILED, verdict.outcome)
    }

    @Test
    fun runnerReportedErrorIsTreatedAsFailedNotVerified() {
        val verifier = TaskVerifier()
        val verdict = verifier.verify(
            Task("goal"),
            Result.success(mapOf("success" to true, "error" to "device unreachable")),
            executionAttemptId = "attempt-1"
        )

        assertEquals(VerificationOutcome.FAILED, verdict.outcome)
        assertEquals("device unreachable", verdict.details["reason"])
    }

    // -----------------------------------------------------------------------
    // Postcondition evaluation
    // -----------------------------------------------------------------------

    @Test
    fun satisfiedPostconditionYieldsVerified() {
        val task = Task(
            "goal",
            postconditions = listOf(
                Postcondition("device_running", "device reports running", "device_status", "running")
            )
        )
        val verifier = TaskVerifier()
        val verdict = verifier.verify(
            task,
            Result.success(mapOf("evidence" to listOf("device_status=running"))),
            executionAttemptId = "attempt-1"
        )

        assertEquals(VerificationOutcome.VERIFIED, verdict.outcome)
        assertEquals(1, verdict.postconditionResults.size)
        assertTrue(verdict.postconditionResults.single().satisfied)
    }

    @Test
    fun unsatisfiedPostconditionYieldsFailed() {
        val task = Task(
            "goal",
            postconditions = listOf(
                Postcondition("device_running", "device reports running", "device_status", "running")
            )
        )
        val verifier = TaskVerifier()
        val verdict = verifier.verify(
            task,
            Result.success(mapOf("evidence" to listOf("device_status=stopped"))),
            executionAttemptId = "attempt-1"
        )

        assertEquals(VerificationOutcome.FAILED, verdict.outcome)
        assertFalse(verdict.postconditionResults.single().satisfied)
    }

    @Test
    fun missingEvidenceYieldsUnknownNotVerifiedAndNotFailed() {
        val task = Task(
            "goal",
            postconditions = listOf(
                Postcondition("device_running", "device reports running", "device_status", "running")
            )
        )
        val verifier = TaskVerifier()
        val verdict = verifier.verify(
            task,
            Result.success(mapOf("evidence" to listOf("something_else=value"))),
            executionAttemptId = "attempt-1"
        )

        assertEquals(VerificationOutcome.UNKNOWN, verdict.outcome)
        assertTrue(verdict.postconditionResults.single().evidenceMissing)
        assertEquals(listOf("device_status"), verdict.missingEvidenceTypes)
    }

    // -----------------------------------------------------------------------
    // TaskEngine integration
    // -----------------------------------------------------------------------

    @Test
    fun verifiedTaskCompletesAndRecordsTheVerdict() = runBlocking {
        val storage = MemoryStorage()
        val engine = TaskEngine(storage, EventBus(), runnerOf(Result.success(mapOf("ok" to true))))
        val task = engine.create("do something", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)
        awaitTerminal(engine, task)

        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals(VerificationOutcome.VERIFIED, task.verificationOutcome)
        assertTrue(task.isVerified)
        engine.close()
    }

    @Test
    fun verificationFailureNeverCompletesTheTask() = runBlocking {
        val storage = MemoryStorage()
        val engine = TaskEngine(
            storage,
            EventBus(),
            runnerOf(Result.success(mapOf("success" to false)))
        )
        val task = engine.create("must not complete", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 0
        engine.run(task.id)
        awaitTerminal(engine, task)

        assertEquals(TaskState.FAILED, task.state)
        assertEquals(VerificationOutcome.FAILED, task.verificationOutcome)
        assertFalse(task.isVerified)
        engine.close()
    }

    @Test
    fun unmetPostconditionNeverCompletesTheTask() = runBlocking {
        val storage = MemoryStorage()
        val engine = TaskEngine(
            storage,
            EventBus(),
            runnerOf(Result.success(mapOf("evidence" to listOf("device_status=stopped"))))
        )
        val task = engine.create(
            "must not complete",
            riskClass = RiskClass.READ_ONLY,
            postconditions = listOf(
                Postcondition("device_running", "device reports running", "device_status", "running")
            )
        )
        task.retryBudget = 0
        engine.run(task.id)
        awaitTerminal(engine, task)

        assertEquals(TaskState.FAILED, task.state)
        assertFalse(task.isVerified)
        engine.close()
    }

    @Test
    fun evidenceIsCollectedFromTheRunnerResult() = runBlocking {
        val storage = MemoryStorage()
        val engine = TaskEngine(
            storage,
            EventBus(),
            runnerOf(Result.success(mapOf("evidence" to listOf("http:200"), "output" to "done")))
        )
        val task = engine.create("collect evidence", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)
        awaitTerminal(engine, task)

        assertEquals(TaskState.COMPLETED, task.state)
        assertTrue(task.verificationEvidence.any { it.type == "http" && it.value == "200" })
        assertTrue(task.verificationEvidence.any { it.type == "output" && it.value == "done" })
        engine.close()
    }

    // -----------------------------------------------------------------------
    // Durability
    // -----------------------------------------------------------------------

    @Test
    fun verificationOutcomeAndEvidenceSurvivePersistence() = runBlocking {
        val storage = MemoryStorage()
        val bus = EventBus()
        val engine = TaskEngine(
            storage,
            bus,
            runnerOf(Result.success(mapOf("evidence" to listOf("http:200"))))
        )
        val task = engine.create("persist evidence", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)
        awaitTerminalEvent(bus, task.id)

        val restored = TaskPersistence(storage).loadAll().single { it.id == task.id }
        assertEquals(TaskState.COMPLETED, restored.state)
        assertEquals(VerificationOutcome.VERIFIED, restored.verificationOutcome)
        assertTrue(restored.isVerified)
        assertTrue(restored.verificationEvidence.any { it.type == "http" && it.value == "200" })
        engine.close()
    }

    @Test
    fun failedVerificationSurvivesPersistence() = runBlocking {
        val storage = MemoryStorage()
        val bus = EventBus()
        val engine = TaskEngine(
            storage,
            bus,
            runnerOf(Result.success(mapOf("success" to false)))
        )
        val task = engine.create("persist failure", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 0
        engine.run(task.id)
        awaitTerminalEvent(bus, task.id)

        val restored = TaskPersistence(storage).loadAll().single { it.id == task.id }
        assertEquals(TaskState.FAILED, restored.state)
        assertEquals(VerificationOutcome.FAILED, restored.verificationOutcome)
        assertFalse(restored.isVerified)
        engine.close()
    }

    @Test
    fun declaredPostconditionsSurvivePersistence() = runBlocking {
        val storage = MemoryStorage()
        val bus = EventBus()
        val engine = TaskEngine(storage, bus, runnerOf(Result.success(mapOf("ok" to true))))
        val task = engine.create(
            "persist postconditions",
            riskClass = RiskClass.READ_ONLY,
            postconditions = listOf(
                Postcondition("device_running", "device reports running", "device_status", "running")
            )
        )
        engine.run(task.id)
        awaitTerminalEvent(bus, task.id)

        val restored = TaskPersistence(storage).loadAll().single { it.id == task.id }
        assertEquals(1, restored.postconditions.size)
        assertEquals("device_running", restored.postconditions.single().name)
        assertEquals("device_status", restored.postconditions.single().evidenceType)
        assertEquals("running", restored.postconditions.single().expectedValue)
        engine.close()
    }

    @Test
    fun evidenceBelongsToTheExecutionAttemptThatProducedIt() = runBlocking {
        val storage = MemoryStorage()
        val engine = TaskEngine(storage, EventBus(), runnerOf(Result.success(mapOf("ok" to true))))
        val task = engine.create("attempt scoped", riskClass = RiskClass.READ_ONLY)
        engine.run(task.id)
        awaitTerminal(engine, task)

        val verification = TaskVerifier().verify(task, Result.success(mapOf("ok" to true)), task.executionAttemptId)
        assertEquals(task.executionAttemptId, verification.executionAttemptId)
        assertNotEquals(task.id, verification.executionAttemptId)
        engine.close()
    }
}
