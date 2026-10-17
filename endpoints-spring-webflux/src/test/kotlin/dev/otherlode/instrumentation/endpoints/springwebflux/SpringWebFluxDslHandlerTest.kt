package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.export.EndpointDiscoverySource
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.function.server.router
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A route written in Spring's Kotlin DSL has its handler inside a class of Spring's own, so no
 * method of the application's is the handler and the endpoint reports none.
 */
class SpringWebFluxDslHandlerTest {
    @Test
    fun `a router DSL route is declared and counted with no handler`() {
        withModule { registry ->
            val routes = router { GET("/dsl") { ServerResponse.ok().build() } }
            val client = WebTestClient.bindToApplicationContext(buildWebFluxContext(routerFunctions = listOf(routes))).build()

            client.send(HttpMethod.GET, "/dsl")

            val endpoint = registry.endpoints().single()
            assertEquals("GET /dsl", "${endpoint.verb} ${endpoint.routeTemplate}")
            assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
            assertNull(endpoint.handlerClass)
            assertNull(endpoint.handlerMethod)
            assertNull(endpoint.handlerDescriptor)
            assertEquals(mapOf("GET /dsl" to 1L), hits(registry))
        }
    }

    @Test
    fun `a coRouter DSL route is declared and counted with no handler`() {
        withModule { registry ->
            val routes = coRouter { GET("/co") { ServerResponse.ok().buildAndAwait() } }
            val client = WebTestClient.bindToApplicationContext(buildWebFluxContext(routerFunctions = listOf(routes))).build()

            client.send(HttpMethod.GET, "/co")

            val endpoint = registry.endpoints().single()
            assertNull(endpoint.handlerClass)
            assertNull(endpoint.handlerMethod)
            assertNull(endpoint.handlerDescriptor)
            assertEquals(mapOf("GET /co" to 1L), hits(registry))
        }
    }

    @Test
    fun `a router DSL route found at dispatch has no handler`() {
        withModule { registry ->
            val routes = router { GET("/found", RequestPredicate { true }) { ServerResponse.ok().build() } }
            val client = WebTestClient.bindToApplicationContext(buildWebFluxContext(routerFunctions = listOf(routes))).build()

            client.send(HttpMethod.GET, "/found")

            val endpoint = registry.endpoints().single()
            assertEquals(EndpointDiscoverySource.DISPATCH, endpoint.discoverySource)
            assertNull(endpoint.handlerClass)
            assertNull(endpoint.handlerMethod)
            assertEquals(mapOf("GET /found" to 1L), hits(registry))
        }
    }

    @Test
    fun `a coRouter DSL route found at dispatch has no handler`() {
        withModule { registry ->
            val routes = coRouter { GET("/cofound", RequestPredicate { true }) { ServerResponse.ok().buildAndAwait() } }
            val client = WebTestClient.bindToApplicationContext(buildWebFluxContext(routerFunctions = listOf(routes))).build()

            client.send(HttpMethod.GET, "/cofound")

            val endpoint = registry.endpoints().single()
            assertEquals(EndpointDiscoverySource.DISPATCH, endpoint.discoverySource)
            assertNull(endpoint.handlerClass)
            assertNull(endpoint.handlerMethod)
            assertEquals(mapOf("GET /cofound" to 1L), hits(registry))
        }
    }
}
