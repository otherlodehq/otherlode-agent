package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.instrumentation.endpoints.springcommon.FunctionalRouteStack
import dev.otherlode.instrumentation.endpoints.springcommon.FunctionalRouteWalk
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val HANDLER_METHOD_MAPPING = "org.springframework.web.reactive.result.method.AbstractHandlerMethodMapping"
private const val REQUEST_MAPPING_HANDLER_MAPPING = "org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping"
private const val URL_HANDLER_MAPPING = "org.springframework.web.reactive.handler.AbstractUrlHandlerMapping"
private const val FUNCTION_PACKAGE = "org.springframework.web.reactive.function.server"
private const val PATH_RESOURCE_LOOKUP_FUNCTION = "$FUNCTION_PACKAGE.PathResourceLookupFunction"
private const val ROUTER_FUNCTION_MAPPING = "$FUNCTION_PACKAGE.support.RouterFunctionMapping"

/** `HandlerFunction.handle`'s descriptor, the same on every supported Spring version. */
private const val HANDLE_DESCRIPTOR =
    "(Lorg/springframework/web/reactive/function/server/ServerRequest;)Lreactor/core/publisher/Mono;"

private val FUNCTIONAL_ROUTE_STACK =
    FunctionalRouteStack(
        moduleName = "spring-webflux",
        moduleClassName = "SpringWebFluxModule",
        routerFunctionClass = "$FUNCTION_PACKAGE.RouterFunction",
        routerFunctionsVisitorClass = "$FUNCTION_PACKAGE.RouterFunctions\$Visitor",
        requestPredicateClass = "$FUNCTION_PACKAGE.RequestPredicate",
        requestPredicatesVisitorClass = "$FUNCTION_PACKAGE.RequestPredicates\$Visitor",
        serverRequestClass = "$FUNCTION_PACKAGE.ServerRequest",
        handlerFunctionClass = "$FUNCTION_PACKAGE.HandlerFunction",
        pathResourceLookupFunctionClass = PATH_RESOURCE_LOOKUP_FUNCTION,
        predicateResourceLookupFunctionClass = "$FUNCTION_PACKAGE.PredicateResourceLookupFunction",
        handleDescriptor = HANDLE_DESCRIPTOR,
        paramLeaf = "queryParam",
        dslClassPrefixes = listOf("$FUNCTION_PACKAGE.RouterFunctionDsl", "$FUNCTION_PACKAGE.CoRouterFunctionDsl"),
    )

private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.springwebflux"

/**
 * Endpoint module for Spring WebFlux (`spring-webflux`), covering Spring Framework 5.3, 6.x and
 * 7.x with one module. Every hook has the same name and descriptor on all three, and WebFlux takes
 * no servlet type, so no advice is rebound by namespace.
 *
 * `AbstractHandlerMethodMapping.registerHandlerMethod` and `registerMapping` declare annotated
 * controllers; Spring Boot Actuator registers through the second. `RequestMappingInfoHandlerMapping.handleMatch`
 * counts them once per matched request, before the handler runs, with the mapping narrowed to the
 * patterns and verb that matched.
 *
 * `AbstractUrlHandlerMapping.registerHandler` declares URL-mapped handlers, and the exit of
 * `lookupHandler` counts a request one of them matched. The reactive side has no root handler, no
 * default fallback and no path-exposing interceptor.
 *
 * `RouterFunctionMapping` covers functional routing. The exit of `afterPropertiesSet` hands the
 * mapping's `RouterFunction` to [declare], which walks it with the walk the Spring MVC module
 * shares, and `setAttributes` counts a request once a route matched. A `resources(pattern,
 * location)` route has no pattern attribute and a new handler per request, so it is counted when
 * its `PathResourceLookupFunction` emits a resource; a `resources(predicate, resource)` route is
 * counted at `setAttributes` under its own key.
 *
 * A CORS preflight reaches every dispatch hook and is no call. WebFlux has no
 * `HandlerMappingIntrospector` and no asynchronous redispatch.
 */
class SpringWebFluxModule : EndpointModule {
    override val name: String = FUNCTIONAL_ROUTE_STACK.moduleName

    private val walk = FunctionalRouteWalk(FUNCTIONAL_ROUTE_STACK)

    override val handlerInterfaces: Set<String> = setOf(FUNCTIONAL_ROUTE_STACK.handlerFunctionClass)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        namedOneOf(
            HANDLER_METHOD_MAPPING,
            REQUEST_MAPPING_HANDLER_MAPPING,
            URL_HANDLER_MAPPING,
            ROUTER_FUNCTION_MAPPING,
            PATH_RESOURCE_LOOKUP_FUNCTION,
        )

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            HANDLER_METHOD_MAPPING -> {
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RegisterHandlerMethodAdvice",
                            named<MethodDescription>("registerHandlerMethod").and(takesArguments(3)),
                        ),
                    ).visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RegisterMappingAdvice",
                            named<MethodDescription>("registerMapping").and(takesArguments(3)),
                        ),
                    )
            }

            REQUEST_MAPPING_HANDLER_MAPPING -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.HandleMatchAdvice",
                        named<MethodDescription>("handleMatch").and(takesArguments(3)).and(not(isBridge())),
                    ),
                )
            }

            URL_HANDLER_MAPPING -> {
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RegisterUrlHandlerAdvice",
                            named<MethodDescription>("registerHandler").and(takesArguments(String::class.java, Any::class.java)),
                        ),
                    ).visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.LookupHandlerAdvice",
                            named<MethodDescription>("lookupHandler").and(takesArguments(2)),
                        ),
                    )
            }

            ROUTER_FUNCTION_MAPPING -> {
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.DeclareRouterFunctionsAdvice",
                            named<MethodDescription>("afterPropertiesSet").and(takesArguments(0)),
                        ),
                    ).visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.SetAttributesAdvice",
                            named<MethodDescription>("setAttributes").and(takesArguments(3)),
                        ),
                    )
            }

            PATH_RESOURCE_LOOKUP_FUNCTION -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.ResourceLookupAdvice",
                        named<MethodDescription>("apply").and(takesArguments(1)).and(not(isBridge())),
                    ),
                )
            }

            else -> {
                builder
            }
        }

    override fun declare(frameworkObject: Any) = walk.declare(frameworkObject)
}
