package dev.otherlode.instrumentation.endpoints.springcommon

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.bootstrap.OtherlodeLoggers
import java.lang.System.Logger.Level
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

private val RESOURCE_VERBS = listOf("GET", "HEAD", "OPTIONS")

// The lookup function is the one object in the key that stays the same across requests.
private const val RESOURCE_KEY = "fnres"

private const val PREDICATE_RESOURCE_KEY = "fnrespred"

// The key holds no handler: Spring wraps a filtered route's handler in a new function on every request.
private const val FUNCTIONAL_KEY = "fn"

/**
 * What differs between Spring's servlet and reactive functional-routing stacks: the class names
 * the walk reaches by reflection, the `HandlerFunction.handle` descriptor, and the module name.
 *
 * @property moduleName the endpoint module's name, as registered and as logged.
 * @property moduleClassName the module's simple class name, used in the walk's proxy `toString`.
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
 * through [OtherlodeEndpoints.register] each route whose path it can resolve. One walk belongs to one endpoint
 * module. A route's path is the join of every enclosing `startNested`/`endNested` prefix with the
 * route's own predicate-derived path.
 */
class FunctionalRouteWalk(
    private val spec: FunctionalRouteStack,
) {
    private val log by lazy { OtherlodeLoggers.get(FunctionalRouteWalk::class.java.name) }
    private val noPathPredicateLogged = AtomicBoolean(false)
    private val resourcesUnreadLogged = AtomicBoolean(false)
    private val resourcesPathlessLogged = AtomicBoolean(false)

    /**
     * Registers each route of [frameworkObject] whose path the walk can resolve when it is a
     * `RouterFunction`, and does nothing otherwise.
     */
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
                        // Nothing to declare: `attributes` carries no path, and an `unknown` subtree is not
                        // entered.
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

    private class HandlerJoin(
        val className: String,
        val methodName: String?,
        val descriptor: String?,
    )

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

private data class Alternative(
    val verb: String?,
    val path: String?,
)

private class PredicateResult(
    val alternatives: Set<Alternative>,
    val unknown: Boolean,
)

private val NEUTRAL = PredicateResult(setOf(Alternative(null, null)), unknown = false)

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
                    // Pure markers: the combination is resolved at the matching end* call.
                }

                // Operands are popped tolerantly: a predicate that describes nothing pushes nothing, and a
                // strict pop would throw out of the visitor proxy and fail the whole module.
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

private fun joinPaths(
    prefix: String,
    suffix: String,
): String = if (suffix.isEmpty()) prefix else "$prefix/$suffix".replace(Regex("/+"), "/")

private fun crossJoinPaths(
    outer: Set<String>,
    inner: Set<String>,
): Set<String> = outer.flatMap { o -> inner.map { i -> joinPaths(o, i) } }.toSet()
