package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.pool.TypePool
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a supertype can be neither described from the pool nor loaded, so the class extending it can never be defined. */
class UndefinableTypeException(
    typeName: String,
) : RuntimeException("$typeName cannot be described or loaded", null, false, false)

/**
 * What a [SupertypeResolver] remembers for one class loader: which supertypes loaded, and which
 * were found definable with everything above them. Neither answer is held by a strong reference to a
 * class, so a cache kept for a loader never keeps that loader alive through its own entries. A module
 * extends it to keep answers of its own, and overrides [isKnown] to say which names those answers cover.
 */
open class SupertypeCache {
    private val loaded = ConcurrentHashMap<String, LoadAnswer>()
    private val definable = ConcurrentHashMap.newKeySet<String>()

    fun isDefinable(name: String): Boolean = name in definable

    fun markDefinable(name: String) {
        definable.add(name)
    }

    /** Whether an earlier read has already shown [name] to be described, so its description needs no further check. */
    open fun isKnown(name: String): Boolean = isDefinable(name)

    /** The class named [name] as [loader] defines it, or null when [loader] cannot load it. */
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
 * One cache per class loader, built by [create] and held weakly so a retired loader stays
 * collectible. A `null` loader gets a fresh cache on every call, which is the same as no cache.
 */
class SupertypeCaches<C : SupertypeCache>(
    private val create: () -> C,
) {
    private val byLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, C>())

    fun cacheFor(loader: ClassLoader?): C = if (loader == null) create() else byLoader.getOrPut(loader) { create() }
}

/**
 * Reads a type's supertypes, erased, for [loader]'s classes, leaving out those [skipped] names.
 * It holds [loader] weakly, so a matcher built on it never keeps a retired loader alive.
 *
 * The agent's pool resolves lazily (`TypePool.Default.WithLazyResolution`), so `asErasure()` hands
 * back a description that throws only when something reads it. A supertype the pool has no class
 * file for is therefore detected here by reading it, then asked of [loader] with
 * `Class.forName(name, false, loader)`. A class defined with no reachable class file, such as an
 * interface OpenTelemetry's agent generates in memory, loads, and is described from the loaded class.
 * One that does not load is a class [loader] cannot define, and [UndefinableTypeException] says so.
 * One that loads but whose methods cannot be reflected, because a type in a signature is absent, is
 * not undefinable, since the JVM resolves signature types lazily: the error reaches the caller, which
 * for a matcher switches its module off rather than guess.
 *
 * The load happens only when the pool had no bytes for a supertype, which is rare, and inside the
 * transform of the class that extends it, so the JVM would load that supertype next on this same
 * thread to define the class. It initialises nothing. A loader that locks itself for loading already
 * holds that lock here and re-enters it; a parallel-capable loader locks per class name. A cyclic
 * hierarchy, the only way the load could need the class being defined, is invalid and fails the
 * definition regardless.
 */
class SupertypeResolver(
    loader: ClassLoader?,
    private val cache: SupertypeCache,
    private val skipped: (String) -> Boolean = { false },
) {
    private val loader: WeakReference<ClassLoader>? = loader?.let { WeakReference(it) }
    private val isBootstrap = loader == null

    /** [type]'s superclass, erased and readable, or null when there is none or it is [skipped]. */
    fun superErasure(type: TypeDescription): TypeDescription? = locate { type.superClass }?.let(::describe)

    /** [type]'s own declared interfaces, erased and readable, without those [skipped]. */
    fun interfaceErasures(type: TypeDescription): List<TypeDescription> = locateInterfaces(type).mapNotNull(::describe)

    /**
     * Whether every supertype above [type] can be described or loaded, so the class can be defined.
     * A supertype found to be definable is remembered for the loader, with everything above it.
     * Throws [UndefinableTypeException] when one cannot.
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
