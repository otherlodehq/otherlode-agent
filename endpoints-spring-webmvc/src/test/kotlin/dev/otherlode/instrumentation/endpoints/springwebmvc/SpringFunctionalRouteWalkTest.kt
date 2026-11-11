package dev.otherlode.instrumentation.endpoints.springwebmvc

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RequestPredicates.GET
import org.springframework.web.servlet.function.RequestPredicates.param
import org.springframework.web.servlet.function.RequestPredicates.path
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.RouterFunctions.route
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpringFunctionalRouteWalkTest {
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

    private class ChattyRouterFunction(
        private val handler: HandlerFunction<ServerResponse>,
    ) : RouterFunction<ServerResponse> {
        override fun route(request: ServerRequest): Optional<HandlerFunction<ServerResponse>> = Optional.empty()

        override fun accept(visitor: RouterFunctions.Visitor) {
            visitor.toString()
            visitor.hashCode()
            @Suppress("EqualsBetweenInconvertibleTypes")
            visitor.equals(visitor)
            visitor.route(GET("/probe"), handler)
        }
    }

    private class OpaqueRouterFunction : RouterFunction<ServerResponse> {
        override fun route(request: ServerRequest): Optional<HandlerFunction<ServerResponse>> = Optional.empty()
    }

    private class SilentPredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true

        override fun accept(visitor: RequestPredicates.Visitor) = Unit
    }

    private class NoVerbPredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true

        override fun accept(visitor: RequestPredicates.Visitor) {
            visitor.method(emptySet())
        }
    }

    @Test
    fun `an and of two path predicates declares the two paths joined`() {
        val declared = declaredIdentities(route(path("/outer").and(path("/inner")), handler))

        assertEquals(setOf("* /outer/inner"), declared)
    }

    @Test
    fun `an and of a path and a predicate that narrows nothing declares the path alone`() {
        val declared = declaredIdentities(route(path("/orders").and(param("mine", "1")), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `an and of two verb predicates keeps the first verb`() {
        val bothVerbs = RequestPredicates.method(HttpMethod.GET).and(RequestPredicates.method(HttpMethod.POST))

        val declared = declaredIdentities(route(bothVerbs.and(path("/report")), handler))

        assertEquals(setOf("GET /report"), declared)
    }

    @Test
    fun `a negated predicate narrows nothing, so a route with no other path is not declared`() {
        val declared = declaredIdentities(route(GET("/legacy").negate(), handler))

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `a negated predicate beside a path leaves the path as the identity`() {
        val declared = declaredIdentities(route(path("/orders").and(param("mine", "1").negate()), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `a predicate the walk cannot interpret leaves the route undeclared, to be found at dispatch`() {
        val declared = declaredIdentities(route(OpaquePredicate(), handler))

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `an unrecognisable predicate poisons the route it is combined with, path and all`() {
        val declared = declaredIdentities(route(path("/orders").and(OpaquePredicate()), handler))

        assertEquals(emptySet(), declared, "an identity resolved from a tree with an unknown part is not trustworthy")
    }

    @Test
    fun `a predicate reporting no verbs narrows nothing, leaving the route's own path and any verb`() {
        val declared = declaredIdentities(route(NoVerbPredicate().and(path("/health")), handler))

        assertEquals(setOf("* /health"), declared)
    }

    @Test
    fun `a nest whose predicate carries no path contributes no prefix segment`() {
        val nested = route(GET("/items"), handler)

        val declared = declaredIdentities(RouterFunctions.nest(param("tenant", "acme"), nested))

        assertEquals(setOf("GET /items"), declared)
    }

    @Test
    fun `an or inside a nest declares one route per prefix`() {
        val nested = route(GET("/items"), handler)

        val declared = declaredIdentities(RouterFunctions.nest(path("/api").or(path("/v2")), nested))

        assertEquals(setOf("GET /api/items", "GET /v2/items"), declared)
    }

    @Test
    fun `a predicate that describes itself not at all narrows nothing`() {
        val declared = declaredIdentities(route(SilentPredicate().and(path("/quiet")), handler))

        assertEquals(setOf("* /quiet"), declared)
    }

    @Test
    fun `negating a predicate that describes itself not at all narrows nothing either`() {
        val declared = declaredIdentities(route(SilentPredicate().negate().and(path("/quiet")), handler))

        assertEquals(setOf("* /quiet"), declared)
    }

    @Test
    fun `an or of a path and a predicate with none declares only the alternative that has a path`() {
        val declared = declaredIdentities(route(path("/orders").or(param("mine", "1")), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `a header predicate narrows nothing, leaving the path beside it as the identity`() {
        val jsonFeed = RequestPredicates.accept(MediaType.APPLICATION_JSON).and(path("/feed"))

        val declared = declaredIdentities(route(jsonFeed, handler))

        assertEquals(setOf("* /feed"), declared)
    }

    @Test
    fun `a router function Spring cannot describe declares nothing and does not fail the walk`() {
        val declared = declaredIdentities(OpaqueRouterFunction())

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `a resources route declares its pattern with the verbs its handler answers`() {
        val declared = declaredIdentities { RouterFunctions.resources("/static/**", ClassPathResource("static/")) }

        assertEquals(setOf("GET /static/*", "HEAD /static/*", "OPTIONS /static/*"), declared)
    }

    @Test
    fun `a resources route inside a nest is declared under the nest prefix`() {
        val declared =
            declaredIdentities {
                RouterFunctions.route().path("/assets") { builder -> builder.resources("/**", ClassPathResource("static/")) }.build()
            }

        assertEquals(setOf("GET /assets/*", "HEAD /assets/*", "OPTIONS /assets/*"), declared)
    }

    @Test
    fun `a route carrying attributes is declared, and its attributes are ignored`() {
        val withAttributes =
            RouterFunctions
                .route()
                .GET("/audited", handler)
                .withAttribute("audit", true)
                .build()

        val declared = declaredIdentities(withAttributes)

        assertEquals(setOf("GET /audited"), declared)
    }

    @Test
    fun `the predicate visitor answers Object's own methods rather than failing the walk`() {
        val declared = declaredIdentities(route(ChattyPredicate(), handler))

        assertEquals(setOf("* /probe"), declared)
    }

    @Test
    fun `the router visitor answers Object's own methods rather than failing the walk`() {
        val declared = declaredIdentities(ChattyRouterFunction(handler))

        assertEquals(setOf("GET /probe"), declared)
    }

    @Test
    fun `declare ignores an object from the application's own loader that is not a RouterFunction`() {
        val declared = declaredIdentities(ServerResponse.ok().build())

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `transform returns the builder untouched for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)
        val advice = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        val returned =
            SpringWebMvcModule().transform(
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
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)
        try {
            SpringWebMvcModule().declare(frameworkObject())
            val endpoints = registry.endpoints()
            assertTrue(
                endpoints.all { it.framework == "spring-webmvc" },
                "every declared endpoint belongs to this module: $endpoints",
            )
            return endpoints.map { "${it.verb} ${it.routeTemplate}" }.toSet()
        } finally {
            instrumentation.removeTransformer(transformer)
        }
    }
}
