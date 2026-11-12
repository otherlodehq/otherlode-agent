package dev.otherlode.instrumentation.endpoints.jaxrs

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.instrumentation.endpoints.api.SupertypeCache
import dev.otherlode.instrumentation.endpoints.api.SupertypeCaches
import dev.otherlode.instrumentation.endpoints.api.SupertypeResolver
import dev.otherlode.instrumentation.endpoints.api.UndefinableTypeException
import net.bytebuddy.description.annotation.AnnotationSource
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isInterface
import net.bytebuddy.matcher.ElementMatchers.isNative
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.not
import java.lang.System.Logger.Level
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val MODULE = "jaxrs"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.jaxrs"
private const val WILDCARD_VERB = "*"
private const val SUPERTYPE_CACHE_SIZE = 8192
private val API_MARKER_RESOURCES = listOf("jakarta/ws/rs/Path.class", "javax/ws/rs/Path.class")
private val PLATFORM_LOADER: ClassLoader = ClassLoader.getPlatformClassLoader()
private const val JERSEY_MARKER_RESOURCE = "org/glassfish/jersey/server/model/Resource.class"
private const val RESTEASY_REACTIVE_MARKER_RESOURCE = "org/jboss/resteasy/reactive/server/core/ResteasyReactiveRequestContext.class"
private const val IGNORED_CLASS_PATH_LOG_LIMIT = 16

private val NAMESPACES = listOf("javax.ws.rs", "jakarta.ws.rs")
private val VERBS = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")
private val PATH_NAMES = NAMESPACES.map { "$it.Path" }.toSet()
private val VERB_NAMES = NAMESPACES.flatMap { namespace -> VERBS.map { verb -> "$namespace.$verb" } }.toSet()
private val HTTP_METHOD_NAMES = NAMESPACES.map { "$it.HttpMethod" }.toSet()

// Built at run time: shadowJar rewrites a constant that starts with `kotlin`.
private val KOTLIN_PREFIX = listOf("kot", "lin", ".").joinToString("")

private val SKIPPED_SUPERTYPE_PREFIXES = listOf("java.", "jdk.", "sun.", "com.sun.", KOTLIN_PREFIX, "jakarta.", "javax.")

private val ELIGIBLE_METHOD: ElementMatcher.Junction<MethodDescription> =
    not(isStatic<MethodDescription>()).and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic()))

/**
 * Endpoint module for JAX-RS, covering the `javax.ws.rs` and `jakarta.ws.rs` namespaces with one
 * module and no compile-time dependency on either.
 *
 * A method the concrete class declares with no JAX-RS annotation of its own inherits the
 * annotations of the method it overrides or implements. A class-level `@Path` on a supertype is
 * inherited only when Jersey or Quarkus REST (RESTEasy Reactive) is on the resource class's loader.
 *
 * [jerseyPresent] and [restEasyReactivePresent] answer whether Jersey, or RESTEasy Reactive's
 * server classes, are on a resource class's loader.
 */
