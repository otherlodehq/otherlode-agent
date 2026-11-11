package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.matcher.ElementMatcher

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
