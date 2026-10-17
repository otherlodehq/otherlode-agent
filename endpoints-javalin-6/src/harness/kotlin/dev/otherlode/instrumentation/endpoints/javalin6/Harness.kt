package dev.otherlode.instrumentation.endpoints.javalin6

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.javalin7.Javalin7Module
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertTrue

/**
 * Runs [block] with both Javalin modules installed on a registry of its own, as an application of
 * either major finds them in the agent, then asserts neither module switched itself off unless
 * [expectNoneDisabled] is false.
 */
fun <T> withModules(
    expectNoneDisabled: Boolean = true,
    block: (EndpointRegistry) -> T,
): T {
    client = newClient()
    val instrumentation = ByteBuddyAgent.install()
    val registry = EndpointRegistry()
    val endpointInstrumentation = EndpointInstrumentation(registry, listOf(Javalin6Module(), Javalin7Module()))
    val transformer = endpointInstrumentation.install(instrumentation)
    try {
        val result = block(registry)
        if (expectNoneDisabled) assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
        return result
    } finally {
        endpointInstrumentation.uninstall(instrumentation, transformer)
    }
}

@Volatile
private var client = newClient()

private fun newClient(): HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

/**
 * Sends one request to a server on [port] and returns the status code. [withModules] replaces the client,
 * so a server on a port an earlier test used is never sent a request on a connection that server
 * closed.
 */
fun send(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String> = emptyMap(),
): Int {
    val request =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
    headers.forEach { (name, value) -> request.header(name, value) }
    return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
}

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
