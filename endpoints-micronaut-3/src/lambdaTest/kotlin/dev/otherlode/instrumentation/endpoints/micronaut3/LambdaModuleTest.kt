package dev.otherlode.instrumentation.endpoints.micronaut3

import com.amazonaws.serverless.proxy.internal.testutils.AwsProxyRequestBuilder
import com.amazonaws.serverless.proxy.internal.testutils.MockLambdaContext
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointDiscoverySource
import io.micronaut.context.ApplicationContext
import io.micronaut.function.aws.proxy.MicronautLambdaHandler
import io.micronaut.web.router.Router
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [Micronaut3Module] against micronaut-aws 3.x's Lambda handler, which builds its own
 * `RouteExecutor` and so reaches neither Netty's nor micronaut-servlet's dispatch point. The
 * controllers are the Netty suite's.
 */
class LambdaModuleTest {
    private val quiet = mapOf<String, Any>("endpoints.all.enabled" to false)

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

    @AfterTest
    fun forget() = forgetDisabled("micronaut-3")

    private fun <T> lambda(
        properties: Map<String, Any> = emptyMap(),
        block: (MicronautLambdaHandler) -> T,
    ): T = MicronautLambdaHandler(ApplicationContext.builder().properties(quiet + properties)).use(block)

    private fun call(
        handler: MicronautLambdaHandler,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Int {
        val request = AwsProxyRequestBuilder(path, method)
        headers.forEach { (name, value) -> request.header(name, value) }
        return handler.handleRequest(request.build(), MockLambdaContext()).statusCode
    }

    @Test
    fun `routes are declared once the handler exists and counted once at dispatch`() {
        withModule {
            lambda { handler ->
                val registry = it
                assertEquals(declared, identities(registry))
                assertTrue(registry.endpoints().all { endpoint -> endpoint.discoverySource == EndpointDiscoverySource.REGISTRATION })
                assertTrue(registry.endpoints().all { endpoint -> endpoint.framework == "micronaut-3" })

                assertEquals(200, call(handler, "GET", "/java/hello"))
                assertEquals(200, call(handler, "GET", "/java/hello"))
                assertEquals(200, call(handler, "GET", "/java/items/7"))
                assertEquals(200, call(handler, "GET", "/java/segment"))
                assertEquals(200, call(handler, "GET", "/java/files/a/b"))
                assertEquals(200, call(handler, "GET", "/programmatic"))
                assertEquals(200, call(handler, "GET", "/kt/hello"))

                assertEquals(
                    mapOf(
                        "GET /java/hello" to 2L,
                        "GET /java/items/{id}" to 1L,
                        "GET /java/segment/{id?}" to 1L,
                        "GET /java/files/*" to 1L,
                        "GET /programmatic" to 1L,
                        "GET /kt/hello" to 1L,
                    ),
                    hits(registry),
                )
                assertEquals(declared, identities(registry), "dispatch discovered no endpoint registration had missed")
                beforeManifest()
                assertEquals(emptyList(), registry.disabledModules())
            }
        }
    }

    @Test
    fun `an implicit HEAD route is not declared and a HEAD request counts against the GET endpoint`() {
        withModule { registry ->
            lambda { handler ->
                assertTrue("HEAD /java/hello" !in identities(registry))

                assertEquals(200, call(handler, "HEAD", "/java/hello"))
                assertEquals(200, call(handler, "GET", "/java/hello"))

                assertEquals(mapOf("GET /java/hello" to 2L), hits(registry))
            }
        }
    }

    @Test
    fun `an Error route is not an endpoint and the route it recovers is counted once`() {
        withModule { registry ->
            lambda { handler ->
                assertEquals(200, call(handler, "GET", "/boom/fail"))
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
            lambda(cors) { handler ->
                val preflight = mapOf("Origin" to "http://example.com", "Access-Control-Request-Method" to "GET")
                call(handler, "OPTIONS", "/java/hello", preflight)
                assertEquals(404, call(handler, "GET", "/nothing"))
                assertEquals(405, call(handler, "DELETE", "/java/hello"))

                assertEquals(emptyMap(), hits(registry))

                assertEquals(200, call(handler, "GET", "/java/hello", mapOf("Origin" to "http://example.com")))
                assertEquals(mapOf("GET /java/hello" to 1L), hits(registry))
            }
        }
    }

    @Test
    fun `a router built before the handler is declared when the handler is constructed`() {
        withModule { registry ->
            ApplicationContext.run(quiet + ("micronaut.server.context-path" to "/pending")).use { context ->
                context.getBean(Router::class.java)
                assertEquals(emptySet(), identities(registry))

                lambda {
                    assertTrue("GET /pending/java/hello" in identities(registry), identities(registry).toString())
                    assertTrue("GET /java/hello" in identities(registry))
                }
            }
        }
    }

    @Test
    fun `a router with no handler constructed is reported ROUTES_NOT_COUNTED at the first manifest`() {
        withModule(expectNoneDisabled = false) { registry ->
            ApplicationContext.run(quiet).use { context ->
                context.getBean(Router::class.java)

                beforeManifest()

                assertEquals(emptySet(), identities(registry))
                assertEquals(DisabledEndpointModuleKind.ROUTES_NOT_COUNTED, registry.disabledModules().single().kind)
            }
        }
    }
}