class JaxRsModule
    // Keeps the no-argument constructor that java.util.ServiceLoader needs.
    @JvmOverloads
    constructor(
        private val jerseyPresent: (ClassLoader?) -> Boolean = ::isJerseyPresent,
        private val restEasyReactivePresent: (ClassLoader?) -> Boolean = ::isRestEasyReactivePresent,
    ) : EndpointModule {
        override val name: String = MODULE

        override val weaveFailureLeavesNothingPartial: Boolean = true

        private val log by lazy { OtherlodeLoggers.get(JaxRsModule::class.java.name) }
        private val classPathIgnoredWarned = ConcurrentHashMap.newKeySet<String>()
        private val interfaceConflictWarned = AtomicBoolean(false)
        private val supertypeCaches = SupertypeCaches(::RouteAnnotationCache)
        private val loaderGate = JaxRsLoaderGate()

        /**
         * A concrete class is in scope if it, or any supertype in its superclass chain or interface
         * graph, carries `@Path`, or declares a method carrying `@Path` or one of the seven
         * standard verb annotations. A supertype that can be neither described nor loaded means
         * the class can never be defined, so it does not match.
         *
         * A method carrying only a custom `@HttpMethod` annotation does not bring a class into
         * scope.
         */
        override fun typeMatcher(): ElementMatcher<in TypeDescription> = typeMatcher(null)

        /**
         * [typeMatcher] with the supertype walk's answers cached per [classLoader]. A `null`
         * loader caches nothing.
         */
        override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
            val cache = supertypeCaches.cacheFor(classLoader)
            val resolver = SupertypeResolver(classLoader, cache, ::isSkippedSupertype)
            return not(isInterface<TypeDescription>())
                .and(not(isAbstract()))
                .and(not(isSynthetic()))
                .and(ElementMatcher<TypeDescription> { type -> inScope(type, cache, resolver) })
        }

        /**
         * Accepts only a loader that can see a JAX-RS API's `Path` class as a resource, found
         * without loading a class and cached per loader. The bootstrap and platform loaders are
         * rejected without a lookup.
         */
        override fun classLoaderMatcher(): ElementMatcher<in ClassLoader> = loaderGate

        /**
         * Reads every eligible declared method for a verb and a route template, binds
         * [dev.otherlode.endpoints.jaxrs.ResourceMethodAdvice], registers each method found
         * through the endpoint seam, then weaves the advice onto exactly those methods.
         *
         * A method with neither a verb annotation nor `@Path`, own or inherited, is not an
         * endpoint and is left alone. A method with `@Path` but no verb is a sub-resource locator
         * and registers with verb `*`.
         */
        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> {
            val resolver = SupertypeResolver(classLoader, supertypeCaches.cacheFor(classLoader), ::isSkippedSupertype)
            val classPath = resolveClassPath(typeDescription, classLoader, resolver) ?: return builder
            val matched =
                typeDescription.declaredMethods.filter(ELIGIBLE_METHOD).mapNotNull { method ->
                    val source = resolveAnnotationSource(method, typeDescription, resolver)
                    val verb = resolveVerb(source)
                    val methodPath = pathValue(source)
                    if (verb == null &&
                        methodPath == null
                    ) {
                        null
                    } else {
                        Triple(method, verb ?: WILDCARD_VERB, combineTemplate(classPath, methodPath))
                    }
                }
            if (matched.isEmpty()) return builder

            val matchedMethods = matched.mapTo(mutableSetOf()) { (method, _, _) -> method.internalName to method.descriptor }
            val matchesRead =
                ElementMatcher<MethodDescription> { method -> (method.internalName to method.descriptor) in matchedMethods }
            // Bound before anything is declared, so advice that cannot be bound leaves no route behind.
            val wrapper = advice.hook("$ADVICE_PACKAGE.ResourceMethodAdvice", matchesRead)
            for ((method, verb, template) in matched) {
                val key = "${typeDescription.name}#${method.internalName}${method.descriptor}"
                OtherlodeEndpoints.register(MODULE, key, verb, template, null, typeDescription.name, method.internalName, method.descriptor)
            }
            return builder.visit(wrapper)
        }

        private fun resolveAnnotationSource(
            method: MethodDescription.InDefinedShape,
            type: TypeDescription,
            resolver: SupertypeResolver,
        ): AnnotationSource {
            if (hasOwnJaxRsAnnotation(method)) return method
            return findSuperclassMethod(type, method, resolver)
                ?: findInterfaceMethod(type, method, resolver)
                ?: method
        }

        private fun findSuperclassMethod(
            type: TypeDescription,
            target: MethodDescription.InDefinedShape,
            resolver: SupertypeResolver,
        ): AnnotationSource? {
            var current = resolver.superErasure(type)
            while (current != null) {
                val candidate = current
                val match = runCatching { candidate.declaredMethods.firstOrNull { matchingAnnotated(it, target) } }.getOrNull()
                if (match != null) return match
                current = resolver.superErasure(candidate)
            }
            return null
        }

        private fun findInterfaceMethod(
            type: TypeDescription,
            target: MethodDescription.InDefinedShape,
            resolver: SupertypeResolver,
        ): AnnotationSource? {
            val matches = mutableListOf<MethodDescription.InDefinedShape>()

            fun walk(iface: TypeDescription) {
                runCatching { iface.declaredMethods.firstOrNull { matchingAnnotated(it, target) } }.getOrNull()?.let { matches += it }
                resolver.interfaceErasures(iface).forEach(::walk)
            }
            resolver.interfaceErasures(type).forEach(::walk)

            val first = matches.firstOrNull() ?: return null
            val conflicting = matches.drop(1).any { !sameVerbAndPath(first, it) }
            if (conflicting && interfaceConflictWarned.compareAndSet(false, true)) {
                log.log(
                    Level.WARNING,
                    "otherlode: jaxrs: ${type.name}#${target.internalName} inherits conflicting JAX-RS annotations " +
                        "from more than one interface, using ${first.declaringType.name}",
                )
            }
            return first
        }

        private fun matchingAnnotated(
            candidate: MethodDescription.InDefinedShape,
            target: MethodDescription.InDefinedShape,
        ): Boolean = sameSignature(candidate, target) && hasOwnJaxRsAnnotation(candidate)

        private fun resolveClassPath(
            type: TypeDescription,
            classLoader: ClassLoader?,
            resolver: SupertypeResolver,
        ): String? {
            pathValue(type)?.let { return it }
            if (jerseyPresent(classLoader)) return findInheritedClassPath(type, resolver)
            if (restEasyReactivePresent(classLoader)) return findQuarkusInheritedClassPath(type, resolver)
            if (findInheritedClassPath(type, resolver) == null) return null
            if (classPathIgnoredWarned.size < IGNORED_CLASS_PATH_LOG_LIMIT && classPathIgnoredWarned.add(type.name)) {
                log.log(
                    Level.INFO,
                    "otherlode: jaxrs: class-level @Path on a supertype of ${type.name} is ignored, so it declares no " +
                        "endpoints: classic RESTEasy and the JAX-RS specification do not inherit it",
                )
            }
            return null
        }

        private fun findInheritedClassPath(
            type: TypeDescription,
            resolver: SupertypeResolver,
        ): String? {
            var foundInterfacePath = resolver.interfaceErasures(type).firstNotNullOfOrNull { pathValue(it) }
            var current: TypeDescription? = resolver.superErasure(type)
            while (current != null) {
                pathValue(current)?.let { return it }
                if (foundInterfacePath ==
                    null
                ) {
                    foundInterfacePath = resolver.interfaceErasures(current).firstNotNullOfOrNull { pathValue(it) }
                }
                current = resolver.superErasure(current)
            }
            return foundInterfacePath
        }

        private fun findQuarkusInheritedClassPath(
            type: TypeDescription,
            resolver: SupertypeResolver,
        ): String? {
            resolver.superErasure(type)?.takeIf { it.isAbstract }?.let { superclass -> pathValue(superclass)?.let { return it } }
            var current: TypeDescription? = type
            while (current != null) {
                resolver.interfaceErasures(current).firstNotNullOfOrNull { pathValue(it) }?.let { return it }
                current = resolver.superErasure(current)
            }
            return null
        }

        private fun resolveVerb(source: AnnotationSource): String? {
            for (annotation in source.declaredAnnotations) {
                val annotationType = annotation.annotationType
                if (annotationType.name in VERB_NAMES) return annotationType.name.substringAfterLast('.')
                val metaAnnotation = annotationType.declaredAnnotations.firstOrNull { it.annotationType.name in HTTP_METHOD_NAMES }
                if (metaAnnotation != null) {
                    val value = metaAnnotation.getValue("value").resolve(String::class.java)
                    if (!value.isNullOrBlank()) return value.uppercase()
                }
            }
            return null
        }

        private fun pathValue(source: AnnotationSource): String? {
            val annotation = source.declaredAnnotations.firstOrNull { it.annotationType.name in PATH_NAMES } ?: return null
            return annotation.getValue("value").resolve(String::class.java)
        }

        private fun sameVerbAndPath(
            a: AnnotationSource,
            b: AnnotationSource,
        ): Boolean = resolveVerb(a) == resolveVerb(b) && pathValue(a) == pathValue(b)
    }

