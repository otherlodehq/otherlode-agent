package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import dev.otherlode.export.DisabledEndpointModuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Jetty 9.4 implements Servlet 3.1, which has no `getHttpServletMapping`: the module is off, and says so. */
class Jetty94LinkageTest : ServletTestSupport() {
    @Test
    fun `the module disables itself with LINKAGE_ERROR at the first request and counts nothing`() {
        container
            .start("") {
                servlet("plain", GetPostServlet(), "/jetty9/plain")
            }.use { app ->
                assertEquals(3, app.servletContext.majorVersion)
                assertEquals(1, app.servletContext.minorVersion)
                assertEquals(emptyList(), registry.disabledModules(), "the module is on until a request reaches a servlet")

                assertEquals(200, app.send("/jetty9/plain"), "the application is served as it was")

                val disabled = registry.disabledModules().single()
                assertEquals("servlet", disabled.module)
                assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, disabled.kind)
                assertTrue("getHttpServletMapping" in disabled.reason, disabled.reason)

                assertEquals(200, app.send("/jetty9/plain"))
                assertEquals(emptyList(), servletEndpoints(), "nothing is declared")
                assertEquals(0, totalHits(), "nothing is counted")
                assertEquals(1, registry.disabledModules().size)
            }
    }
}
