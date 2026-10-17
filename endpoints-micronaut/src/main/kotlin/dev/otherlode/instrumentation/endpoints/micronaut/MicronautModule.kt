package dev.otherlode.instrumentation.endpoints.micronaut

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
private const val ROUTE_EXECUTOR = "io.micronaut.http.server.RouteExecutor"
private const val URI_ROUTE_INFO = "io.micronaut.web.router.UriRouteInfo"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.micronaut"

/** Whether [method] returns a `Stream` of `UriRouteInfo`, which Micronaut 3's `uriRoutes()` (a stream of `UriRoute`) does not. */
private val RETURNS_URI_ROUTE_INFOS: ElementMatcher<MethodDescription> =
    ElementMatcher { method ->
        try {
            method.returnType.typeArguments
                .singleOrNull()
                ?.asErasure()
                ?.name == URI_ROUTE_INFO
        } catch (e: RuntimeException) {
            false
        }
    }

private val ROUTER_SHAPE: ElementMatcher<TypeDescription> =
    declaresMethod(named<MethodDescription>("uriRoutes").and(takesArguments(0)).and(RETURNS_URI_ROUTE_INFOS))

private fun routerIsMicronaut4(classLoader: ClassLoader): Boolean =
    try {
        val router = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(classLoader)).describe(DEFAULT_ROUTER)
        router.isResolved && ROUTER_SHAPE.matches(router.resolve())
    } catch (e: Exception) {
        false
    }

/**
 * Endpoint module for Micronaut (`micronaut`), covering the 4.0 to 5.x lines, compiled against 4.0.0.
 *
 * The exit of the `DefaultRouter(Collection)` constructor declares every route. The router has
 * then built all its `UriRouteInfo` objects, and [declare] reads them through `Router.uriRoutes()`,
 * keyed by the `UriRouteInfo` instance that dispatch returns. The exit of the package-private
 * `RouteExecutor.findRouteMatch` counts the `UriRouteInfo` of a non-null match. Micronaut's request
 * lifecycle calls it once per request, after the pre-matching filters and before argument binding:
 * a CORS preflight a filter answers, an `OPTIONS` request, a 404, a 405 and an ambiguous match
 * return no match and count nothing, and a filter that rejects the request after the match still
 * counts it.
 *
 * Micronaut 3 has no `UriRouteInfo` and no `RouteExecutor.findRouteMatch`. `DefaultRouter` is
 * accepted only with the `uriRoutes()` that returns `UriRouteInfo`, and `RouteExecutor` by name on a
 * class loader whose `DefaultRouter` has that shape, so its hook is checked like any other. This
 * module matches nothing on a 3.x application.
 *
 * A HEAD route Micronaut adds beside each `@Get` is not declared, and a HEAD request that matches
 * one counts against the GET endpoint. A WebSocket route is not declared. `micronaut.server.context-path`
 * is part of an annotated route's template and not of a programmatic one, and stays as it is.
 */
class MicronautModule : EndpointModule {
    override val name: String = MODULE_NAME

    private val walk = RouterWalk()

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named<TypeDescription>(DEFAULT_ROUTER).and(ROUTER_SHAPE)

    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val loader = WeakReference(classLoader)
        val routeExecutorIsMicronaut4 = lazy(LazyThreadSafetyMode.PUBLICATION) { loader.get()?.let(::routerIsMicronaut4) == true }
        val router = typeMatcher()
        return ElementMatcher { type ->
            when (type.name) {
                DEFAULT_ROUTER -> router.matches(type)
                ROUTE_EXECUTOR -> routeExecutorIsMicronaut4.value
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

            ROUTE_EXECUTOR -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.DispatchAdvice",
                        named<MethodDescription>("findRouteMatch").and(takesArguments(1)),
                    ),
                )
            }

            else -> {
                builder
            }
        }

    override fun declare(frameworkObject: Any) = walk.declare(frameworkObject)
}