private fun combineTemplate(
    classPath: String,
    methodPath: String?,
): String {
    val segments = listOfNotNull(classPath, methodPath).map { it.trim('/') }.filter { it.isNotEmpty() }
    return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
}

private fun hasOwnJaxRsAnnotation(source: AnnotationSource): Boolean =
    source.declaredAnnotations.any { annotation ->
        NAMESPACES.any { namespace -> annotation.annotationType.name.startsWith("$namespace.") }
    }

private fun declaresOwnPathOrVerb(type: TypeDescription): Boolean =
    pathAnnotated(type) || type.declaredMethods.any { pathAnnotated(it) || verbAnnotated(it) }

private fun pathAnnotated(source: AnnotationSource): Boolean = source.declaredAnnotations.any { it.annotationType.name in PATH_NAMES }

private fun verbAnnotated(source: AnnotationSource): Boolean = source.declaredAnnotations.any { it.annotationType.name in VERB_NAMES }

private fun inScope(
    type: TypeDescription,
    cache: RouteAnnotationCache,
    resolver: SupertypeResolver,
): Boolean =
    try {
        (declaresOwnPathOrVerb(type) || hasInheritedPathOrVerb(type, cache, resolver)) && resolver.canBeDefined(type)
    } catch (_: UndefinableTypeException) {
        false
    }

