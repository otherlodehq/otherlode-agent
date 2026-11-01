package dev.soaktest.events

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.listRoute(service: EventService) {
    get("/events") {
        call.respond(service.recent(20))
    }
}
