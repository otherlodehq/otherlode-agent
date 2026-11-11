package dev.otherlode.instrumentation.endpoints.micronaut3

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

fun <T> withModule(
    expectNoneDisabled: Boolean = true,
    block: (EndpointRegistry) -> T,
): T {
    val instrumentation = ByteBuddyAgent.install()
    val registry = EndpointRegistry()
    val endpointInstrumentation = EndpointInstrumentation(registry, listOf(Micronaut3Module()))
    val transformer = endpointInstrumentation.install(instrumentation)
    installed = endpointInstrumentation
    try {
        val result = block(registry)
        if (expectNoneDisabled) assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
        return result
    } finally {
        installed = null
        endpointInstrumentation.uninstall(instrumentation, transformer)
    }
}

@Volatile
private var installed: EndpointInstrumentation? = null

fun beforeManifest(
    graceMillis: Long = 0,
    finalFlush: Boolean = false,
) = checkNotNull(installed) { "no module is installed" }.beforeManifest(graceMillis, finalFlush)

// The seam keeps the disabled state for the life of the JVM, and the tests of one module share a JVM.
fun forgetDisabled(module: String) {
    val seam = Class.forName("dev.otherlode.bootstrap.OtherlodeEndpoints")
    listOf("DISABLED_MODULES", "FAILURE_LOGGED").forEach { field ->
        @Suppress("UNCHECKED_CAST")
        val names = seam.getDeclaredField(field).apply { isAccessible = true }.get(null) as MutableSet<String>
        names.remove(module)
    }
}

fun <T> serve(
    properties: Map<String, Any> = emptyMap(),
    block: (Int) -> T,
): T {
    // A fresh client per server, so no request reuses a pooled connection an earlier server on the same port closed.
    client = newClient()
    val defaults =
        mapOf<String, Any>(
            "micronaut.server.host" to "127.0.0.1",
            "micronaut.server.port" to -1,
            "endpoints.all.enabled" to false,
        )
    val server = startOnFreePort(defaults + properties)
    try {
        return block(server.port)
    } finally {
        server.stop()
    }
}

private fun startOnFreePort(properties: Map<String, Any>): EmbeddedServer {
    repeat(2) {
        try {
            return ApplicationContext.run(EmbeddedServer::class.java, properties)
        } catch (e: RuntimeException) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it is java.net.BindException }) throw e
        }
    }
    return ApplicationContext.run(EmbeddedServer::class.java, properties)
}

@Volatile
private var client = newClient()

private fun newClient(): HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

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
            .newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10))
            .method(method, publisher)
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
