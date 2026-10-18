package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.bootstrap.OtherlodeEndpoints
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiServlet : HttpServlet() {
    override fun doGet(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.writer.write("api")
    }
}

/**
 * A plain servlet mapped under `/api` with a wildcard, in the context path `/shop`, under the
 * OpenTelemetry Java agent. OpenTelemetry reports the mapping with the context path in front of
 * it; the servlet module counts the mapping alone.
 */
class ServletContextPathTest {
    @Test
    fun `a servlet request under a context path is counted once and the bridge adds no endpoint`() {
        TomcatApp("/shop") { context ->
            context.servlet("api", ApiServlet(), "/api/*")
        }.use { app ->
            assertEquals(200, app.get("/shop/api/anything"))
        }

        val observed = observedEndpoints()

        assertEquals(1L, observed.single { it.framework == "servlet" && it.template == "/api/*" }.hits, "$observed")
        assertTrue(observed.none { it.framework == "otel" }, "the bridge recorded an endpoint: $observed")
        assertEquals("/api/*", OtherlodeEndpoints.resolveRoutePrefix("/shop/api/*"))
    }
}
