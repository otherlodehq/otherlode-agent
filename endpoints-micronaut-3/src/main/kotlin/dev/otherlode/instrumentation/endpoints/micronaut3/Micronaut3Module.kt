package dev.otherlode.instrumentation.endpoints.micronaut3

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresMethod
import net.bytebuddy.matcher.ElementMatchers.isConstructor
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.pool.TypePool
import java.lang.ref.WeakReference

private const val DEFAULT_ROUTER = "io.micronaut.web.router.DefaultRouter"
private const val ROUTING_IN_BOUND_HANDLER = "io.micronaut.http.server.netty.RoutingInBoundHandler"
private const val SERVLET_HTTP_HANDLER = "io.micronaut.servlet.http.ServletHttpHandler"
private const val URI_ROUTE = "io.micronaut.web.router.UriRoute"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.micronaut3"

/** Whether [method] returns a `Stream` of `UriRoute`, which Micronaut 4's `uriRoutes()` (a stream of `UriRouteInfo`) does not. */
private val RETURNS_URI_ROUTES: ElementMatcher<MethodDescription> =
    ElementMatcher { method ->
        try {
            method.returnType.typeArguments
                .singleOrNull()
                ?.asErasure()
                ?.name == URI_ROUTE
        } catch (e: RuntimeException) {
            false
        }
    }

private val ROUTER_SHAPE: ElementMatcher<TypeDescription> =
    declaresMethod(named<MethodDescription>("uriRoutes").and(takesArguments(0)).and(RETURNS_URI_ROUTES))

private fun routerIsMicronaut3(classLoader: ClassLoader): Boolean =
    try {
        val router = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(classLoader)).describe(DEFAULT_ROUTER)
        router.isResolved && ROUTER_SHAPE.matches(router.resolve())
    } catch (e: Exception) {
        false
    }

/**
 * Endpoint module for Micronaut 3 (`micronaut-3`), compiled against 3.0.0.
 *
 * The exit of the `DefaultRouter(Collection)` constructor declares every route. The router has
 * then built all its `UriRoute` objects, and [declare] reads them through `Router.uriRoutes()`,
 * keyed by the `UriRoute` instance that `UriRouteMatch.getRoute()` returns at dispatch.
 *
 * A request counts at the entry of the Netty server's private `RoutingInBoundHandler.handleRouteMatch`
 * when the route it matched is a `UriRouteMatch`, which runs once per request before the filters
 * and the handler: a 404, a 405, an unsupported media type and a CORS preflight the server answers
 * itself reach no `UriRouteMatch`, a status route is not one, and a filter that rejects the request
 * after the match still counts it. micronaut-servlet's private `ServletHttpHandler.invokeRouteMatch`
 * is hooked the same way, and counts a route only when its verb is the request's, since the handler
 * dispatches a preflight to a route of another verb.
 *
 * Micronaut 4 lists `UriRouteInfo` where this lists `UriRoute`. `DefaultRouter` is accepted only
 * with the `uriRoutes()` that returns `UriRoute`, and the Netty and servlet handlers by name on a
 * class loader whose `DefaultRouter` has that shape, so their hooks are checked like any other.
 * This module matches nothing on a Micronaut 4 or 5 application.
 *
 * A HEAD route Micronaut adds beside each `@Get` is not declared, and a HEAD request that matches
 * one counts against the GET endpoint. A WebSocket route is not declared.
 * `micronaut.server.context-path` is part of an annotated route's template and not of a
 * programmatic one, and stays as it is.
 */
class Micronaut3Module : EndpointModule {
    override val name: String = MODULE_NAME

    private val walk = RouterWalk()

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named<TypeDescription>(DEFAULT_ROUTER).and(ROUTER_SHAPE)

    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val loader = WeakReference(classLoader)
        val routerIsMicronaut3 = lazy(LazyThreadSafetyMode.PUBLICATION) { loader.get()?.let(::routerIsMicronaut3) == true }
        val router = typeMatcher()
        return ElementMatcher { type ->
            when (type.name) {
                DEFAULT_ROUTER -> router.matches(type)
                ROUTING_IN_BOUND_HANDLER, SERVLET_HTTP_HANDLER -> routerIsMicronaut3.value
                else -> false
            }
        }
    }

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            DEFAULT_ROUTER -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.DeclareRoutesAdvice",
                        isConstructor<MethodDescription>().and(takesArguments(Collection::class.java)),
                    ),
                )
            }

            ROUTING_IN_BOUND_HANDLER -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.DispatchAdvice",
                        named<MethodDescription>("handleRouteMatch").and(takesArguments(3)),
                    ),
                )
            }

            SERVLET_HTTP_HANDLER -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.ServletDispatchAdvice",
                        named<MethodDescription>("invokeRouteMatch").and(takesArguments(6)),
                    ),
                )
            }

            else -> {
                builder
            }
        }

    override fun declare(frameworkObject: Any) = walk.declare(frameworkObject)
}
