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

fun <T> withModules(
    expectNoneDisabled: Boolean = true,
    block: (EndpointRegistry) -> T,
): T {
    // Each test gets a fresh client, so no request reuses a pooled connection that an earlier server on the same port closed.
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

fun send(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String> = emptyMap(),
): Int {
    val request =
        HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
    headers.forEach { (name, value) -> request.header(name, value) }
    return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
}

fun hits(registry: EndpointRegistry): Map<String, Long> {
    val endpoints = registry.endpoints().associateBy { it.endpointId }
    return registry
        .computeDeltas(maxPerBatch = 100)
        .flatMap { it.deltas }
        .associate { delta -> endpoints.getValue(delta.endpointId).let { "${it.verb} ${it.routeTemplate}" } to delta.hitsTotal }
}

fun identities(registry: EndpointRegistry): Set<String> = registry.endpoints().map { "${it.verb} ${it.routeTemplate}" }.toSet()
