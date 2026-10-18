package dev.otherlode.instrumentation.endpoints.servlet

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.ErrorPageErrorHandler
import org.eclipse.jetty.servlet.FilterHolder
import org.eclipse.jetty.servlet.NoJspServlet
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.nio.file.Files
import java.util.EnumSet
import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.Servlet
import javax.servlet.ServletContext

fun containerUnderTest(): ServletContainer = JettyContainer()

/** An adopter's subclass of the container's default servlet. */
class MyDefaultServlet : DefaultServlet()

/** Embedded Jetty with `org.eclipse.jetty.servlet`, in the release the suite's dependencies select. */
class JettyContainer : ServletContainer {
    override val defaultServlets: Set<String> =
        setOf("org.eclipse.jetty.servlet.DefaultServlet", "org.eclipse.jetty.servlet.NoJspServlet", "org.eclipse.jetty.jsp.JettyJspServlet")

    override val ownServlets: Set<String> = setOf("org.eclipse.jetty.servlet.ServletHandler\$Default404Servlet")

    override fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp {
        val server = Server()
        val connector = ServerConnector(server)
        connector.port = 0
        server.addConnector(connector)
        val base = Files.createTempDirectory("servlet-test")
        val handler = ServletContextHandler(ServletContextHandler.NO_SESSIONS)
        handler.contextPath = contextPath.ifEmpty { "/" }
        handler.resourceBase = base.toString()
        JettyWebApp(handler).configure()
        server.handler = handler
        server.start()
        return JettyRunningApp(server, handler, connector.localPort, contextPath)
    }
}

private class JettyWebApp(
    private val handler: ServletContextHandler,
) : WebApp {
    override fun servlet(
        name: String,
        servlet: Servlet,
        vararg mappings: String,
    ) {
        val holder = ServletHolder(name, servlet)
        holder.isAsyncSupported = true
        register(holder, *mappings)
    }

    private fun register(
        holder: ServletHolder,
        vararg mappings: String,
    ) {
        for (mapping in mappings) handler.addServlet(holder, mapping)
    }

    override fun filter(
        name: String,
        filter: Filter,
        pattern: String,
    ) {
        val holder = FilterHolder(filter)
        holder.name = name
        handler.addFilter(holder, pattern, EnumSet.of(DispatcherType.REQUEST))
    }

    override fun errorPage(
        status: Int,
        location: String,
    ) {
        val errors = handler.errorHandler as? ErrorPageErrorHandler ?: ErrorPageErrorHandler().also { handler.errorHandler = it }
        errors.addErrorPage(status, location)
    }

    override fun containerDefaults() {
        register(ServletHolder("default", DefaultServlet()), "/")
        register(ServletHolder("jsp", NoJspServlet()), "*.jsp")
        val jsp = ServletHolder()
        jsp.name = "jettyjsp"
        jsp.className = "org.eclipse.jetty.jsp.JettyJspServlet"
        register(jsp, "*.jspx")
    }

    override fun adopterDefaultServlet(
        name: String,
        mapping: String,
    ) = register(ServletHolder(name, MyDefaultServlet()), mapping)
}

private class JettyRunningApp(
    private val server: Server,
    private val handler: ServletContextHandler,
    private val port: Int,
    private val contextPath: String,
) : RunningApp {
    override val servletContext: ServletContext = handler.servletContext

    override fun addMappingAfterStart(
        servletName: String,
        pattern: String,
    ) {
        val servlets = handler.servletHandler
        servlets.addServletWithMapping(checkNotNull(servlets.getServlet(servletName)), pattern)
    }

    override fun send(
        path: String,
        method: String,
    ): Int = sendHttp(port, contextPath, path, method)

    override fun close() {
        server.stop()
        server.destroy()
    }
}
