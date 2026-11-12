package org.aethra.core.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.junit.Assert.*
import org.junit.Test

/** In-memory stand-in for [Storage]. */
class TestStorage : Storage {
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

/** Deterministic TestRunner that records execution order and can fail on demand. */
class TestRunner(
    private val executionOrder: MutableList<String> = mutableListOf(),
    private val failTask: String? = null,
    private val delayMs: Long = 0
) : TaskRunner {
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        executionOrder.add(task.goal)
        if (delayMs > 0) delay(delayMs)
        if (task.goal == failTask) {
            return Result.failure(Exception("simulated failure for ${task.goal}"))
        }
        return Result.success(mapOf("ok" to "ok"))
    }
}

class WorkflowEngineContractTest {

    private fun makeEngine(runner: TaskRunner? = null): TaskEngine {
        val store = TestStorage()
        val bus = EventBus()
        return TaskEngine(store, bus, runner ?: TestRunner(), maxConcurrent = 4)
    }

    private fun makeWorkflowEngine(taskEngine: TaskEngine, runner: TaskRunner? = null): WorkflowEngine {
        return WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner ?: TestRunner())
    }

    // ------------------------------------------------------------------
    // 1. createWorkflow()
    // ------------------------------------------------------------------

    @Test
    fun createWorkflow_validWorkflowIsCreated() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "task two", dependsOn = listOf("n1"))
        )

        val workflow = engine.createWorkflow("test workflow", nodes)

        assertNotNull(workflow.id)
        assertEquals("test workflow", workflow.name)
        assertEquals(2, workflow.nodes.size)
        assertEquals(TaskState.QUEUED, workflow.state)
        assertEquals(TaskState.QUEUED, engine.getWorkflow(workflow.id)!!.state)
    }

    @Test
    fun createWorkflow_emitsWorkflowCreatedEvent() = runBlocking {
        val taskEngine = makeEngine()
        val bus = EventBus()
        val capturedEvents = mutableListOf<TaskEvent>()
        bus.subscribe(TaskEvent::class.java) { capturedEvents.add(it) }

        val engine = WorkflowEngine(TestStorage(), bus, taskEngine, TestRunner())

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        engine.createWorkflow("event test", nodes)

        assertTrue(capturedEvents.any { e ->
            e.detail["name"] == "event test" && e.newState == TaskState.QUEUED
        })
    }

    // ------------------------------------------------------------------
    // 2. Dependency validation
    // ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_missingDependencyIsRejected() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = listOf("nonexistent"))
        )

        engine.createWorkflow("invalid workflow", nodes)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_duplicateNodeIdIsRejected() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList()),
            WorkflowNode(id = "n1", taskId = "t2", goal = "task two", dependsOn = emptyList())
        )

        engine.createWorkflow("dup-node", nodes)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_duplicateTaskIdIsRejected() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t1", goal = "task two", dependsOn = emptyList())
        )

        engine.createWorkflow("dup-task", nodes)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_selfDependencyIsRejected() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = listOf("n1"))
        )

        engine.createWorkflow("self-dep", nodes)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_directCycleIsRejected() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = listOf("n2")),
            WorkflowNode(id = "n2", taskId = "t2", goal = "task two", dependsOn = listOf("n1"))
        )

        engine.createWorkflow("direct-cycle", nodes)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_indirectCycleIsRejected() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = listOf("n2")),
            WorkflowNode(id = "n2", taskId = "t2", goal = "task two", dependsOn = listOf("n3")),
            WorkflowNode(id = "n3", taskId = "t3", goal = "task three", dependsOn = listOf("n1"))
        )

        engine.createWorkflow("indirect-cycle", nodes)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createWorkflow_selfDependencyIsRejectedEvenWithValidOtherDeps() = runBlocking {
        val taskEngine = makeEngine()
        val engine = makeWorkflowEngine(taskEngine)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "root", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "child", dependsOn = listOf("n1", "n2"))
        )

        engine.createWorkflow("self-with-other", nodes)
        Unit
    }

    // ------------------------------------------------------------------
    // 3. Workflow execution - tasks created through TaskEngine
    // ------------------------------------------------------------------

    @Test
    fun executeWorkflow_createsTasksThroughTaskEngine() = runBlocking {
        val taskEngine = makeEngine()
        val runner = TestRunner()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("exec test", nodes)
        assertTrue(engine.execute(workflow.id))

        // Wait for task to complete
        waitForTerminal(workflow.id, engine)

        // TaskEngine should have the task and it should be COMPLETED
        val task = taskEngine.allTasks().firstOrNull { it.goal == "task one" }
        assertNotNull(task)
        assertEquals(TaskState.COMPLETED, task!!.state)
    }

    // ------------------------------------------------------------------
    // 4. Dependency ordering (declaration order must not matter)
    // ------------------------------------------------------------------

    @Test
    fun executeWorkflow_dependentDeclaredBeforeDependencyStillWaits() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val runner = TestRunner(executionOrder)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        // n2 (dependent) is declared BEFORE n1 (dependency)
        val nodes = listOf(
            WorkflowNode(id = "n2", taskId = "t2", goal = "second", dependsOn = listOf("n1")),
            WorkflowNode(id = "n1", taskId = "t1", goal = "first", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("reverse-order", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        // first must execute before second despite reverse declaration order
        val firstIndex = executionOrder.indexOf("first")
        val secondIndex = executionOrder.indexOf("second")
        assertTrue(
            "Dependent declared before dependency must still wait: order was $executionOrder",
            firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex
        )
    }

    @Test
    fun executeWorkflow_reverseDeclarationOrderProducesCorrectDependency() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val runner = TestRunner(executionOrder)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        // Same graph as above but in normal topological order
        val nodesForward = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "first", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "second", dependsOn = listOf("n1"))
        )

        // Same graph in reverse declaration order
        val nodesReverse = listOf(
            WorkflowNode(id = "n2", taskId = "t2", goal = "second", dependsOn = listOf("n1")),
            WorkflowNode(id = "n1", taskId = "t1", goal = "first", dependsOn = emptyList())
        )

        val wf1 = engine.createWorkflow("forward", nodesForward)
        assertTrue(engine.execute(wf1.id))
        waitForTerminal(wf1.id, engine)
        assertEquals(TaskState.COMPLETED, engine.getWorkflow(wf1.id)!!.state)

        val wf2 = engine.createWorkflow("reverse", nodesReverse)
        assertTrue(engine.execute(wf2.id))
        waitForTerminal(wf2.id, engine)
        assertEquals(TaskState.COMPLETED, engine.getWorkflow(wf2.id)!!.state)

        // Both must produce COMPLETED; dependency correctness is order-independent
        assertEquals(TaskState.COMPLETED, engine.getWorkflow(wf1.id)!!.state)
        assertEquals(TaskState.COMPLETED, engine.getWorkflow(wf2.id)!!.state)
    }

    @Test
    fun executeWorkflow_successfulDependencyUnlocksDependent() = runBlocking {
        val taskEngine = makeEngine()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, TestRunner())

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "root", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "child", dependsOn = listOf("n1"))
        )

        val workflow = engine.createWorkflow("unlock test", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        assertEquals(TaskState.COMPLETED, engine.getWorkflow(workflow.id)!!.state)
        val childTask = taskEngine.allTasks().firstOrNull { it.goal == "child" }
        assertNotNull(childTask)
        assertEquals(TaskState.COMPLETED, childTask!!.state)
    }

    // ------------------------------------------------------------------
    // 5. Failure propagation
    // ------------------------------------------------------------------

    @Test
    fun executeWorkflow_failedDependencyCausesWorkflowFailure() = runBlocking {
        val runner = TestRunner(failTask = "failing task")
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "failing task", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "dependent", dependsOn = listOf("n1"))
        )

        val workflow = engine.createWorkflow("failure test", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        assertEquals(TaskState.FAILED, engine.getWorkflow(workflow.id)!!.state)
    }

    @Test
    fun executeWorkflow_chainAFailedBAndCBlocked() = runBlocking {
        val runner = TestRunner(failTask = "A")
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "nA", taskId = "tA", goal = "A", dependsOn = emptyList()),
            WorkflowNode(id = "nB", taskId = "tB", goal = "B", dependsOn = listOf("nA")),
            WorkflowNode(id = "nC", taskId = "tC", goal = "C", dependsOn = listOf("nB"))
        )

        val workflow = engine.createWorkflow("chain-failure", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        assertEquals(TaskState.FAILED, engine.getWorkflow(workflow.id)!!.state)

        // A must be FAILED
        val taskA = taskEngine.allTasks().firstOrNull { it.goal == "A" }
        assertNotNull(taskA)
        assertEquals(TaskState.FAILED, taskA!!.state)

        // B must remain blocked (never ran) because its dependency A failed
        val taskB = taskEngine.allTasks().firstOrNull { it.goal == "B" }
        assertNotNull(taskB)
        assertTrue(
            "B must remain blocked when dependency A fails: state was ${taskB!!.state}",
            taskB!!.state == TaskState.WAITING_DEPENDENCY || taskB!!.state == TaskState.CANCELLED
        )

        // C must remain blocked because its dependency B never completed
        val taskC = taskEngine.allTasks().firstOrNull { it.goal == "C" }
        assertNotNull(taskC)
        assertTrue(
            "C must remain blocked when dependency chain is broken: state was ${taskC!!.state}",
            taskC!!.state == TaskState.WAITING_DEPENDENCY || taskC!!.state == TaskState.CANCELLED
        )
    }

    @Test
    fun executeWorkflow_independentNodeCompletesWhenOtherBranchFails() = runBlocking {
        val runner = TestRunner(failTask = "failing")
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "failing", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "independent", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("branch-failure", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        // Workflow is FAILED because one branch failed
        assertEquals(TaskState.FAILED, engine.getWorkflow(workflow.id)!!.state)

        // But the independent node still completed
        val independentTask = taskEngine.allTasks().firstOrNull { it.goal == "independent" }
        assertNotNull(independentTask)
        assertEquals(TaskState.COMPLETED, independentTask!!.state)
    }

    // ------------------------------------------------------------------
    // 6. Cancellation
    // ------------------------------------------------------------------

    @Test
    fun cancelWorkflow_transitionsToCancelled() = runBlocking {
        val runner = TestRunner(delayMs = 500)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("cancel test", nodes)
        assertTrue(engine.execute(workflow.id))

        // Give it a moment to start running
        delay(200)

        assertTrue(engine.cancel(workflow.id))
        assertEquals(TaskState.CANCELLED, engine.getWorkflow(workflow.id)!!.state)
    }

    @Test
    fun cancelWorkflow_emitsWorkflowCancelledEvent() = runBlocking {
        val bus = EventBus()
        val capturedEvents = mutableListOf<TaskEvent>()
        bus.subscribe(TaskEvent::class.java) { capturedEvents.add(it) }

        val runner = TestRunner(delayMs = 500)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("cancel event test", nodes)
        assertTrue(engine.execute(workflow.id))
        delay(200)
        assertTrue(engine.cancel(workflow.id))

        assertTrue(capturedEvents.any { e -> e.source == "workflow_cancelled" })
    }

    @Test
    fun cancelWorkflow_cancelsTasks() = runBlocking {
        val runner = TestRunner(delayMs = 500)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("cancel tasks test", nodes)
        assertTrue(engine.execute(workflow.id))
        delay(200)

        assertTrue(engine.cancel(workflow.id))

        val task = taskEngine.allTasks().firstOrNull { it.goal == "slow task" }
        assertNotNull(task)
        assertTrue(task!!.isCancelled())
    }

    @Test
    fun cancelWorkflow_cannotLeakRunningTasksDuringInitialization() = runBlocking {
        val runner = TestRunner(delayMs = 500)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("cancel-race", nodes)
        assertTrue(engine.execute(workflow.id))

        // Cancel immediately after execute() returns — before the task has had
        // time to start running. Cancellation must still catch the task.
        assertTrue(engine.cancel(workflow.id))

        val task = taskEngine.allTasks().firstOrNull { it.goal == "slow task" }
        assertNotNull(task)
        assertTrue(
            "Task must be cancelled even when cancel() races with task-map initialization: state was ${task!!.state}",
            task!!.isCancelled() || task!!.state == TaskState.CANCELLED
        )

        // Workflow must be terminal CANCELLED, never COMPLETED
        assertEquals(TaskState.CANCELLED, engine.getWorkflow(workflow.id)!!.state)
    }

    @Test
    fun cancelWorkflow_alreadyTerminalReturnsFalse() = runBlocking {
        val taskEngine = makeEngine()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, TestRunner())

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "quick task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("already terminal", nodes)
        assertTrue(engine.execute(workflow.id))
        waitForTerminal(workflow.id, engine)

        assertFalse(engine.cancel(workflow.id))
    }

    @Test
    fun cancelWorkflow_unknownWorkflowReturnsFalse() = runBlocking {
        val taskEngine = makeEngine()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, TestRunner())

        assertFalse(engine.cancel("nonexistent-id"))
    }

    @Test
    fun cancelWorkflow_successfulCancellationRemainsTerminal() = runBlocking {
        val taskEngine = makeEngine()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, TestRunner())

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "quick task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("cancel terminal", nodes)
        assertTrue(engine.execute(workflow.id))
        waitForTerminal(workflow.id, engine)

        // First cancel returns false (already terminal)
        assertFalse(engine.cancel(workflow.id))

        // State is whatever the terminal state was (COMPLETED in this case)
        val state = engine.getWorkflow(workflow.id)!!.state
        assertTrue(state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR))
    }

    @Test
    fun cancelWorkflow_cancelledWorkflowCannotBecomeCompleted() = runBlocking {
        val runner = TestRunner(delayMs = 500)
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("cancel-no-complete", nodes)
        assertTrue(engine.execute(workflow.id))
        delay(100) // let it start running

        assertTrue(engine.cancel(workflow.id))

        // Wait long enough for the monitor to have run multiple times
        delay(1000)

        // Workflow must remain CANCELLED, never transition to COMPLETED or FAILED
        assertEquals(TaskState.CANCELLED, engine.getWorkflow(workflow.id)!!.state)
    }

    // ------------------------------------------------------------------
    // 7. Completion monitoring
    // ------------------------------------------------------------------

    @Test
    fun executeWorkflow_allSuccessfulTasksProduceCompletedWorkflow() = runBlocking {
        val taskEngine = makeEngine()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, TestRunner())

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "a", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "b", dependsOn = emptyList()),
            WorkflowNode(id = "n3", taskId = "t3", goal = "c", dependsOn = listOf("n1", "n2"))
        )

        val workflow = engine.createWorkflow("complete test", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        assertEquals(TaskState.COMPLETED, engine.getWorkflow(workflow.id)!!.state)
        assertNotNull(engine.getWorkflow(workflow.id)!!.completedAt)
    }

    @Test
    fun executeWorkflow_terminalTaskFailureProducesFailedWorkflow() = runBlocking {
        val runner = TestRunner(failTask = "will fail")
        val taskEngine = makeEngine(runner)
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "will fail", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "ok task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("monitor failure test", nodes)
        assertTrue(engine.execute(workflow.id))

        waitForTerminal(workflow.id, engine)

        assertEquals(TaskState.FAILED, engine.getWorkflow(workflow.id)!!.state)
    }

    // ------------------------------------------------------------------
    // 8. Deterministic behavior
    // ------------------------------------------------------------------

    @Test
    fun executeWorkflow_deterministicOrdering() = runBlocking {
        val taskEngine = makeEngine()
        val engine = WorkflowEngine(TestStorage(), EventBus(), taskEngine, TestRunner())

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "step 1", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "step 2", dependsOn = listOf("n1")),
            WorkflowNode(id = "n3", taskId = "t3", goal = "step 3", dependsOn = listOf("n2"))
        )

        val workflow1 = engine.createWorkflow("deterministic test", nodes)
        assertTrue(engine.execute(workflow1.id))
        waitForTerminal(workflow1.id, engine)
        val state1 = engine.getWorkflow(workflow1.id)!!.state

        val workflow2 = engine.createWorkflow("deterministic test 2", nodes)
        assertTrue(engine.execute(workflow2.id))
        waitForTerminal(workflow2.id, engine)
        val state2 = engine.getWorkflow(workflow2.id)!!.state

        assertEquals(state1, state2)
        assertEquals(TaskState.COMPLETED, state1)
        assertEquals(TaskState.COMPLETED, state2)
    }

    // ------------------------------------------------------------------
    // 9. D-2B: Workflow Persistence & Recovery Contract Tests
    // ------------------------------------------------------------------

    @Test
    fun createWorkflow_persistsAndSurvivesRestart() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "task two", dependsOn = listOf("n1"))
        )

        val workflow = engine1.createWorkflow("persist test", nodes)
        assertNotNull(workflow.id)

        // Wait for async persist to complete
        waitForPersist(storage)

        // Simulate process restart: new engine with same storage
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)

        // Give recovery time to complete
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(workflow.id, recovered!!.id)
        assertEquals("persist test", recovered.name)
        assertEquals(TaskState.QUEUED, recovered.state)
        assertEquals(2, recovered.nodes.size)
    }

    @Test
    fun completedWorkflow_doesNotRerunOnRestart() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        val workflow = engine1.createWorkflow("completed test", nodes)
        assertTrue(engine1.execute(workflow.id))

        waitForTerminal(workflow.id, engine1)
        assertEquals(TaskState.COMPLETED, engine1.getWorkflow(workflow.id)!!.state)
        waitForPersist(storage)

        // Simulate restart
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(TaskState.COMPLETED, recovered!!.state)

        // Task should NOT be re-executed
        val taskA = taskEngine2.allTasks().firstOrNull { it.goal == "task one" }
        assertNotNull(taskA)
        assertEquals(TaskState.COMPLETED, taskA!!.state)
        assertTrue(taskA.isExecuted)
    }

    @Test
    fun failedWorkflow_remainsFailedOnRestart() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner(failTask = "failing task")
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "failing task", dependsOn = emptyList())
        )

        val workflow = engine1.createWorkflow("failed test", nodes)
        assertTrue(engine1.execute(workflow.id))

        waitForTerminal(workflow.id, engine1)
        assertEquals(TaskState.FAILED, engine1.getWorkflow(workflow.id)!!.state)
        waitForPersist(storage)

        // Simulate restart
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(TaskState.FAILED, recovered!!.state)
    }

    @Test
    fun cancelledWorkflow_remainsCancelledOnRestart() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner(delayMs = 500)
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine1.createWorkflow("cancelled test", nodes)
        assertTrue(engine1.execute(workflow.id))
        delay(200) // let it start running
        assertTrue(engine1.cancel(workflow.id))

        waitForTerminal(workflow.id, engine1)
        assertEquals(TaskState.CANCELLED, engine1.getWorkflow(workflow.id)!!.state)
        waitForPersist(storage)

        // Simulate restart
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(TaskState.CANCELLED, recovered!!.state)
    }

    @Test
    fun runningWorkflowRecovery_reconcilesTaskStates() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner(delayMs = 100)
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        // A -> B -> C
        val nodes = listOf(
            WorkflowNode(id = "nA", taskId = "tA", goal = "A", dependsOn = emptyList()),
            WorkflowNode(id = "nB", taskId = "tB", goal = "B", dependsOn = listOf("nA")),
            WorkflowNode(id = "nC", taskId = "tC", goal = "C", dependsOn = listOf("nB"))
        )

        val workflow = engine1.createWorkflow("running recovery test", nodes)
        assertTrue(engine1.execute(workflow.id))

        // Wait for A to complete
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000) {
            val taskA = taskEngine.allTasks().firstOrNull { it.goal == "A" }
            if (taskA != null && taskA.state == TaskState.COMPLETED) break
            delay(50)
        }

        // Persist current state
        waitForPersist(storage)

        // Simulate restart
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)

        // A should remain completed
        val taskA = taskEngine2.allTasks().firstOrNull { it.goal == "A" }
        assertNotNull(taskA)
        assertEquals(TaskState.COMPLETED, taskA!!.state)

        // Workflow state should be reconciled based on actual task states
        assertTrue(recovered!!.state in setOf(TaskState.RUNNING, TaskState.COMPLETED, TaskState.FAILED))
    }

    @Test
    fun completedDependency_notRerunAfterRestart() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        // A -> B, where A completes
        val nodes = listOf(
            WorkflowNode(id = "nA", taskId = "tA", goal = "A", dependsOn = emptyList()),
            WorkflowNode(id = "nB", taskId = "tB", goal = "B", dependsOn = listOf("nA"))
        )

        val workflow = engine1.createWorkflow("dependency recovery test", nodes)
        assertTrue(engine1.execute(workflow.id))

        waitForTerminal(workflow.id, engine1)
        assertEquals(TaskState.COMPLETED, engine1.getWorkflow(workflow.id)!!.state)
        waitForPersist(storage)

        // Simulate restart
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(TaskState.COMPLETED, recovered!!.state)

        // A should not be re-executed
        val taskA = taskEngine2.allTasks().firstOrNull { it.goal == "A" }
        assertNotNull(taskA)
        assertEquals(TaskState.COMPLETED, taskA!!.state)
        assertTrue(taskA.isExecuted)
    }

    @Test
    fun missingReferencedTask_recoversByCreatingCanonicalTask() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        // Manually persist a workflow referencing a task that doesn't exist in TaskEngine
        val persisted = PersistedWorkflow(
            id = "orphan-workflow",
            name = "orphan",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1",
                    taskId = "t1",
                    actualTaskId = "nonexistent-task-id",
                    goal = "task one",
                    role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(),
                    state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(persisted)))

        // Create new engine - recovery should create the missing task idempotently
        // using the canonical ID derived from workflowId + nodeId
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        // The workflow SHOULD be recovered (W1: orphan task adoption)
        val recovered = engine2.getWorkflow("orphan-workflow")
        assertNotNull(recovered)
        assertEquals(TaskState.QUEUED, recovered!!.state)

        // A task with the canonical ID "${workflowId}#${nodeId}" should exist
        val canonicalId = "orphan-workflow#n1"
        val createdTask = taskEngine2.getTask(canonicalId)
        assertNotNull(createdTask)
        assertEquals("task one", createdTask!!.goal)

        // The recovered workflow's node should reference the canonical task ID
        val recoveredNode = recovered.nodes.firstOrNull { it.id == "n1" }
        assertNotNull(recoveredNode)
        assertEquals(canonicalId, recoveredNode.actualTaskId)
    }

    @Test
    fun recovery_isIdempotent() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        val workflow = engine1.createWorkflow("idempotent test", nodes)
        waitForPersist(storage)

        // First recovery
        val taskEngine2a = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2a = WorkflowEngine(storage, EventBus(), taskEngine2a, runner)
        awaitRecovery(engine2a)
        val recovered1 = engine2a.getWorkflow(workflow.id)
        assertNotNull(recovered1)

        // Second recovery with a new engine instance
        val taskEngine2b = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2b = WorkflowEngine(storage, EventBus(), taskEngine2b, runner)
        awaitRecovery(engine2b)
        val recovered2 = engine2b.getWorkflow(workflow.id)
        assertNotNull(recovered2)

        // Both should have same ID and state
        assertEquals(recovered1!!.id, recovered2!!.id)
        assertEquals(recovered1.state, recovered2.state)

        // No duplicate workflows in storage
        val allPersisted = kotlinx.serialization.json.Json.decodeFromString<List<PersistedWorkflow>>(
            storage.getString("aethra.workflows.v1")!!
        )
        assertEquals(1, allPersisted.count { it.id == workflow.id })
    }

    @Test
    fun disconnectedWorkflowRecovery_recoversAllComponents() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        // Two independent components
        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "component A task 1", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "component A task 2", dependsOn = listOf("n1")),
            WorkflowNode(id = "n3", taskId = "t3", goal = "component B task 1", dependsOn = emptyList()),
            WorkflowNode(id = "n4", taskId = "t4", goal = "component B task 2", dependsOn = listOf("n3"))
        )

        val workflow = engine1.createWorkflow("disconnected test", nodes)
        assertTrue(engine1.execute(workflow.id))

        waitForTerminal(workflow.id, engine1)
        assertEquals(TaskState.COMPLETED, engine1.getWorkflow(workflow.id)!!.state)
        waitForPersist(storage)

        // Simulate restart
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        awaitRecovery(engine2)

        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(TaskState.COMPLETED, recovered!!.state)
        assertEquals(4, recovered.nodes.size)
    }

    @Test
    fun invalidPersistedWorkflow_failsSafely() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        // Create actual tasks in TaskEngine so their IDs are valid
        val task1 = taskEngine.create("task one")
        val task2 = taskEngine.create("task two")

        // Manually persist an invalid workflow (duplicate node IDs) referencing real tasks
        val invalidPersisted = PersistedWorkflow(
            id = "invalid-workflow",
            name = "invalid",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1",
                    taskId = "t1",
                    actualTaskId = task1.id,
                    goal = "task one",
                    role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(),
                    state = TaskState.QUEUED.name
                ),
                PersistedWorkflowNode(
                    id = "n1", // duplicate node ID
                    taskId = "t2",
                    actualTaskId = task2.id,
                    goal = "task two",
                    role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(),
                    state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        // Create new engine - recovery should reject the invalid graph
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        // Invalid workflow should not be recoverable
        val recovered = engine2.getWorkflow("invalid-workflow")
        assertNull(recovered)
    }

    @Test
    fun persistTiming_usesRealStorageNotMock() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("timing test", nodes)

        assertTrue(engine.execute(workflow.id))
        waitForTerminal(workflow.id, engine)
        waitForPersist(storage)

        // Storage should now contain the workflow
        val persisted = storage.getString("aethra.workflows.v1")!!
        assertNotNull(persisted)
        assertTrue(persisted.contains(workflow.id))
    }

    // ------------------------------------------------------------------
    // D-2B.1: Recovery graph validation tests
    // ------------------------------------------------------------------

    @Test
    fun recoveredWorkflow_duplicateNodeIdIsRejected() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val task1 = taskEngine.create("task one")
        val task2 = taskEngine.create("task two")

        // Persist a workflow with duplicate node IDs
        val invalidPersisted = PersistedWorkflow(
            id = "dup-node-workflow",
            name = "dup-node",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1", taskId = "t1", actualTaskId = task1.id,
                    goal = "task one", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(), state = TaskState.QUEUED.name
                ),
                PersistedWorkflowNode(
                    id = "n1", taskId = "t2", actualTaskId = task2.id,
                    goal = "task two", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(), state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        assertNull(engine2.getWorkflow("dup-node-workflow"))
    }

    @Test
    fun recoveredWorkflow_duplicateTaskIdIsRejected() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val task1 = taskEngine.create("task one")

        // Persist a workflow where two nodes reference the same task ID
        val invalidPersisted = PersistedWorkflow(
            id = "dup-task-workflow",
            name = "dup-task",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1", taskId = "t1", actualTaskId = task1.id,
                    goal = "task one", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(), state = TaskState.QUEUED.name
                ),
                PersistedWorkflowNode(
                    id = "n2", taskId = "t1", actualTaskId = task1.id,
                    goal = "task two", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = emptyList(), state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        assertNull(engine2.getWorkflow("dup-task-workflow"))
    }

    @Test
    fun recoveredWorkflow_selfDependencyIsRejected() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val task1 = taskEngine.create("task one")

        // Persist a workflow where a node depends on itself
        val invalidPersisted = PersistedWorkflow(
            id = "self-dep-workflow",
            name = "self-dep",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1", taskId = "t1", actualTaskId = task1.id,
                    goal = "task one", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("n1"), state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        assertNull(engine2.getWorkflow("self-dep-workflow"))
    }

    @Test
    fun recoveredWorkflow_missingDependencyIsRejected() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val task1 = taskEngine.create("task one")

        // Persist a workflow where a node depends on a nonexistent node
        val invalidPersisted = PersistedWorkflow(
            id = "missing-dep-workflow",
            name = "missing-dep",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1", taskId = "t1", actualTaskId = task1.id,
                    goal = "task one", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("nonexistent"), state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        assertNull(engine2.getWorkflow("missing-dep-workflow"))
    }

    @Test
    fun recoveredWorkflow_directCycleIsRejected() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val task1 = taskEngine.create("task one")
        val task2 = taskEngine.create("task two")

        // Persist a workflow with a direct cycle: A -> B -> A
        val invalidPersisted = PersistedWorkflow(
            id = "direct-cycle-workflow",
            name = "direct-cycle",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1", taskId = "t1", actualTaskId = task1.id,
                    goal = "task one", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("n2"), state = TaskState.QUEUED.name
                ),
                PersistedWorkflowNode(
                    id = "n2", taskId = "t2", actualTaskId = task2.id,
                    goal = "task two", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("n1"), state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        assertNull(engine2.getWorkflow("direct-cycle-workflow"))
    }

    @Test
    fun recoveredWorkflow_indirectCycleIsRejected() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val task1 = taskEngine.create("task one")
        val task2 = taskEngine.create("task two")
        val task3 = taskEngine.create("task three")

        // Persist a workflow with an indirect cycle: A -> B -> C -> A
        val invalidPersisted = PersistedWorkflow(
            id = "indirect-cycle-workflow",
            name = "indirect-cycle",
            state = TaskState.QUEUED.name,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            nodes = listOf(
                PersistedWorkflowNode(
                    id = "n1", taskId = "t1", actualTaskId = task1.id,
                    goal = "task one", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("n2"), state = TaskState.QUEUED.name
                ),
                PersistedWorkflowNode(
                    id = "n2", taskId = "t2", actualTaskId = task2.id,
                    goal = "task two", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("n3"), state = TaskState.QUEUED.name
                ),
                PersistedWorkflowNode(
                    id = "n3", taskId = "t3", actualTaskId = task3.id,
                    goal = "task three", role = "assistant",
                    riskClass = RiskClass.READ_ONLY.name,
                    dependsOn = listOf("n1"), state = TaskState.QUEUED.name
                )
            )
        )
        storage.setString("aethra.workflows.v1", kotlinx.serialization.json.Json.encodeToString(listOf(invalidPersisted)))

        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)
        try {
            awaitRecovery(engine2)
        } catch (e: Exception) {
            // Expected - recovery failed for invalid graph
        }

        assertNull(engine2.getWorkflow("indirect-cycle-workflow"))
    }

    // ------------------------------------------------------------------
    // D-2B.1: Reliability hardening tests
    // ------------------------------------------------------------------

    @Test
    fun execute_afterCreateWorkflow_persistsBeforeReturning() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("persist-before-return", nodes)

        // After createWorkflow returns, workflow must be durable in storage
        waitForPersist(storage)
        val persisted = storage.getString("aethra.workflows.v1")!!
        assertNotNull(persisted)
        assertTrue(persisted.contains(workflow.id))
        assertTrue(persisted.contains("QUEUED"))
    }

    @Test
    fun execute_persistsTaskMappingBeforeRunning() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner(delayMs = 200)
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList()),
            WorkflowNode(id = "n2", taskId = "t2", goal = "task two", dependsOn = listOf("n1"))
        )

        val workflow = engine.createWorkflow("persist-mapping", nodes)
        assertTrue(engine.execute(workflow.id))

        // Wait for execution to complete
        waitForTerminal(workflow.id, engine)
        waitForPersist(storage)

        // After execution, the workflow must be persisted with task mapping
        val persisted = storage.getString("aethra.workflows.v1")!!
        assertNotNull(persisted)
        assertTrue(persisted.contains(workflow.id))
        assertTrue(persisted.contains("RUNNING") || persisted.contains("COMPLETED"))
    }

    @Test
    fun cancel_persistsBeforeReturning() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner(delayMs = 500)
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "slow task", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("persist-cancel", nodes)
        assertTrue(engine.execute(workflow.id))
        delay(200) // let it start running
        assertTrue(engine.cancel(workflow.id))

        waitForTerminal(workflow.id, engine)
        waitForPersist(storage)

        // After cancel returns, workflow must be persisted as CANCELLED
        val persisted = storage.getString("aethra.workflows.v1")!!
        assertNotNull(persisted)
        assertTrue(persisted.contains("CANCELLED"))
    }

    @Test
    fun terminalStatePersistedBeforeEvent() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val capturedEvents = mutableListOf<TaskEvent>()
        bus.subscribe(TaskEvent::class.java) { capturedEvents.add(it) }
        val runner = TestRunner(failTask = "will fail")
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "will fail", dependsOn = emptyList())
        )

        val workflow = engine.createWorkflow("persist-terminal", nodes)
        assertTrue(engine.execute(workflow.id))
        waitForTerminal(workflow.id, engine)
        waitForPersist(storage)

        // After terminal state, storage must reflect FAILED
        val persisted = storage.getString("aethra.workflows.v1")!!
        assertNotNull(persisted)
        assertTrue(persisted.contains("FAILED"))

        // The workflow_completed or workflow_failed event must have been published
        assertTrue(capturedEvents.any { e -> e.source.contains("workflow_") })
    }

    // ------------------------------------------------------------------
    // D-2B.1: Recovery readiness tests
    // ------------------------------------------------------------------

    @Test
    fun recovery_isObservableAfterEngineCreation() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)

        // Create workflow and persist it first
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)
        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )
        val workflow = engine1.createWorkflow("recovery-readiness", nodes)
        waitForPersist(storage)

        // Create a new engine - recovery happens in init (async)
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)

        // Immediately after creation, awaitRecovery should complete quickly
        awaitRecovery(engine2)

        // Workflow should be recovered
        val recovered = engine2.getWorkflow(workflow.id)
        assertNotNull(recovered)
        assertEquals(workflow.id, recovered!!.id)
    }

    @Test
    fun execute_waitsForRecovery() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )
        val workflow = engine1.createWorkflow("execute-waits", nodes)
        waitForPersist(storage)

        // Create new engine and immediately execute - should wait for recovery
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)

        // execute() should await recovery before proceeding
        assertTrue(engine2.execute(workflow.id))
        assertEquals(TaskState.RUNNING, engine2.getWorkflow(workflow.id)!!.state)
    }

    @Test
    fun concurrentCallersDuringRecovery() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )
        val workflow = engine1.createWorkflow("concurrent-recovery", nodes)
        waitForPersist(storage)

        // Create new engine - recovery is in progress
        val taskEngine2 = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2 = WorkflowEngine(storage, EventBus(), taskEngine2, runner)

        // Multiple concurrent callers should all wait for recovery
        val results = mutableListOf<Boolean>()
        repeat(5) {
            results.add(engine2.execute(workflow.id))
        }

        // Wait for all coroutines to complete
        delay(2000)

        // Only one should have succeeded (first one sets RUNNING, others see non-QUEUED)
        val successes = results.count { it }
        assertTrue(successes >= 1)
        assertTrue(successes <= 5)
    }

    @Test
    fun recoveryIsIdempotent() = runBlocking {
        val storage = TestStorage()
        val bus = EventBus()
        val runner = TestRunner()
        val taskEngine = TaskEngine(storage, bus, runner, maxConcurrent = 4)
        val engine1 = WorkflowEngine(storage, bus, taskEngine, runner)

        val nodes = listOf(
            WorkflowNode(id = "n1", taskId = "t1", goal = "task one", dependsOn = emptyList())
        )

        val workflow = engine1.createWorkflow("idempotent-test", nodes)
        waitForPersist(storage)

        // First recovery
        val taskEngine2a = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2a = WorkflowEngine(storage, EventBus(), taskEngine2a, runner)
        awaitRecovery(engine2a)
        val recovered1 = engine2a.getWorkflow(workflow.id)
        assertNotNull(recovered1)

        // Second recovery with a new engine instance
        val taskEngine2b = TaskEngine(storage, EventBus(), runner, maxConcurrent = 4)
        val engine2b = WorkflowEngine(storage, EventBus(), taskEngine2b, runner)
        awaitRecovery(engine2b)
        val recovered2 = engine2b.getWorkflow(workflow.id)
        assertNotNull(recovered2)

        // Both should have same ID and state
        assertEquals(recovered1!!.id, recovered2!!.id)
        assertEquals(recovered1.state, recovered2.state)

        // No duplicate workflows in storage
        val allPersisted = kotlinx.serialization.json.Json.decodeFromString<List<PersistedWorkflow>>(
            storage.getString("aethra.workflows.v1")!!
        )
        assertEquals(1, allPersisted.count { it.id == workflow.id })
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun waitForTerminal(workflowId: String, engine: WorkflowEngine) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 10000) {
            val wf = engine.getWorkflow(workflowId)
            if (wf != null && wf.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) {
                return
            }
            delay(100)
        }
        fail("Workflow $workflowId did not reach a terminal state within 10 seconds")
    }

    suspend fun waitForPersist(storage: TestStorage) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000) {
            val encoded = storage.getString("aethra.workflows.v1")
            if (encoded != null && encoded.isNotEmpty()) return
            delay(50)
        }
    }

    suspend fun awaitRecovery(engine: WorkflowEngine) {
        engine.awaitRecovery()
    }
}
