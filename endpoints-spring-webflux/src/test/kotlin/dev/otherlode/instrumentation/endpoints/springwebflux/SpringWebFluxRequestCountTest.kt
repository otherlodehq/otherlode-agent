package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.export.EndpointDiscoverySource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.core.io.FileUrlResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ContextPathCompositeHandler
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates.GET
import org.springframework.web.reactive.function.server.RequestPredicates.OPTIONS
import org.springframework.web.reactive.function.server.RequestPredicates.path
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.RouterFunctions.resources
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val RESOURCE_ENDPOINTS = { prefix: String -> setOf("GET $prefix/*", "HEAD $prefix/*", "OPTIONS $prefix/*") }

/**
 * Proves that a request counts once, that a preflight is no call, and how a `resources(...)` route
 * is declared and counted, on every route kind, against a real `DispatcherHandler`. Each case
 * asserts the module is still on, since a hook that does not match the Spring in use switches it off.
 */
class SpringWebFluxRequestCountTest {
    private fun clientFor(
        controllers: List<Class<*>> = emptyList(),
        routerFunctions: List<RouterFunction<*>> = emptyList(),
        urlMap: Map<String, Any> = emptyMap(),
    ): WebTestClient = WebTestClient.bindToApplicationContext(buildWebFluxContext(controllers, routerFunctions, urlMap)).build()

    private fun staticDirectory(): Path {
        val directory = Files.createTempDirectory("otherlode-static")
        Files.writeString(directory.resolve("app.js"), "console.log(1)")
        directory.toFile().deleteOnExit()
        directory.resolve("app.js").toFile().deleteOnExit()
        return directory
    }

    private fun directoryResource(directory: Path) = FileUrlResource(directory.toUri().toURL())

