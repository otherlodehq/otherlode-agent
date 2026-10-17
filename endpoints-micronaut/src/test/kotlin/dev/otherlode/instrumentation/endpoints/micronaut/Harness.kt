package dev.otherlode.instrumentation.endpoints.micronaut

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import net.bytebuddy.agent.ByteBuddyAgent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertTrue

/**
 * Runs [block] with [MicronautModule] installed on a registry of its own, then asserts the module
 * did not switch itself off unless [expectNoneDisabled] is false.
 */
fun <T> withModule(
    expectNoneDisabled: Boolean = true,
    block: (EndpointRegistry) -> T,
): T {
    val instrumentation = ByteBuddyAgent.install()
    val registry = EndpointRegistry()
    val endpointInstrumentation = EndpointInstrumentation(registry, listOf(MicronautModule()))
    val transformer = endpointInstrumentation.install(instrumentation)
    try {
        val result = block(registry)
        if (expectNoneDisabled) assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
        return result
    } finally {
        endpointInstrumentation.uninstall(instrumentation, transformer)
    }
}

/** Starts an embedded server on a free port with [properties], runs [block] with its port and stops it. */
fun <T> serve(
    properties: Map<String, Any> = emptyMap(),
    block: (Int) -> T,
): T {
    val defaults = mapOf<String, Any>("micronaut.server.port" to -1, "endpoints.all.enabled" to false)
    val server = ApplicationContext.run(EmbeddedServer::class.java, defaults + properties)
    try {
        return block(server.port)
    } finally {
        server.stop()
    }
}

private val client = HttpClient.newHttpClient()

/** Sends one request to a server on [port] and returns the status code. */
fun send(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
): Int {
    val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
    val request =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(10))
            .method(method, publisher)
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
