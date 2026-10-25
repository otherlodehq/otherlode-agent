package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.endpoints.springwebflux.HandleMatchAdvice
import dev.otherlode.endpoints.springwebflux.LookupHandlerAdvice
import dev.otherlode.endpoints.springwebflux.ResourceLookupAdvice
import dev.otherlode.endpoints.springwebflux.SetAttributesAdvice
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs each WebFlux advice that tests for a CORS preflight against a request whose headers throw
 * from `getFirst`, the way Spring Security's firewall does for a header value with a control
 * character. The preflight test must ask only whether a header is present.
 */
class SpringWebFluxPreflightHeaderTest {
    private class UnreadableValueHeaders : HttpHeaders() {
        override fun getFirst(headerName: String): String? = throw IllegalArgumentException("marker-9c1e")
    }

    private fun preflightExchange(): ServerWebExchange {
        val request =
            MockServerHttpRequest
                .options(
                    "/orders/1",
                ).header("Origin", "http://example.com\u0085")
                .header("Access-Control-Request-Method", "GET")
                .build()
        val headers = UnreadableValueHeaders()
        headers.add("Origin", "http://example.com\u0085")
        headers.add("Access-Control-Request-Method", "GET")
        val decorated =
            object : ServerHttpRequestDecorator(request) {
                override fun getHeaders(): HttpHeaders = headers
            }
        return MockServerWebExchange
            .from(request)
            .mutate()
            .request(decorated as ServerHttpRequest)
            .build()
    }

    @Test
    fun `a preflight whose Origin value cannot be read does not disable the module on any advice`() {
        withModule { registry ->
            val exchange = preflightExchange()

            HandleMatchAdvice.onEnter(null, exchange)
            LookupHandlerAdvice.onExit(exchange, Any())
            val request = ServerRequest.create(exchange, ServerCodecConfigurer.create().readers)
            SetAttributesAdvice.onEnter(
                mutableMapOf<String, Any>(),
                request,
                HandlerFunction<ServerResponse> { ServerResponse.ok().build() },
            )
            ResourceLookupAdvice.onExit(
                Any(),
                PathPatternParser().parse("/orders/**"),
                request,
                Mono.just<Resource>(ByteArrayResource(ByteArray(1))),
            )

            assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
            assertEquals(emptyMap(), hits(registry))
        }
    }
}
