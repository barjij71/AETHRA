package org.aethra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aethra.core.orchestration.AuditEvent
import org.aethra.core.orchestration.InMemoryWorkflowEventBus
import org.aethra.core.orchestration.StepResult

class WorkflowEventBusTest {
    @Test
    fun publishesOrderedLifecycleEventsToSubscribers() {
        val bus = InMemoryWorkflowEventBus()
        val received = mutableListOf<String>()
        bus.subscribe { received += it.event }

        val base = AuditEvent(
            eventId = "1",
            workflowId = "wf",
            taskId = "task",
            event = "workflow.started"
        )
        bus.publish(base)
        bus.publish(base.copy(eventId = "2", event = "step.finished", status = StepResult.Status.SUCCEEDED))
        bus.publish(base.copy(eventId = "3", event = "workflow.finished"))

        assertEquals(
            listOf("workflow.started", "step.finished", "workflow.finished"),
            received
        )
    }

    @Test
    fun subscriberFailureCannotBreakEventDeliveryToOtherSubscribers() {
        val bus = InMemoryWorkflowEventBus()
        val received = mutableListOf<String>()

        bus.subscribe { error("observer failure") }
        bus.subscribe { received += it.event }

        bus.publish(
            AuditEvent(
                eventId = "1",
                workflowId = "wf",
                taskId = "task",
                event = "workflow.finished"
            )
        )

        assertEquals(listOf("workflow.finished"), received)
        assertTrue(received.isNotEmpty())
    }

    @Test
    fun closingSubscriptionStopsFurtherDelivery() {
        val bus = InMemoryWorkflowEventBus()
        val received = mutableListOf<String>()
        val subscription = bus.subscribe { received += it.event }

        bus.publish(
            AuditEvent(
                eventId = "1",
                workflowId = "wf",
                taskId = "task",
                event = "workflow.started"
            )
        )
        subscription.close()
        bus.publish(
            AuditEvent(
                eventId = "2",
                workflowId = "wf",
                taskId = "task",
                event = "workflow.finished"
            )
        )

        assertEquals(listOf("workflow.started"), received)
    }
}
