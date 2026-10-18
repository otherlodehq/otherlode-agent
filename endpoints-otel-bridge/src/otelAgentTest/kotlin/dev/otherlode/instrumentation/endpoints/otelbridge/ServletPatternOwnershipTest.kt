package dev.otherlode.instrumentation.endpoints.otelbridge

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionServlet : HttpServlet() {
    override fun doGet(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.writer.write("extension")
    }
}

class DefaultMappedServlet : HttpServlet() {
    override fun doGet(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.writer.write("default")
    }
}

/**
 * An extension mapping and the default mapping under the context path `/shop`, with the
 * OpenTelemetry Java agent. OpenTelemetry reports the extension mapping with a leading slash
 * before its star, and the default mapping as a slash and a star, or a bare slash at the context
 * root; the servlet module holds them as the servlet patterns `*.do` and `/`.
 */
class ServletPatternOwnershipTest {
    @Test
    fun `an extension servlet and a default servlet count once and the bridge adds no endpoint`() {
        TomcatApp("/shop") { context ->
            context.servlet("extension", ExtensionServlet(), "*.do")
            context.servlet("default", DefaultMappedServlet(), "/")
        }.use { app ->
            assertEquals(200, app.get("/shop/orders.do"))
            assertEquals(200, app.get("/shop/anything"))
            assertEquals(200, app.get("/shop/"))
        }

        val observed = observedEndpoints()

        assertEquals(1L, observed.single { it.framework == "servlet" && it.template == "*.do" }.hits, "$observed")
        assertEquals(2L, observed.single { it.framework == "servlet" && it.template == "/" }.hits, "$observed")
        assertTrue(observed.none { it.framework == "otel" }, "the bridge recorded an endpoint: $observed")
    }
}
