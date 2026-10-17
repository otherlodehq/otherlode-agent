package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointLocation
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.reactive.function.server.HandlerFilterFunction
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates.GET
import org.springframework.web.reactive.function.server.RequestPredicates.POST
import org.springframework.web.reactive.function.server.RequestPredicates.method
import org.springframework.web.reactive.function.server.RequestPredicates.path
import org.springframework.web.reactive.function.server.RequestPredicates.queryParam
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.result.method.RequestMappingInfo
import reactor.core.publisher.Mono
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR =
    "(Lorg/springframework/web/reactive/function/server/ServerRequest;)Lreactor/core/publisher/Mono;"

/** A functional route's handler, passed by reference. */
fun referencedHandler(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()

/** A handler lambda spun before the lambda factory hook installs, in its own function so the class is spun before any test installs. */
private fun spunBeforeHook(): HandlerFunction<ServerResponse> = HandlerFunction { ServerResponse.ok().build() }

/**
 * Proves [SpringWebFluxModule] end to end against a real `DispatcherHandler`, driven through
 * `WebTestClient.bindToApplicationContext`, which uses the application's own handler mappings.
 * Spring's handler-mapping types are never named as a compile-time type in these sources, so none
 * loads before the module installs. The same sources run against Spring 5.3, 6 and 7.
 */
class SpringWebFluxModuleTest {
    @Test
    fun `annotated registration and dispatch are tracked end to end`() {
        withModule { registry ->
            val client =
                WebTestClient
                    .bindToApplicationContext(
                        buildWebFluxContext(
                            controllers =
                                listOf(
                                    TestController::class.java,
                                    InheritingController::class.java,
                                    ItemsController::class.java,
                                    RootController::class.java,
                                ),
                        ),
                    ).build()

            client.send(HttpMethod.GET, "/checkout/42")
            client.send(HttpMethod.GET, "/checkout/42")
            client.send(HttpMethod.GET, "/a")
            client.send(HttpMethod.GET, "/any")
            client.send(HttpMethod.GET, "/inherited")
            client.send(HttpMethod.GET, "/items/7")

            val endpoints = registry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf(
                    "GET /checkout/{id}",
                    "POST /promo",
                    "GET /a",
                    "POST /a",
                    "GET /b",
                    "POST /b",
                    "* /any",
                    "GET /inherited",
                    "GET /items/{id}",
                    "GET /items/*",
                    "GET /",
                ),
                byIdentity.keys,
            )

            assertEquals(BaseController::class.java.name, byIdentity.getValue("GET /inherited").handlerClass)
            assertEquals(
                mapOf(
                    "GET /checkout/{id}" to 2L,
                    "GET /a" to 1L,
                    "* /any" to 1L,
                    "GET /inherited" to 1L,
                    "GET /items/{id}" to 1L,
                ),
                hits(registry),
                "a request counts once, against the best of the patterns it matches",
            )

            val controllerClassName = TestController::class.java.name
            for (endpoint in endpoints.filter { it.routeTemplate in setOf("/checkout/{id}", "/promo", "/a", "/b", "/any") }) {
                assertEquals("spring-webflux", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertEquals(controllerClassName, endpoint.handlerClass)
                assertNotNull(endpoint.handlerMethod, "handler method missing for ${endpoint.verbatimTemplate}")
                assertNotNull(endpoint.handlerDescriptor, "handler descriptor missing for ${endpoint.verbatimTemplate}")
            }
            val checkoutDescriptor = MethodType.methodType(String::class.java, String::class.java).toMethodDescriptorString()
            assertEquals(checkoutDescriptor, byIdentity.getValue("GET /checkout/{id}").handlerDescriptor)
            assertEquals("checkout", byIdentity.getValue("GET /checkout/{id}").handlerMethod)
        }
    }

    @Test
    fun `a mapping with no path declares the root and counts a request to it`() {
        withModule { registry ->
            val client =
                WebTestClient
                    .bindToApplicationContext(
                        buildWebFluxContext(controllers = listOf(RootController::class.java)),
                    ).build()

            assertEquals(setOf("GET /"), identities(registry))

            client.send(HttpMethod.GET, "/")

            assertEquals(mapOf("GET /" to 1L), hits(registry))
        }
    }

    @Test
    fun `a mapping registered in code is declared and counted`() {
        withModule { registry ->
            val context = buildWebFluxContext(controllers = listOf(TestController::class.java))
            val mapping = context.getBean("requestMappingHandlerMapping")
            val registerMapping = mapping.javaClass.methods.first { it.name == "registerMapping" && it.parameterCount == 3 }
            registerMapping.invoke(
                mapping,
                RequestMappingInfo.paths("/registered").methods(RequestMethod.GET).build(),
                ProgrammaticHandler(),
                ProgrammaticHandler::class.java.getMethod("registered"),
            )

            val registered = registry.endpoints().single { it.routeTemplate == "/registered" }
            assertEquals("GET", registered.verb)
            assertEquals(EndpointDiscoverySource.REGISTRATION, registered.discoverySource)
            assertEquals(ProgrammaticHandler::class.java.name, registered.handlerClass)
            assertEquals("registered", registered.handlerMethod)

            WebTestClient.bindToApplicationContext(context).build().send(HttpMethod.GET, "/registered")

            assertEquals(mapOf("GET /registered" to 1L), hits(registry))
        }
    }

    @Test
    fun `URL-mapped handlers are declared and counted beside annotated ones`() {
        withModule { registry ->
            val staticHandler = urlMappedWebHandler()
            val legacyHandler = urlMappedWebHandler()
            val context =
                buildWebFluxContext(
                    controllers = listOf(TestController::class.java),
                    urlMap = mapOf("/static/**" to staticHandler, "legacy/old" to legacyHandler),
                )
            val client = WebTestClient.bindToApplicationContext(context).build()

            client.send(HttpMethod.GET, "/checkout/42")
            client.send(HttpMethod.GET, "/static/app.js")
            client.send(HttpMethod.GET, "/static/app.js")
            client.send(HttpMethod.GET, "/legacy/old")
            client.send(HttpMethod.GET, "/unmapped/a")

            val byIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf(
                    "GET /checkout/{id}",
                    "POST /promo",
                    "GET /a",
                    "POST /a",
                    "GET /b",
                    "POST /b",
                    "* /any",
                    "* /static/*",
                    "* /legacy/old",
                ),
                byIdentity.keys,
                "a request no registered path matches adds no endpoint",
            )
            val staticEndpoint = byIdentity.getValue("* /static/*")
            assertEquals(EndpointDiscoverySource.REGISTRATION, staticEndpoint.discoverySource)
            assertEquals(staticHandler.javaClass.name, staticEndpoint.handlerClass)
            assertNull(staticEndpoint.handlerMethod)
            assertEquals(legacyHandler.javaClass.name, byIdentity.getValue("* /legacy/old").handlerClass)

            assertEquals(
                mapOf("GET /checkout/{id}" to 1L, "* /static/*" to 2L, "* /legacy/old" to 1L),
                hits(registry),
            )
        }
    }

    /**
     * Handlers A and C are object expressions, which always compile to a named class. The lambdas
     * and the reference are hidden classes, named through the lambda factory hook.
     */
    @Test
    fun `functional routes are declared through RouterFunction and counted at dispatch`() {
        val early = spunBeforeHook()
        withModule { registry ->
            val handlerA =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
                }
            val handlerB = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerC =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
                }
            val handlerD = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerE = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerF = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val byReference = HandlerFunction(::referencedHandler)
            val handlerG =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
                }

            val routerFunction =
                route(GET("/fn/{id}"), handlerA)
                    .andRoute(POST("/fn"), handlerB)
                    .andNest(
                        path("/api"),
                        route(GET("/items"), handlerC)
                            .andRoute(GET("/items/{id}").or(GET("/things/{id}")), handlerD)
                            .andRoute(method(HttpMethod.DELETE), handlerG),
                    ).andRoute(method(HttpMethod.GET).and(queryParam("custom", "1")), handlerE)
                    .andRoute(GET("/by-reference"), byReference)
                    .andRoute(GET("/early"), early)
                    .andRoute(path("/any-verb"), handlerG)
                    .and(route(GET("/filtered"), handlerG).filter(HandlerFilterFunction { request, next -> next.handle(request) }))
                    .andRoute(path("/discovered").and(RequestPredicate { true }), handlerF)

            val client = WebTestClient.bindToApplicationContext(buildWebFluxContext(routerFunctions = listOf(routerFunction))).build()

            val declared = registry.endpoints()
            val declaredByIdentity = declared.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf(
                    "GET /fn/{id}",
                    "POST /fn",
                    "GET /api/items",
                    "GET /api/items/{id}",
                    "GET /api/things/{id}",
                    "GET /by-reference",
                    "GET /early",
                    "* /any-verb",
                    "DELETE /api",
                    "GET /filtered",
                ),
                declaredByIdentity.keys,
            )
            for (endpoint in declared) {
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertEquals("spring-webflux", endpoint.framework)
            }
            assertFalse(handlerA.javaClass.isHidden)
            assertTrue(handlerB.javaClass.isHidden)
            val fnById = declaredByIdentity.getValue("GET /fn/{id}")
            assertEquals(handlerA.javaClass.name, fnById.handlerClass)
            assertEquals("handle", fnById.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, fnById.handlerDescriptor)
            assertEquals(handlerC.javaClass.name, declaredByIdentity.getValue("GET /api/items").handlerClass)
            assertJoinsLambdaBody(declaredByIdentity.getValue("POST /fn"))
            assertJoinsLambdaBody(declaredByIdentity.getValue("GET /api/items/{id}"))
            val referenced = declaredByIdentity.getValue("GET /by-reference")
            assertEquals("${javaClass.name}Kt", referenced.handlerClass, "a reference joins to the function it names")
            assertEquals("referencedHandler", referenced.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, referenced.handlerDescriptor)
            assertNull(declaredByIdentity.getValue("GET /early").handlerClass, "a lambda spun before the hook was not recorded")

            client.send(HttpMethod.GET, "/fn/1")
            client.send(HttpMethod.GET, "/fn/1")
            client.send(HttpMethod.POST, "/fn")
            client.send(HttpMethod.GET, "/api/items")
            client.send(HttpMethod.GET, "/api/things/9")
            client.send(HttpMethod.GET, "/discovered")
            client.send(HttpMethod.POST, "/api/items")
            client.send(HttpMethod.DELETE, "/api")
            client.send(HttpMethod.GET, "/filtered")
            val keysAfterFirstFilteredCall = registry.boundKeyCount()
            client.send(HttpMethod.GET, "/filtered")
            client.send(HttpMethod.GET, "/filtered")
            assertEquals(keysAfterFirstFilteredCall, registry.boundKeyCount(), "a filtered route binds no key per request")
            client.send(HttpMethod.OPTIONS, "/fn/1", PREFLIGHT_HEADERS)

            assertEquals(
                mapOf(
                    "GET /fn/{id}" to 2L,
                    "POST /fn" to 1L,
                    "GET /api/items" to 1L,
                    "GET /api/things/{id}" to 1L,
                    "DELETE /api" to 1L,
                    "GET /filtered" to 3L,
                    "GET /discovered" to 1L,
                ),
                hits(registry),
            )
            val discovered = registry.endpoints().single { it.routeTemplate == "/discovered" }
            assertEquals(EndpointDiscoverySource.DISPATCH, discovered.discoverySource)
            assertJoinsLambdaBody(discovered)
        }
    }

    @Test
    fun `a RouterFunctionMapping built with a router function declares its routes`() {
        withModule { registry ->
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            initialisedRouterFunctionMapping(route(GET("/built/{id}"), handler))

            assertEquals(setOf("GET /built/{id}"), identities(registry))
            assertEquals(EndpointDiscoverySource.REGISTRATION, registry.endpoints().single().discoverySource)
        }
    }

    private fun assertJoinsLambdaBody(endpoint: EndpointLocation) {
        val route = "${endpoint.verb} ${endpoint.routeTemplate}"
        assertEquals(javaClass.name, endpoint.handlerClass, "$route: handler class")
        val method = assertNotNull(endpoint.handlerMethod, "$route: handler method")
        assertTrue("\$lambda\$" in method, "$route: $method should be a lambda body")
        assertEquals(HANDLE_DESCRIPTOR, endpoint.handlerDescriptor, "$route: handler descriptor")
    }
}
