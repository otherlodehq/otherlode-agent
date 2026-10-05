package dev.otherlode.instrumentation

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.dynamic.ClassFileLocator
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * A JVM-wide, bounded cache of class-file bytes, in front of [ClassFileLocator.ForClassLoader].
 *
 * Every transform reads the class files of the supertypes and referenced types of the class it
 * weaves, and most of those reads repeat one another: a nested-jar loader answers each in tens of
 * microseconds, which adds up across thousands of classes. Bytes are cached rather than parsed
 * descriptions, which are far heavier for the same saving.
 *
 * Entries are keyed by class loader and binary name. A loader is held weakly, so a retired loader
 * and its entries become collectible; the bootstrap loader (`null`) is a key of its own. A name no
 * loader can provide is cached too, so a type an application names but never ships is asked for
 * once. The total weight of cached bytes is capped at [capBytes], evicting the least recently used
 * entry first; a negative entry weighs [NEGATIVE_WEIGHT] so that a flood of misses is bounded as
 * well. A lock guards the map, which costs little beside a class-file read.
 *
 * Two reads must not go through it, and keep using [uncachedLocatorFor]: a class's own class file
 * when it is first analysed, which is read once anyway, and the comparison a re-weave makes between
 * the class file and the one the class was woven from (ADR 0053), where a cached copy of a class
 * that was HotSwapped would let edited code through on the old plan.
 *
 * Each lookup or store marks its loader active. [takeActiveLoaders] reports the marked loaders and
 * clears the marks, and [dropLoadersNotIn] drops the entries of every loader that is neither in the
 * set it is given nor marked since, which is how the agent releases the entries of a loader that has
 * stopped defining classes. A dropped entry refills on demand.
 *
 * An entry can be wrong only for a class whose file appears or changes during the process, a miss
 * for one that is added later or bytes for one that is rewritten; only [dropLoader] or [dropAll]
 * fixes it. The arrays handed out are shared, so a reader must not modify them.
 */
