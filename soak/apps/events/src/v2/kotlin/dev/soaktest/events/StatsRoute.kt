package dev.soaktest.events

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.statsRoute(store: EventStore) {
    get("/events/stats") {
        call.respond(mapOf("total" to store.all().size))
    }
}
