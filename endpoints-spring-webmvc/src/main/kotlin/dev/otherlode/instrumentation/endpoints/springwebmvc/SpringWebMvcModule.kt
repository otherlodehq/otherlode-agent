package dev.otherlode.instrumentation.endpoints.springwebmvc

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

private const val HANDLER_METHOD_MAPPING = "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping"
private const val REQUEST_MAPPING_HANDLER_MAPPING = "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping"
private const val URL_HANDLER_MAPPING = "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping"
private const val PATH_EXPOSING_INTERCEPTOR = "$URL_HANDLER_MAPPING\$PathExposingHandlerInterceptor"
private const val PATH_RESOURCE_LOOKUP_FUNCTION = "org.springframework.web.servlet.function.PathResourceLookupFunction"
private const val ROUTER_FUNCTION_MAPPING = "org.springframework.web.servlet.function.support.RouterFunctionMapping"
private const val FUNCTION_PACKAGE = "org.springframework.web.servlet.function"

/** `HandlerFunction.handle`'s descriptor, the same on every supported Spring version. */
private const val HANDLE_DESCRIPTOR =
    "(Lorg/springframework/web/servlet/function/ServerRequest;)Lorg/springframework/web/servlet/function/ServerResponse;"

private val FUNCTIONAL_ROUTE_STACK =
    FunctionalRouteStack(
        moduleName = "spring-webmvc",
        moduleClassName = "SpringWebMvcModule",
        routerFunctionClass = "$FUNCTION_PACKAGE.RouterFunction",
        routerFunctionsVisitorClass = "$FUNCTION_PACKAGE.RouterFunctions\$Visitor",
        requestPredicateClass = "$FUNCTION_PACKAGE.RequestPredicate",
        requestPredicatesVisitorClass = "$FUNCTION_PACKAGE.RequestPredicates\$Visitor",
        serverRequestClass = "$FUNCTION_PACKAGE.ServerRequest",
        handlerFunctionClass = "$FUNCTION_PACKAGE.HandlerFunction",
        pathResourceLookupFunctionClass = PATH_RESOURCE_LOOKUP_FUNCTION,
        predicateResourceLookupFunctionClass = "$FUNCTION_PACKAGE.PredicateResourceLookupFunction",
        handleDescriptor = HANDLE_DESCRIPTOR,
        paramLeaf = "param",
    )

private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.springwebmvc"

private const val SERVLET_PREFIX = "javax/servlet/"
private const val JAKARTA_SERVLET_PREFIX = "jakarta/servlet/"

/**
 * Endpoint module for Spring MVC (`spring-webmvc`), covering Spring Framework 5.3, 6.x and 7.x
 * with one module. A dispatch advice reads the servlet request, whose package is `javax` in
 * Spring 5.3 and `jakarta` from 6.0. Each is written once against `javax` and, when the woven
 * method takes `jakarta` types, bound with the `javax/servlet/` prefix rewritten to
 * `jakarta/servlet/` (see [servletRemap]), so a hook never weaves where its types do not exist.
 *
 * `AbstractHandlerMethodMapping.registerHandlerMethod` is the registration hook: it runs once per
 * handler method as Spring builds its route table. `registerMapping` is the second one, for a
 * handler method registered in code. `RequestMappingInfoHandlerMapping.handleMatch`
 * is the dispatch point: it runs once per matched request, before the handler runs, with the
 * winning `RequestMappingInfo` already narrowed to the matched pattern and verb. A lookup Spring's
 * `HandlerMappingIntrospector` makes, an async redispatch and a CORS preflight reach every
 * dispatch point and are no call.
 *
 * A Spring Boot Actuator endpoint registers through `registerMapping` on a
 * `RequestMappingInfoHandlerMapping` subclass, so it is declared there and counted by
 * `handleMatch`. `AbstractUrlHandlerMapping` covers the URL-mapped side instead:
 * `SimpleUrlHandlerMapping`, `BeanNameUrlHandlerMapping`, and Spring Boot's static-resource and
 * webjars mappings all extend it. Its `registerHandler` and `setRootHandler` are that hierarchy's
 * registration hooks. Its dispatch point is `PathExposingHandlerInterceptor.preHandle`, which only
 * `DispatcherServlet` runs.
 *
 * `RouterFunctionMapping` covers Spring's functional routing style. Its routes are declared as a
 * `RouterFunction` object built once and revealed only to a visitor, never through a registration
 * hook's own arguments, so this side uses the second seam call, [declare], instead: advice on the
 * exit of `afterPropertiesSet` hands the `RouterFunction` the mapping holds to [declare], which walks it with a
 * [Proxy] implementing `RouterFunctions.Visitor` (and, for each route's predicate, a second
 * [Proxy] implementing `RequestPredicates.Visitor`), since neither visitor type is on this
 * module's own classloader. `setAttributes` is this style's dispatch point: it runs once per
 * matched request, after functional routing has already resolved the winning pattern and handler.
 * A `resources(pattern, location)` route has no pattern attribute of its own and a new handler
 * per request, so it is counted at the exit of its `PathResourceLookupFunction.apply`; a
 * `resources(predicate, resource)` route is counted at `setAttributes` under its own key.
 */
