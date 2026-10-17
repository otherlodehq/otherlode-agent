package dev.otherlode.instrumentation.endpoints.vertxweb

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * A concurrent map from an object, compared by identity and held weakly, to a value. A value must
 * not reach its own key strongly, or the entry is never collected.
 */
internal class WeakIdentityMap<V : Any> {
    private interface Id {
        val target: Any?
        val hash: Int
    }

    private class WeakId(
        target: Any,
        queue: ReferenceQueue<Any>,
    ) : WeakReference<Any>(target, queue),
        Id {
        override val hash: Int = System.identityHashCode(target)
        override val target: Any? get() = get()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other === this || (other is Id && target != null && target === other.target)
    }

    private class StrongId(
        override val target: Any,
    ) : Id {
        override val hash: Int = System.identityHashCode(target)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Id && target === other.target
    }

    private val map = ConcurrentHashMap<Any, V>()
    private val collected = ReferenceQueue<Any>()

    /** The value held for [key], or null. */
    operator fun get(key: Any): V? = map[StrongId(key)]

    /** The value held for [key], made by [create] and held when there is none. */
    fun getOrPut(
        key: Any,
        create: () -> V,
    ): V {
        get(key)?.let { return it }
        while (true) {
            val stale = collected.poll() ?: break
            map.remove(stale)
        }
        return map.computeIfAbsent(WeakId(key, collected)) { create() }
    }

    /** Holds [value] for [key], replacing any value held. */
    fun put(
        key: Any,
        value: V,
    ) {
        while (true) {
            val stale = collected.poll() ?: break
            map.remove(stale)
        }
        map.remove(StrongId(key))
        map[WeakId(key, collected)] = value
    }

    /** The values whose keys are still reachable. */
    fun liveValues(): List<V> = map.entries.filter { (it.key as Id).target != null }.map { it.value }

    /** How many entries the map holds, collected ones included until the next [getOrPut]. */
    fun size(): Int = map.size
}
