package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import org.eclipse.jetty.ee10.servlet.DefaultServlet
import org.eclipse.jetty.ee10.servlet.ErrorPageErrorHandler
import org.eclipse.jetty.ee10.servlet.FilterHolder
import org.eclipse.jetty.ee10.servlet.NoJspServlet
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import java.nio.file.Files
import java.util.EnumSet

fun containerUnderTest(): ServletContainer = Jetty12Container()

/** An adopter's subclass of the container's default servlet. */
class MyDefaultServlet : DefaultServlet()

/** Embedded Jetty 12 with the `ee10` servlet module, in the release the suite's dependencies select. */
class Jetty12Container : ServletContainer {
    override val defaultServlets: Set<String> =
        setOf(
            "org.eclipse.jetty.ee10.servlet.DefaultServlet",
            "org.eclipse.jetty.ee10.servlet.NoJspServlet",
            "org.eclipse.jetty.ee10.jsp.JettyJspServlet",
        )

    override val ownServlets: Set<String> = setOf("org.eclipse.jetty.ee10.servlet.ServletHandler\$Default404Servlet")

    override fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp {
        val server = Server()
        val connector = ServerConnector(server)
        connector.host = LOOPBACK
        connector.port = 0
        server.addConnector(connector)
        val base = Files.createTempDirectory("servlet-test")
        val handler = ServletContextHandler(ServletContextHandler.NO_SESSIONS)
        handler.contextPath = contextPath.ifEmpty { "/" }
        handler.setBaseResourceAsPath(base)
        Jetty12WebApp(handler).configure()
        server.handler = handler
        server.start()
        return Jetty12RunningApp(server, handler, connector.localPort, contextPath)
    }
}

private class Jetty12WebApp(
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
        jsp.className = "org.eclipse.jetty.ee10.jsp.JettyJspServlet"
        register(jsp, "*.jspx")
    }

    override fun adopterDefaultServlet(
        name: String,
        mapping: String,
    ) = register(ServletHolder(name, MyDefaultServlet()), mapping)
}

private class Jetty12RunningApp(
    private val server: Server,
    private val handler: ServletContextHandler,
    override val port: Int,
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