class ClassFileByteCache(
    private val capBytes: Long = DEFAULT_CAP_BYTES,
) {
    private val queue = ReferenceQueue<ClassLoader>()
    private val entries = LinkedHashMap<Key, ByteArray?>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var weight = 0L
    private val activeLoaders: MutableSet<ClassLoader> = Collections.newSetFromMap(WeakHashMap())
    private var bootstrapActive = false

    /** The number of cached entries, negative ones included. */
    val size: Int get() =
        synchronized(this) {
            purge()
            entries.size
        }

    /** The total weight of the cached entries; never above the cap. */
    val cachedBytes: Long get() =
        synchronized(this) {
            purge()
            weight
        }

    /** A locator for [classLoader] (null for the bootstrap loader) that reads through this cache. */
    fun locatorFor(classLoader: ClassLoader?): ClassFileLocator = CachingLocator(classLoader)

    /** A [AgentBuilder.LocationStrategy] whose locators read through this cache. */
    fun locationStrategy(): AgentBuilder.LocationStrategy = AgentBuilder.LocationStrategy { classLoader, _ -> locatorFor(classLoader) }

    /** Forgets everything cached for [classLoader] (null for the bootstrap loader). */
    fun dropLoader(classLoader: ClassLoader?) {
        synchronized(this) {
            purge()
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.isLoader(classLoader)) {
                    weight -= weightOf(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    /**
     * The loaders (null for the bootstrap loader) that looked up or stored an entry since the last
     * call, and clears the marks.
     */
    fun takeActiveLoaders(): Set<ClassLoader?> {
        val taken = Collections.newSetFromMap(IdentityHashMap<ClassLoader?, Boolean>())
        synchronized(this) {
            taken.addAll(activeLoaders)
            if (bootstrapActive) taken.add(null)
            activeLoaders.clear()
            bootstrapActive = false
        }
        return taken
    }

    /**
     * Forgets the entries of every loader that is not in [keep] and has not been marked active since
     * the marks were last cleared. Takes this cache's lock only.
     */
    fun dropLoadersNotIn(keep: Set<ClassLoader?>) {
        synchronized(this) {
            purge()
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val loader = entry.key.get()
                val bootstrap = entry.key.isLoader(null)
                val kept =
                    if (bootstrap) {
                        bootstrapActive || null in keep
                    } else {
                        loader == null || loader in activeLoaders || loader in keep
                    }
                if (!kept) {
                    weight -= weightOf(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    /** Forgets everything cached. */
    fun dropAll() {
        synchronized(this) {
            entries.clear()
            weight = 0
        }
    }

    private fun purge() {
        while (true) {
            val cleared = queue.poll() as? Key ?: return
            if (entries.containsKey(cleared)) weight -= weightOf(entries.remove(cleared))
        }
    }

    private fun markActive(classLoader: ClassLoader?) {
        if (classLoader == null) bootstrapActive = true else activeLoaders.add(classLoader)
    }

    private fun weightOf(bytes: ByteArray?): Long = bytes?.size?.toLong() ?: NEGATIVE_WEIGHT

    private fun lookup(
        classLoader: ClassLoader?,
        name: String,
    ): Cached {
        val probe = Key(classLoader, name, null)
        synchronized(this) {
            purge()
            markActive(classLoader)
            if (entries.containsKey(probe)) return Cached(entries[probe])
        }
        return Cached(null, miss = true)
    }

    private fun store(
        classLoader: ClassLoader?,
        name: String,
        bytes: ByteArray?,
    ) {
        val entryWeight = weightOf(bytes)
        if (entryWeight > capBytes) return
        synchronized(this) {
            purge()
            markActive(classLoader)
            val probe = Key(classLoader, name, null)
            if (entries.containsKey(probe)) {
                weight -= weightOf(entries.put(probe, bytes))
            } else {
                entries[Key(classLoader, name, queue)] = bytes
            }
            weight += entryWeight
            val iterator = entries.entries.iterator()
            while (weight > capBytes && iterator.hasNext()) {
                weight -= weightOf(iterator.next().value)
                iterator.remove()
            }
        }
    }

    private class Cached(
        val bytes: ByteArray?,
        val miss: Boolean = false,
    )

    private inner class CachingLocator(
        classLoader: ClassLoader?,
    ) : ClassFileLocator {
        private val loader = classLoader
        private val delegate = uncachedLocatorFor(classLoader)

        override fun locate(name: String): ClassFileLocator.Resolution {
            val cached = lookup(loader, name)
            if (!cached.miss) {
                val bytes = cached.bytes
                return if (bytes == null) ClassFileLocator.Resolution.Illegal(name) else ClassFileLocator.Resolution.Explicit(bytes)
            }
            val resolution = delegate.locate(name)
            if (!resolution.isResolved) {
                store(loader, name, null)
                return resolution
            }
            val bytes = resolution.resolve()
            store(loader, name, bytes)
            return ClassFileLocator.Resolution.Explicit(bytes)
        }

        override fun close() = Unit
    }

    /**
     * A weak reference to a loader plus the name of a class, with the loader's identity hash fixed
     * at construction so a key that is still in the map after its loader is cleared keeps hashing
     * where it was stored.
     */
    private class Key(
        classLoader: ClassLoader?,
        private val name: String,
        queue: ReferenceQueue<ClassLoader>?,
    ) : WeakReference<ClassLoader>(classLoader, queue) {
        private val bootstrap = classLoader == null
        private val hash = 31 * System.identityHashCode(classLoader) + name.hashCode()

        fun isLoader(classLoader: ClassLoader?): Boolean = if (classLoader == null) bootstrap else get() === classLoader

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key || name != other.name || bootstrap != other.bootstrap) return false
            if (bootstrap) return true
            val mine = get() ?: return false
            return mine === other.get()
        }
    }

    companion object {
        /** The default cap on the weight of cached bytes, 16 MB. */
        const val DEFAULT_CAP_BYTES: Long = 16L * 1024 * 1024

        /** What a negative entry weighs against the cap. */
        const val NEGATIVE_WEIGHT: Long = 64

        private const val INITIAL_CAPACITY = 1024
        private const val LOAD_FACTOR = 0.75f

        /** A locator that reads [classLoader] (null for the bootstrap loader) directly, bypassing every cache. */
        fun uncachedLocatorFor(classLoader: ClassLoader?): ClassFileLocator =
            if (classLoader != null) {
                ClassFileLocator.ForClassLoader.of(classLoader)
            } else {
                ClassFileLocator.ForClassLoader.ofBootLoader()
            }
    }
}