    private val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }

    @Test
    fun `an annotated GET counts exactly once`() {
        withModule { registry ->
            val client = clientFor(controllers = listOf(TestController::class.java))

            client.send(HttpMethod.GET, "/checkout/42")

            assertEquals(mapOf("GET /checkout/{id}" to 1L), hits(registry))
        }
    }

    @Test
    fun `a preflight counts nothing on annotated, URL-mapped and functional routes`() {
        withModule { registry ->
            val client =
                clientFor(
                    controllers = listOf(TestController::class.java),
                    routerFunctions = listOf(route(GET("/fn/{id}"), handler)),
                    urlMap = mapOf("/static/**" to urlMappedWebHandler(), "/legacy/old" to urlMappedWebHandler()),
                )

            client.send(HttpMethod.OPTIONS, "/checkout/42", PREFLIGHT_HEADERS)
            client.send(HttpMethod.OPTIONS, "/static/app.js", PREFLIGHT_HEADERS)
            client.send(HttpMethod.OPTIONS, "/legacy/old", PREFLIGHT_HEADERS)
            client.send(HttpMethod.OPTIONS, "/fn/1", PREFLIGHT_HEADERS)

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `an OPTIONS request without the CORS headers to an OPTIONS-mapped route still counts`() {
        withModule { registry ->
            val client = clientFor(controllers = listOf(OptionsController::class.java))

            client.send(HttpMethod.OPTIONS, "/opt")
            client.send(HttpMethod.OPTIONS, "/opt", mapOf("Origin" to "http://example.com"))

            assertEquals(mapOf("OPTIONS /opt" to 2L), hits(registry))
        }
    }

    @Test
    fun `an OPTIONS request without the CORS headers to a functional OPTIONS route counts`() {
        withModule { registry ->
            val client = clientFor(routerFunctions = listOf(route(OPTIONS("/fnopt"), handler)))

            client.send(HttpMethod.OPTIONS, "/fnopt")

            assertEquals(mapOf("OPTIONS /fnopt" to 1L), hits(registry))
        }
    }

    @Test
    fun `a top-level resources route is declared with its verbs and counted once per served request`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val routes = route(GET("/other"), handler).andOther(resources("/static/**", location))
            val client = clientFor(routerFunctions = listOf(routes))

            assertEquals(setOf("GET /other") + RESOURCE_ENDPOINTS("/static"), identities(registry))
            assertTrue(registry.endpoints().all { it.discoverySource == EndpointDiscoverySource.REGISTRATION })

            client.send(HttpMethod.GET, "/static/app.js")
            client.send(HttpMethod.GET, "/static/app.js")
            client.send(HttpMethod.HEAD, "/static/app.js")
            client.send(HttpMethod.GET, "/static/missing.js")
            client.send(HttpMethod.POST, "/static/app.js")

            assertEquals(mapOf("GET /static/*" to 2L, "HEAD /static/*" to 1L), hits(registry))
            assertEquals(
                setOf("GET /other") + RESOURCE_ENDPOINTS("/static"),
                identities(registry),
                "a miss and a refused verb add no endpoint",
            )
        }
    }

    @Test
    fun `a nested resources route keeps its prefix`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val routes = route(GET("/other"), handler).andNest(path("/assets"), resources("/**", location))
            val client = clientFor(routerFunctions = listOf(routes))

            assertEquals(setOf("GET /other") + RESOURCE_ENDPOINTS("/assets"), identities(registry))

            client.send(HttpMethod.GET, "/assets/app.js")

            assertEquals(mapOf("GET /assets/*" to 1L), hits(registry))
            assertEquals(setOf("GET /other") + RESOURCE_ENDPOINTS("/assets"), identities(registry))
        }
    }

    @Test
    fun `a resources route reached through two nest paths counts under the one the request came through`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val routes = route(GET("/other"), handler).andNest(path("/a").or(path("/b")), resources("/**", location))
            val client = clientFor(routerFunctions = listOf(routes))

            client.send(HttpMethod.GET, "/b/app.js")
            client.send(HttpMethod.GET, "/b/app.js")
            client.send(HttpMethod.GET, "/a/app.js")

            assertEquals(mapOf("GET /b/*" to 2L, "GET /a/*" to 1L), hits(registry))
        }
    }

    @Test
    fun `a filtered resources route inside a nest counts once and adds no endpoint`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val routes =
                RouterFunctions
                    .route()
                    .path("/assets") { builder -> builder.resources("/**", location) }
                    .filter { request, next -> next.handle(request) }
                    .build()
            val client = clientFor(routerFunctions = listOf(routes))
            val declared = identities(registry)

            client.send(HttpMethod.GET, "/assets/app.js")

            assertEquals(mapOf("GET /assets/*" to 1L), hits(registry))
            assertEquals(declared, identities(registry))
        }
    }

    @Test
    fun `a resources route over a lookup function of another class is not declared`() {
        withModule { registry ->
            val lookup = Function<ServerRequest, Mono<Resource>> { Mono.empty() }
            val routes = route(GET("/other"), handler).andOther(resources(lookup))
            clientFor(routerFunctions = listOf(routes))

            assertEquals(setOf("GET /other"), identities(registry))
        }
    }

    @Test
    fun `a resource a composed lookup serves inside a nest is not counted as the nest's own route`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val index = FileUrlResource(staticDirectory().resolve("app.js").toUri().toURL())
            val lookup = RouterFunctions.resourceLookupFunction("/**", location).andThen { found -> found.switchIfEmpty(Mono.just(index)) }
            val routes =
                RouterFunctions
                    .route()
                    .path("/docs") { builder -> builder.resources(lookup).GET(handler) }
                    .build()
            val client = clientFor(routerFunctions = listOf(routes))

            client.send(HttpMethod.GET, "/docs/missing.js")

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `a resources route over a predicate is declared from it and counted`() {
        val resource = RouterFunctions::class.java.methods.firstOrNull { it.name == "resource" && it.parameterCount == 2 }
        assumeTrue(resource != null, "resources(predicate, resource) arrived in Spring 6.1.4")
        withModule { registry ->
            val file = staticDirectory().resolve("app.js")
            val index = resource!!.invoke(null, GET("/index.html") as RequestPredicate, FileUrlResource(file.toUri().toURL()))

            @Suppress("UNCHECKED_CAST")
            val routes = route(GET("/other"), handler).andOther(index as RouterFunction<ServerResponse>)
            val client = clientFor(routerFunctions = listOf(routes))

            assertEquals(setOf("GET /other", "GET /index.html"), identities(registry))

            client.send(HttpMethod.GET, "/index.html")

            assertEquals(mapOf("GET /index.html" to 1L), hits(registry))
        }
    }

    @Test
    fun `a preflight on a resources route is no call`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val client = clientFor(routerFunctions = listOf(resources("/static/**", location)))

            client.send(HttpMethod.OPTIONS, "/static/app.js", PREFLIGHT_HEADERS)

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `a preflight on a nested resources route is no call`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val client =
                clientFor(routerFunctions = listOf(route(GET("/other"), handler).andNest(path("/assets"), resources("/**", location))))

            client.send(HttpMethod.OPTIONS, "/assets/app.js", PREFLIGHT_HEADERS)

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `a resource lookup applied to a request with no exchange leaves the module on`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            RouterFunctions
                .resourceLookupFunction("/static/**", location)
                .apply(MockServerRequest.builder().uri(URI.create("http://localhost/static/app.js")).build())
                .block()

            assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
        }
    }

    @Test
    fun `the base path is left out of the template`() {
        withModule { registry ->
            val context = buildWebFluxContext(controllers = listOf(TestController::class.java))
            val httpHandler = WebHttpHandlerBuilder.applicationContext(context).build()
            val composite = ContextPathCompositeHandler(mapOf("/shop" to httpHandler))
            val client = WebTestClient.bindToServer(HttpHandlerConnector(composite)).baseUrl("http://localhost").build()

            client.method(HttpMethod.GET).uri("/shop/checkout/42").exchange()

            assertEquals(mapOf("GET /checkout/{id}" to 1L), hits(registry))
        }
    }
}
