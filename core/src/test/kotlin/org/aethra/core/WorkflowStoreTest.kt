package org.aethra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.aethra.core.orchestration.*

class WorkflowStoreTest {
    @Test
    fun savesAndLoadsWorkflowExecutionRecord() {
        val store = InMemoryWorkflowStore()
        val record = WorkflowExecutionRecord(
            workflowId = "wf-1",
            taskId = "task-1",
            steps = mapOf(
                "a" to StepExecutionRecord("a", StepResult.Status.SUCCEEDED, artifactId = "artifact-a"),
                "b" to StepExecutionRecord("b", StepResult.Status.BLOCKED, reason = "dependency failed")
            )
        )

        store.save(record)

        val loaded = store.load("wf-1", "task-1")
        assertNotNull(loaded)
        assertEquals(record, loaded)
    }

    @Test
    fun missingWorkflowReturnsNull() {
        val store = InMemoryWorkflowStore()
        assertEquals(null, store.load("missing", "task"))
    }
}
