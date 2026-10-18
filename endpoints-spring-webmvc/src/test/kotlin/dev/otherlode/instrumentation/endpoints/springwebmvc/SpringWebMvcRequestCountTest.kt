package dev.otherlode.instrumentation.endpoints.springwebmvc

import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.core.io.FileUrlResource
import org.springframework.core.io.Resource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates.GET
import org.springframework.web.servlet.function.RequestPredicates.path
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.RouterFunctions.resources
import org.springframework.web.servlet.function.RouterFunctions.route
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that a request counts once and that a preflight is no call, on every route kind, against
 * a real `DispatcherServlet` and a real `HandlerMappingIntrospector`. It also proves the
 * `resources(...)` and constructor-built `RouterFunctionMapping` declarations. A hook woven with
 * the servlet package the framework does not have would fail to weave and switch the module off,
 * so each case also asserts the module is still on: the javax variant runs under Spring 5.3 and
 * the jakarta one under 6 and 7.
 */
class SpringWebMvcRequestCountTest {
    private fun <T> withModule(block: (EndpointRegistry) -> T): T {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)
        try {
            val result = block(registry)
            assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
            return result
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }

    private fun hits(registry: EndpointRegistry): Map<String, Long> {
        val endpoints = registry.endpoints().associateBy { it.endpointId }
        return registry
            .computeDeltas(maxPerBatch = 100)
            .flatMap { it.deltas }
            .associate { delta -> endpoints.getValue(delta.endpointId).let { "${it.verb} ${it.routeTemplate}" } to delta.hitsTotal }
    }

    private fun identities(registry: EndpointRegistry): Set<String> = registry.endpoints().map { "${it.verb} ${it.routeTemplate}" }.toSet()

    private fun urlMappedContext() =
        buildUrlMappedWebApplicationContext(
            urlMappedTestHandler("static content"),
            urlMappedTestHandler("legacy content"),
            urlMappedTestHandler("fallback content"),
        )

    @Test
    fun `an annotated and a URL-mapped request count once when an introspector lookup runs too`() {
        withModule { registry ->
            val context = urlMappedContext()
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            introspectorLookup(context, "/checkout/42")
            introspectorLookup(context, "/static/app.js")
            introspectorLookup(context, "/unmapped/a")
            assertEquals(emptyMap(), hits(registry), "an introspector lookup alone is no call")

            introspectorLookup(context, "/checkout/42")
            mockMvc.perform(get("/checkout/42"))
            introspectorLookup(context, "/static/app.js")
            mockMvc.perform(get("/static/app.js"))
            introspectorLookup(context, "/unmapped/a")
            mockMvc.perform(get("/unmapped/a"))

            assertEquals(mapOf("GET /checkout/{id}" to 1L, "* /static/*" to 1L, "* /*" to 1L), hits(registry))
        }
    }

    @Test
    fun `a functional request counts once when an introspector lookup runs too`() {
        withModule { registry ->
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val context = buildFunctionalWebApplicationContext(route(GET("/fn/{id}"), handler))
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            introspectorLookup(context, "/fn/1")
            assertEquals(emptyMap(), hits(registry), "an introspector lookup alone is no call")

            introspectorLookup(context, "/fn/1")
            mockMvc.perform(get("/fn/1"))

            assertEquals(mapOf("GET /fn/{id}" to 1L), hits(registry))
        }
    }

    @Test
    fun `a lookup made outside the dispatcher is no call on every route kind`() {
        withModule { registry ->
            val context = urlMappedContext()
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            lookupOutsideDispatcher(context, "/checkout/42")
            lookupOutsideDispatcher(context, "/static/app.js")
            lookupOutsideDispatcher(context, "/unmapped/a")
            assertEquals(emptyMap(), hits(registry), "a filter's lookup after the chain is no call")

            mockMvc.perform(get("/checkout/42"))

            assertEquals(mapOf("GET /checkout/{id}" to 1L), hits(registry))
        }
    }

    @Test
    fun `a functional lookup made outside the dispatcher is no call`() {
        withModule { registry ->
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val context = buildFunctionalWebApplicationContext(route(GET("/fn/{id}"), handler))
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            lookupOutsideDispatcher(context, "/fn/1")
            assertEquals(emptyMap(), hits(registry), "a filter's lookup after the chain is no call")

            mockMvc.perform(get("/fn/1"))

            assertEquals(mapOf("GET /fn/{id}" to 1L), hits(registry))
        }
    }

    @Test
    fun `an async request counts once`() {
        withModule { registry ->
            val mockMvc = MockMvcBuilders.standaloneSetup(AsyncController()).build()

            val started = mockMvc.perform(get("/async")).andReturn()
            started.asyncResult
            mockMvc.perform(asyncDispatch(started))

            assertEquals(mapOf("GET /async" to 1L), hits(registry))
        }
    }

