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

internal class ClassListingWatch(
    private val includes: List<String>,
    private val excludes: List<String>,
) : ClassFileTransformer {
    private val log by agentLogger(ClassListingWatch::class.java.name)
    private val blind = AtomicBoolean(false)
    private val lock = Any()

    private val seen = ConcurrentHashMap<String, String>()

    private val unlocated = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var overflowed = false

    @Volatile
    private var roots: Set<String>? = null

    @Volatile
    private var listed: Set<String>? = null

    val isBlind: Boolean get() = blind.get()

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

    // Call outside the lock, since logging can load classes.
    private fun report(found: Blind?) {
        if (found == null || !blind.compareAndSet(false, true)) return
        log.log(Level.INFO, "otherlode: ${found.what}, so Otherlode removes no class from this environment while this run is live")
    }

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
        val JDK_VERSION: String? = Any::class.java.module.descriptor?.rawVersion()?.orElse(null)

        const val MAX_LOCATIONS = 4096
    }
}
