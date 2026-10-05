package dev.otherlode.instrumentation

import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool

/**
 * One pool for every `java.` type, shared by the pool of each transform as its parent.
 *
 * The JVM refuses to define a class in a `java.` package in any loader but the boot and platform
 * loaders ("Prohibited package name"), so every class loader's delegation gives the same answer for
 * such a name and a description made here cannot differ from the one a loader's own pool would make.
 * A name outside `java.` that is asked of this pool is answered as unresolved at once, with no read
 * and no cache entry, so the transform's own pool describes it as it always did. Primitives and
 * arrays of them are answered as ByteBuddy answers them, without a read.
 *
 * Types are read through the platform loader, which delegates to the boot loader, so `java.sql` and
 * other platform-loader `java.` types resolve. A description made here resolves the types it names
 * through the same platform loader whatever their package, so a `java.awt` class that implements a
 * `javax.accessibility` interface still has it; those types are cached beside the others and are
 * never answered to a caller that asks for them by name.
 *
 * Safe to share between threads: [TypePool.Default] keeps no state of its own, and the cache is
 * [BoundedTypeCache], whose every access holds its lock. Two threads that miss together may each
 * parse a type; they register equal descriptions and one wins.
 */
internal class JdkTypePool private constructor(
    private val cache: BoundedTypeCache,
    classFileLocator: ClassFileLocator,
) : TypePool {
    private val platform: TypePool = TypePool.Default.WithLazyResolution(cache, classFileLocator, TypePool.Default.ReaderMode.FAST)

    /**
     * A pool that reads through [classFileLocator], by default the platform class loader's, and keeps
     * at most [maxTypes] descriptions, least recently used out first.
     */
    constructor(
        classFileLocator: ClassFileLocator = ClassFileLocator.ForClassLoader.of(ClassLoader.getPlatformClassLoader()),
        maxTypes: Int = DEFAULT_MAX_TYPES,
    ) : this(BoundedTypeCache(maxTypes), classFileLocator)

    /** How many descriptions the cache holds. */
    fun cachedTypes(): Int = cache.size()

    override fun describe(name: String): TypePool.Resolution =
        if (isAnswered(name)) platform.describe(name) else TypePool.Resolution.Illegal(name)

    override fun clear() = platform.clear()

    internal companion object {
        /** The bound on cached descriptions; the `java.` types a large application describes number a few hundred. */
        const val DEFAULT_MAX_TYPES = 1024

        private const val JAVA_PREFIX = "java."
        private val PRIMITIVES = setOf("boolean", "byte", "short", "char", "int", "long", "float", "double", "void")
        private const val PRIMITIVE_DESCRIPTORS = "ZBSCIJFD"

        /** Whether [name], an array name or a binary name, is a type under `java.` or a primitive or an array of one. */
        fun isAnswered(name: String): Boolean {
            var start = 0
            while (start < name.length && name[start] == '[') start++
            if (start == 0) return name.startsWith(JAVA_PREFIX) || name in PRIMITIVES
            val element = name.substring(start)
            if (element.length == 1) return PRIMITIVE_DESCRIPTORS.indexOf(element[0]) >= 0
            return element.length > 2 && element[0] == 'L' && element[element.length - 1] == ';' && element.startsWith(JAVA_PREFIX, 1)
        }
    }
}

/**
 * A least-recently-used cache of at most `maxTypes` resolved descriptions. A resolution that is not
 * resolved is returned and not stored, so a name nobody provides never pins an answer.
 */
internal class BoundedTypeCache(
    private val maxTypes: Int,
) : TypePool.CacheProvider {
    private val entries =
        object : LinkedHashMap<String, TypePool.Resolution>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TypePool.Resolution>): Boolean = size > maxTypes
        }

    init {
        require(maxTypes > 0) { "maxTypes must be positive, was $maxTypes" }
    }

    /** The number of cached descriptions. */
    fun size(): Int = synchronized(entries) { entries.size }

    override fun find(name: String): TypePool.Resolution? = synchronized(entries) { entries[name] }

    override fun register(
        name: String,
        resolution: TypePool.Resolution,
    ): TypePool.Resolution {
        if (!resolution.isResolved) return resolution
        return synchronized(entries) { entries.putIfAbsent(name, resolution) ?: resolution }
    }

    override fun clear() = synchronized(entries) { entries.clear() }

    private companion object {
        const val INITIAL_CAPACITY = 512
        const val LOAD_FACTOR = 0.75f
    }
}
