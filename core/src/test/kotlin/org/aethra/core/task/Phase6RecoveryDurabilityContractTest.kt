package org.aethra.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 6 + Phase 3 durability contract for verification state:
 *
 * Invariant under test: verification + evidence -> durable persistence ->
 * terminal state -> terminal event.
 *
 * - Evidence and the verdict must survive process death with the task.
 * - Corrupt journal / corrupt verification fields must fail closed (never be
 *   read as verified, never silently become an empty journal).
 * - Recovery of an in-flight execution must NOT reuse the previous attempt's
 *   evidence for the new execution attempt.
 */
class Phase6RecoveryDurabilityContractTest {

    private class MemoryStorage : Storage {
        val data = mutableMapOf<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = data[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { data[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull() ?: defaultValue
        override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = data[key]?.toBooleanStrictOrNull() ?: defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
        override suspend fun getAll(): Map<String, String> = data.toMap()
        override suspend fun remove(key: String) { data.remove(key) }
        override suspend fun clear() { data.clear() }
    }

    private class CorruptReadStorage : Storage {
        override suspend fun getString(key: String, defaultValue: String?): String? {
            throw StorageException.Corrupt("journal unreadable")
        }
        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun getLong(key: String, defaultValue: Long?): Long? = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = emptyMap()
        override suspend fun remove(key: String) = Unit
        override suspend fun clear() = Unit
    }

    private fun runningTaskWithEvidence(goal: String, evidenceType: String, evidenceValue: String): Task {
        val task = Task(goal, riskClass = RiskClass.READ_ONLY)
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        task.updateState(TaskState.RUNNING)
        // Simulate a verification pass recorded mid-flight before process death.
        task.recordVerification(
            TaskVerification(
                outcome = VerificationOutcome.VERIFIED,
                executionAttemptId = task.executionAttemptId,
                evidence = listOf(EvidenceItem(
                    type = evidenceType,
                    value = evidenceValue,
                    source = "runner",
                    executionAttemptId = task.executionAttemptId
                ))
            )
        )
        return task
    }

    // -------------------------------------------------------------------
    // Recovery must not reuse old evidence for a different execution
    // -------------------------------------------------------------------

    @Test
    fun inFlightRecoveryClearsStaleEvidenceAndStartsNewAttempt() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = runningTaskWithEvidence("died mid-flight", "http", "200")
        val oldAttemptId = task.executionAttemptId
        val oldCounter = task.attemptCounter

        persistence.saveAll(listOf(task))
        val restored = persistence.loadAll().single { it.id == task.id }

        assertEquals(TaskState.QUEUED, restored.state)
        assertNotEquals(oldAttemptId, restored.executionAttemptId)
        assertEquals(oldCounter + 1, restored.attemptCounter)
        // The old attempt's evidence/verdict must not leak into the new execution.
        assertEquals(VerificationOutcome.UNKNOWN, restored.verificationOutcome)
        assertTrue("stale evidence must be cleared on recovery", restored.verificationEvidence.isEmpty())
        assertTrue(restored.verificationPostconditions.isEmpty())
        assertFalse(restored.isVerified)
    }

    @Test
    fun engineRestartReExecutesInFlightTaskWithoutReusingOldEvidence() = runBlocking {
        val storage = MemoryStorage()
        val stale = runningTaskWithEvidence("restart me", "http", "200")
        val staleId = stale.id
        val staleAttemptId = stale.executionAttemptId
        TaskPersistence(storage).saveAll(listOf(stale))

        // "Process death": a fresh engine over the same journal, with a runner
        // that produces DIFFERENT evidence for the new execution.
        val bus = EventBus()
        val engine = TaskEngine(storage, bus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> =
                Result.success(mapOf("evidence" to listOf("http:201")))
        })

        var restored: Task? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            restored = engine.getTask(staleId)
            if (restored != null && restored!!.isTerminal()) break
            delay(25)
        }

        assertEquals(TaskState.COMPLETED, restored?.state)
        assertNotEquals(staleAttemptId, restored!!.executionAttemptId)
        assertTrue(
            "new execution must carry only its own evidence, not the pre-death evidence",
            restored!!.verificationEvidence.any { it.type == "http" && it.value == "201" }
        )
        assertTrue(
            "stale pre-death evidence must not be reused for the new execution",
            restored!!.verificationEvidence.none { it.value == "200" }
        )
        assertEquals(VerificationOutcome.VERIFIED, restored!!.verificationOutcome)
        engine.close()
    }

    @Test
    fun explicitRetryClearsOldVerificationEvidence() = runBlocking {
        val storage = MemoryStorage()
        val bus = EventBus()
        var executions = 0
        val engine = TaskEngine(storage, bus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> {
                executions++
                return if (executions == 1) {
                    Result.success(mapOf("success" to false)) // verification FAILED
                } else {
                    Result.success(mapOf("evidence" to listOf("http:202")))
                }
            }
        })
        val task = engine.create("retry with new evidence", riskClass = RiskClass.READ_ONLY)
        task.retryBudget = 0 // fail after first cycle
        engine.run(task.id)

        var deadline = System.currentTimeMillis() + 5_000
        while (!task.isTerminal() && System.currentTimeMillis() < deadline) delay(20)
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(VerificationOutcome.FAILED, task.verificationOutcome)
        assertTrue(task.verificationEvidence.any { it.type == "reported_success" })

