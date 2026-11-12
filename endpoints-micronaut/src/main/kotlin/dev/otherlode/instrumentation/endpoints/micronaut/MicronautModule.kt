package dev.otherlode.instrumentation.endpoints.micronaut

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.instrumentation.endpoints.micronautcommon.DispatchGate
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
private const val ROUTE_EXECUTOR_CONSTRUCTOR_ARGUMENTS = 6

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
 * If the process constructs no `RouteExecutor`, [beforeManifest] reports the module off with
 * `ROUTES_NOT_COUNTED` once the module has held a router for a flush interval.
 *
 * A request counts when `RouteExecutor.findRouteMatch` returns a match, unless the match is an
 * unregistered WebSocket route. A null return counts nothing.
 *
 * The module declares no WebSocket route, and no HEAD route with the `@Get` stereotype that shares
 * its template and target with a GET route. A request that matches such a HEAD route counts against
 * the GET endpoint of the same template. This module matches nothing on a Micronaut 3 application.
 */
class MicronautModule : EndpointModule {
    override val name: String = MODULE_NAME

    private val walk = RouterWalk()

    private val gate =
        DispatchGate(
            declareRoutes = walk::declare,
            notCounted = {
                OtherlodeEndpoints.moduleDisabled(
                    MODULE_NAME,
                    OtherlodeEndpoints.KIND_ROUTES_NOT_COUNTED,
                    "a router was built but no $ROUTE_EXECUTOR was constructed, so no dispatch point this module counts at exists on this runtime",
                )
            },
        )

    override val typeNames: Set<String> = setOf(DEFAULT_ROUTER, ROUTE_EXECUTOR)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named<TypeDescription>(DEFAULT_ROUTER).and(ROUTER_SHAPE)

    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val loader = WeakReference(classLoader)
        val routeExecutorIsMicronaut4 = lazy(LazyThreadSafetyMode.PUBLICATION) { loader.get()?.let(::routerIsMicronaut4) == true }
        val router = typeMatcher()
        return ElementMatcher { type ->
            when (type.name) {
                !in typeNames -> false
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
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.ArmAdvice",
                            isConstructor<MethodDescription>().and(takesArguments(ROUTE_EXECUTOR_CONSTRUCTOR_ARGUMENTS)),
                        ),
                    ).visit(
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

    override fun declare(frameworkObject: Any) = gate.accept(frameworkObject)

    override fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) = gate.beforeManifest(graceMillis, finalFlush)
}
