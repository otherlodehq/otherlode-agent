package dev.soaktest.events

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

fun startEvents(versionRoutes: Route.(EventStore) -> Unit) {
    val port = System.getenv().getOrDefault("PORT", "8080").toInt()
    embeddedServer(CIO, port = port) { eventsModule(versionRoutes) }.start(wait = true)
}

fun Application.eventsModule(versionRoutes: Route.(EventStore) -> Unit) {
    val store = EventStore()
    val service = EventService(store)
    install(ContentNegotiation) { json() }
    routing {
        listRoute(service)
        createRoute(store)
        archiveRoute()
        versionRoutes(store)
    }
}