private fun hasInheritedPathOrVerb(
    type: TypeDescription,
    cache: RouteAnnotationCache,
    resolver: SupertypeResolver,
): Boolean {
    val inProgress = mutableSetOf<String>()
    var guardHits = 0

    fun walk(candidate: TypeDescription): Boolean {
        val name = candidate.name
        cache[name]?.let { return it }
        if (!inProgress.add(name)) {
            guardHits++
            return false
        }
        val hitsBefore = guardHits
        val found =
            declaresOwnPathOrVerb(candidate) ||
                resolver.superErasure(candidate)?.let { walk(it) } == true ||
                resolver.interfaceErasures(candidate).any { walk(it) }
        inProgress.remove(name)
        if (found || guardHits == hitsBefore) cache[name] = found
        return found
    }

    val parent = resolver.superErasure(type)
    if (parent != null && walk(parent)) return true
    return resolver.interfaceErasures(type).any { walk(it) }
}

private class RouteAnnotationCache : SupertypeCache() {
    private val entries =
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > SUPERTYPE_CACHE_SIZE
        }

    override fun isKnown(name: String): Boolean = super.isKnown(name) || get(name) != null

    operator fun get(name: String): Boolean? = synchronized(entries) { entries[name] }

    operator fun set(
        name: String,
        value: Boolean,
    ) {
        synchronized(entries) { entries[name] = value }
    }
}

private class JaxRsLoaderGate : ElementMatcher<ClassLoader?> {
    private val answers = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())

    override fun matches(target: ClassLoader?): Boolean {
        if (target == null || target === PLATFORM_LOADER) return false
        answers[target]?.let { return it }
        val seen = API_MARKER_RESOURCES.any { target.getResource(it) != null }
        answers[target] = seen
        return seen
    }
}

private fun isSkippedSupertype(name: String): Boolean = SKIPPED_SUPERTYPE_PREFIXES.any { name.startsWith(it) }

private fun sameSignature(
    a: MethodDescription,
    b: MethodDescription,
): Boolean = a.internalName == b.internalName && a.descriptor == b.descriptor

private class MarkerPresence(
    private val marker: String,
) {
    private val answers = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())

    fun isPresentOn(classLoader: ClassLoader?): Boolean {
        val loader = classLoader ?: ClassLoader.getSystemClassLoader()
        answers[loader]?.let { return it }
        val seen = loader.getResource(marker) != null
        answers[loader] = seen
        return seen
    }
}

private val JERSEY_PRESENCE = MarkerPresence(JERSEY_MARKER_RESOURCE)
private val RESTEASY_REACTIVE_PRESENCE = MarkerPresence(RESTEASY_REACTIVE_MARKER_RESOURCE)

private fun isJerseyPresent(classLoader: ClassLoader?): Boolean = JERSEY_PRESENCE.isPresentOn(classLoader)

private fun isRestEasyReactivePresent(classLoader: ClassLoader?): Boolean = RESTEASY_REACTIVE_PRESENCE.isPresentOn(classLoader)
