package dev.otherlode.instrumentation.endpoints.micronaut3

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
import net.bytebuddy.matcher.ElementMatchers.takesArgument
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.pool.TypePool
import java.lang.ref.WeakReference

private const val DEFAULT_ROUTER = "io.micronaut.web.router.DefaultRouter"
private const val ROUTING_IN_BOUND_HANDLER = "io.micronaut.http.server.netty.RoutingInBoundHandler"
private const val SERVLET_HTTP_HANDLER = "io.micronaut.servlet.http.ServletHttpHandler"
private const val LAMBDA_CONTAINER_HANDLER = "io.micronaut.function.aws.proxy.MicronautLambdaContainerHandler"
private const val AWS_PROXY_REQUEST = "io.micronaut.function.aws.proxy.MicronautAwsProxyRequest"
private const val LAMBDA_CONSTRUCTOR_ARGUMENTS = 3
private const val LAMBDA_HANDLE_REQUEST_ARGUMENTS = 3
private const val URI_ROUTE = "io.micronaut.web.router.UriRoute"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.micronaut3"

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
 * If the process constructs no dispatch point, [beforeManifest] reports the module off with
 * `ROUTES_NOT_COUNTED` once the module has held a router for a flush interval.
 *
 * A request counts only when its matched route is a `UriRouteMatch`, and an unregistered WebSocket
 * route counts nothing. At `ServletHttpHandler`, a route counts only when its verb is the request's.
 *
 * The module declares no WebSocket route, and no HEAD route with the `@Get` stereotype that shares
 * its template and target with a GET route. A request that matches such a HEAD route counts against
 * the GET endpoint of the same template. This module matches nothing on a Micronaut 4 or 5
 * application.
 */
class Micronaut3Module : EndpointModule {
    override val name: String = MODULE_NAME

    private val walk = RouterWalk()

    private val gate =
        DispatchGate(
            declareRoutes = walk::declare,
            notCounted = {
                OtherlodeEndpoints.moduleDisabled(
                    MODULE_NAME,
                    OtherlodeEndpoints.KIND_ROUTES_NOT_COUNTED,
                    "a router was built but none of $ROUTING_IN_BOUND_HANDLER, $SERVLET_HTTP_HANDLER or " +
                        "$LAMBDA_CONTAINER_HANDLER was constructed, so no dispatch point this module counts at exists on this runtime",
                )
            },
        )

    override val typeNames: Set<String> =
        setOf(DEFAULT_ROUTER, ROUTING_IN_BOUND_HANDLER, SERVLET_HTTP_HANDLER, LAMBDA_CONTAINER_HANDLER)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named<TypeDescription>(DEFAULT_ROUTER).and(ROUTER_SHAPE)

    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val loader = WeakReference(classLoader)
        val routerIsMicronaut3 = lazy(LazyThreadSafetyMode.PUBLICATION) { loader.get()?.let(::routerIsMicronaut3) == true }
        val router = typeMatcher()
        return ElementMatcher { type ->
            when (type.name) {
                !in typeNames -> false
                DEFAULT_ROUTER -> router.matches(type)
                ROUTING_IN_BOUND_HANDLER, SERVLET_HTTP_HANDLER, LAMBDA_CONTAINER_HANDLER -> routerIsMicronaut3.value
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
                builder
                    .visit(advice.hook("$ADVICE_PACKAGE.ArmAdvice", isConstructor<MethodDescription>()))
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.DispatchAdvice",
                            named<MethodDescription>("handleRouteMatch").and(takesArguments(3)),
                        ),
                    )
            }

            SERVLET_HTTP_HANDLER -> {
                builder
                    .visit(advice.hook("$ADVICE_PACKAGE.ArmAdvice", isConstructor<MethodDescription>()))
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.ServletDispatchAdvice",
                            named<MethodDescription>("invokeRouteMatch").and(takesArguments(6)),
                        ),
                    )
            }

            LAMBDA_CONTAINER_HANDLER -> {
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.ArmAdvice",
                            isConstructor<MethodDescription>().and(takesArguments(LAMBDA_CONSTRUCTOR_ARGUMENTS)),
                        ),
                    ).visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.LambdaDispatchAdvice",
                            named<MethodDescription>("handleRequest")
                                .and(takesArguments(LAMBDA_HANDLE_REQUEST_ARGUMENTS))
                                .and(takesArgument(0, named<TypeDescription>(AWS_PROXY_REQUEST))),
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
