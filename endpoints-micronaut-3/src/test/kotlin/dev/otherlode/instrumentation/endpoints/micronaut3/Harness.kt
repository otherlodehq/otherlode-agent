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

/**
 * Runs [block] with [Micronaut3Module] installed on a registry of its own, then asserts the module
 * did not switch itself off unless [expectNoneDisabled] is false.
 */
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

/** Runs what the agent runs before it builds a manifest, on the instrumentation [withModule] installed. */
fun beforeManifest(
    graceMillis: Long = 0,
    finalFlush: Boolean = false,
) = checkNotNull(installed) { "no module is installed" }.beforeManifest(graceMillis, finalFlush)

/**
 * Forgets that the seam switched [module] off. The seam keeps that for the life of the JVM, and
 * the tests of one module share a JVM.
 */
fun forgetDisabled(module: String) {
    val seam = Class.forName("dev.otherlode.bootstrap.OtherlodeEndpoints")
    listOf("DISABLED_MODULES", "FAILURE_LOGGED").forEach { field ->
        @Suppress("UNCHECKED_CAST")
        val names = seam.getDeclaredField(field).apply { isAccessible = true }.get(null) as MutableSet<String>
        names.remove(module)
    }
}

/** Starts an embedded server on a free port with [properties], runs [block] with its port and stops it. */
fun <T> serve(
    properties: Map<String, Any> = emptyMap(),
    block: (Int) -> T,
): T {
    client = newClient()
    val defaults = mapOf<String, Any>("micronaut.server.port" to -1, "endpoints.all.enabled" to false)
    val server = startOnFreePort(defaults + properties)
    try {
        return block(server.port)
    } finally {
        server.stop()
    }
}

/**
 * Starts the server, again when it lost the port Micronaut picked for it to another process before
 * it could bind, up to three attempts.
 */
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

/**
 * Sends one request to a server on [port] and returns the status code. [serve] replaces the client,
 * so a server on a port an earlier test used is never sent a request on a connection that server
 * closed.
 */
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
