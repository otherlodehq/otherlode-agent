package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import dev.otherlode.export.DisabledEndpointModuleKind
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.http.pathmap.PathSpec
import org.eclipse.jetty.http.pathmap.RegexPathSpec
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [ServletHandler] that reads a mapping starting with `^` as a regular expression, as its `asPathSpec` allows. */
private class RegexServletHandler : ServletHandler() {
    override fun asPathSpec(pathSpec: String): PathSpec =
        if (pathSpec.startsWith("^")) RegexPathSpec(pathSpec) else super.asPathSpec(pathSpec)
}

/**
 * Jetty's stock `ServletHandler` reads every servlet mapping as a servlet path spec, but its
 * `asPathSpec` is the hook for another reading, which can put a regular expression into a context's
 * servlet registrations: none of the specification's five forms.
 */
class Jetty12RegexMappingTest : ServletTestSupport() {
    @Test
    fun `a regex path spec disables the module with ROUTE_WALK_FAILED and declares nothing`() {
        val server = Server()
        val connector = ServerConnector(server)
        connector.host = LOOPBACK
        connector.port = 0
        server.addConnector(connector)
        val handler = ServletContextHandler(ServletContextHandler.NO_SESSIONS)
        handler.contextPath = "/"
        handler.servletHandler = RegexServletHandler()
        handler.addServlet(ServletHolder("fine", GetPostServlet()), "/regex/fine")
        handler.addServlet(ServletHolder("regex", GetPostServlet()), "^/regex/[0-9]+")
        server.handler = handler
        server.start()
        try {
            assertEquals(200, sendHttp(connector.localPort, "", "/regex/fine", "GET"))
            assertEquals(200, sendHttp(connector.localPort, "", "/regex/42", "GET"))

            val disabled = registry.disabledModules().single()
            assertEquals("servlet", disabled.module)
            assertEquals(DisabledEndpointModuleKind.ROUTE_WALK_FAILED, disabled.kind)
            assertTrue("^/regex/[0-9]+" in disabled.reason, disabled.reason)
            assertEquals(emptySet(), declared("/regex"))
        } finally {
            server.stop()
        }
    }
}
