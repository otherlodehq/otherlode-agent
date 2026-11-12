package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.pool.TypePool
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a supertype can be neither described from the type pool nor loaded. */
class UndefinableTypeException(
    typeName: String,
) : RuntimeException("$typeName cannot be described or loaded", null, false, false)

/**
 * What a [SupertypeResolver] remembers for one class loader: which supertypes loaded and which were
 * found definable. It holds loaded classes weakly. A subclass can keep answers of its own and
 * override [isKnown] to cover them.
 */
open class SupertypeCache {
    private val loaded = ConcurrentHashMap<String, LoadAnswer>()
    private val definable = ConcurrentHashMap.newKeySet<String>()

    fun isDefinable(name: String): Boolean = name in definable

    fun markDefinable(name: String) {
        definable.add(name)
    }

    /** Whether [name]'s type pool description can be used without a further check. The default is [isDefinable]. */
    open fun isKnown(name: String): Boolean = isDefinable(name)

    /**
     * The class named [name] as [loader] loads it, without initialising it, or null when [loader]
     * cannot load it. The cache keeps answers by [name] alone, so pass the same loader on each call to one cache.
     */
    fun loadedClass(
        name: String,
        loader: ClassLoader?,
    ): Class<*>? {
        loaded[name]?.let { answer ->
            if (!answer.loadable) return null
            answer.reference?.get()?.let { return it }
        }
        val found = tryLoad(name, loader)
        loaded[name] = LoadAnswer(found != null, found?.let { WeakReference(it) })
        return found
    }

    private fun tryLoad(
        name: String,
        loader: ClassLoader?,
    ): Class<*>? =
        try {
            Class.forName(name, false, loader)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    private class LoadAnswer(
        val loadable: Boolean,
        val reference: WeakReference<Class<*>>?,
    )
}

/**
 * One cache per class loader, built by [create] and keyed weakly by the loader. A `null` loader gets
 * a fresh cache on each call.
 */
class SupertypeCaches<C : SupertypeCache>(
    private val create: () -> C,
) {
    private val byLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, C>())

    fun cacheFor(loader: ClassLoader?): C = if (loader == null) create() else byLoader.getOrPut(loader) { create() }
}

/**
 * Reads a type's erased supertypes for `loader`'s classes, leaving out names that `skipped` accepts.
 * It holds `loader` weakly. It loads a supertype the type pool cannot describe through `loader`,
 * without initialising it, and describes it from the loaded class. When the supertype does not load,
 * or `loader` has been collected, it throws [UndefinableTypeException]. A supertype that loads but
 * cannot be reflected raises its own error.
 */
class SupertypeResolver(
    loader: ClassLoader?,
    private val cache: SupertypeCache,
    private val skipped: (String) -> Boolean = { false },
) {
    private val loader: WeakReference<ClassLoader>? = loader?.let { WeakReference(it) }
    private val isBootstrap = loader == null

    /** [type]'s superclass, erased, or null when there is none or it is skipped. */
    fun superErasure(type: TypeDescription): TypeDescription? = locate { type.superClass }?.let(::describe)

    /** [type]'s own declared interfaces, erased, without those skipped. */
    fun interfaceErasures(type: TypeDescription): List<TypeDescription> = locateInterfaces(type).mapNotNull(::describe)

    /**
     * Returns true when each supertype above [type] that it reaches without passing a skipped one can be
     * described or loaded, and throws [UndefinableTypeException] when one cannot. It never returns false.
     * It marks the types it finds definable in the cache.
     */
    fun canBeDefined(type: TypeDescription): Boolean {
        val seen = mutableSetOf<String>()

        fun visit(
            candidate: TypeDescription,
            located: List<Supertype>,
        ) {
            if (!seen.add(candidate.name)) return
            for (supertype in located) {
                if (skipped(supertype.name) || cache.isDefinable(supertype.name)) continue
                val described = describe(supertype) ?: continue
                visit(described, locate { described.superClass }.let(::listOfNotNull) + locateInterfaces(described))
            }
            cache.markDefinable(candidate.name)
        }
        visit(type, locate { type.superClass }.let(::listOfNotNull) + locateInterfaces(type))
        return true
    }

    private class Supertype(
        val name: String,
        val erased: TypeDescription?,
    )

    private fun locateInterfaces(type: TypeDescription): List<Supertype> {
        val interfaces = type.interfaces
        return (0 until interfaces.size).mapNotNull { index -> locate { interfaces[index] } }
    }

    private fun locate(read: () -> TypeDescription.Generic?): Supertype? =
        try {
            read()?.asErasure()?.let { Supertype(it.name, it) }
        } catch (e: TypePool.Resolution.NoSuchTypeException) {
            Supertype(e.name, null)
        }

    private fun describe(supertype: Supertype): TypeDescription? {
        if (skipped(supertype.name)) return null
        val erased = supertype.erased
        if (erased != null && (cache.isKnown(supertype.name) || isReadable(erased))) return erased
        val loaded = loadedClass(supertype.name) ?: throw UndefinableTypeException(supertype.name)
        return TypeDescription.ForLoadedType.of(loaded)
    }

    private fun loadedClass(name: String): Class<*>? {
        val live = loader?.get()
        return if (live == null && !isBootstrap) null else cache.loadedClass(name, live)
    }

    private fun isReadable(type: TypeDescription): Boolean =
        try {
            type.modifiers
            true
        } catch (_: TypePool.Resolution.NoSuchTypeException) {
            false
        }
}
