package dev.otherlode.classlisting

import dev.otherlode.agentLogger
import dev.otherlode.dependencies.rootKeyOf
import dev.otherlode.instrumentation.TypeMatchPolicy
import java.lang.System.Logger.Level
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import net.bytebuddy.jar.asm.ClassReader

/**
 * Watches where the classes the include rules admit load from, and says when one loads from a root
 * the run's class listing did not read (ADR 0085).
 *
 * The listing hands its roots and names over through [listingRead] when it is complete. A class
 * counts when [TypeMatchPolicy.isIncluded] admits its name and it is not runtime-generated. A class
 * with a code source is judged by its location, compared once: a location seen by a counted class
 * is remembered, so a root that loads a thousand classes costs one comparison. A class with no code
 * source, as a loader that defines classes with no protection domain gives them, is judged by its
 * name: the listing lacks it, so it came from somewhere the listing did not read. What is seen
 * before the listing finished is judged when it does, and so is every class already loaded then,
 * through [listingRead]'s `loaded`. A class of the JDK says nothing, as the agent never instruments
 * one: a named module of the boot layer that the boot or platform loader defines, or whose name
 * starts with `java.` or `jdk.` and whose version is the JDK's own. An application module there, from an upgrade module path or a
 * linked image, is judged as any other class. A run whose listing never completes is never
 * blind.
 *
 * [isBlind] turns true once and stays true. Register this as a plain transformer: it reads the
 * protection domain and changes no class.
 */
internal class ClassListingWatch(
    private val includes: List<String>,
    private val excludes: List<String>,
) : ClassFileTransformer {
    private val log by agentLogger(ClassListingWatch::class.java.name)
    private val blind = AtomicBoolean(false)
    private val lock = Any()

    /** Each location a counted class loaded from, with the first such class's name. */
    private val seen = ConcurrentHashMap<String, String>()

    /** Counted classes with no code source, seen before the listing finished. Bounded like [seen]. */
    private val unlocated = ConcurrentHashMap.newKeySet<String>()

    /** Set when [seen] or [unlocated] was full before the listing finished, so something went unjudged. */
    @Volatile
    private var overflowed = false

    @Volatile
    private var roots: Set<String>? = null

    @Volatile
    private var listed: Set<String>? = null

    /** Whether a class the include rules admit loaded from a root the listing did not read. */
    val isBlind: Boolean get() = blind.get()

    /**
     * Records the roots and names of a complete listing, and judges everything seen so far against
     * them: what this transformer saw, and the classes in [loaded], which the JVM loaded before it
     * was registered.
     */
    fun listingRead(
        roots: Set<String>,
        names: Set<String>,
        loaded: () -> Array<Class<*>> = { emptyArray() },
    ) {
        val found =
            synchronized(lock) {
                this.roots = roots.toSet()
                this.listed = names
                if (overflowed) return@synchronized Blind.OVERFLOW
                seen.entries.firstNotNullOfOrNull { (location, className) -> judgeLocation(location, className) }
                    ?: unlocated.firstNotNullOfOrNull { judgeName(it) }
            } ?: loadedBefore(loaded)
        report(found)
    }

    override fun transform(
        module: Module?,
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (classBeingRedefined != null || blind.get() || isJdk(module)) return null
        val name =
            className ?: try {
                ClassReader(classfileBuffer).className
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                report(Blind("a class loaded whose name the agent could not read"))
                return null
            }
        try {
            report(observe(name, protectionDomain))
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
        }
        return null
    }

    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? = transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer)

    /** Whether [module] is a module of the JDK. */
    private fun isJdk(module: Module?): Boolean {
        if (module == null || !module.isNamed || module.layer !== ModuleLayer.boot()) return false
        val loader = module.classLoader
        if (loader == null || loader === ClassLoader.getPlatformClassLoader()) return true
        val name = module.name
        if (!name.startsWith("java.") && !name.startsWith("jdk.")) return false
        return module.descriptor?.rawVersion()?.orElse(null) == JDK_VERSION
    }

    private fun observe(
        internalName: String,
        protectionDomain: ProtectionDomain?,
    ): Blind? {
        val location = protectionDomain?.codeSource?.location?.toString()
        if (location != null && seen.containsKey(location)) return null
        if (!TypeMatchPolicy.isIncludedInternal(internalName, includes, excludes)) return null
        val dotted = internalName.replace('/', '.')
        if (TypeMatchPolicy.isRuntimeGenerated(dotted)) return null
        synchronized(lock) {
            if (roots == null) {
                val kept =
                    if (location != null) {
                        seen.size < MAX_LOCATIONS && seen.putIfAbsent(location, dotted).let { true }
                    } else {
                        dotted in unlocated || (unlocated.size < MAX_LOCATIONS && unlocated.add(dotted).let { true })
                    }
                if (!kept) overflowed = true
                return null
            }
            if (location == null) return judgeName(dotted)
            if (seen.size < MAX_LOCATIONS) seen.putIfAbsent(location, dotted)
            return judgeLocation(location, dotted)
        }
    }

    private fun loadedBefore(loaded: () -> Array<Class<*>>): Blind? {
        if (blind.get()) return null
        for (type in loaded()) {
            val name = type.name ?: continue
            if (type.isArray || type.isPrimitive || type.isHidden || isJdk(type.module)) continue
            val found =
                try {
                    observe(name.replace('.', '/'), type.protectionDomain)
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    Blind("the agent could not read where $name loaded from")
                }
            if (found != null) return found
        }
        return null
    }

    private fun judgeLocation(
        location: String,
        className: String,
    ): Blind? {
        val key = rootKeyOf(location)
        if (key != null && key in checkNotNull(roots)) return null
        return Blind(className, location)
    }

    private fun judgeName(className: String): Blind? {
        if (className in checkNotNull(listed)) return null
        return Blind(className, null)
    }

    /** Marks the run blind and logs it once, outside [lock], since logging can load classes. */
    private fun report(found: Blind?) {
        if (found == null || !blind.compareAndSet(false, true)) return
        log.log(Level.INFO, "otherlode: ${found.what}, so Otherlode removes no class from this environment while this run is live")
    }

    /** Why the run is blind, as the log line says it. */
    private class Blind(
        val what: String,
    ) {
        constructor(className: String, location: String?) : this(
            if (location != null) {
                "$className loaded from $location, which the class listing did not read"
            } else {
                "$className loaded with no code source, and the class listing does not name it"
            },
        )

        companion object {
            val OVERFLOW = Blind("more classes loaded before the class listing finished than the agent keeps")
        }
    }

    private companion object {
        /** The version every module of the JDK carries, read from `java.base`. */
        val JDK_VERSION: String? = Any::class.java.module.descriptor?.rawVersion()?.orElse(null)

        /** Bounds what is remembered for a JVM that makes a loader and a location for every request. */
        const val MAX_LOCATIONS = 4096
    }
}