        task.retryBudget = 0
        engine.retry(task.id)
        deadline = System.currentTimeMillis() + 5_000
        while (task.state != TaskState.COMPLETED && System.currentTimeMillis() < deadline) delay(20)

        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals(VerificationOutcome.VERIFIED, task.verificationOutcome)
        assertTrue(
            "after explicit retry the evidence must come from the new execution only",
            task.verificationEvidence.any { it.type == "http" && it.value == "202" }
        )
        assertTrue(
            "old failed-cycle evidence must not survive the retry",
            task.verificationEvidence.none { it.type == "reported_success" }
        )
        engine.close()
    }

    // -------------------------------------------------------------------
    // Evidence recoverable after death for terminal tasks
    // -------------------------------------------------------------------

    @Test
    fun terminalTaskRetainsVerificationAndEvidenceAfterDeath() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = Task("finished", riskClass = RiskClass.READ_ONLY)
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        task.updateState(TaskState.RUNNING)
        val verification = TaskVerification(
            outcome = VerificationOutcome.VERIFIED,
            executionAttemptId = task.executionAttemptId,
            evidence = listOf(EvidenceItem(
                type = "http",
                value = "200",
                source = "runner",
                executionAttemptId = task.executionAttemptId
            ))
        )
        task.completeWithVerification(verification)

        persistence.saveAll(listOf(task))
        val restored = persistence.loadAll().single { it.id == task.id }

        assertEquals(TaskState.COMPLETED, restored.state)
        assertEquals(VerificationOutcome.VERIFIED, restored.verificationOutcome)
        assertTrue(restored.isVerified)
        assertTrue(restored.verificationEvidence.any { it.type == "http" && it.value == "200" })
        assertEquals(task.executionAttemptId, restored.executionAttemptId)
    }

    // -------------------------------------------------------------------
    // Corrupt data fails closed
    // -------------------------------------------------------------------

    @Test
    fun corruptJournalThrowsInsteadOfReturningEmpty() = runBlocking {
        val storage = MemoryStorage()
        storage.data["aethra.tasks.v1"] = "not-json{{{corrupt"
        try {
            TaskPersistence(storage).loadAll()
            fail("corrupt journal must fail closed, not return an empty list")
        } catch (e: PersistenceException) {
            // expected: fail closed
        }
    }

    @Test
    fun corruptStorageReadMapsToPersistenceException() = runBlocking {
        try {
            TaskPersistence(CorruptReadStorage()).loadAll()
            fail("storage corruption must propagate as PersistenceException")
        } catch (e: PersistenceException.Corrupt) {
            assertTrue(e.cause is StorageException.Corrupt)
        }
    }

    @Test
    fun tamperedOutcomeNameNeverReadsAsVerified() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = Task("tampered", riskClass = RiskClass.READ_ONLY)
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        task.updateState(TaskState.RUNNING)
        task.completeWithVerification(
            TaskVerification(
                outcome = VerificationOutcome.VERIFIED,
                executionAttemptId = task.executionAttemptId,
                evidence = listOf(EvidenceItem(
                            type = "http",
                            value = "200",
                            source = "runner",
                            executionAttemptId = task.executionAttemptId
                        ))
            )
        )
        persistence.saveAll(listOf(task))

        // Tamper: claim an outcome name no version of the code ever produced,
        // while keeping isVerified=true.
        val tampered = storage.data.getValue("aethra.tasks.v1")
            .replace("\"verificationOutcome\":\"VERIFIED\"", "\"verificationOutcome\":\"VERIFIED_TAMPERED\"")
        assertNotEquals(tampered, storage.data.getValue("aethra.tasks.v1"))
        storage.data["aethra.tasks.v1"] = tampered

        val restored = persistence.loadAll().single { it.id == task.id }
        assertEquals(
            "unknown outcome names must degrade to UNKNOWN",
            VerificationOutcome.UNKNOWN, restored.verificationOutcome
        )
        assertFalse(
            "a tampered journal must never be read as verified",
            restored.isVerified
        )
    }

    @Test
    fun malformedEvidenceEntryDecodesToRawWithoutFailingTheJournal() = runBlocking {
        val storage = MemoryStorage()
        val persistence = TaskPersistence(storage)
        val task = Task("bad evidence", riskClass = RiskClass.READ_ONLY)
        task.updateState(TaskState.PREPARING)
        task.updateState(TaskState.APPROVED)
        task.updateState(TaskState.RUNNING)
        task.completeWithVerification(
                    TaskVerification(
                        outcome = VerificationOutcome.VERIFIED,
                        executionAttemptId = task.executionAttemptId,
                        evidence = listOf(EvidenceItem(
                            type = "http",
                            value = "200",
                            source = "runner",
                            executionAttemptId = task.executionAttemptId
                        ))
                    )
                )
        persistence.saveAll(listOf(task))

        // Tamper: replace the one evidence entry with a malformed record.
                val tampered = storage.data.getValue("aethra.tasks.v1")
                    .replace("\"http=200@runner@${task.executionAttemptId}\"", "\"garbage-no-separator\"")
                assertNotEquals(tampered, storage.data.getValue("aethra.tasks.v1"))
                storage.data["aethra.tasks.v1"] = tampered

        val restored = persistence.loadAll().single { it.id == task.id }
        val evidence = restored.verificationEvidence.single()
        assertEquals("raw", evidence.type)
        assertEquals("garbage-no-separator", evidence.value)
    }
}
