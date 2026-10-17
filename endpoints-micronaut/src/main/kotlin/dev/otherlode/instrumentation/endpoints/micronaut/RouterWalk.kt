package dev.otherlode.instrumentation.endpoints.micronaut

import dev.otherlode.bootstrap.OtherlodeEndpoints
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.stream.Stream

internal const val MODULE_NAME = "micronaut"

private const val ROUTER = "io.micronaut.web.router.Router"
private const val ROUTE_INFO = "io.micronaut.web.router.RouteInfo"
private const val URI_ROUTE_INFO = "io.micronaut.web.router.UriRouteInfo"
private const val METHOD_BASED_ROUTE_INFO = "io.micronaut.web.router.MethodBasedRouteInfo"
private const val URI_MATCH_TEMPLATE = "io.micronaut.http.uri.UriMatchTemplate"
private const val METHOD_REFERENCE = "io.micronaut.inject.MethodReference"
private const val ANNOTATION_METADATA = "io.micronaut.core.annotation.AnnotationMetadata"

internal const val GET_STEREOTYPE = "io.micronaut.http.annotation.Get"
internal const val ON_OPEN_ANNOTATION = "io.micronaut.websocket.annotation.OnOpen"

/**
 * Reads the URI routes of a Micronaut `Router` and registers each one as an endpoint.
 *
 * No Micronaut type is on this class's own class loader, so every one is reached by name through
 * the loader the router came from. Every method is resolved on a public interface, never on the
 * implementing class, which may not be public.
 */
internal class RouterWalk {
    /**
     * Registers the URI routes of [router], keyed by the `UriRouteInfo` instance that dispatch
     * returns. A route is registered once however often the router lists it. A HEAD route that
     * Micronaut adds beside a `@Get` and a WebSocket route are not declared.
     */
    fun declare(router: Any) {
        val loader = router.javaClass.classLoader
        val routerType = Class.forName(ROUTER, false, loader)
        if (!routerType.isInstance(router)) return
        val routeInfo = Class.forName(ROUTE_INFO, false, loader)
        val uriRouteInfo = Class.forName(URI_ROUTE_INFO, false, loader)
        val methodBased = Class.forName(METHOD_BASED_ROUTE_INFO, false, loader)
        val uriMatchTemplate = Class.forName(URI_MATCH_TEMPLATE, false, loader)
        val methodReference = Class.forName(METHOD_REFERENCE, false, loader)
        val annotationMetadata = Class.forName(ANNOTATION_METADATA, false, loader)

        val uriRoutes = routerType.getMethod("uriRoutes")
        val httpMethodName = uriRouteInfo.getMethod("getHttpMethodName")
        val getUriMatchTemplate = uriRouteInfo.getMethod("getUriMatchTemplate")
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

private class Handler(
    val className: String,
    val methodName: String,
    val descriptor: String,
)

private class HandleJoin(
    private val getTargetMethod: Method,
    private val getDeclaringType: Method,
    private val getMethodName: Method,
    private val getArgumentTypes: Method,
) {
    private val pools = HashMap<ClassLoader?, TypePool>()

    /** The class Micronaut names and the method name of [route]'s target, or null for a route that is not method-based. */
    fun target(route: Any): Pair<String, String>? {
        if (!getTargetMethod.declaringClass.isInstance(route)) return null
        val target = getTargetMethod.invoke(route) ?: return null
        return (getDeclaringType.invoke(target) as Class<*>).name to (getMethodName.invoke(target) as String)
    }

    /**
     * The method [route] runs, as the class file that declares it has it, or null when it is not
     * method-based or not found. Micronaut names the bean class, which for an AOP bean is its
     * generated `$Definition$Intercepted` subclass, and the argument classes resolved for that bean,
     * so the method is found among the bean's superclasses and interfaces by its name and arguments.
     * Class files are read; nothing is loaded.
     */
    fun of(route: Any): Handler? {
        if (!getTargetMethod.declaringClass.isInstance(route)) return null
        val target = getTargetMethod.invoke(route) ?: return null
        val beanType = getDeclaringType.invoke(target) as Class<*>
        val methodName = getMethodName.invoke(target) as String
        val arguments = (getArgumentTypes.invoke(target) as Array<*>).map { (it as Class<*>).name }
        return try {
            val pool =
                pools.getOrPut(
                    beanType.classLoader,
                ) { TypePool.Default.of(ClassFileLocator.ForClassLoader.of(beanType.classLoader)) }
            declaringMethod(pool, pool.describe(beanType.name).resolve(), methodName, arguments)?.let {
                Handler(it.declaringType.asErasure().name, methodName, it.descriptor)
            }
        } catch (e: RuntimeException) {
            null
        }
    }
}

private fun declaringMethod(
    pool: TypePool,
    beanType: TypeDescription,
    methodName: String,
    arguments: List<String>,
): MethodDescription? {
    val classes = generateSequence(beanType) { it.superClass?.asErasure() }.filterNot { it.name.endsWith(INTERCEPTED_SUFFIX) }.toList()
    val interfaces = LinkedHashMap<String, TypeDescription>()
    val pending = ArrayDeque(classes.flatMap { type -> type.interfaces.map { it.asErasure() } })
    while (pending.isNotEmpty()) {
        val type = pending.removeFirst()
        if (interfaces.putIfAbsent(type.name, type) == null) type.interfaces.forEach { pending.addLast(it.asErasure()) }
    }

    fun candidates(types: Collection<TypeDescription>): List<MethodDescription> =
        types.flatMap { type ->
            type.declaredMethods.filter {
                it.internalName == methodName &&
                    it.parameters.size == arguments.size &&
                    !(it.isBridge && it.isSynthetic) &&
                    !it.isAbstract &&
                    !it.isStatic &&
                    !it.isPrivate
            }
        }
    val inClasses = candidates(classes)
    val inInterfaces = candidates(interfaces.values)

    fun pick(accepts: (MethodDescription) -> Boolean): MethodDescription? {
        inClasses.firstOrNull(accepts)?.let { return it }
        val accepted = inInterfaces.filter(accepts)
        return accepted
            .filter { method ->
                accepted.none { other ->
                    other.declaringType != method.declaringType &&
                        other.declaringType.asErasure().isAssignableTo(method.declaringType.asErasure())
                }
            }.singleOrNull()
    }

    val argumentTypes by lazy { arguments.map { pool.describe(it).resolve() } }
    return pick { method -> method.parameters.map { it.type.asErasure().name } == arguments }
        ?: pick { method ->
            method.parameters.zip(argumentTypes).all { (parameter, argument) ->
                argument.isAssignableTo(parameter.type.asErasure())
            }
        }
}

/** The suffix of the subclass Micronaut generates for a bean with AOP advice. */
private const val INTERCEPTED_SUFFIX = "\$Intercepted"
