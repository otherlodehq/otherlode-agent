package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext

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

    fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp
}
