package dev.otherlode.instrumentation.endpoints.vertxweb

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import net.bytebuddy.agent.ByteBuddyAgent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Runs [block] with [module] installed on a registry of its own, then asserts the module did not
 * switch itself off unless [expectNotDisabled] is false. The endpoint seam keeps a module it
 * switched off off for the rest of the JVM, so a test that wants a walk to fail calls the module's
 * `declare` itself.
 */
fun <T> withModule(
    module: VertxWebModule = VertxWebModule(),
    expectNotDisabled: Boolean = true,
    block: (EndpointRegistry) -> T,
): T {
    val instrumentation = ByteBuddyAgent.install()
    val registry = EndpointRegistry()
    val endpointInstrumentation = EndpointInstrumentation(registry, listOf(module))
    val transformer = endpointInstrumentation.install(instrumentation)
    try {
        val result = block(registry)
        if (expectNotDisabled) assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
        return result
    } finally {
        endpointInstrumentation.uninstall(instrumentation, transformer)
        client = newClient()
    }
}

/** A running server on a free port. */
class Server(
    val vertx: Vertx,
    val router: Router,
    val port: Int,
)

/** Starts a server on a free port for the router [build] fills in, runs [block] and stops the server. */
fun <T> serve(
    build: (Vertx, Router) -> Unit = { _, _ -> },
    block: Server.() -> T,
): T {
    val vertx = Vertx.vertx()
    try {
        val router = Router.router(vertx)
        build(vertx, router)
        val server =
            vertx
                .createHttpServer()
                .requestHandler(router)
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
        return Server(vertx, router, server.actualPort()).block()
    } finally {
        vertx
            .close()
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
    }
}

@Volatile
private var client = newClient()

private fun newClient(): HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

/**
 * Sends one request to a server on [port] and returns the status code. The client keeps its
 * connections between requests, and [withModule] replaces it so a later test's server on a port an
 * earlier one used is never sent a request on a connection that server closed.
 */
fun send(
    port: Int,
    method: String,
    path: String,
): Int =
    client
        .send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

/** The hit count of every endpoint that has one, keyed `"<verb> <template>"`. */
fun hits(registry: EndpointRegistry): Map<String, Long> {
    val endpoints = registry.endpoints().associateBy { it.endpointId }
    return registry
        .computeDeltas(maxPerBatch = 100)
        .flatMap { it.deltas }
        .associate { delta -> endpoints.getValue(delta.endpointId).let { "${it.verb} ${it.routeTemplate}" } to delta.hitsTotal }
}

/** Every declared or discovered endpoint, as `"<verb> <template>"`. */
fun identities(registry: EndpointRegistry): Set<String> = registry.endpoints().map { "${it.verb} ${it.routeTemplate}" }.toSet()
