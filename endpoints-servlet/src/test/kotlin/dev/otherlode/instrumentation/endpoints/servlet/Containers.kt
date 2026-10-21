package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The address every test container binds and every request targets. A listener on a specific address
 * and one on the wildcard can share a port number on macOS, and a connection then reaches the specific
 * one, so a request meant for one suite's server would be answered by another's.
 */
const val LOOPBACK = "127.0.0.1"

private val client = HttpClient.newHttpClient()

/** Sends [method] to `http://127.0.0.1:[port][contextPath][path]` and returns the status. */
fun sendHttp(
    port: Int,
    contextPath: String,
    path: String,
    method: String,
): Int {
    val request =
        HttpRequest
            .newBuilder(URI.create("http://$LOOPBACK:$port$contextPath$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
}

/** What a test sets up in a servlet container before it starts. */
interface WebApp {
    /** Registers [servlet] under [name], mapped by every pattern in [mappings]. */
    fun servlet(
        name: String,
        servlet: Servlet,
        vararg mappings: String,
    )

    /** Registers [filter] for [pattern]. */
    fun filter(
        name: String,
        filter: Filter,
        pattern: String,
    )

    /** Sends a response with [status] to [location]. */
    fun errorPage(
        status: Int,
        location: String,
    )

    /** Adds the container's own default and JSP servlets. */
    fun containerDefaults()

    /** Registers an adopter's subclass of the container's default servlet under [name], mapped by [mapping]. */
    fun adopterDefaultServlet(
        name: String,
        mapping: String,
    )
}

/** A started container with one web application in it. */
interface RunningApp : AutoCloseable {
    val servletContext: ServletContext

    /** The port the container listens on, at [LOOPBACK]. */
    val port: Int

    /**
     * Maps [pattern] to the servlet registered as [servletName] after the container started, by the
     * container's own means. The servlet API refuses this from Tomcat 9.0 and 10.1 releases that
     * check the context's state, so the test does not go through `ServletRegistration.addMapping`.
     */
    fun addMappingAfterStart(
        servletName: String,
        pattern: String,
    )

    /** Sends [method] for [path] and returns the status. */
    fun send(
        path: String,
        method: String = "GET",
    ): Int
}

/** A servlet container a test can run an application in. Adding a container is adding an implementation. */
interface ServletContainer {
    /**
     * Whether the container's `HttpServlet` calls `doPatch` (Servlet 6.1), so `PATCH` is a verb a servlet
     * declares. The build states it per suite, as a system property, from the container's release.
     */
    val servesPatch: Boolean
        get() = System.getProperty("otherlode.servlet.patch") == "true"

    /** The class names of the servlets [WebApp.containerDefaults] registers. */
    val defaultServlets: Set<String>

    /** The class names of servlets the container registers in a context by itself, whatever the application asks for. */
    val ownServlets: Set<String>
        get() = emptySet()

    fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp
}
