package org.aethra.core.runtime.events

import org.aethra.core.domain.DomainEvent
import org.aethra.core.domain.TaskEvent
import org.aethra.core.domain.TaskState

/**
 * Minimal in-process event bus used by the CoreRuntime to notify subsystems of
 * lifecycle, task and security events without creating direct dependencies
 * between them. Thread safety is adequate for app-scale single-process runtime
 * and is sufficient for core-level tests.
 */
class EventBus {
    private val listeners =
        mutableMapOf<Class<out DomainEvent>, MutableList<(DomainEvent) -> Unit>>()
    private val history = ArrayDeque<DomainEvent>()
    private val lock = Any()
    private val historyLimit = 100

    fun <T : DomainEvent> subscribe(eventClass: Class<T>, listener: (T) -> Unit) {
        synchronized(lock) {
            listeners.getOrPut(eventClass) { mutableListOf() }
                .add(listener as (DomainEvent) -> Unit)
        }
    }

    fun publish(event: DomainEvent) {
        val callbacks = synchronized(lock) {
            history.addLast(event)
            while (history.size > historyLimit) history.removeFirst()
            listeners[event::class.java]?.toList() ?: emptyList()
        }
        callbacks.forEach { it.invoke(event) }
    }

    /**
     * Return the most recent events in chronological order.
     * The snapshot is independent from the internal event history.
     */
    fun recent(limit: Int = historyLimit): List<DomainEvent> = synchronized(lock) {
        history.takeLast(limit.coerceAtLeast(0))
    }
}

/** Helper for tests and examples: emits a single [TaskEvent] on the bus. */
fun EventBus.emitTaskTransition(taskId: String, previous: TaskState, next: TaskState) {
    publish(TaskEvent("core", System.currentTimeMillis(), taskId, previous, next))
}
