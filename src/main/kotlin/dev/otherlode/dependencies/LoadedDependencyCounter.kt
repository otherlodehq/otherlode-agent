package dev.otherlode.dependencies

import dev.otherlode.registry.DependencyRegistry
import java.security.ProtectionDomain

/**
 * Counts the distinct classes loaded from each dependency. Counts nothing until
 * [DependencyRegistry.isListingComplete]. Arrays, primitives, hidden classes, and classes with no
 * protection domain or code source are skipped. A class name stays in its dependency's set when
 * the class unloads, so a total never falls.
 */
class LoadedDependencyCounter internal constructor(
    private val registry: DependencyRegistry,
    private val resolve: (ProtectionDomain) -> Int?,
) {
    /** Judges a jar the listing never saw by [JarClassifier] rules built from [includes] and [excludes]. */
    constructor(registry: DependencyRegistry, includes: List<String>, excludes: List<String>) :
        this(registry, DependencyResolver(registry, JarClassifier(includes, excludes))::resolve)

    /**
     * Adds every class in [loaded] to the dependency it came from, then marks a counting generation
     * ([DependencyRegistry.markCounted]). Does nothing until the listing is complete. A call that
     * throws partway marks nothing.
     */
    fun count(loaded: Array<Class<*>>) {
        if (!registry.isListingComplete) return
        for (type in loaded) {
            if (type.isArray || type.isPrimitive || type.isHidden) continue
            val domain = type.protectionDomain ?: continue
            if (domain.codeSource == null) continue
            val dependencyId = resolve(domain) ?: continue
            registry.recordLoaded(dependencyId, type.name)
        }
        registry.markCounted()
    }
}
