package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import org.apache.catalina.Context
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.ErrorPage
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

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
    fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp
}

/** Embedded Tomcat, in the namespace and release the suite's dependencies select. */
class TomcatContainer : ServletContainer {
    override fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp {
        val base = Files.createTempDirectory("servlet-test")
        val tomcat = Tomcat()
        tomcat.setBaseDir(base.toString())
        tomcat.setPort(0)
        tomcat.connector
        val context = tomcat.addContext(contextPath, base.toString())
        TomcatWebApp(context).configure()
        tomcat.start()
        return TomcatRunningApp(tomcat, context, tomcat.connector.localPort, contextPath)
    }
}

private class TomcatWebApp(
    private val context: Context,
) : WebApp {
    override fun servlet(
        name: String,
        servlet: Servlet,
        vararg mappings: String,
    ) {
        Tomcat.addServlet(context, name, servlet)
        for (mapping in mappings) context.addServletMappingDecoded(mapping, name)
    }

    override fun filter(
        name: String,
        filter: Filter,
        pattern: String,
    ) {
        val definition = FilterDef()
        definition.filterName = name
        definition.filter = filter
        context.addFilterDef(definition)
        val map = FilterMap()
        map.filterName = name
        map.addURLPattern(pattern)
        context.addFilterMap(map)
    }

    override fun errorPage(
        status: Int,
        location: String,
    ) {
        val page = ErrorPage()
        page.errorCode = status
        page.location = location
        context.addErrorPage(page)
    }

    override fun containerDefaults() {
        Tomcat.initWebappDefaults(context)
    }
}

private class TomcatRunningApp(
    private val tomcat: Tomcat,
    context: Context,
    private val port: Int,
    private val contextPath: String,
) : RunningApp {
    private val tomcatContext = context
    override val servletContext: ServletContext = context.servletContext
    private val client = HttpClient.newHttpClient()

    override fun addMappingAfterStart(
        servletName: String,
        pattern: String,
    ) = tomcatContext.addServletMappingDecoded(pattern, servletName)

    override fun send(
        path: String,
        method: String,
    ): Int {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$contextPath$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    override fun close() {
        tomcat.stop()
        tomcat.destroy()
    }
}
