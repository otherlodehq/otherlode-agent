package dev.soaktest.events

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.createRoute(store: EventStore) {
    post("/events") {
        val event = call.receive<Event>()
        store.add(event)
        call.respond(HttpStatusCode.Created, event)
    }
}
