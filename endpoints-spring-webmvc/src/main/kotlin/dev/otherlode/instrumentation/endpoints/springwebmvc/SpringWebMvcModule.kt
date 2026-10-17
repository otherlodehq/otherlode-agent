package dev.otherlode.instrumentation.endpoints.springwebmvc

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.lang.System.Logger.Level
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

private const val HANDLER_METHOD_MAPPING = "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping"
private const val REQUEST_MAPPING_HANDLER_MAPPING = "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping"
private const val URL_HANDLER_MAPPING = "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping"
private const val PATH_EXPOSING_INTERCEPTOR = "$URL_HANDLER_MAPPING\$PathExposingHandlerInterceptor"
private const val PATH_RESOURCE_LOOKUP_FUNCTION = "org.springframework.web.servlet.function.PathResourceLookupFunction"
private const val PREDICATE_RESOURCE_LOOKUP_FUNCTION = "org.springframework.web.servlet.function.PredicateResourceLookupFunction"
private const val ROUTER_FUNCTION_MAPPING = "org.springframework.web.servlet.function.support.RouterFunctionMapping"
private const val ROUTER_FUNCTION = "org.springframework.web.servlet.function.RouterFunction"
private const val REQUEST_PREDICATE = "org.springframework.web.servlet.function.RequestPredicate"
private const val ROUTER_FUNCTIONS_VISITOR = "org.springframework.web.servlet.function.RouterFunctions\$Visitor"
private const val REQUEST_PREDICATES_VISITOR = "org.springframework.web.servlet.function.RequestPredicates\$Visitor"
private const val SERVER_REQUEST = "org.springframework.web.servlet.function.ServerRequest"
private const val HANDLER_FUNCTION = "org.springframework.web.servlet.function.HandlerFunction"

/** `HandlerFunction.handle`'s descriptor, the same on every supported Spring version. */
private const val HANDLE_DESCRIPTOR =
    "(Lorg/springframework/web/servlet/function/ServerRequest;)Lorg/springframework/web/servlet/function/ServerResponse;"

/** A `ResourceHandlerFunction` answers these verbs in Spring 5.3, 6.2 and 7.0, and 405 for any other. */
private val RESOURCE_VERBS = listOf("GET", "HEAD", "OPTIONS")

/**
 * Leads a `resources(pattern, location)` route's dispatch key, `[RESOURCE_KEY, lookupFunction,
 * nestPath, verb]`. The lookup function is the one object that is the same for every request,
 * since Spring builds a new handler function per request, and it is held weakly. The nest path
 * tells apart the templates one lookup function serves under several nests.
 */
private const val RESOURCE_KEY = "fnres"

/**
 * Leads a `resources(predicate, resource)` route's dispatch key, `[PREDICATE_RESOURCE_KEY,
 * template, verb]`, apart from an ordinary route's, so a resource handler is never counted as the
 * route that shares its nest's path.
 */
private const val PREDICATE_RESOURCE_KEY = "fnrespred"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.springwebmvc"
private const val MODULE_NAME = "spring-webmvc"

private const val SERVLET_PREFIX = "javax/servlet/"
private const val JAKARTA_SERVLET_PREFIX = "jakarta/servlet/"

/**
 * Leads a functional route's dispatch key, `[FUNCTIONAL_KEY, template, verb]`, so it never equals
 * an annotation-mapped route's two-element key. The key holds no handler: Spring wraps a filtered
 * route's handler in a new function on every request, which no key holding it would ever match.
 */
