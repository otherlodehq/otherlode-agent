package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.bootstrap.OtherlodeEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spring MVC served from a context path under the OpenTelemetry Java agent. OpenTelemetry reports
 * the route as `/shop/orders`; the Spring MVC module counts `/orders`.
 */
class SpringContextPathTest {
    @Test
    fun `a Spring MVC request under a context path is counted once and the bridge adds no endpoint`() {
        springApp("/shop").use { app ->
            assertEquals(200, app.get("/shop/orders"))
            assertEquals(200, app.get("/shop/orders"))
        }

        val observed = observedEndpoints()

        assertEquals(2L, observed.single { it.framework == "spring-webmvc" && it.template == "/orders" }.hits, "$observed")
        assertTrue(observed.none { it.framework == "otel" }, "the bridge recorded an endpoint: $observed")
        assertEquals("/orders", OtherlodeEndpoints.resolveRoutePrefix("/shop/orders"))
    }
}
