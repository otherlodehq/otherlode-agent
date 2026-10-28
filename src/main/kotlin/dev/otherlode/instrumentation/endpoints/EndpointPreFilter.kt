package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.matcher.ElementMatcher

/**
 * Finds, before ByteBuddy sees a class, the modules that could take it. A module is a candidate
 * when its [EndpointModule.typeNames] is `null` or holds the class's name, its
 * [EndpointModule.classLoaderMatcher] accepts the loader and, when the bytes are known, its
 * [EndpointModule.classFileMayMatch] accepts them. A check that throws makes the module a
 * candidate, so its raw matcher runs the same check and switches the module off.
 */
internal class EndpointPreFilter(
    modules: List<EndpointModule>,
) {
    private class Entry(
        val module: EndpointModule,
        val loaderMatcher: ElementMatcher<in ClassLoader>,
    )

    private val anyName: List<Entry>
    private val byInternalName: Map<String, List<Entry>>

    init {
        val any = ArrayList<Entry>()
        val named = HashMap<String, MutableList<Entry>>()
        for (module in modules) {
            val entry = Entry(module, module.classLoaderMatcher())
            val names = module.typeNames
            if (names == null) {
                any += entry
            } else {
                for (name in names) named.getOrPut(name.replace('.', '/')) { ArrayList() } += entry
            }
        }
        anyName = any
        byInternalName = named
    }

    /**
     * The modules that could take the class [internalName] on [loader], empty when none can, or
     * `null` when the class has no name and nothing can be decided.
     */
    fun candidates(
        internalName: String?,
        loader: ClassLoader?,
        classFile: ByteArray?,
    ): List<EndpointModule>? {
        if (internalName == null) return null
        return try {
            var found: MutableList<EndpointModule>? = null
            for (entry in anyName) {
                if (accepts(entry, loader, classFile)) (found ?: ArrayList<EndpointModule>().also { found = it }).add(entry.module)
            }
            val named = byInternalName[internalName]
            if (named != null) {
                for (entry in named) {
                    if (accepts(entry, loader, classFile)) (found ?: ArrayList<EndpointModule>().also { found = it }).add(entry.module)
                }
            }
            found ?: emptyList()
        } catch (t: Throwable) {
            null
        }
    }

    private fun accepts(
        entry: Entry,
        loader: ClassLoader?,
        classFile: ByteArray?,
    ): Boolean =
        try {
            entry.loaderMatcher.matches(loader) && (classFile == null || entry.module.classFileMayMatch(classFile))
        } catch (t: Throwable) {
            true
        }
}