class SpringWebMvcModule : EndpointModule {
    override val name: String = FUNCTIONAL_ROUTE_STACK.moduleName

    private val walk = FunctionalRouteWalk(FUNCTIONAL_ROUTE_STACK)

    /**
     * A functional route's handler passed as a lambda or a method reference is a `HandlerFunction`
     * lambda. The name is the same in Spring 5.3, 6.2 and 7.0.
     */
    override val handlerInterfaces: Set<String> = setOf(FUNCTIONAL_ROUTE_STACK.handlerFunctionClass)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        namedOneOf(
            HANDLER_METHOD_MAPPING,
            REQUEST_MAPPING_HANDLER_MAPPING,
            URL_HANDLER_MAPPING,
            PATH_EXPOSING_INTERCEPTOR,
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
                        named<MethodDescription>("handleMatch").and(takesArguments(3)),
                        servletRemap(typeDescription, "handleMatch", 3) { it.parameters[2].type },
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
                            "$ADVICE_PACKAGE.RegisterRootHandlerAdvice",
                            named<MethodDescription>("setRootHandler").and(takesArguments(Any::class.java)),
                        ),
                    )
            }

            PATH_EXPOSING_INTERCEPTOR -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.PathExposingPreHandleAdvice",
                        named<MethodDescription>("preHandle").and(takesArguments(3)),
                        servletRemap(typeDescription, "preHandle", 3) { it.parameters[0].type },
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
                            servletRemap(typeDescription, "setAttributes", 3) { it.parameters[0].type },
                        ),
                    )
            }

            PATH_RESOURCE_LOOKUP_FUNCTION -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.ResourceLookupAdvice",
                        named<MethodDescription>("apply").and(takesArguments(1)).and(not(isBridge())),
                        servletRemap(typeDescription, "apply", 1) { servletRequestType(it.parameters[0].type) },
                    ),
                )
            }

            else -> {
                builder
            }
        }

    override fun declare(frameworkObject: Any) = walk.declare(frameworkObject)
}

/**
 * The prefix rewrite that turns an advice class written against `javax.servlet` into one bound to
 * `jakarta.servlet`, or none when the woven method takes `javax` types or the shape is not found.
 * [type] picks the type to inspect from the declared method of [typeDescription] named [method]
 * with [arguments] parameters, so the answer comes from the woven method's own signature and a
 * class loader that sees both packages cannot mislead it. A method that is not found gets no
 * rewrite, and its hook then matches nothing, which the pipeline reports as `HOOK_UNMATCHED`.
 */
private fun servletRemap(
    typeDescription: TypeDescription,
    method: String,
    arguments: Int,
    type: (MethodDescription) -> TypeDescription.Generic?,
): Map<String, String> {
    val servletType =
        typeDescription.declaredMethods
            .filter { it.internalName == method && it.parameters.size == arguments && !it.isBridge }
            .firstNotNullOfOrNull { runCatching { type(it) }.getOrNull() }
    return if (servletType != null && servletType.asErasure().name.startsWith("jakarta.servlet.")) {
        mapOf(SERVLET_PREFIX to JAKARTA_SERVLET_PREFIX)
    } else {
        emptyMap()
    }
}

/** The return type of `ServerRequest.servletRequest()`, which is the servlet request class of the Spring in use. */
private fun servletRequestType(serverRequest: TypeDescription.Generic): TypeDescription.Generic? =
    serverRequest
        .asErasure()
        .declaredMethods
        .firstOrNull { it.internalName == "servletRequest" && it.parameters.isEmpty() }
        ?.returnType
