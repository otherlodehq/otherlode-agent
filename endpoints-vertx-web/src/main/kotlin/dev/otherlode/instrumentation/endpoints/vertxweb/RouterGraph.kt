package dev.otherlode.instrumentation.endpoints.vertxweb

import java.lang.ref.WeakReference

private const val BLOCKING_HANDLER_DECORATOR = "io.vertx.ext.web.impl.BlockingHandlerDecorator"

/**
 * What the module has learned about the routers of one JVM from the events its advice raises:
 * which route mounts which router, each route's regex source and context handlers, and when each
 * router last changed. Objects are held weakly, so a router an application discards is forgotten.
 *
 * A change to a router moves a generation counter. A root router's request compares it with the
 * generation the root was last declared at, and only a root with a changed router among those it
 * reached declares again.
 */
internal class RouterGraph {
    /** What the events say about one route. Read and written under the graph's lock. */
    internal class RouteInfo {
        var regexSource: String? = null
        var handlerCount = 0
        val handlers = ArrayList<WeakReference<Any>>()
        var mounted: WeakReference<Any>? = null
        var mounting = false
        var pendingBlocking: WeakReference<Any>? = null
    }

    private class RouterInfo {
        @Volatile
        var changedAt = 0L
    }

    private class RootRecord {
        @Volatile
        var declaredAt = -1L
        var reached: List<WeakReference<Any>>? = null
    }

    private val lock = Any()

    @Volatile
    private var generation = 0L
    private val routers = WeakIdentityMap<RouterInfo>()
    private val routes = WeakIdentityMap<RouteInfo>()
    private val roots = WeakIdentityMap<RootRecord>()

    /** Applies one event an advice raised; the first element names its kind. */
    fun apply(event: Array<*>) {
        synchronized(lock) {
            when (event[0]) {
                "add", "remove", "clear", "changed" -> {
                    touch(event[1] ?: return)
                }

                "mounting" -> {
                    routeInfo(event[2] ?: return).mounting = true
                    touch(event[1] ?: return)
                }

                "mount" -> {
                    val info = routeInfo(event[2] ?: return)
                    info.mounting = false
                    info.mounted = WeakReference(event[3] ?: return)
                    touch(event[1] ?: return)
                }

                "mountFailed" -> {
                    routeInfo(event[2] ?: return).mounting = false
                    touch(event[1] ?: return)
                }

                "regex" -> {
                    routeInfo(event[2] ?: return).regexSource = event[3] as String
                    touch(event[1] ?: return)
                }

                "blocking" -> {
                    routeInfo(event[1] ?: return).pendingBlocking = WeakReference(event[2] ?: return)
                }

                "handler" -> {
                    val info = routeInfo(event[2] ?: return)
                    val handler = event[3] ?: return
                    val written = info.pendingBlocking?.get()
                    info.pendingBlocking = null
                    info.handlerCount++
                    info.handlers +=
                        WeakReference(if (written != null && handler.javaClass.name == BLOCKING_HANDLER_DECORATOR) written else handler)
                    touch(event[1] ?: return)
                }
            }
        }
    }

    /** What the events said about [route], or null when none named it. Call it only from the `declare` function [declareFrom] runs. */
    fun infoOf(route: Any): RouteInfo? = routes[route]

    /**
     * Runs [declare] for [root] when it has never been declared, or a router it reached changed
     * since. [declare] returns the routers it reached. A root that is current costs a map lookup
     * and two volatile reads.
     */
    fun declareFrom(
        root: Any,
        declare: (Any) -> List<Any>,
    ) {
        val known = roots[root]
        if (known != null && known.declaredAt == generation) return
        synchronized(lock) {
            val record = roots.getOrPut(root) { RootRecord() }
            val now = generation
            if (record.declaredAt == now) return
            val reached = record.reached
            val stale =
                reached == null ||
                    reached.any { ref -> ref.get()?.let { (routers[it]?.changedAt ?: 0L) > record.declaredAt } == true }
            if (stale) record.reached = declare(root).map { WeakReference(it) }
            record.declaredAt = now
        }
    }

    private fun routeInfo(route: Any): RouteInfo = routes.getOrPut(route) { RouteInfo() }

    private fun touch(router: Any) {
        generation++
        routers.getOrPut(router) { RouterInfo() }.changedAt = generation
    }
}