    @Test
    fun `a preflight counts nothing on annotated and URL-mapped routes`() {
        withModule { registry ->
            val mockMvc = MockMvcBuilders.webAppContextSetup(urlMappedContext()).build()

            mockMvc.perform(options("/checkout/42").header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET"))
            mockMvc.perform(options("/static/app.js").header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET"))
            mockMvc.perform(options("/legacy/old").header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET"))

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `a preflight counts nothing on a functional route`() {
        withModule { registry ->
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(route(GET("/fn/{id}"), handler))).build()

            mockMvc.perform(options("/fn/1").header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET"))

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `an OPTIONS request without the CORS headers to an OPTIONS-mapped route still counts`() {
        withModule { registry ->
            val mockMvc = MockMvcBuilders.standaloneSetup(OptionsController()).build()

            mockMvc.perform(options("/opt"))
            mockMvc.perform(options("/opt").header("Origin", "http://example.com"))

            assertEquals(mapOf("OPTIONS /opt" to 2L), hits(registry))
        }
    }

    @Test
    fun `a root handler is counted against the root and the default handler against the wildcard`() {
        withModule { registry ->
            val mapped = urlMappedTestHandler("mapped root")
            val mockMvc =
                MockMvcBuilders
                    .webAppContextSetup(
                        buildUrlMappedWebApplicationContext(
                            urlMappedTestHandler("s"),
                            urlMappedTestHandler("l"),
                            urlMappedTestHandler("f"),
                            mappedRootHandler = mapped,
                        ),
                    ).build()

            mockMvc.perform(get("/"))
            mockMvc.perform(get("/nowhere"))

            assertEquals(mapOf("* /" to 1L, "* /*" to 1L), hits(registry))
        }
    }

    @Test
    fun `a root handler set directly is declared and counted against the root`() {
        withModule { registry ->
            val context =
                buildUrlMappedWebApplicationContext(
                    urlMappedTestHandler("s"),
                    urlMappedTestHandler("l"),
                    urlMappedTestHandler("f"),
                    directRootHandler = urlMappedTestHandler("welcome"),
                )
            assertTrue("* /" in identities(registry), "declared: ${identities(registry)}")
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            mockMvc.perform(get("/"))
            mockMvc.perform(get("/nowhere"))

            assertEquals(mapOf("* /" to 1L, "* /*" to 1L), hits(registry))
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

    private fun staticDirectory(): Path {
        val directory = Files.createTempDirectory("otherlode-static")
        Files.writeString(directory.resolve("app.js"), "console.log(1)")
        directory.toFile().deleteOnExit()
        directory.resolve("app.js").toFile().deleteOnExit()
        return directory
    }

    private fun directoryResource(directory: Path) = FileUrlResource(directory.toUri().toURL())

    @Test
    fun `a top-level resources route is declared with its verbs and counted once per served request`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val routes = route(GET("/other"), handler).andOther(resources("/static/**", location))
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            assertEquals(
                setOf("GET /other", "GET /static/*", "HEAD /static/*", "OPTIONS /static/*"),
                identities(registry),
            )
            assertTrue(registry.endpoints().all { it.discoverySource == EndpointDiscoverySource.REGISTRATION })

            mockMvc.perform(get("/static/app.js"))
            mockMvc.perform(get("/static/app.js"))
            mockMvc.perform(head("/static/app.js"))
            mockMvc.perform(get("/static/missing.js"))
            mockMvc.perform(post("/static/app.js"))

            assertEquals(mapOf("GET /static/*" to 2L, "HEAD /static/*" to 1L), hits(registry))
            assertEquals(
                setOf("GET /other", "GET /static/*", "HEAD /static/*", "OPTIONS /static/*"),
                identities(registry),
                "a miss and a refused verb add no endpoint",
            )
        }
    }

    @Test
    fun `a nested resources route keeps its prefix`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val routes = route(GET("/other"), handler).andNest(path("/assets"), resources("/**", location))
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            assertEquals(setOf("GET /other", "GET /assets/*", "HEAD /assets/*", "OPTIONS /assets/*"), identities(registry))

            mockMvc.perform(get("/assets/app.js"))

            assertEquals(mapOf("GET /assets/*" to 1L), hits(registry))
            assertEquals(setOf("GET /other", "GET /assets/*", "HEAD /assets/*", "OPTIONS /assets/*"), identities(registry))
        }
    }

    @Test
    fun `a resources route over a lookup function of another class is not declared`() {
        withModule { registry ->
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val lookup = Function<ServerRequest, Optional<Resource>> { Optional.empty() }
            val routes = route(GET("/other"), handler).andOther(resources(lookup))
            MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            assertEquals(setOf("GET /other"), identities(registry))
        }
    }

    @Test
    fun `a resources route reached through two nest paths counts under the one the request came through`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val routes = route(GET("/other"), handler).andNest(path("/a").or(path("/b")), resources("/**", location))
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            mockMvc.perform(get("/b/app.js"))
            mockMvc.perform(get("/b/app.js"))
            mockMvc.perform(get("/a/app.js"))

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
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()
            val declared = identities(registry)

            mockMvc.perform(get("/assets/app.js"))

            assertEquals(mapOf("GET /assets/*" to 1L), hits(registry))
            assertEquals(declared, identities(registry))
        }
    }

    @Test
    fun `a resources route over a predicate is declared from it and counted`() {
        val resource = RouterFunctions::class.java.methods.firstOrNull { it.name == "resource" && it.parameterCount == 2 }
        assumeTrue(resource != null, "resources(predicate, resource) arrived in Spring 6.1.4")
        withModule { registry ->
            val file = staticDirectory().resolve("app.js")
            val index = resource!!.invoke(null, GET("/index.html") as RequestPredicate, FileUrlResource(file.toUri().toURL()))
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val routes = route(GET("/other"), handler).andOther(index as RouterFunction<ServerResponse>)
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            assertEquals(setOf("GET /other", "GET /index.html"), identities(registry))

            mockMvc.perform(get("/index.html"))

            assertEquals(mapOf("GET /index.html" to 1L), hits(registry))
        }
    }

    @Test
    fun `an introspector lookup or a preflight on a resources route is no call`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val context = buildFunctionalWebApplicationContext(resources("/static/**", location))
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            introspectorLookup(context, "/static/app.js")
            mockMvc.perform(options("/static/app.js").header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET"))

            assertEquals(emptyMap(), hits(registry))
        }
    }

    @Test
    fun `the root without a root handler counts against the wildcard`() {
        withModule { registry ->
            val mockMvc = MockMvcBuilders.webAppContextSetup(urlMappedContext()).build()

            mockMvc.perform(get("/"))

            assertEquals(mapOf("* /*" to 1L), hits(registry))
        }
    }

    @Test
    fun `an async functional request counts once`() {
        withModule { registry ->
            val response = CompletableFuture<ServerResponse>()
            val handler = HandlerFunction<ServerResponse> { ServerResponse.async(response) }
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(route(GET("/fnasync"), handler))).build()

            val started = mockMvc.perform(get("/fnasync")).andReturn()
            assertTrue(started.request.isAsyncStarted)
            response.complete(ServerResponse.ok().build())
            started.asyncResult
            mockMvc.perform(asyncDispatch(started))

            assertEquals(mapOf("GET /fnasync" to 1L), hits(registry))
        }
    }

    @Test
    fun `a root handler named by its bean is counted against the root`() {
        withModule { registry ->
            val context =
                buildUrlMappedWebApplicationContext(
                    urlMappedTestHandler("s"),
                    urlMappedTestHandler("l"),
                    urlMappedTestHandler("f"),
                    lazyRootHandler = urlMappedTestHandler("lazy root"),
                )
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            mockMvc.perform(get("/"))

            assertEquals(mapOf("* /" to 1L), hits(registry))
        }
    }

    @Test
    fun `another mapping's default handler serving the root counts against the wildcard`() {
        withModule { registry ->
            val context =
                buildUrlMappedWebApplicationContext(
                    urlMappedTestHandler("s"),
                    urlMappedTestHandler("l"),
                    urlMappedTestHandler("f"),
                    directRootHandler = urlMappedTestHandler("welcome"),
                    earlierDefaultHandler = urlMappedTestHandler("catch-all"),
                )
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            mockMvc.perform(get("/"))

            assertEquals(mapOf("* /*" to 1L), hits(registry))
        }
    }

    @Test
    fun `a resource a composed lookup serves inside a nest is not counted as the nest's own route`() {
        withModule { registry ->
            val location = directoryResource(staticDirectory())
            val index = FileUrlResource(staticDirectory().resolve("app.js").toUri().toURL())
            val lookup = RouterFunctions.resourceLookupFunction("/**", location).andThen { found -> found.or { Optional.of(index) } }
            val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val routes =
                RouterFunctions
                    .route()
                    .path("/docs") { builder -> builder.resources(lookup).GET(handler) }
                    .build()
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            mockMvc.perform(get("/docs/missing.js"))

            assertEquals(emptyMap(), hits(registry))
        }
    }
}