private const val FUNCTIONAL_KEY = "fn"

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
    override val name: String = MODULE_NAME

    private val log = System.getLogger(SpringWebMvcModule::class.java.name)
    private val noPathPredicateLogged = AtomicBoolean(false)
    private val resourcesUnreadLogged = AtomicBoolean(false)
    private val resourcesPathlessLogged = AtomicBoolean(false)

    /**
     * A functional route's handler passed as a lambda or a method reference is a `HandlerFunction`
     * lambda. The name is the same in Spring 5.3, 6.2 and 7.0.
     */
    override val handlerInterfaces: Set<String> = setOf(HANDLER_FUNCTION)

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

    /**
     * Walks [frameworkObject] (a `RouterFunction`) with a [Proxy] implementing
     * `RouterFunctions.Visitor`, registering every route it finds through [OtherlodeEndpoints.register].
     *
     * Neither `RouterFunction` nor its visitor types are on this module's own classloader, so
     * every framework type here is reached only by name, through reflection and the classloader
     * [frameworkObject] itself came from. A route's full path is the join of every enclosing
     * `startNested`/`endNested` prefix with the route's own predicate-derived path; see
     * [walkPredicate] for how one predicate's verbs and paths, including `and`/`or` combinations,
     * are collected.
     */
    override fun declare(frameworkObject: Any) {
        val loader = frameworkObject.javaClass.classLoader
        val routerFunctionClass = Class.forName(ROUTER_FUNCTION, false, loader)
        if (!routerFunctionClass.isInstance(frameworkObject)) return

        val routerFunctionsVisitorClass = Class.forName(ROUTER_FUNCTIONS_VISITOR, false, loader)
        val requestPredicateClass = Class.forName(REQUEST_PREDICATE, false, loader)
        val requestPredicatesVisitorClass = Class.forName(REQUEST_PREDICATES_VISITOR, false, loader)
        val acceptMethod = routerFunctionClass.getMethod("accept", routerFunctionsVisitorClass)

        val prefixStack = ArrayDeque<Set<String>>()

        fun currentPrefixes(): Set<String> = prefixStack.lastOrNull() ?: setOf("")

        val routerVisitor =
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "startNested" -> {
                        val result = walkPredicate(args[0]!!, requestPredicateClass, requestPredicatesVisitorClass, loader)
                        val ownPrefixes =
                            result.alternatives
                                .mapNotNull { it.path }
                                .toSet()
                                .ifEmpty { setOf("") }
                        prefixStack.addLast(crossJoinPaths(currentPrefixes(), ownPrefixes))
                    }

                    "endNested" -> {
                        prefixStack.removeLastOrNull()
                    }

                    "route" -> {
                        val predicateResult = walkPredicate(args[0]!!, requestPredicateClass, requestPredicatesVisitorClass, loader)
                        registerRoute(predicateResult, currentPrefixes(), args[1]!!)
                    }

                    "resources" -> {
                        registerResources(args[0]!!, currentPrefixes(), requestPredicateClass, requestPredicatesVisitorClass, loader)
                    }

                    "attributes", "unknown" -> {
                        // Not declared: `attributes` carries no path; a whole `unknown` router
                        // subtree is never visited any deeper than this.
                    }

                    "toString" -> {
                        return@InvocationHandler "SpringWebMvcModule.RouterFunctionsVisitor"
                    }

                    "hashCode" -> {
                        return@InvocationHandler System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        return@InvocationHandler (args?.getOrNull(0) === proxy)
                    }
                }
                null
            }
        val visitor = Proxy.newProxyInstance(loader, arrayOf(routerFunctionsVisitorClass), routerVisitor)
        acceptMethod.invoke(frameworkObject, visitor)
    }

    /**
     * Registers one `route(predicate, handlerFunction)` call for every (prefix, alternative)
     * combination. Inside a nest, an alternative with no path of its own takes the nest's path,
     * which is the pattern Spring matches it under. Logs once at INFO and registers nothing when
     * [predicateResult] contained a predicate this walk could not interpret, or carries no path
     * and has no enclosing nest. Such a route is found at dispatch only if a path predicate
     * matched on the way to it; one with no path anywhere is not tracked at all, since Spring
     * records no pattern for it.
     */
    private fun registerRoute(
        predicateResult: PredicateResult,
        prefixes: Set<String>,
        handlerFunction: Any,
    ) {
        val nested = prefixes != setOf("")
        if (predicateResult.unknown || (!nested && predicateResult.alternatives.none { it.path != null })) {
            if (noPathPredicateLogged.compareAndSet(false, true)) {
                log.log(
                    Level.INFO,
                    "otherlode: a $MODULE_NAME functional route had no path predicate this walk could resolve; " +
                        "it is counted only if a path predicate matched on the way to it",
                )
            }
            return
        }
        val handler = handlerJoin(handlerFunction)
        for (alternative in predicateResult.alternatives) {
            val path = alternative.path ?: if (nested) "" else continue
            val verb = alternative.verb ?: "*"
            for (prefix in prefixes) {
                val template = joinPaths(prefix, path)
                val key = listOf(FUNCTIONAL_KEY, template, verb)
                OtherlodeEndpoints.register(
                    MODULE_NAME,
                    key,
                    verb,
                    template,
                    null,
                    handler?.className,
                    handler?.methodName,
                    handler?.descriptor,
                )
            }
        }
    }

    /**
     * Registers a `resources(lookupFunction)` route under every prefix, once per verb its handler
     * answers. Spring's `PathResourceLookupFunction` is read through its `pattern` field and keyed
     * by the lookup function and the prefix, since its dispatch hook is the lookup itself.
     * `PredicateResourceLookupFunction` (`resources(predicate, resource)`, Spring 6.1.4 and later)
     * is read through its `predicate` field and keyed by template, since its predicate sets the
     * matching pattern the route mapping's dispatch hook reads; one whose predicate gives no path
     * is not declared. Any other lookup function is an unread shape, logged once at INFO and not
     * declared.
     */
    private fun registerResources(
        lookupFunction: Any,
        prefixes: Set<String>,
        requestPredicateClass: Class<*>,
        requestPredicatesVisitorClass: Class<*>,
        loader: ClassLoader?,
    ) {
        val read =
            when (lookupFunction.javaClass.name) {
                PATH_RESOURCE_LOOKUP_FUNCTION -> {
                    declaredField(lookupFunction, "pattern")?.toString()?.let { pattern ->
                        for (prefix in prefixes) {
                            val template = joinPaths(prefix, pattern)
                            for (verb in RESOURCE_VERBS) {
                                OtherlodeEndpoints.register(
                                    MODULE_NAME,
                                    listOf(RESOURCE_KEY, lookupFunction, prefix, verb),
                                    verb,
                                    template,
                                    null,
                                    null,
                                    null,
                                    null,
                                )
                            }
                        }
                    }
                }

                PREDICATE_RESOURCE_LOOKUP_FUNCTION -> {
                    declaredField(lookupFunction, "predicate")?.let { predicate ->
                        val result = walkPredicate(predicate, requestPredicateClass, requestPredicatesVisitorClass, loader)
                        if (result.unknown || result.alternatives.none { it.path != null }) {
                            if (resourcesPathlessLogged.compareAndSet(false, true)) {
                                log.log(
                                    Level.INFO,
                                    "otherlode: a $MODULE_NAME resources route's predicate had no path this walk could resolve; " +
                                        "it is not declared or counted",
                                )
                            }
                            return@let Unit
                        }
                        for (alternative in result.alternatives) {
                            val path = alternative.path ?: continue
                            val verbs = alternative.verb?.let { verb -> listOf(verb).filter { it in RESOURCE_VERBS } } ?: RESOURCE_VERBS
                            for (prefix in prefixes) {
                                val template = joinPaths(prefix, path)
                                for (verb in verbs) {
                                    OtherlodeEndpoints.register(
                                        MODULE_NAME,
                                        listOf(PREDICATE_RESOURCE_KEY, template, verb),
                                        verb,
                                        template,
                                        null,
                                        null,
                                        null,
                                        null,
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    null
                }
            }
        if (read == null && resourcesUnreadLogged.compareAndSet(false, true)) {
            log.log(
                Level.INFO,
                "otherlode: a $MODULE_NAME resources route had a lookup function this walk could not read; " +
                    "it is not declared, and is counted only when it delegates to Spring's own path lookup",
            )
        }
    }

    private fun declaredField(
        target: Any,
        name: String,
    ): Any? =
        try {
            target.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(target)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** The class, method and descriptor an endpoint record names as its handler. */
    private class HandlerJoin(
        val className: String,
        val methodName: String?,
        val descriptor: String?,
    )

    /**
     * The handler join for a functional route's `HandlerFunction`: the `handle` method Spring
     * invokes and the class that declares it, so a handler inheriting `handle` from a base class
     * joins to the probe on that base. A hidden class, spun for a lambda or a method reference,
     * joins to the method the lambda calls, as [OtherlodeEndpoints.lambdaImplementation] recorded it.
     * When nothing was recorded it gets no join. A class the reflection cannot see
     * `handle` on is reported by name alone. `ServerRequest` is resolved through the handler's own
     * loader rather than named here, so this module never links against Spring.
     */
    private fun handlerJoin(handlerFunction: Any): HandlerJoin? {
        val type = handlerFunction.javaClass
        if (type.isHidden) {
            val implementation = OtherlodeEndpoints.lambdaImplementation(type) ?: return null
            return HandlerJoin(implementation.className, implementation.methodName, implementation.descriptor)
        }
        return try {
            val serverRequest = Class.forName(SERVER_REQUEST, false, type.classLoader)
            val handle = type.getMethod("handle", serverRequest)
            HandlerJoin(handle.declaringClass.name, "handle", HANDLE_DESCRIPTOR)
        } catch (_: ReflectiveOperationException) {
            HandlerJoin(type.name, null, null)
        }
    }
}

/** One resolved (verb, path) pairing from a predicate tree; either half is null when unconstrained. */
private data class Alternative(
    val verb: String?,
    val path: String?,
)

/** Every alternative a predicate tree can match as, plus whether any part of it could not be interpreted. */
private class PredicateResult(
    val alternatives: Set<Alternative>,
    val unknown: Boolean,
)

private val NEUTRAL = PredicateResult(setOf(Alternative(null, null)), unknown = false)

/**
 * Visits [predicate] with a [Proxy] implementing `RequestPredicates.Visitor` and returns the
 * [PredicateResult] it produces.
 *
 * A leaf predicate (`method`, `path`) pushes one [PredicateResult] onto a small operand stack;
 * `and`/`or` combine the two most recently pushed results into one, `endAnd` taking the Cartesian
 * product of their alternatives and `endOr` their union; `endNegate` discards its one operand and
 * pushes [NEUTRAL], treating a negated subtree as contributing nothing, since it says what a route
 * is not. Every other leaf (`pathExtension`, `header`, `param`, a 7.0-only `version`) is neutral
 * too: none of them narrow a route's identity. `unknown` pushes a result marked unrecognisable, which propagates
 * through any `and`/`or` it takes part in. One `accept` call leaves at most one result on the
 * stack, which this function pops and returns; an empty stack reads as [NEUTRAL].
 */
private fun walkPredicate(
    predicate: Any,
    requestPredicateClass: Class<*>,
    visitorClass: Class<*>,
    loader: ClassLoader?,
): PredicateResult {
    val stack = ArrayDeque<PredicateResult>()
    val handler =
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "method" -> {
                    @Suppress("UNCHECKED_CAST")
                    val verbs = (args[0] as Set<Any>).map { it.javaClass.getMethod("name").invoke(it) as String }.toSet()
                    stack.addLast(
                        if (verbs.isEmpty()) {
                            NEUTRAL
                        } else {
                            PredicateResult(verbs.map { Alternative(it, null) }.toSet(), unknown = false)
                        },
                    )
                }

                "path" -> {
                    stack.addLast(PredicateResult(setOf(Alternative(null, args[0] as String)), unknown = false))
                }

                "pathExtension", "header", "param", "version" -> {
                    stack.addLast(NEUTRAL)
                }

                "startAnd", "and", "startOr", "or", "startNegate" -> {
                    // Pure markers: every combination is resolved at its matching end* call below.
                }

                // Every operand is popped tolerantly: a predicate whose own `accept` describes it
                // to the visitor not at all pushes nothing, and a missing operand narrows nothing,
                // exactly as a neutral leaf does. Popping it strictly would raise
                // NoSuchElementException out of the visitor proxy, which the seam can only treat
                // as this module failing, taking every Spring endpoint in the process down with it.
                "endAnd" -> {
                    val right = stack.removeLastOrNull() ?: NEUTRAL
                    val left = stack.removeLastOrNull() ?: NEUTRAL
                    stack.addLast(combineAnd(left, right))
                }

                "endOr" -> {
                    val right = stack.removeLastOrNull() ?: NEUTRAL
                    val left = stack.removeLastOrNull() ?: NEUTRAL
                    stack.addLast(PredicateResult(left.alternatives + right.alternatives, left.unknown || right.unknown))
                }

                "endNegate" -> {
                    stack.removeLastOrNull()
                    stack.addLast(NEUTRAL)
                }

                "unknown" -> {
                    stack.addLast(PredicateResult(setOf(Alternative(null, null)), unknown = true))
                }

                "toString" -> {
                    return@InvocationHandler "SpringWebMvcModule.RequestPredicatesVisitor"
                }

                "hashCode" -> {
                    return@InvocationHandler System.identityHashCode(proxy)
                }

                "equals" -> {
                    return@InvocationHandler (args?.getOrNull(0) === proxy)
                }
            }
            null
        }
    val visitor = Proxy.newProxyInstance(loader, arrayOf(visitorClass), handler)
    requestPredicateClass.getMethod("accept", visitorClass).invoke(predicate, visitor)
    return stack.removeLastOrNull() ?: NEUTRAL
}

private fun combineAnd(
    left: PredicateResult,
    right: PredicateResult,
): PredicateResult {
    val combined = mutableSetOf<Alternative>()
    for (a in left.alternatives) {
        for (b in right.alternatives) {
            val verb = a.verb ?: b.verb
            val path =
                when {
                    a.path == null -> b.path
                    b.path == null -> a.path
                    else -> joinPaths(a.path, b.path)
                }
            combined += Alternative(verb, path)
        }
    }
    return PredicateResult(combined, left.unknown || right.unknown)
}

/** Joins [prefix] and [suffix] with a single slash, collapsing any doubled slash the join produces. */
private fun joinPaths(
    prefix: String,
    suffix: String,
): String = if (suffix.isEmpty()) prefix else "$prefix/$suffix".replace(Regex("/+"), "/")

private fun crossJoinPaths(
    outer: Set<String>,
    inner: Set<String>,
): Set<String> = outer.flatMap { o -> inner.map { i -> joinPaths(o, i) } }.toSet()

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
