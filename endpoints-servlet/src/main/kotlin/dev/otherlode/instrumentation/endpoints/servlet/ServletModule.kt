package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.instrumentation.endpoints.api.SupertypeCache
import dev.otherlode.instrumentation.endpoints.api.SupertypeCaches
import dev.otherlode.instrumentation.endpoints.api.SupertypeResolver
import dev.otherlode.instrumentation.endpoints.api.UndefinableTypeException
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresMethod
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isInterface
import net.bytebuddy.matcher.ElementMatchers.isPublic
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArgument
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.util.Collections
import java.util.WeakHashMap

private const val ADVICE = "dev.otherlode.endpoints.servlet.ServletServiceAdvice"
private val NAMESPACES = listOf("javax", "jakarta")
private val SERVLET_MARKERS = NAMESPACES.map { "$it/servlet/Servlet.class" }
private val SERVLET_INTERFACES = NAMESPACES.map { "$it.servlet.Servlet" }.toSet()
private val SKIPPED_SUPERTYPE_PREFIXES = listOf("java.", "jdk.", "sun.", "com.sun.", "kotlin.")
private val PLATFORM_LOADER: ClassLoader = ClassLoader.getPlatformClassLoader()

private val SERVICE_DESCRIPTOR_MARKER = "/servlet/ServletResponse;)".toByteArray(Charsets.US_ASCII)

private fun isSkippedSupertype(name: String): Boolean = SKIPPED_SUPERTYPE_PREFIXES.any { name.startsWith(it) }

/**
 * Whether [type] is, or extends or implements, a servlet interface, and every supertype it needs to
 * be defined can be described or loaded. A supertype that can be neither means [type] can never be
 * defined, so it is not a servlet this module can count.
 */
private fun isServlet(
    type: TypeDescription,
    resolver: SupertypeResolver,
): Boolean =
    try {
        reachesServlet(type, resolver, mutableSetOf()) && resolver.canBeDefined(type)
    } catch (_: UndefinableTypeException) {
        false
    }

private fun reachesServlet(
    type: TypeDescription,
    resolver: SupertypeResolver,
    visited: MutableSet<String>,
): Boolean {
    if (type.name in SERVLET_INTERFACES) return true
    if (!visited.add(type.name)) return false
    val parent = resolver.superErasure(type)
    if (parent != null && reachesServlet(parent, resolver, visited)) return true
    return resolver.interfaceErasures(type).any { reachesServlet(it, resolver, visited) }
}

private fun serviceMethod(namespace: String): ElementMatcher.Junction<MethodDescription> =
    named<MethodDescription>("service")
        .and(takesArguments(2))
        .and(takesArgument(0, named<TypeDescription>("$namespace.servlet.ServletRequest")))
        .and(takesArgument(1, named<TypeDescription>("$namespace.servlet.ServletResponse")))
        .and(isPublic())
        .and(not(isAbstract()))
        .and(not(isStatic()))
        .and(not(isBridge()))

private val MARKER_SHIFTS =
    IntArray(256) { SERVICE_DESCRIPTOR_MARKER.size }.also { shifts ->
        for (i in 0 until SERVICE_DESCRIPTOR_MARKER.size - 1) {
            shifts[SERVICE_DESCRIPTOR_MARKER[i].toInt() and 0xff] = SERVICE_DESCRIPTOR_MARKER.size - 1 - i
        }
    }

private fun containsMarker(haystack: ByteArray): Boolean {
    val marker = SERVICE_DESCRIPTOR_MARKER
    val last = marker.size - 1
    var start = 0
    while (start <= haystack.size - marker.size) {
        var i = last
        while (i >= 0 && haystack[start + i] == marker[i]) i--
        if (i < 0) return true
        start += MARKER_SHIFTS[haystack[start + last].toInt() and 0xff]
    }
    return false
}

/** Whether a loader can see a servlet API, looked up once per loader and held weakly. */
private class ServletApiGate : ElementMatcher<ClassLoader?> {
    private val answers = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())

    override fun matches(target: ClassLoader?): Boolean {
        if (target == null || target === PLATFORM_LOADER) return false
        answers[target]?.let { return it }
        val seen = SERVLET_MARKERS.any { target.getResource(it) != null }
        answers[target] = seen
        return seen
    }
}

/**
 * Endpoint module for plain servlets, covering the `javax.servlet` and `jakarta.servlet`
 * namespaces with one module compiled against Servlet 4.0. See ADR 0074.
 *
 * A request is counted at the entry of the servlet's public `service(ServletRequest,
 * ServletResponse)`, for a `REQUEST` dispatch, against the mapping the container matched
 * (`HttpServletRequest.getHttpServletMapping`). The advice is woven onto every servlet class that
 * declares that method, `HttpServlet` included, and a request is counted by exactly one of the
 * frames it passes through: the keys of a servlet are bound only for the most derived class that
 * declares the method (see [ServletShapes]).
 *
 * A servlet context is declared at the first `REQUEST` dispatch that reaches a hooked servlet in it,
 * from its servlet registrations, and again before each manifest for a mapping added since (see [ServletContexts]). Servlets another
 * module counts behind, and a container's own servlets, are left out.
 */
class ServletModule : EndpointModule {
    override val name: String = MODULE_NAME

    private val contexts = ServletContexts(ServletShapes())
    private val apiGate = ServletApiGate()
    private val supertypeCaches = SupertypeCaches(::SupertypeCache)

    override fun classLoaderMatcher(): ElementMatcher<in ClassLoader> = apiGate

    /**
     * Whether [classFile] holds the text `/servlet/ServletResponse;)`. Every method [serviceMethod]
     * accepts has a descriptor that ends its arguments with it, stored as a constant pool entry.
     */
    override fun classFileMayMatch(classFile: ByteArray): Boolean = containsMarker(classFile)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = typeMatcher(null)

    /**
     * A supertype the pool has no class file for is asked of [classLoader] (see
     * [SupertypeResolver]): one that cannot be loaded means the class can never be defined, so the
     * answer is no match, and one that loads is read from the loaded class.
     */
    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val resolver = SupertypeResolver(classLoader, supertypeCaches.cacheFor(classLoader), ::isSkippedSupertype)
        return not(isInterface<TypeDescription>())
            .and(declaresMethod(serviceMethod("javax").or(serviceMethod("jakarta"))))
            .and(ElementMatcher<TypeDescription> { type -> isServlet(type, resolver) })
    }

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        var woven = builder
        for (namespace in NAMESPACES) {
            val method = serviceMethod(namespace)
            if (typeDescription.declaredMethods.none { method.matches(it) }) continue
            val remap = if (namespace == "jakarta") mapOf("javax/servlet/" to "jakarta/servlet/") else emptyMap()
            woven = woven.visit(advice.hook(ADVICE, method, remap))
        }
        return woven
    }

    override fun declare(frameworkObject: Any) = contexts.declare(frameworkObject)

    override fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) = contexts.refreshAll()
}
