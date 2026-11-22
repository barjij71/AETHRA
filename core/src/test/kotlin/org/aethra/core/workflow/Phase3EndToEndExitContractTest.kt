package org.aethra.core.workflow

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class Phase3EndToEndExitContractTest {
    @Test
    fun `plan execute test repair execute test verify succeeds with durable trace`() = runBlocking {
        val storage = MemoryStorage()
        val fixture = Fixture(storage)
        var loop = fixture.loops.create("e2e-success", maxRepairCycles = 1, workflowId = "workflow-e2e")

        val plan = "run deterministic contract checks"
        loop = fixture.record(loop, ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, plan)

        val first = fixture.execute(plan)
        loop = fixture.record(
            loop, ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS,
            "TaskEngine completed initial execution", first.id
        )
        val firstTestPassed = first.result["testPassed"] as Boolean
        loop = fixture.record(
            loop, ReviewRepairStage.TEST,
            if (firstTestPassed) ReviewRepairOutcome.SUCCESS else ReviewRepairOutcome.FAILURE,
            "deterministic assertion: expected repaired marker", first.id
        )
        assertEquals(ReviewRepairLoopState.REPAIRING, loop.state)
        assertEquals(1, loop.repairCyclesUsed)
        assertEquals(0, first.task.retriesUsed)

        // Recovery at the persistence boundary must preserve the failed test and repair budget.
        val recovered = ReviewRepairLoopController(storage, fixture.clock::get).also { it.load() }
        loop = recovered.get("e2e-success")!!
        assertEquals(1, loop.repairCyclesUsed)
        assertEquals(ReviewRepairStage.TEST, loop.evidence.last().stage)
        assertEquals(ReviewRepairOutcome.FAILURE, loop.evidence.last().outcome)

        loop = fixture.record(loop, ReviewRepairStage.REPAIR, ReviewRepairOutcome.SUCCESS, "apply deterministic repair")
        fixture.repaired.set(true)
        val second = fixture.execute(plan)
        loop = fixture.record(
            loop, ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS,
            "TaskEngine completed repaired execution", second.id
        )
        assertEquals(0, second.task.retriesUsed)
        assertEquals(1, loop.repairCyclesUsed)

        val secondTestPassed = second.result["testPassed"] as Boolean
        assertTrue("second deterministic test must pass", secondTestPassed)
        loop = fixture.record(loop, ReviewRepairStage.TEST, ReviewRepairOutcome.SUCCESS, "deterministic assertion passed", second.id)
        assertEquals(ReviewRepairLoopState.VERIFYING, loop.state)
        assertTrue("verification must depend on the recorded successful test", loop.evidence.last().outcome == ReviewRepairOutcome.SUCCESS)
        loop = fixture.record(loop, ReviewRepairStage.VERIFY, ReviewRepairOutcome.SUCCESS, "verified from completed task and passing test", second.id)

        val restored = ReviewRepairLoopController(storage, fixture.clock::get).also { it.load() }.get("e2e-success")!!
        assertEquals(ReviewRepairLoopState.SUCCEEDED, restored.state)
        assertEquals(1, restored.repairCyclesUsed)
        assertEquals(
            listOf("PLAN", "EXECUTE", "TEST", "REPAIR", "EXECUTE", "TEST", "VERIFY"),
            restored.evidence.map { it.stage.name }
        )
        assertEquals(
            listOf("SUCCESS", "SUCCESS", "FAILURE", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
            restored.evidence.map { it.outcome.name }
        )
        assertEquals(listOf(first.id, second.id), restored.evidence.mapNotNull { it.taskId }.distinct())
        assertTrue(restored.evidence.all { it.loopId == restored.id && it.recordedAt > 0 })
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.task.executionAttemptId, second.task.executionAttemptId)
        assertFalse("no task execution retries should be used for the repair", fixture.executions.any { it.task.retriesUsed != 0 })
    }

    @Test
    fun `second deterministic test failure exhausts one repair cycle and persists failed trace`() = runBlocking {
        val storage = MemoryStorage()
        val fixture = Fixture(storage)
        var loop = fixture.loops.create("e2e-exhausted", maxRepairCycles = 1)
        val plan = "repeatable failing contract check"
        loop = fixture.record(loop, ReviewRepairStage.PLAN, ReviewRepairOutcome.SUCCESS, plan)

        val first = fixture.execute(plan)
        loop = fixture.record(loop, ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "first TaskEngine execution completed", first.id)
        loop = fixture.record(loop, ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "deterministic assertion failed", first.id)
        assertEquals(ReviewRepairLoopState.REPAIRING, loop.state)
        assertEquals(1, loop.repairCyclesUsed)

        loop = fixture.record(loop, ReviewRepairStage.REPAIR, ReviewRepairOutcome.SUCCESS, "one repair applied")
        val second = fixture.execute(plan)
        loop = fixture.record(loop, ReviewRepairStage.EXECUTE, ReviewRepairOutcome.SUCCESS, "repaired TaskEngine execution completed", second.id)
        loop = fixture.record(loop, ReviewRepairStage.TEST, ReviewRepairOutcome.FAILURE, "same deterministic assertion still fails", second.id)

        assertEquals(ReviewRepairLoopState.FAILED, loop.state)
        assertEquals(1, loop.repairCyclesUsed)
        val restored = ReviewRepairLoopController(storage, fixture.clock::get).also { it.load() }.get(loop.id)!!
        assertEquals(ReviewRepairLoopState.FAILED, restored.state)
        assertEquals(1, restored.repairCyclesUsed)
        assertEquals("same deterministic assertion still fails", restored.evidence.last().summary)
        assertEquals(listOf("PLAN", "EXECUTE", "TEST", "REPAIR", "EXECUTE", "TEST"), restored.evidence.map { it.stage.name })
        assertTrue("TaskEngine retries remain separate from repair cycles", fixture.executions.all { it.task.retriesUsed == 0 })
    }

    private class Fixture(private val storage: Storage) {
        val clock = AtomicLong(10_000L)
        val repaired = AtomicBoolean(false)
        private val completions = Channel<TaskEvent>(Channel.UNLIMITED)
        private val taskResults = ConcurrentHashMap<String, Map<String, Any>>()
        private val bus = EventBus().also { eventBus ->
            eventBus.subscribe(TaskEvent::class.java) { event ->
                if (event.source == "execution_completed" || event.source == "execution_failed") {
                    completions.trySend(event)
                }
            }
        }
        val executions = mutableListOf<Execution>()
        val loops = ReviewRepairLoopController(storage, clock::get)
        private val engine = TaskEngine(storage, bus, object : TaskRunner {
            override suspend fun run(task: Task): Result<Map<String, Any>> = Result.success(
                mapOf("plan" to task.goal, "testPassed" to repaired.get()).also {
                    taskResults[task.id] = it
                }
            )
        })

        suspend fun execute(plan: String): Execution {
            val task = engine.create(plan, "phase3-e2e", RiskClass.READ_ONLY)
            engine.run(task.id)
            var event = completions.receive()
            while (event.taskId != task.id) event = completions.receive()
            val executed = engine.getTask(event.taskId)!!
            check(event.newState == TaskState.COMPLETED)
            check(executed.goal == plan)
            return Execution(executed, taskResults.getValue(task.id)).also { executions += it }
        }

        suspend fun record(
            loop: ReviewRepairLoop,
            stage: ReviewRepairStage,
            outcome: ReviewRepairOutcome,
            summary: String,
            taskId: String? = null
        ) = loops.recordStageOutcome(loop.id, stage, outcome, summary, taskId, loop.agentId)
    }

    private data class Execution(val task: Task, val result: Map<String, Any>) {
        val id: String get() = task.id
    }

    private class MemoryStorage : Storage {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun getLong(key: String, defaultValue: Long?) = defaultValue
        override suspend fun setLong(key: String, value: Long) = Unit
        override suspend fun getBoolean(key: String, defaultValue: Boolean?) = defaultValue
        override suspend fun setBoolean(key: String, value: Boolean) = Unit
        override suspend fun getAll(): Map<String, String> = values.toMap()
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun clear() { values.clear() }
    }
}
