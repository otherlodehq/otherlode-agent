package dev.soaktest.events

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.replayRoute() {
    get("/events/{id}/replay") {
        call.respondText(ReplayEngine().replay(call.parameters["id"] ?: "0"))
    }
}
