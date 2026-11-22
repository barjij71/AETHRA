package org.aethra.core.workflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.aethra.core.task.PersistenceException
import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class ReviewRepairLoopContractTest {
    @Test
    fun `new loop starts planning with zero repair cycles`() = runBlocking {
        val controller = controller()

        val loop = controller.create(
            loopId = "loop-initial",
            maxRepairCycles = 2,
            workflowId = "workflow-1",
            agentId = "agent-1"
        )

        assertEquals("loop-initial", loop.id)
        assertEquals(ReviewRepairLoopState.PLANNING, loop.state)
        assertEquals(2, loop.maxRepairCycles)
        assertEquals(0, loop.repairCyclesUsed)
        assertEquals("workflow-1", loop.workflowId)
        assertEquals("agent-1", loop.agentId)
        assertTrue(loop.evidence.isEmpty())
        assertNull(loop.completedAt)
    }

    @Test
    fun `plan success advances to executing`() = runBlocking {
        val controller = controller()
        controller.create("loop-plan", 1)

        val loop = controller.recordStageOutcome(
            "loop-plan", ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, "plan ready"
        )

        assertEquals(ReviewRepairLoopState.EXECUTING, loop.state)
        assertEquals(ReviewRepairStage.PLAN, loop.evidence.single().stage)
        assertEquals(ReviewRepairOutcome.SUCCESS, loop.evidence.single().outcome)
    }

    @Test
    fun `execute success advances to testing`() = runBlocking {
        val controller = controller()
        createExecuting(controller, "loop-execute")

        val loop = controller.recordStageOutcome(
            "loop-execute", ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "execution complete"
        )

        assertEquals(ReviewRepairLoopState.TESTING, loop.state)
    }

    @Test
    fun `test success advances to verifying without consuming repair budget`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-test-success", maxRepairCycles = 2)

        val loop = controller.recordStageOutcome(
            "loop-test-success", ReviewRepairStage.TEST, ReviewRepairOutcome.SUCCESS, "tests passed"
        )

        assertEquals(ReviewRepairLoopState.VERIFYING, loop.state)
        assertEquals(0, loop.repairCyclesUsed)
    }

    @Test
    fun `test failure enters repairing and consumes exactly one cycle`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-test-failure", maxRepairCycles = 2)

        val failed = controller.recordStageOutcome(
            "loop-test-failure", ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "assertion failed"
        )

        assertEquals(ReviewRepairLoopState.REPAIRING, failed.state)
        assertEquals(1, failed.repairCyclesUsed)
        assertEquals(0, failed.evidence.last().cycleNumber)
        assertEquals("assertion failed", failed.evidence.last().summary)

        val stored = controller.get("loop-test-failure")!!
        assertEquals(1, stored.repairCyclesUsed)
    }

    @Test
    fun `successful repair returns to execute then test path`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-repair-return", maxRepairCycles = 2)
        controller.recordStageOutcome(
            "loop-repair-return", ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "needs repair"
        )

        val repaired = controller.recordStageOutcome(
            "loop-repair-return", ReviewRepairStage.REPAIR, ReviewRepairOutcome.SUCCESS, "patch applied"
        )
        assertEquals(ReviewRepairLoopState.EXECUTING, repaired.state)
        assertEquals(1, repaired.evidence.last().cycleNumber)

        val executed = controller.recordStageOutcome(
            "loop-repair-return", ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "rerun complete"
        )
        assertEquals(ReviewRepairLoopState.TESTING, executed.state)
        assertEquals(1, executed.repairCyclesUsed)
    }

    @Test
    fun `successful verification is the only path to succeeded`() = runBlocking {
        val controller = controller()
        createVerifying(controller, "loop-verified")

        val loop = controller.recordStageOutcome(
            "loop-verified", ReviewRepairStage.VERIFY, ReviewRepairOutcome.SUCCESS, "evidence verified"
        )

        assertEquals(ReviewRepairLoopState.SUCCEEDED, loop.state)
        assertNotNull(loop.completedAt)
        assertEquals(ReviewRepairStage.VERIFY, loop.evidence.last().stage)
        assertEquals(ReviewRepairOutcome.SUCCESS, loop.evidence.last().outcome)
    }

    @Test
    fun `failed verification can enter bounded repair`() = runBlocking {
        val controller = controller()
        createVerifying(controller, "loop-verify-failure", maxRepairCycles = 1)

        val loop = controller.recordStageOutcome(
            "loop-verify-failure", ReviewRepairStage.VERIFY, ReviewRepairOutcome.FAILURE, "verification mismatch"
        )

        assertEquals(ReviewRepairLoopState.REPAIRING, loop.state)
        assertEquals(1, loop.repairCyclesUsed)
    }

    @Test
    fun `zero repair budget makes first test failure terminal`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-zero-budget", maxRepairCycles = 0)

        val loop = controller.recordStageOutcome(
            "loop-zero-budget", ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "no repair allowed"
        )

        assertEquals(ReviewRepairLoopState.FAILED, loop.state)
        assertEquals(0, loop.repairCyclesUsed)
        assertNotNull(loop.completedAt)
    }

    @Test
    fun `budget exhaustion makes later failure terminal without extra cycle`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-exhaustion", maxRepairCycles = 1)
        controller.recordStageOutcome(
            "loop-exhaustion", ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "first failure"
        )
        controller.recordStageOutcome(
            "loop-exhaustion", ReviewRepairStage.REPAIR, ReviewRepairOutcome.SUCCESS, "first repair"
        )
        controller.recordStageOutcome(
            "loop-exhaustion", ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "rerun"
        )

        val exhausted = controller.recordStageOutcome(
            "loop-exhaustion", ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "still failing"
        )

        assertEquals(ReviewRepairLoopState.FAILED, exhausted.state)
        assertEquals(1, exhausted.repairCyclesUsed)
        assertEquals(1, exhausted.evidence.last().cycleNumber)
    }

    @Test
    fun `cannot repair after budget exhaustion`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-no-repair-after-failure", maxRepairCycles = 0)
        controller.recordStageOutcome(
            "loop-no-repair-after-failure", ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "terminal"
        )

        val result = runCatching {
            controller.recordStageOutcome(
                "loop-no-repair-after-failure",
                ReviewRepairStage.REPAIR,
                ReviewRepairOutcome.SUCCESS,
                "must not run"
            )
        }

        assertTrue(result.isFailure)
        assertEquals(ReviewRepairLoopState.FAILED, controller.get("loop-no-repair-after-failure")?.state)
    }

    @Test
    fun `cannot report verification or success before verification state`() = runBlocking {
        val controller = controller()
        controller.create("loop-no-early-success", 1)

        val result = runCatching {
            controller.recordStageOutcome(
                "loop-no-early-success",
                ReviewRepairStage.VERIFY,
                ReviewRepairOutcome.SUCCESS,
                "premature"
            )
        }

        assertTrue(result.isFailure)
        assertEquals(ReviewRepairLoopState.PLANNING, controller.get("loop-no-early-success")?.state)
    }

    @Test
    fun `terminal success cannot reenter active loop`() = runBlocking {
        val controller = controller()
        createVerifying(controller, "loop-terminal")
        controller.recordStageOutcome(
            "loop-terminal", ReviewRepairStage.VERIFY, ReviewRepairOutcome.SUCCESS, "done"
        )

        val result = runCatching {
            controller.recordStageOutcome(
                "loop-terminal", ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, "restart"
            )
        }

        assertTrue(result.isFailure)
        assertEquals(ReviewRepairLoopState.SUCCEEDED, controller.get("loop-terminal")?.state)
    }

    @Test
    fun `evidence links loop cycle stage outcome and execution identities`() = runBlocking {
        val controller = controller()
        controller.create("loop-evidence", 1, workflowId = "workflow-e", agentId = "agent-e")

        val loop = controller.recordStageOutcome(
            loopId = "loop-evidence",
            stage = ReviewRepairStage.PLAN,
            outcome = ReviewRepairOutcome.SUCCESS,
            summary = "traceable plan",
            taskId = "task-e"
        )
        val evidence = loop.evidence.single()

        assertEquals("loop-evidence", evidence.loopId)
        assertEquals(0, evidence.cycleNumber)
        assertEquals(ReviewRepairStage.PLAN, evidence.stage)
        assertEquals(ReviewRepairOutcome.SUCCESS, evidence.outcome)
        assertEquals("traceable plan", evidence.summary)
        assertEquals("task-e", evidence.taskId)
        assertEquals("agent-e", evidence.agentId)
        assertTrue(evidence.recordedAt > 0)
    }

    @Test
    fun `blank summary and negative budget are rejected without state`() = runBlocking {
        val controller = controller()
        assertTrue(runCatching { controller.create("negative", -1) }.isFailure)
        assertNull(controller.get("negative"))

        controller.create("blank-summary", 1)
        val result = runCatching {
            controller.recordStageOutcome(
                "blank-summary", ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, " "
            )
        }
        assertTrue(result.isFailure)
        assertEquals(ReviewRepairLoopState.PLANNING, controller.get("blank-summary")?.state)
        assertTrue(controller.get("blank-summary")?.evidence?.isEmpty() == true)
    }

    @Test
    fun `concurrent duplicate failure reports consume repair budget once`() = runBlocking {
        val controller = controller()
        createTesting(controller, "loop-concurrent", maxRepairCycles = 2)

        val results = listOf(1, 2).map { index ->
            async(Dispatchers.Default) {
                runCatching {
                    controller.recordStageOutcome(
                        "loop-concurrent",
                        ReviewRepairStage.TEST,
                        ReviewRepairOutcome.FAILURE,
                        "failure-$index"
                    )
                }
            }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(1, controller.get("loop-concurrent")?.repairCyclesUsed)
        assertEquals(ReviewRepairLoopState.REPAIRING, controller.get("loop-concurrent")?.state)
    }

    @Test
    fun `active loop budget and evidence survive controller recreation`() = runBlocking {
        val storage = MemoryStorage()
        val first = controller(storage)
        createTesting(first, "loop-restore-active", maxRepairCycles = 2)
        first.recordStageOutcome(
            "loop-restore-active",
            ReviewRepairStage.TEST,
            ReviewRepairOutcome.FAILURE,
            "persisted failure",
            taskId = "task-active"
        )

        val restoredController = controller(storage)
        restoredController.load()
        val restored = restoredController.get("loop-restore-active")!!

        assertEquals(ReviewRepairLoopState.REPAIRING, restored.state)
        assertEquals(1, restored.repairCyclesUsed)
        assertEquals(3, restored.evidence.size)
        assertEquals("persisted failure", restored.evidence.last().summary)
        assertEquals("task-active", restored.evidence.last().taskId)
    }

    @Test
    fun `first mutation restores existing journal instead of overwriting it`() = runBlocking {
        val storage = MemoryStorage()
        controller(storage).create("existing-loop", 1)

        val second = controller(storage)
        second.create("new-loop", 1)

        val restored = controller(storage).apply { load() }
        assertEquals(listOf("existing-loop", "new-loop"), restored.all().map { it.id })
    }

    @Test
    fun `terminal success and verification evidence survive restoration`() = runBlocking {
        val storage = MemoryStorage()
        val first = controller(storage)
        createVerifying(first, "loop-restore-terminal")
        first.recordStageOutcome(
            "loop-restore-terminal",
            ReviewRepairStage.VERIFY,
            ReviewRepairOutcome.SUCCESS,
            "durably verified"
        )

        val restoredController = controller(storage)
        restoredController.load()
        val restored = restoredController.get("loop-restore-terminal")!!

        assertEquals(ReviewRepairLoopState.SUCCEEDED, restored.state)
        assertNotNull(restored.completedAt)
        assertEquals(ReviewRepairStage.VERIFY, restored.evidence.last().stage)
        assertEquals("durably verified", restored.evidence.last().summary)
    }

    @Test
    fun `corrupt and inconsistent loop journals fail closed`() = runBlocking {
        val corrupt = MemoryStorage().apply { values[LOOP_KEY] = "{broken" }
        assertTrue(runCatching { controller(corrupt).load() }.exceptionOrNull() is PersistenceException.Corrupt)

        val inconsistent = MemoryStorage()
        controller(inconsistent).create("loop-inconsistent", 1)
        inconsistent.values[LOOP_KEY] = inconsistent.values.getValue(LOOP_KEY)
            .replace("\"state\":\"PLANNING\"", "\"state\":\"SUCCEEDED\"")

        assertTrue(
            runCatching { controller(inconsistent).load() }.exceptionOrNull() is PersistenceException.Corrupt
        )
    }

    @Test
    fun `duplicate persisted loop ids fail closed`() = runBlocking {
        val storage = MemoryStorage()
        val first = controller(storage)
        first.create("loop-a", 1)
        first.create("loop-b", 1)
        storage.values[LOOP_KEY] = storage.values.getValue(LOOP_KEY)
            .replace("\"id\":\"loop-b\"", "\"id\":\"loop-a\"")

        val failure = runCatching { controller(storage).load() }.exceptionOrNull()

        assertTrue(failure is PersistenceException.Corrupt)
    }

    @Test
    fun `create write failure leaves controller empty`() = runBlocking {
        val storage = MemoryStorage().apply { failWrites = true }
        val controller = controller(storage)

        val failure = runCatching { controller.create("loop-create-write-failure", 1) }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
        assertNull(controller.get("loop-create-write-failure"))
    }

    @Test
    fun `stage write failure leaves state budget and evidence unchanged`() = runBlocking {
        val storage = MemoryStorage()
        val controller = controller(storage)
        createTesting(controller, "loop-stage-write-failure", maxRepairCycles = 2)
        val before = controller.get("loop-stage-write-failure")!!
        storage.failWrites = true

        val failure = runCatching {
            controller.recordStageOutcome(
                "loop-stage-write-failure",
                ReviewRepairStage.TEST,
                ReviewRepairOutcome.FAILURE,
                "must not publish"
            )
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
        assertEquals(before, controller.get("loop-stage-write-failure"))
    }

    @Test
    fun `terminal write failure does not publish succeeded state`() = runBlocking {
        val storage = MemoryStorage()
        val controller = controller(storage)
        createVerifying(controller, "loop-terminal-write-failure")
        val before = controller.get("loop-terminal-write-failure")!!
        storage.failWrites = true

        val failure = runCatching {
            controller.recordStageOutcome(
                "loop-terminal-write-failure",
                ReviewRepairStage.VERIFY,
                ReviewRepairOutcome.SUCCESS,
                "must stay uncommitted"
            )
        }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
        assertEquals(ReviewRepairLoopState.VERIFYING, controller.get(before.id)?.state)
        assertNull(controller.get(before.id)?.completedAt)
        assertEquals(before.evidence, controller.get(before.id)?.evidence)
    }

    @Test
    fun `storage read failure is typed and does not become empty state`() = runBlocking {
        val storage = MemoryStorage().apply {
            failReadsWith = StorageException.IoFailure("read failed")
        }
        val controller = controller(storage)

        val failure = runCatching { controller.load() }.exceptionOrNull()

        assertTrue(failure is PersistenceException.IoFailure)
        assertTrue(controller.all().isEmpty())
    }

    @Test
    fun `Task retriesUsed remains execution retry state and never consumes repair budget`() = runBlocking {
        val controller = controller()
        createExecuting(controller, "loop-task-retry", maxRepairCycles = 2)
        val task = Task("provider-backed execution", riskClass = RiskClass.READ_ONLY)

        assertEquals(1, task.recordRetry())
        assertEquals(1, task.retriesUsed)
        assertEquals(0, controller.get("loop-task-retry")?.repairCyclesUsed)

        val loop = controller.recordStageOutcome(
            "loop-task-retry",
            ReviewRepairStage.EXECUTE,
            ReviewRepairOutcome.SUCCESS,
            "execution succeeded after one TaskEngine retry",
            taskId = task.id
        )

        assertEquals(ReviewRepairLoopState.TESTING, loop.state)
        assertEquals(0, loop.repairCyclesUsed)
        assertEquals(1, task.retriesUsed)
    }

    @Test
    fun `provider fallback result does not consume repair cycle`() = runBlocking {
        val controller = controller()
        createExecuting(controller, "loop-provider-fallback", maxRepairCycles = 2)
        val task = Task("fallback execution", riskClass = RiskClass.READ_ONLY)
        val fallbackRunner = object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> = Result.success(
                mapOf(
                    "usedFallback" to true,
                    "primaryProviderId" to "primary",
                    "fallbackProviderId" to "backup"
                )
            )
        }

        val providerResult = fallbackRunner.run(task).getOrThrow()
        assertEquals(true, providerResult["usedFallback"])
        val loop = controller.recordStageOutcome(
            "loop-provider-fallback",
            ReviewRepairStage.EXECUTE,
            ReviewRepairOutcome.SUCCESS,
            "provider policy completed through fallback",
            taskId = task.id
        )

        assertEquals(0, loop.repairCyclesUsed)
        assertEquals(0, task.retriesUsed)
        assertEquals(ReviewRepairLoopState.TESTING, loop.state)
    }

    @Test
    fun `controller completes deterministic path without provider or network dependency`() = runBlocking {
        val controller = controller()
        controller.create("loop-offline", 0)
        controller.recordStageOutcome(
            "loop-offline", ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, "offline plan"
        )
        controller.recordStageOutcome(
            "loop-offline", ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "offline execution evidence"
        )
        controller.recordStageOutcome(
            "loop-offline", ReviewRepairStage.TEST, ReviewRepairOutcome.SUCCESS, "offline tests"
        )

        val completed = controller.recordStageOutcome(
            "loop-offline", ReviewRepairStage.VERIFY, ReviewRepairOutcome.SUCCESS, "offline verification"
        )

        assertEquals(ReviewRepairLoopState.SUCCEEDED, completed.state)
        assertEquals(0, completed.repairCyclesUsed)
    }

    private fun controller(storage: MemoryStorage = MemoryStorage()): ReviewRepairLoopController {
        val clock = AtomicLong(1_000L)
        return ReviewRepairLoopController(storage) { clock.getAndIncrement() }
    }

    private suspend fun createExecuting(
        controller: ReviewRepairLoopController,
        id: String,
        maxRepairCycles: Int = 1
    ): ReviewRepairLoop {
        controller.create(id, maxRepairCycles)
        return controller.recordStageOutcome(
            id, ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, "plan ready"
        )
    }

    private suspend fun createTesting(
        controller: ReviewRepairLoopController,
        id: String,
        maxRepairCycles: Int = 1
    ): ReviewRepairLoop {
        createExecuting(controller, id, maxRepairCycles)
        return controller.recordStageOutcome(
            id, ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "execution done"
        )
    }

    private suspend fun createVerifying(
        controller: ReviewRepairLoopController,
        id: String,
        maxRepairCycles: Int = 1
    ): ReviewRepairLoop {
        createTesting(controller, id, maxRepairCycles)
        return controller.recordStageOutcome(
            id, ReviewRepairStage.TEST, ReviewRepairOutcome.SUCCESS, "tests passed"
        )
    }

    private class MemoryStorage : Storage {
        val values = ConcurrentHashMap<String, String>()
        @Volatile var failWrites = false
        @Volatile var failReadsWith: StorageException? = null

        override suspend fun getString(key: String, defaultValue: String?): String? {
            failReadsWith?.let { throw it }
            return values[key] ?: defaultValue
        }
        override suspend fun setString(key: String, value: String) {
            if (failWrites) throw StorageException.IoFailure("write failed")
            values[key] = value
        }
        override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }

    private companion object {
        const val LOOP_KEY = "aethra.review-repair-loops.v1"
    }
}
