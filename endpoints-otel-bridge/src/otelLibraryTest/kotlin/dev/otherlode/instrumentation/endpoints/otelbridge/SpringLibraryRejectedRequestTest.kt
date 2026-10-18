package dev.otherlode.instrumentation.endpoints.otelbridge

import io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A filter inside OpenTelemetry's Spring MVC library filter answers 401, as Spring Security does.
 * The library still asks the handler mappings for the route after the chain returns, and that is
 * the first lookup the request makes. The request never reached the dispatcher, so it is no call.
 */
class SpringLibraryRejectedRequestTest {
    @Test
    fun `a request a filter answered with 401 is no call of the route`() {
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().build()).build()
        val telemetry = SpringWebMvcTelemetry.create(openTelemetry).createServletFilter()
        val rejecting =
            object : Filter {
                override fun init(filterConfig: FilterConfig) = telemetry.init(filterConfig)

                override fun doFilter(
                    request: ServletRequest,
                    response: ServletResponse,
                    chain: FilterChain,
                ) = telemetry.doFilter(request, response) { _, rejected -> (rejected as HttpServletResponse).status = 401 }
            }

        springApp("/shop", rejecting).use { app ->
            assertEquals(401, app.get("/shop/orders"))
        }

        val observed = observedEndpoints()

        assertEquals(0L, observed.singleOrNull { it.framework == "spring-webmvc" && it.template == "/orders" }?.hits ?: 0L, "$observed")
        assertTrue(observed.none { it.framework == "otel" }, "the bridge recorded an endpoint: $observed")
    }
}
