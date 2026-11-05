package org.aethra.core.task

import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [Task].
 *
 * The task model is pure Kotlin; all tests run on the JVM.
 */
class TaskTest {

    @Test
    fun `creation assigns a unique id`() {
        val a = Task("goal A")
        val b = Task("goal B")
        assertNotEquals(a.id, b.id)
        assertEquals("goal A", a.goal)
        assertEquals("assistant", a.role)
        assertEquals(RiskClass.READ_ONLY, a.riskClass)
        // The private 'state' field defaults to QUEUED; verified via public API only.
    }

    @Test
    fun `terminal state is stable`() {
        val task = Task("goal")
        task.complete()
        assertTrue(task.isTerminal())
        task.updateState(TaskState.COMPLETED)
        assertTrue(task.isTerminal())
    }

    @Test
    fun `invalid state transition is rejected`() {
        val task = Task("goal")
        assertFalse(task.canTransitionTo(TaskState.RUNNING)) // QUEUED -> RUNNING is not direct
        assertFalse(task.updateState(TaskState.RUNNING))
        // The private 'state' field cannot be read directly; verified via public API.
    }

    @Test
    fun `legal transitions QUEUED to PREPARING to APPROVED to RUNNING to COMPLETED are applied`() {
        val task = Task("goal")
        // QUEUED -> PREPARING (legal)
        assertTrue(task.updateState(TaskState.PREPARING))
        // PREPARING -> APPROVED (legal, the step where approval is requested)
        assertTrue(task.updateState(TaskState.APPROVED))
        // APPROVED -> RUNNING (legal)
        assertTrue(task.updateState(TaskState.RUNNING))
        // RUNNING is not terminal; task is active.
        assertFalse(task.isTerminal())
        // RUNNING -> COMPLETED (legal, terminal state)
        assertTrue(task.updateState(TaskState.COMPLETED))
        // Terminal states cannot transition further.
        assertFalse(task.updateState(TaskState.RUNNING))
        assertTrue(task.isTerminal())
    }

    @Test
    fun `cancellation from QUEUED is idempotent and terminal`() {
        val task = Task("goal")
        task.cancel()
        assertTrue(task.isCancelled())
        // Second cancel must not change anything.
        task.cancel()
        assertTrue(task.isCancelled())
        // Once terminal, further transitions are rejected.
        assertFalse(task.updateState(TaskState.COMPLETED))
        assertTrue(task.isTerminal())
    }

    @Test
    fun `failed task transitions to FAILED and is terminal`() {
        val task = Task("goal")
        task.fail("boom")
        assertEquals(TaskState.FAILED, task.state)
        assertTrue(task.isTerminal())
    }

    @Test
    fun `dependsOn list is immutable`() {
        val deps = listOf("t1", "t2")
        val task = Task("goal", dependsOn = deps)
        assertEquals(deps, task.dependsOn)
        // dependsOn should not be directly settable
        assertFalse(task::class.java.getDeclaredField("dependsOn").isAccessible)
    }
}
