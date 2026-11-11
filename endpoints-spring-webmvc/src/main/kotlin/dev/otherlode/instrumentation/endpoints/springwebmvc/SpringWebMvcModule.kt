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
        dslClassPrefixes = listOf("$FUNCTION_PACKAGE.RouterFunctionDsl"),
    )

private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.springwebmvc"

private const val SERVLET_PREFIX = "javax/servlet/"
private const val JAKARTA_SERVLET_PREFIX = "jakarta/servlet/"

/**
 * Endpoint module for Spring MVC (`spring-webmvc`), covering Spring Framework 5.3, 6.x and 7.x
 * with one module. Each dispatch advice is written once against `javax.servlet` and, when the
 * woven method takes `jakarta` types, bound with the `javax/servlet/` prefix rewritten to
 * `jakarta/servlet/`.
 *
 * Annotation-mapped endpoints are declared at `registerHandlerMethod` and `registerMapping` and
 * counted at `handleMatch`. URL-mapped endpoints are declared at `registerHandler` and
 * `setRootHandler` and counted at `PathExposingHandlerInterceptor.preHandle`. Functional routes
 * are declared by [declare], which walks the `RouterFunction` of a `RouterFunctionMapping`, and
 * counted at `setAttributes` or, for `resources(pattern, location)`, at
 * `PathResourceLookupFunction.apply`. An introspector lookup, an async redispatch and a CORS
 * preflight are no call.
 */
class SpringWebMvcModule : EndpointModule {
    override val name: String = FUNCTIONAL_ROUTE_STACK.moduleName

    private val walk = FunctionalRouteWalk(FUNCTIONAL_ROUTE_STACK)

    override val handlerInterfaces: Set<String> = setOf(FUNCTIONAL_ROUTE_STACK.handlerFunctionClass)

    override val typeNames: Set<String> =
        setOf(
            HANDLER_METHOD_MAPPING,
            REQUEST_MAPPING_HANDLER_MAPPING,
            URL_HANDLER_MAPPING,
            PATH_EXPOSING_INTERCEPTOR,
            ROUTER_FUNCTION_MAPPING,
            PATH_RESOURCE_LOOKUP_FUNCTION,
        )

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(*typeNames.toTypedArray())

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

private fun servletRequestType(serverRequest: TypeDescription.Generic): TypeDescription.Generic? =
    serverRequest
        .asErasure()
        .declaredMethods
        .firstOrNull { it.internalName == "servletRequest" && it.parameters.isEmpty() }
        ?.returnType
