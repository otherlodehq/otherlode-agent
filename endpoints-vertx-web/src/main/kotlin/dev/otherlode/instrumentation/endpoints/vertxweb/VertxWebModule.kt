package dev.otherlode.instrumentation.endpoints.vertxweb

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArgument
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val ROUTER_IMPL = "io.vertx.ext.web.impl.RouterImpl"
private const val ROUTE_IMPL = "io.vertx.ext.web.impl.RouteImpl"
private const val ROUTE_STATE = "io.vertx.ext.web.impl.RouteState"
private const val HTTP_SERVER_REQUEST = "io.vertx.core.http.HttpServerRequest"
private const val HANDLER = "io.vertx.core.Handler"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.vertxweb"

/**
 * Endpoint module for Vert.x Web (`vertx-web`), covering 4.5 to 5.x.
 *
 * A root router declares every route reachable from it at its first request, and again at a later
 * request after a router it reached changed. A JVM whose routers never receive a request declares
 * nothing.
 *
 * An endpoint is a route with a context handler that is not a sub-router mount. The exit of
 * `RouteState.matches` counts a route that matched, so a request that passes through several routes
 * counts each, and a `reroute` counts again. A removed route is not retracted.
 */
class VertxWebModule : EndpointModule {
    override val name: String = MODULE_NAME

    override val bootModulesNeedingSeamRead: Set<String> = setOf("io.vertx.web")

    override val handlerInterfaces: Set<String> = setOf(HANDLER)

    private val graph = RouterGraph()
    private val walk = RouterWalk(graph)
    private val declareRoutes: (Any) -> List<Any> = walk::declare

    override val typeNames: Set<String> = setOf(ROUTER_IMPL, ROUTE_IMPL, ROUTE_STATE)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf<TypeDescription>(*typeNames.toTypedArray())

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            ROUTER_IMPL -> {
                val routeArgument = takesArgument<MethodDescription>(0, named<TypeDescription>(ROUTE_IMPL))
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RouteAddedAdvice",
                            named<MethodDescription>("add").and(takesArguments(1)).and(routeArgument),
                        ),
                    ).visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RouteRemovedAdvice",
                            named<MethodDescription>("remove").and(takesArguments(1)).and(routeArgument),
                        ),
                    ).visit(advice.hook("$ADVICE_PACKAGE.RoutesClearedAdvice", named<MethodDescription>("clear").and(takesArguments(0))))
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RootRequestAdvice",
                            named<MethodDescription>("handle")
                                .and(takesArguments(1))
                                .and(takesArgument(0, named<TypeDescription>(HTTP_SERVER_REQUEST))),
                        ),
                    )
            }

            ROUTE_IMPL -> {
                builder
                    .visit(
                        advice.hook("$ADVICE_PACKAGE.SubRouterMountedAdvice", named<MethodDescription>("subRouter").and(takesArguments(1))),
                    ).visit(advice.hook("$ADVICE_PACKAGE.RegexSetAdvice", named<MethodDescription>("setRegex").and(takesArguments(1))))
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RouteChangedAdvice",
                            namedOneOf<MethodDescription>("setPath", "method").and(takesArguments(1)),
                        ),
                    ).visit(advice.hook("$ADVICE_PACKAGE.HandlerAddedAdvice", named<MethodDescription>("handler").and(takesArguments(1))))
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.BlockingHandlerAdvice",
                            named<MethodDescription>("blockingHandler").and(takesArguments(2)),
                        ),
                    )
            }

            ROUTE_STATE -> {
                builder.visit(advice.hook("$ADVICE_PACKAGE.DispatchAdvice", named<MethodDescription>("matches").and(takesArguments(3))))
            }

            else -> {
                builder
            }
        }

    override fun declare(frameworkObject: Any) {
        if (frameworkObject is Array<*>) {
            graph.apply(frameworkObject)
        } else {
            graph.declareFrom(frameworkObject, declareRoutes)
        }
    }
}
