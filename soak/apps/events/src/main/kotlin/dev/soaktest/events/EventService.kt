package dev.soaktest.events

import kotlinx.coroutines.delay

class EventService(
    private val store: EventStore,
) {
    suspend fun recent(limit: Int): List<Event> {
        delay(1)
        val all = store.all()
        if (all.size > 10_000) {
            return all.takeLast(limit)
        }
        return all.take(limit)
    }
}
