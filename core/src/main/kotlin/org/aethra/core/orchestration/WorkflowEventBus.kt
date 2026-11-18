package org.aethra.core.orchestration

fun interface WorkflowEventSubscription {
    fun onEvent(event: AuditEvent)
}

interface WorkflowEventBus {
    fun subscribe(subscription: WorkflowEventSubscription): AutoCloseable
    fun publish(event: AuditEvent)
}

object NoOpWorkflowEventBus : WorkflowEventBus {
    override fun subscribe(subscription: WorkflowEventSubscription): AutoCloseable = AutoCloseable {}
    override fun publish(event: AuditEvent) = Unit
}

/**
 * In-memory lifecycle bus for Core-owned orchestration.
 *
 * Delivery is synchronous and ordered. Subscriber failures are isolated so an
 * observer can never change workflow execution or policy decisions.
 */
class InMemoryWorkflowEventBus : WorkflowEventBus {
    private val subscribers = mutableListOf<WorkflowEventSubscription>()

    @Synchronized
    override fun subscribe(subscription: WorkflowEventSubscription): AutoCloseable {
        subscribers += subscription
        return AutoCloseable {
            synchronized(this) {
                subscribers.remove(subscription)
            }
        }
    }

    override fun publish(event: AuditEvent) {
        val current = synchronized(this) { subscribers.toList() }
        current.forEach { subscription ->
            runCatching { subscription.onEvent(event) }
        }
    }
}
