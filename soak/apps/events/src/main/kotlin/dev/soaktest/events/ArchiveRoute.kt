package dev.soaktest.events

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.archiveRoute() {
    get("/events/archive") {
        call.respondText("archive empty")
    }
}
