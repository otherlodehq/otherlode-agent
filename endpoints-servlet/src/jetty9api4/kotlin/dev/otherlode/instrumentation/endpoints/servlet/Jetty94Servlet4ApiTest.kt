package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import dev.otherlode.export.DisabledEndpointModuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Jetty94Servlet4ApiTest : ServletTestSupport() {
    @Test
    fun `the API default mapping disables the module with LINKAGE_ERROR and declares nothing`() {
        container
            .start("") {
                servlet("plain", GetPostServlet(), "/jetty9api4/plain")
                servlet("other", GetPostServlet(), "/jetty9api4/other")
            }.use { app ->
                assertEquals(3, app.servletContext.majorVersion, "the container implements Servlet 3.1")
                assertEquals(1, app.servletContext.minorVersion)
                assertEquals(emptyList(), registry.disabledModules(), "the module is on until a request reaches a servlet")

                assertEquals(200, app.send("/jetty9api4/plain"), "the application is served as it was")

                val disabled = registry.disabledModules().single()
                assertEquals("servlet", disabled.module)
                assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, disabled.kind)
                assertTrue("Servlet 4.0" in disabled.reason, disabled.reason)

                assertEquals(200, app.send("/jetty9api4/other"))
                assertEquals(emptyList(), servletEndpoints(), "nothing is declared, so nothing reads as never called")
                assertEquals(0, totalHits())
                assertEquals(1, registry.disabledModules().size)
            }
    }
}
