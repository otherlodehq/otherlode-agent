package dev.soaktest.events

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicReference

class EventStore {
    private val events = ConcurrentSkipListMap<Long, Event>()
    private val lastSummary = AtomicReference("")

    init {
        add(Event(1, "created", "first"))
        add(Event(2, "updated", "second"))
        add(Event(3, "deleted", "third"))
    }

    fun add(event: Event) {
        events.put(event.id % 100, event)
        lastSummary.set("${event.kind}:${event.payload.length}")
    }

    fun all(): List<Event> = events.values.toList()
}
