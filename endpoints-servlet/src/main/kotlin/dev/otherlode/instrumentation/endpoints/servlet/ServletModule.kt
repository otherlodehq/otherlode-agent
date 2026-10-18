package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresMethod
import net.bytebuddy.matcher.ElementMatchers.hasSuperType
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isInterface
import net.bytebuddy.matcher.ElementMatchers.isPublic
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArgument
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.util.Collections
import java.util.WeakHashMap

private const val ADVICE = "dev.otherlode.endpoints.servlet.ServletServiceAdvice"
private val NAMESPACES = listOf("javax", "jakarta")
private val SERVLET_MARKERS = NAMESPACES.map { "$it/servlet/Servlet.class" }
private val PLATFORM_LOADER: ClassLoader = ClassLoader.getPlatformClassLoader()

private fun serviceMethod(namespace: String): ElementMatcher.Junction<MethodDescription> =
    named<MethodDescription>("service")
        .and(takesArguments(2))
        .and(takesArgument(0, named<TypeDescription>("$namespace.servlet.ServletRequest")))
        .and(takesArgument(1, named<TypeDescription>("$namespace.servlet.ServletResponse")))
        .and(isPublic())
        .and(not(isAbstract()))
        .and(not(isStatic()))
        .and(not(isBridge()))

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
 * A servlet context is declared at its first counted request, from its servlet registrations, and
 * again before each manifest for a mapping added since (see [ServletContexts]). Servlets another
 * module counts behind, and a container's own servlets, are left out.
 */
class ServletModule : EndpointModule {
    override val name: String = MODULE_NAME

    private val contexts = ServletContexts(ServletShapes())
    private val apiGate = ServletApiGate()

    override fun classLoaderMatcher(): ElementMatcher<in ClassLoader> = apiGate

    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        not(isInterface<TypeDescription>())
            .and(declaresMethod(serviceMethod("javax").or(serviceMethod("jakarta"))))
            .and(hasSuperType(namedOneOf<TypeDescription>("javax.servlet.Servlet", "jakarta.servlet.Servlet")))

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
