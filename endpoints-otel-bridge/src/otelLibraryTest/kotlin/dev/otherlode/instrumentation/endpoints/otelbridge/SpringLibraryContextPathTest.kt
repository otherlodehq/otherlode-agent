package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.bootstrap.OtherlodeEndpoints
import io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
