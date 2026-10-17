package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RequestPredicates.GET
import org.springframework.web.reactive.function.server.RequestPredicates.path
import org.springframework.web.reactive.function.server.RequestPredicates.queryParam
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives [SpringWebFluxModule.declare] against real reactive `RouterFunction` objects, one
 * predicate shape per test, to pin what each arm of the shared walk resolves a route's identity
 * to under the reactive names. `declare` is called directly, not through the woven
 * `afterPropertiesSet`, so no handler-mapping class loads here.
 */
class SpringWebFluxFunctionalRouteWalkTest {
    private val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }

    private class OpaquePredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true
    }

    private class ChattyPredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true

        override fun accept(visitor: RequestPredicates.Visitor) {
            visitor.toString()
            visitor.hashCode()
            @Suppress("EqualsBetweenInconvertibleTypes")
            visitor.equals(visitor)
            visitor.path("/probe")
        }
    }

    private class OpaqueRouterFunction : RouterFunction<ServerResponse> {
        override fun route(request: ServerRequest): Mono<HandlerFunction<ServerResponse>> = Mono.empty()
    }

    @Test
    fun `a queryParam predicate narrows nothing, leaving the path beside it as the identity`() {
        val declared = declaredIdentities(route(path("/orders").and(queryParam("mine", "1")), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `a negated queryParam predicate beside a path leaves the path as the identity`() {
        val declared = declaredIdentities(route(path("/orders").and(queryParam("mine", "1").negate()), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `an or of a path and a queryParam predicate declares only the alternative that has a path`() {
        val declared = declaredIdentities(route(path("/orders").or(queryParam("mine", "1")), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `a verb and a path declare one route and a nest prefixes every route inside it`() {
        val nested = route(GET("/items"), handler).andRoute(RequestPredicates.POST("/items/{id}"), handler)

        val declared = declaredIdentities(RouterFunctions.nest(path("/api").or(path("/v2")), nested))

        assertEquals(setOf("GET /api/items", "POST /api/items/{id}", "GET /v2/items", "POST /v2/items/{id}"), declared)
    }

    @Test
    fun `a nest whose predicate carries no path contributes no prefix segment`() {
        val declared = declaredIdentities(RouterFunctions.nest(queryParam("tenant", "acme"), route(GET("/items"), handler)))

        assertEquals(setOf("GET /items"), declared)
    }

    @Test
    fun `a predicate the walk cannot interpret leaves the route undeclared, to be found at dispatch`() {
        assertEquals(emptySet(), declaredIdentities(route(OpaquePredicate(), handler)))
        assertEquals(emptySet(), declaredIdentities(route(path("/orders").and(OpaquePredicate()), handler)))
    }

    @Test
    fun `a router function Spring cannot describe declares nothing and does not fail the walk`() {
        assertEquals(emptySet(), declaredIdentities(OpaqueRouterFunction()))
    }

    @Test
    fun `a resources route declares its pattern with the verbs its handler answers, under each nest prefix`() {
        assertEquals(
            setOf("GET /static/*", "HEAD /static/*", "OPTIONS /static/*"),
            declaredIdentities { RouterFunctions.resources("/static/**", ClassPathResource("static/")) },
        )
        assertEquals(
            setOf("GET /assets/*", "HEAD /assets/*", "OPTIONS /assets/*"),
            declaredIdentities {
                RouterFunctions.route().path("/assets") { builder -> builder.resources("/**", ClassPathResource("static/")) }.build()
            },
        )
    }

    @Test
    fun `a route carrying attributes is declared, and its attributes are ignored`() {
        val withAttributes =
            RouterFunctions
                .route()
                .GET("/audited", handler)
                .withAttribute("audit", true)
                .build()

        assertEquals(setOf("GET /audited"), declaredIdentities(withAttributes))
    }

    @Test
    fun `the predicate visitor answers Object's own methods rather than failing the walk`() {
        assertEquals(setOf("* /probe"), declaredIdentities(route(ChattyPredicate(), handler)))
    }

    @Test
    fun `a method predicate alone inside a nest is declared at the nest's path`() {
        val nested = route(RequestPredicates.method(HttpMethod.DELETE), handler)

        assertEquals(setOf("DELETE /api"), declaredIdentities(RouterFunctions.nest(path("/api"), nested)))
    }

    @Test
    fun `declare ignores an object from the application's own loader that is not a RouterFunction`() {
        assertEquals(emptySet(), declaredIdentities(ServerResponse.ok().build()))
    }

    @Test
    fun `transform returns the builder untouched for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)
        val advice = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        val returned =
            SpringWebFluxModule().transform(
                builder,
                TypeDescription.ForLoadedType.of(UnrelatedType::class.java),
                advice,
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unchanged, not visited")
    }

    private class UnrelatedType

    private fun declaredIdentities(frameworkObject: Any): Set<String> = declaredIdentities { frameworkObject }

    private fun declaredIdentities(frameworkObject: () -> Any): Set<String> {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebFluxModule()))
        val transformer = endpointInstrumentation.install(instrumentation)
        try {
            SpringWebFluxModule().declare(frameworkObject())
            val endpoints = registry.endpoints()
            assertTrue(
                endpoints.all { it.framework == "spring-webflux" },
                "every declared endpoint belongs to this module: $endpoints",
            )
            return endpoints.map { "${it.verb} ${it.routeTemplate}" }.toSet()
        } finally {
            instrumentation.removeTransformer(transformer)
        }
    }
}
