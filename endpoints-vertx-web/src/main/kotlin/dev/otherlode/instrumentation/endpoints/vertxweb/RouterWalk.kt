package dev.otherlode.instrumentation.endpoints.vertxweb

import dev.otherlode.bootstrap.OtherlodeEndpoints
import java.lang.System.Logger.Level
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal const val MODULE_NAME = "vertx-web"

private val PRIORITY_HANDLERS =
    listOf(
        "PlatformHandler",
        "SecurityPolicyHandler",
        "BodyHandler",
        "MultiTenantHandler",
        "AuthenticationHandler",
        "InputTrustHandler",
        "AuthorizationHandler",
    ).map { "io.vertx.ext.web.handler.$it" }

private val LIBRARY_PREFIXES =
    listOf(
        "io.vertx.ext.web.",
        "io.vertx.core.",
        "io.vertx.lang.rx.",
        "io.vertx.rxjava.",
        "io.vertx.rxjava3.",
        "io.vertx.reactivex.",
        "io.vertx.mutiny.",
        "io.smallrye.mutiny.vertx.",
        "io.opentelemetry.javaagent.",
        "io.opentelemetry.instrumentation.",
    )

/** Stands for a root router's own use in a dispatch key; no mount point a request can give equals it. */
private const val ROOT_CHAIN = "\u0000root"

/** Stands for a router's one parameterised mount chain in a dispatch key; its mount point holds matched values. */
private const val PARAMETERISED_CHAIN = "\u0000parameterised"

private const val HANDLE_DESCRIPTOR = "(Lio/vertx/ext/web/RoutingContext;)V"
private const val ERASED_HANDLE_DESCRIPTOR = "(Ljava/lang/Object;)V"

/**
 * The Vert.x Web types and methods the walk reads, resolved by name from the loader the router
 * came from. Vert.x is not on the agent's own loader, and only the public API is read: on Vert.x
 * Web 5 the implementation package is exported to a few Vert.x modules and no one else.
 */
private class Api(
    private val loader: ClassLoader?,
) {
    private fun load(name: String) = Class.forName(name, false, loader)

    private val route = load("io.vertx.ext.web.Route")
    private val getRoutes: Method = load("io.vertx.ext.web.Router").getMethod("getRoutes")
    private val getPath: Method = route.getMethod("getPath")
    private val isRegexPath: Method = route.getMethod("isRegexPath")
    private val isExactPath: Method = route.getMethod("isExactPath")
    private val methods: Method = route.getMethod("methods")
    private val methodName: Method = load("io.vertx.core.http.HttpMethod").getMethod("name")
    val routingContext: Class<*> = load("io.vertx.ext.web.RoutingContext")
    val priorityHandlers: List<Class<*>> = PRIORITY_HANDLERS.mapNotNull { runCatching { load(it) }.getOrNull() }

    fun routesOf(router: Any): List<Any> = (getRoutes.invoke(router) as List<*>).filterNotNull()

    fun pathOf(route: Any): String? = getPath.invoke(route) as String?

    fun isRegexPath(route: Any): Boolean = isRegexPath.invoke(route) == true

    fun isExactPath(route: Any): Boolean = isExactPath.invoke(route) == true

    fun verbsOf(route: Any): List<String> =
        (methods.invoke(route) as Collection<*>?).orEmpty().map { methodName.invoke(it) as String }.ifEmpty { listOf("*") }
}

/** A mount path chain: [prefix] is the mount point a router sees, null at a root, and a parameterised mount has matched values in place of it at run time. */
private class Chain(
    val prefix: String?,
    val parameterised: Boolean,
)

private class Reached(
    val routes: List<Any>,
) {
    val chains = ArrayList<Chain>()
}

private class Join(
    val className: String,
    val method: String?,
    val descriptor: String?,
)

/**
 * Declares the routes of every router reachable from a root router, as endpoints, with the
 * template, the verbs and the handler join each has at the time.
 *
 * Vert.x Web gives a route no way to read its handlers, so those come from [graph]. A router
 * reached through more than one chain, as a root or through a mount, declares one endpoint per
 * chain, each keyed by the route and the chain: a root's own use, a static mount's mount point,
 * or its one parameterised mount, whose mount point holds the matched values at run time and so
 * is any mount point that is neither. A router reached through two parameterised mounts, from any
 * roots, cannot say which one a request came through, so the walk fails, which switches the module
 * off. A route `subRouter` is mounting is not declared until the mount is complete.
 */
