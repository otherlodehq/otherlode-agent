package dev.otherlode.instrumentation.endpoints.micronaut3

import dev.otherlode.export.EndpointDiscoverySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [Micronaut3Module] against micronaut-servlet's embedded Jetty server, which dispatches
 * through `ServletHttpHandler` instead of Netty's `RoutingInBoundHandler`. The controllers are the
 * Netty suite's.
 */
class ServletModuleTest {
    private val declared =
        setOf(
            "GET /java/hello",
            "POST /java/echo",
            "GET /java/items/{id}",
            "GET /java/colon/{id?}",
            "GET /java/segment/{id?}",
            "GET /java/files/*",
            "HEAD /java/probe",
            "GET /java/nohead",
            "GET /java/primitives/{id}",
            "GET /java/generic/{id}",
            "GET /java/inherited",
            "GET /kt/hello",
            "GET /kt/susp/{id}",
            "GET /kt/susp-unit",
            "GET /boom/fail",
            "GET /programmatic",
            "HEAD /programmatic-head",
            "GET /api/declared",
            "GET /traced/own",
            "GET /traced/inherited",
            "POST /generic/save",
            "GET /overload/list/{name}",
        )

    @Test
    fun `routes are declared at registration and counted once at dispatch`() {
        withModule { registry ->
            serve { port ->
                assertEquals(declared, identities(registry))
                assertTrue(registry.endpoints().all { it.discoverySource == EndpointDiscoverySource.REGISTRATION })
                assertTrue(registry.endpoints().all { it.framework == "micronaut-3" })

                assertEquals(200, send(port, "GET", "/java/hello"))
                assertEquals(200, send(port, "GET", "/java/hello"))
                assertEquals(200, send(port, "POST", "/java/echo", mapOf("Content-Type" to "text/plain"), "x"))
                assertEquals(200, send(port, "GET", "/java/items/7"))
                assertEquals(200, send(port, "GET", "/java/segment"))
                assertEquals(200, send(port, "GET", "/java/segment/9"))
                assertEquals(200, send(port, "GET", "/java/files/a/b"))
                assertEquals(200, send(port, "GET", "/java/inherited"))
                assertEquals(200, send(port, "GET", "/programmatic"))
                assertEquals(200, send(port, "GET", "/kt/hello"))

                assertEquals(
                    mapOf(
                        "GET /java/hello" to 2L,
                        "POST /java/echo" to 1L,
                        "GET /java/items/{id}" to 1L,
                        "GET /java/segment/{id?}" to 2L,
                        "GET /java/files/*" to 1L,
                        "GET /java/inherited" to 1L,
                        "GET /programmatic" to 1L,
                        "GET /kt/hello" to 1L,
                    ),
                    hits(registry),
                )
                assertEquals(declared, identities(registry))
            }
        }
    }

    @Test
    fun `an implicit HEAD route is not declared and a HEAD request counts against the GET endpoint`() {
        withModule { registry ->
            serve { port ->
                assertTrue("HEAD /java/hello" !in identities(registry))

                assertEquals(200, send(port, "HEAD", "/java/hello"))
                assertEquals(200, send(port, "GET", "/java/hello"))

                assertEquals(mapOf("GET /java/hello" to 2L), hits(registry))
            }
        }
    }

    @Test
    fun `an Error route is not an endpoint and the route it recovers is counted once`() {
        withModule { registry ->
            serve { port ->
                assertEquals(200, send(port, "GET", "/boom/fail"))
                assertEquals(mapOf("GET /boom/fail" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a 404, a 405 and a CORS preflight count nothing`() {
        withModule { registry ->
            val cors =
                mapOf(
                    "micronaut.server.cors.enabled" to true,
                    "micronaut.server.cors.configurations.web.allowed-origins" to listOf("http://example.com"),
                )
            serve(cors) { port ->
                val preflight =
                    mapOf(
                        "Origin" to "http://example.com",
                        "Access-Control-Request-Method" to "GET",
                    )
                assertEquals(200, send(port, "OPTIONS", "/java/hello", preflight))
                assertEquals(404, send(port, "GET", "/nothing"))
                assertEquals(405, send(port, "DELETE", "/java/hello"))

                assertEquals(emptyMap(), hits(registry))

                assertEquals(200, send(port, "GET", "/java/hello", mapOf("Origin" to "http://example.com")))
                assertEquals(mapOf("GET /java/hello" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `the module is never disabled by a server that starts and stops twice`() {
        withModule { registry ->
            repeat(2) {
                serve { port ->
                    assertEquals(200, send(port, "GET", "/java/hello"))
                }
            }
            assertEquals(mapOf("GET /java/hello" to 2L), hits(registry))
        }
    }
}
