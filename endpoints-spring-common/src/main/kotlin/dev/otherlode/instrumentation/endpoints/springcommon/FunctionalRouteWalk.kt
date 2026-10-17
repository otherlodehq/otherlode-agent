package dev.otherlode.instrumentation.endpoints.springcommon

import dev.otherlode.bootstrap.OtherlodeEndpoints
import java.lang.System.Logger.Level
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * Leads a functional route's dispatch key, `[FUNCTIONAL_KEY, template, verb]`, so it never equals
 * an annotation-mapped route's two-element key. The key holds no handler: Spring wraps a filtered
 * route's handler in a new function on every request, which no key holding it would ever match.
 */
private const val FUNCTIONAL_KEY = "fn"

/**
 * Everything that differs between Spring's servlet and reactive functional-routing stacks: the
 * class names the walk reaches by reflection, the `HandlerFunction.handle` descriptor, the module
 * name used in keys, registrations and log lines, and the visitor leaf that reports a request
 * parameter.
 *
 * @property moduleName the endpoint module's name, as registered and as logged.
 * @property moduleClassName the module's simple class name, used in the walk's proxy `toString`.
 * @property routerFunctionClass `RouterFunction`.
 * @property routerFunctionsVisitorClass `RouterFunctions$Visitor`.
 * @property requestPredicateClass `RequestPredicate`.
 * @property requestPredicatesVisitorClass `RequestPredicates$Visitor`.
 * @property serverRequestClass `ServerRequest`.
 * @property handlerFunctionClass `HandlerFunction`.
 * @property pathResourceLookupFunctionClass `PathResourceLookupFunction`.
 * @property predicateResourceLookupFunctionClass `PredicateResourceLookupFunction`.
 * @property handleDescriptor `HandlerFunction.handle`'s descriptor.
 * @property paramLeaf the `RequestPredicates.Visitor` method that reports a request-parameter predicate.
 * @property dslClassPrefixes the class-name prefixes of Spring's own Kotlin DSL classes. A handler
 * that resolves to a class under one of them is inside Spring, so the route has no handler.
 */
class FunctionalRouteStack(
    val moduleName: String,
    val moduleClassName: String,
    val routerFunctionClass: String,
    val routerFunctionsVisitorClass: String,
    val requestPredicateClass: String,
    val requestPredicatesVisitorClass: String,
    val serverRequestClass: String,
    val handlerFunctionClass: String,
    val pathResourceLookupFunctionClass: String,
    val predicateResourceLookupFunctionClass: String,
    val handleDescriptor: String,
    val paramLeaf: String,
    val dslClassPrefixes: List<String>,
)

/**
 * Walks a `RouterFunction` with a [Proxy] implementing `RouterFunctions.Visitor`, registering
 * every route it finds through [OtherlodeEndpoints.register]. One walk belongs to one endpoint
 * module, which owns its log-once flags.
 *
 * Neither `RouterFunction` nor its visitor types are on this class's own classloader, so every
 * framework type here is reached only by name, through reflection and the classloader the walked
 * object came from. A route's full path is the join of every enclosing `startNested`/`endNested`
 * prefix with the route's own predicate-derived path; see [walkPredicate] for how one predicate's
 * verbs and paths, including `and`/`or` combinations, are collected.
 */
class FunctionalRouteWalk(
    private val spec: FunctionalRouteStack,
) {
    private val log = System.getLogger(FunctionalRouteWalk::class.java.name)
    private val noPathPredicateLogged = AtomicBoolean(false)
    private val resourcesUnreadLogged = AtomicBoolean(false)
    private val resourcesPathlessLogged = AtomicBoolean(false)

    /** Registers every route of [frameworkObject] when it is a `RouterFunction`, and does nothing otherwise. */
    fun declare(frameworkObject: Any) {
        val loader = frameworkObject.javaClass.classLoader
        val routerFunctionClass = Class.forName(spec.routerFunctionClass, false, loader)
        if (!routerFunctionClass.isInstance(frameworkObject)) return

        val routerFunctionsVisitorClass = Class.forName(spec.routerFunctionsVisitorClass, false, loader)
        val requestPredicateClass = Class.forName(spec.requestPredicateClass, false, loader)
        val requestPredicatesVisitorClass = Class.forName(spec.requestPredicatesVisitorClass, false, loader)
        val acceptMethod = routerFunctionClass.getMethod("accept", routerFunctionsVisitorClass)

        val prefixStack = ArrayDeque<Set<String>>()

        fun currentPrefixes(): Set<String> = prefixStack.lastOrNull() ?: setOf("")

        val routerVisitor =
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "startNested" -> {
                        val result = walkPredicate(args[0]!!, requestPredicateClass, requestPredicatesVisitorClass, loader, spec)
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
                        val predicateResult = walkPredicate(args[0]!!, requestPredicateClass, requestPredicatesVisitorClass, loader, spec)
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
                        return@InvocationHandler "${spec.moduleClassName}.RouterFunctionsVisitor"
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
                    "otherlode: a ${spec.moduleName} functional route had no path predicate this walk could resolve; " +
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
                    spec.moduleName,
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
                spec.pathResourceLookupFunctionClass -> {
                    declaredField(lookupFunction, "pattern")?.toString()?.let { pattern ->
                        for (prefix in prefixes) {
                            val template = joinPaths(prefix, pattern)
                            for (verb in RESOURCE_VERBS) {
                                OtherlodeEndpoints.register(
                                    spec.moduleName,
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

                spec.predicateResourceLookupFunctionClass -> {
                    declaredField(lookupFunction, "predicate")?.let { predicate ->
                        val result = walkPredicate(predicate, requestPredicateClass, requestPredicatesVisitorClass, loader, spec)
                        if (result.unknown || result.alternatives.none { it.path != null }) {
                            if (resourcesPathlessLogged.compareAndSet(false, true)) {
                                log.log(
                                    Level.INFO,
                                    "otherlode: a ${spec.moduleName} resources route's predicate had no path this walk could resolve; " +
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
                                        spec.moduleName,
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
                "otherlode: a ${spec.moduleName} resources route had a lookup function this walk could not read; " +
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
     * `handle` on is reported by name alone. A handler that resolves to one of Spring's own Kotlin
     * DSL classes gets no join, since a name inside Spring joins to nothing the application wrote.
     * `ServerRequest` is resolved through the handler's own loader rather than named here, so this
     * module never links against Spring.
     */
    private fun handlerJoin(handlerFunction: Any): HandlerJoin? {
        val type = handlerFunction.javaClass
        val join =
            if (type.isHidden) {
                val implementation = OtherlodeEndpoints.lambdaImplementation(type) ?: return null
                HandlerJoin(implementation.className, implementation.methodName, implementation.descriptor)
            } else {
                try {
                    val serverRequest = Class.forName(spec.serverRequestClass, false, type.classLoader)
                    val handle = type.getMethod("handle", serverRequest)
                    HandlerJoin(handle.declaringClass.name, "handle", spec.handleDescriptor)
                } catch (_: ReflectiveOperationException) {
                    HandlerJoin(type.name, null, null)
                }
            }
        return if (spec.dslClassPrefixes.any { join.className.startsWith(it) }) null else join
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
    spec: FunctionalRouteStack,
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

                "pathExtension", "header", "version", spec.paramLeaf -> {
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
                    return@InvocationHandler "${spec.moduleClassName}.RequestPredicatesVisitor"
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
