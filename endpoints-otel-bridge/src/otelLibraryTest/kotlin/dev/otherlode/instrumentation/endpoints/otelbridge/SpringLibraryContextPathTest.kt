package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.bootstrap.OtherlodeEndpoints
import io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spring MVC served from a context path with OpenTelemetry's Spring MVC library instrumentation
 * and no Java agent. The library puts the request's context path in front of the route, so it
 * reports `/shop/orders` where the Spring MVC module counts `/orders`. The library asks the handler
 * mappings for the match a second time to learn the route, after the dispatch, and that lookup is
 * no call.
 */
class SpringLibraryContextPathTest {
    @Test
    fun `a Spring MVC request under a context path adds no bridge endpoint`() {
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().build()).build()
        val filter = SpringWebMvcTelemetry.create(openTelemetry).createServletFilter()

        springApp("/shop", filter).use { app ->
            assertEquals(200, app.get("/shop/orders"))
            assertEquals(200, app.get("/shop/orders"))
        }

        val observed = observedEndpoints()

        assertEquals(
            2L,
            observed.singleOrNull { it.framework == "spring-webmvc" && it.template == "/orders" }?.hits,
            "$observed",
        )
        assertTrue(observed.none { it.framework == "otel" }, "the bridge recorded an endpoint: $observed")
        assertEquals("/orders", OtherlodeEndpoints.resolveRoutePrefix("/shop/orders"))
    }
}
