package dev.otherlode.instrumentation.endpoints.springwebmvc

import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointLocation
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.function.HandlerFilterFunction
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates.GET
import org.springframework.web.servlet.function.RequestPredicates.POST
import org.springframework.web.servlet.function.RequestPredicates.method
import org.springframework.web.servlet.function.RequestPredicates.param
import org.springframework.web.servlet.function.RequestPredicates.path
import org.springframework.web.servlet.function.RouterFunctions.route
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import java.lang.invoke.MethodType
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR =
    "(Lorg/springframework/web/servlet/function/ServerRequest;)Lorg/springframework/web/servlet/function/ServerResponse;"

fun referencedHandler(request: ServerRequest): ServerResponse = ServerResponse.ok().build()

private fun spunBeforeHook(): HandlerFunction<ServerResponse> = HandlerFunction { ServerResponse.ok().build() }

// install must run before Spring's handler-mapping classes load, so no field or companion may reference them.
class SpringWebMvcModuleTest {
    @Test
    fun `registration and dispatch through a real DispatcherServlet are tracked end to end`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            val controller = TestController()
            val mockMvc = MockMvcBuilders.standaloneSetup(controller, InheritingController(), ItemsController()).build()

            mockMvc.perform(get("/checkout/42"))
            mockMvc.perform(get("/checkout/42"))
            mockMvc.perform(get("/a"))
            mockMvc.perform(get("/any"))
            mockMvc.perform(get("/inherited"))
            mockMvc.perform(get("/items/7"))

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
                ),
                byIdentity.keys,
            )

            assertEquals(BaseController::class.java.name, byIdentity.getValue("GET /inherited").handlerClass)

            val deltasByIdentity =
                registry.computeDeltas(maxPerBatch = 20).flatMap { it.deltas }.associate { delta ->
                    endpoints.single { it.endpointId == delta.endpointId }.let { "${it.verb} ${it.routeTemplate}" } to delta.hitsTotal
                }
            assertEquals(1L, deltasByIdentity["GET /items/{id}"], "deltas: $deltasByIdentity")
            assertNull(deltasByIdentity["GET /items/*"], "deltas: $deltasByIdentity")

            val controllerClassName = controller.javaClass.name
            for (endpoint in endpoints.filter { it.routeTemplate != "/inherited" && !it.routeTemplate.startsWith("/items") }) {
                assertEquals("spring-webmvc", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertEquals(controllerClassName, endpoint.handlerClass)
                assertNotNull(endpoint.handlerMethod, "handler method missing for ${endpoint.verbatimTemplate}")
                assertNotNull(endpoint.handlerDescriptor, "handler descriptor missing for ${endpoint.verbatimTemplate}")
            }

            val checkoutDescriptor = MethodType.methodType(String::class.java, String::class.java).toMethodDescriptorString()
            assertEquals(checkoutDescriptor, byIdentity.getValue("GET /checkout/{id}").handlerDescriptor)

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(byIdentity.getValue("GET /checkout/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /a").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("* /any").endpointId).hitsTotal)
            assertTrue(byIdentity.getValue("POST /promo").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("POST /a").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("GET /b").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("POST /b").endpointId !in deltasById)

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `URL-mapped handlers are declared and counted alongside annotation-mapped ones`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            val staticHandler = urlMappedTestHandler("static content")
            val legacyHandler = urlMappedTestHandler("legacy content")

            val fallbackHandler = urlMappedTestHandler("fallback content")

            val context = buildUrlMappedWebApplicationContext(staticHandler, legacyHandler, fallbackHandler)
            val annotationMapping = context.getBean("requestMappingHandlerMapping")
            annotationMapping.javaClass
                .getMethod("registerMapping", RequestMappingInfo::class.java, Any::class.java, java.lang.reflect.Method::class.java)
                .invoke(
                    annotationMapping,
                    RequestMappingInfo.paths("/registered").methods(RequestMethod.GET).build(),
                    ProgrammaticHandler(),
                    ProgrammaticHandler::class.java.getMethod("registered"),
                )
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            mockMvc.perform(get("/checkout/42"))
            mockMvc.perform(get("/static/app.js"))
            mockMvc.perform(get("/static/app.js"))
            mockMvc.perform(get("/legacy/old"))
            mockMvc.perform(get("/registered"))
            mockMvc.perform(get("/unmapped/a"))
            mockMvc.perform(get("/unmapped/b"))

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
                    "* /static/*",
                    "* /legacy/old",
                    "* /*",
                    "GET /registered",
                ),
                byIdentity.keys,
            )

            val staticEndpoint = byIdentity.getValue("* /static/*")
            assertEquals(EndpointDiscoverySource.REGISTRATION, staticEndpoint.discoverySource)
            assertEquals(staticHandler.javaClass.name, staticEndpoint.handlerClass)
            assertNull(staticEndpoint.handlerMethod)

            val legacyEndpoint = byIdentity.getValue("* /legacy/old")
            assertEquals(EndpointDiscoverySource.REGISTRATION, legacyEndpoint.discoverySource)
            assertEquals(legacyHandler.javaClass.name, legacyEndpoint.handlerClass)
            assertNull(legacyEndpoint.handlerMethod)

            val checkoutEndpoint = byIdentity.getValue("GET /checkout/{id}")
            assertEquals(EndpointDiscoverySource.REGISTRATION, checkoutEndpoint.discoverySource)
            assertEquals(TestController::class.java.name, checkoutEndpoint.handlerClass)

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(staticEndpoint.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(legacyEndpoint.endpointId).hitsTotal)
            assertEquals(2L, deltasById.getValue(byIdentity.getValue("* /*").endpointId).hitsTotal)
            val registered = byIdentity.getValue("GET /registered")
            assertEquals(EndpointDiscoverySource.REGISTRATION, registered.discoverySource)
            assertEquals(ProgrammaticHandler::class.java.name, registered.handlerClass)
            assertEquals("registered", registered.handlerMethod)
            assertEquals(1L, deltasById.getValue(registered.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(checkoutEndpoint.endpointId).hitsTotal)
            assertTrue(byIdentity.getValue("POST /promo").endpointId !in deltasById)

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `functional routes are declared through RouterFunction and counted at dispatch`() {
        val early = spunBeforeHook()
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            val handlerA =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
                }
            val handlerB = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerC =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
                }
            val handlerD = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerE = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerF = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val byReference = HandlerFunction(::referencedHandler)
            val handlerG =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
                }

            val routerFunction =
                route(GET("/fn/{id}"), handlerA)
                    .andRoute(POST("/fn"), handlerB)
                    .andNest(
                        path("/api"),
                        route(GET("/items"), handlerC)
                            .andRoute(GET("/items/{id}").or(GET("/things/{id}")), handlerD)
                            .andRoute(method(HttpMethod.DELETE), handlerG),
                    ).andRoute(method(HttpMethod.GET).and(param("custom", "1")), handlerE)
                    .andRoute(GET("/by-reference"), byReference)
                    .andRoute(GET("/early"), early)
                    .andRoute(path("/any-verb"), handlerG)
                    .and(route(GET("/filtered"), handlerG).filter(HandlerFilterFunction { request, next -> next.handle(request) }))
                    .andRoute(path("/discovered").and(RequestPredicate { true }), handlerF)

            val context = buildFunctionalWebApplicationContext(routerFunction)
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

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
                assertEquals("spring-webmvc", endpoint.framework)
            }
            assertFalse(handlerA.javaClass.isHidden, "handlerA is an object expression and must not be a hidden class")
            assertTrue(handlerB.javaClass.isHidden, "handlerB is a SAM-converted lambda and must be a hidden class")
            assertEquals(handlerA.javaClass.name, declaredByIdentity.getValue("GET /fn/{id}").handlerClass)
            assertJoinsLambdaBody(declaredByIdentity.getValue("POST /fn"))
            assertEquals(handlerC.javaClass.name, declaredByIdentity.getValue("GET /api/items").handlerClass)
            assertJoinsLambdaBody(declaredByIdentity.getValue("GET /api/items/{id}"))
            assertJoinsLambdaBody(declaredByIdentity.getValue("GET /api/things/{id}"))
            val referenced = declaredByIdentity.getValue("GET /by-reference")
            assertEquals("${javaClass.name}Kt", referenced.handlerClass, "a reference joins to the function it names")
            assertEquals("referencedHandler", referenced.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, referenced.handlerDescriptor)
            assertTrue(early.javaClass.isHidden)
            assertNull(declaredByIdentity.getValue("GET /early").handlerClass, "a lambda spun before the hook was not recorded")

            mockMvc.perform(get("/fn/1"))
            mockMvc.perform(get("/fn/1"))
            mockMvc.perform(post("/fn"))
            mockMvc.perform(get("/api/items"))
            mockMvc.perform(get("/api/things/9"))
            mockMvc.perform(get("/anything").param("custom", "1"))
            mockMvc.perform(get("/discovered"))
            mockMvc.perform(get("/early"))
            mockMvc.perform(request("PROPFIND", URI.create("/any-verb")))
            mockMvc.perform(post("/api/items"))
            mockMvc.perform(delete("/api"))
            mockMvc.perform(get("/filtered"))
            val keysAfterFirstFilteredCall = registry.boundKeyCount()
            mockMvc.perform(get("/filtered"))
            mockMvc.perform(get("/filtered"))
            assertEquals(keysAfterFirstFilteredCall, registry.boundKeyCount(), "a filtered route binds no key per request")
            mockMvc.perform(options("/fn/1").header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET"))

            val afterDispatch = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
            val deltasById = registry.computeDeltas(maxPerBatch = 20).flatMap { it.deltas }.associateBy { it.endpointId }

            assertEquals(2L, deltasById.getValue(afterDispatch.getValue("GET /fn/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("POST /fn").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("GET /api/items").endpointId).hitsTotal)
            assertTrue(afterDispatch.getValue("GET /api/items/{id}").endpointId !in deltasById)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("GET /api/things/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("* /any-verb").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("DELETE /api").endpointId).hitsTotal)
            assertEquals(3L, deltasById.getValue(afterDispatch.getValue("GET /filtered").endpointId).hitsTotal)

            val fnById = afterDispatch.getValue("GET /fn/{id}")
            assertEquals(handlerA.javaClass.name, fnById.handlerClass)
            assertEquals("handle", fnById.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, fnById.handlerDescriptor)
            val itemsById = afterDispatch.getValue("GET /api/items")
            assertEquals(handlerC.javaClass.name, itemsById.handlerClass)
            assertEquals("handle", itemsById.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, itemsById.handlerDescriptor)

            assertJoinsLambdaBody(afterDispatch.getValue("POST /fn"))
            assertJoinsLambdaBody(afterDispatch.getValue("GET /api/things/{id}"))
            val discovered = afterDispatch.getValue("GET /discovered")
            assertEquals(EndpointDiscoverySource.DISPATCH, discovered.discoverySource)
            assertJoinsLambdaBody(discovered)
            assertNull(afterDispatch.getValue("GET /early").handlerClass, "a dispatch adds no join a registration did not have")

            // The param-only route has no path predicate, so Spring sets no matching pattern and dispatch
            // discovers nothing for it.
            assertEquals(
                declaredByIdentity.keys + "GET /discovered",
                registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }.keys,
            )

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
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
