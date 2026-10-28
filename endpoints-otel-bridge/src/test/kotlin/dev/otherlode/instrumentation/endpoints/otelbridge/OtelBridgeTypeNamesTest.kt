package dev.otherlode.instrumentation.endpoints.otelbridge

import kotlin.test.Test
import kotlin.test.assertEquals

class OtelBridgeTypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor",
                "io.opentelemetry.javaagent.shaded.instrumentation.api.semconv.http.HttpServerAttributesExtractor",
                "io.opentelemetry.javaagent.bootstrap.servlet.ServletContextPath",
                "io.opentelemetry.javaagent.bootstrap.jaxrs.JaxrsContextPath",
                "io.opentelemetry.instrumentation.spring.webmvc.v5_3.HttpRouteSupport",
                "io.opentelemetry.instrumentation.spring.webmvc.v6_0.HttpRouteSupport",
            ),
            OtelBridgeModule().typeNames,
        )
    }
}
