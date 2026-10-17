package dev.otherlode.instrumentation.endpoints.micronaut3

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.endpoints.micronautcommon.HandleJoin
import java.util.IdentityHashMap
import java.util.stream.Stream

internal const val MODULE_NAME = "micronaut-3"

private const val ROUTER = "io.micronaut.web.router.Router"
private const val ROUTE_INFO = "io.micronaut.web.router.RouteInfo"
private const val URI_ROUTE = "io.micronaut.web.router.UriRoute"
private const val METHOD_BASED_ROUTE = "io.micronaut.web.router.MethodBasedRoute"
private const val URI_MATCH_TEMPLATE = "io.micronaut.http.uri.UriMatchTemplate"
private const val METHOD_REFERENCE = "io.micronaut.inject.MethodReference"
private const val ANNOTATION_METADATA = "io.micronaut.core.annotation.AnnotationMetadata"
private const val GET_STEREOTYPE = "io.micronaut.http.annotation.Get"
private const val ON_OPEN_ANNOTATION = "io.micronaut.websocket.annotation.OnOpen"

/**
 * Reads the URI routes of a Micronaut 3 `Router` and registers each one as an endpoint.
 *
 * No Micronaut type is on this class's own class loader, so every one is reached by name through
 * the loader the router came from. Every method is resolved on a public interface, never on the
 * implementing class, which may not be public.
 */
internal class RouterWalk {
    /**
     * Registers the URI routes of [router], keyed by the `UriRoute` instance that dispatch returns
     * from `UriRouteMatch.getRoute()`. A route is registered once however often the router lists
     * it. A HEAD route that Micronaut adds beside a `@Get` and a WebSocket route are not declared.
     */
    fun declare(router: Any) {
        val loader = router.javaClass.classLoader
        val routerType = Class.forName(ROUTER, false, loader)
        if (!routerType.isInstance(router)) return
        val routeInfo = Class.forName(ROUTE_INFO, false, loader)
        val uriRoute = Class.forName(URI_ROUTE, false, loader)
        val methodBased = Class.forName(METHOD_BASED_ROUTE, false, loader)
        val uriMatchTemplate = Class.forName(URI_MATCH_TEMPLATE, false, loader)
        val methodReference = Class.forName(METHOD_REFERENCE, false, loader)
        val annotationMetadata = Class.forName(ANNOTATION_METADATA, false, loader)

        val uriRoutes = routerType.getMethod("uriRoutes")
        val httpMethodName = uriRoute.getMethod("getHttpMethodName")
        val getUriMatchTemplate = uriRoute.getMethod("getUriMatchTemplate")
        val toPathString = uriMatchTemplate.getMethod("toPathString")
        val isWebSocketRoute = routeInfo.getMethod("isWebSocketRoute")
        val getAnnotationMetadata = routeInfo.getMethod("getAnnotationMetadata")
        val hasStereotype = annotationMetadata.getMethod("hasStereotype", String::class.java)
        val hasAnnotation = annotationMetadata.getMethod("hasAnnotation", String::class.java)
        val handleJoin =
            HandleJoin(
                methodBased.getMethod("getTargetMethod"),
                methodReference.getMethod("getDeclaringType"),
                methodReference.getMethod("getMethodName"),
                methodReference.getMethod("getArgumentTypes"),
            )

        val seen = IdentityHashMap<Any, Boolean>()
        val routes = (uriRoutes.invoke(router) as Stream<*>).toList().filterNotNull().filter { seen.put(it, true) == null }
        val getTargets =
            routes
                .filter { httpMethodName.invoke(it) == "GET" }
                .mapTo(HashSet()) { (toPathString.invoke(getUriMatchTemplate.invoke(it)) as String) to handleJoin.target(it) }
        for (route in routes) {
            val metadata = getAnnotationMetadata.invoke(route)
            val verb = httpMethodName.invoke(route) as String
            if (isWebSocketRoute.invoke(route) == true || hasAnnotation.invoke(metadata, ON_OPEN_ANNOTATION) == true) continue
            val template = toPathString.invoke(getUriMatchTemplate.invoke(route)) as String
            if (verb == "HEAD" && hasStereotype.invoke(metadata, GET_STEREOTYPE) == true &&
                (template to handleJoin.target(route)) in getTargets
            ) {
                continue
            }
            val handler = handleJoin.of(route)
            OtherlodeEndpoints.register(
                MODULE_NAME,
                route,
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