internal class RouterWalk(
    private val graph: RouterGraph,
) {
    private val log = System.getLogger(RouterWalk::class.java.name)
    private val apis = WeakHashMap<ClassLoader, WeakReference<Api>>()
    private val unreadRegexLogged = ConcurrentHashMap.newKeySet<String>()
    private val parameterisedChainsByRoot = WeakIdentityMap<List<Pair<WeakReference<Any>, String>>>()

    private fun apiFor(loader: ClassLoader?): Api {
        if (loader == null) return Api(null)
        apis[loader]?.get()?.let { return it }
        return Api(loader).also { apis[loader] = WeakReference(it) }
    }

    /** Declares the routes reachable from [root] and returns the routers it reached. Call it under the graph's lock. */
    fun declare(root: Any): List<Any> {
        val api = apiFor(root.javaClass.classLoader)
        val reached = IdentityHashMap<Any, Reached>()
        visit(api, root, Chain(null, false), mutableListOf(), reached)
        val ownChains =
            reached.flatMap { (router, entry) ->
                entry.chains.filter { it.parameterised }.map {
                    WeakReference(router) to
                        it.prefix!!
                }
            }
        parameterisedChainsByRoot.put(root, ownChains)
        val allChains = parameterisedChainsByRoot.liveValues().flatten()
        for (router in reached.keys) {
            val prefixes = allChains.filter { it.first.get() === router }.mapTo(HashSet()) { it.second }
            check(prefixes.size < 2) {
                "a router is reachable through two parameterised mounts, which a request cannot be told apart by"
            }
        }
        for ((_, entry) in reached) {
            for (route in entry.routes) declareRoute(api, route, entry.chains)
        }
        return reached.keys.toList()
    }

    private fun visit(
        api: Api,
        router: Any,
        chain: Chain,
        stack: MutableList<Any>,
        reached: IdentityHashMap<Any, Reached>,
    ) {
        if (stack.any { it === router }) return
        val entry = reached.getOrPut(router) { Reached(api.routesOf(router)) }
        if (entry.chains.any { it.prefix == chain.prefix && it.parameterised == chain.parameterised }) return
        entry.chains += chain
        stack += router
        for (route in entry.routes) {
            val child = graph.infoOf(route)?.mounted?.get() ?: continue
            val mountPath = api.pathOf(route) ?: "/"
            visit(
                api,
                child,
                Chain(composeMountPoint(chain.prefix, mountPath), chain.parameterised || api.isRegexPath(route)),
                stack,
                reached,
            )
        }
        stack.removeAt(stack.size - 1)
    }

    private fun declareRoute(
        api: Api,
        route: Any,
        chains: List<Chain>,
    ) {
        val info = graph.infoOf(route) ?: return
        if (info.mounted != null || info.mounting || info.handlerCount == 0) return
        val path = api.pathOf(route)
        val regexRoute = path == null && api.isRegexPath(route)
        if (regexRoute && info.regexSource == null) {
            if (unreadRegexLogged.add(route.javaClass.name)) {
                log.log(Level.WARNING, "otherlode: a vertx-web regex route's source was never seen, so it is not declared")
            }
            return
        }
        val verbs = api.verbsOf(route)
        val join = joinOf(api, info.handlers.mapNotNull { it.get() })
        for (chain in chains) {
            val template =
                if (regexRoute) {
                    regexTemplate(chain.prefix, info.regexSource!!)
                } else {
                    pathTemplate(chain.prefix, path, api.isExactPath(route))
                }
            val chainKey =
                when {
                    chain.parameterised -> PARAMETERISED_CHAIN
                    chain.prefix == null -> ROOT_CHAIN
                    else -> chain.prefix
                }
            for (verb in verbs) {
                val key: Any = if (verbs.size > 1) listOf(route, chainKey, verb) else listOf(route, chainKey)
                OtherlodeEndpoints.register(
                    MODULE_NAME,
                    key,
                    verb,
                    template,
                    null,
                    join?.className,
                    join?.method,
                    join?.descriptor,
                    if (regexRoute) OtherlodeEndpoints.TEMPLATE_REGEX else OtherlodeEndpoints.TEMPLATE_PATH,
                )
            }
        }
    }

    private fun joinOf(
        api: Api,
        handlers: List<Any>,
    ): Join? {
        val handler = handlers.lastOrNull { handler -> api.priorityHandlers.none { it.isInstance(handler) } } ?: return null
        val type = handler.javaClass
        if (type.isHidden) {
            val implementation = OtherlodeEndpoints.lambdaImplementation(type) ?: return null
            if (isLibrary(implementation.className)) return null
            return Join(implementation.className, implementation.methodName, implementation.descriptor)
        }
        val typed = runCatching { type.getMethod("handle", api.routingContext) }.getOrNull()
        val erased = if (typed == null) runCatching { type.getMethod("handle", Any::class.java) }.getOrNull() else null
        val declaring = (typed ?: erased)?.declaringClass?.name ?: return Join(type.name, null, null).takeUnless { isLibrary(type.name) }
        if (isLibrary(declaring)) return null
        return Join(declaring, "handle", if (typed != null) HANDLE_DESCRIPTOR else ERASED_HANDLE_DESCRIPTOR)
    }

    private fun isLibrary(className: String) = LIBRARY_PREFIXES.any { className.startsWith(it) }
}
