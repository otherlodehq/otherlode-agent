package dev.otherlode.instrumentation

import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal class ReferencedClassLocator(
    private val platformLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
) {
    data class Found(
        val location: String?,
    )

    private val byLoader = WeakHashMap<ClassLoader, MutableMap<String, Any>>()
    private val platformClasses = PlatformProvidedClasses(platformLoader)

    fun locate(
        className: String,
        classLoader: ClassLoader?,
    ): Found? {
        val loader = classLoader ?: platformLoader
        val cache = synchronized(byLoader) { byLoader.getOrPut(loader) { ConcurrentHashMap() } }
        val cached = cache[className]
        if (cached != null) return cached as? Found
        val found = compute(className, loader)
        cache.putIfAbsent(className, found ?: DROPPED)
        return found
    }

    private fun compute(
        className: String,
        loader: ClassLoader,
    ): Found? {
        val resourceName = className.replace('.', '/') + ".class"
        val url =
            try {
                loader.getResource(resourceName)?.toString()
            } catch (_: Exception) {
                null
            }
        if (url == null) return if (className.startsWith("java.")) null else Found(null)
        if (url.startsWith("jrt:", ignoreCase = true)) return null
        return classify(url, platformClasses.provides(className))
    }

    companion object {
        private val DROPPED = Any()
        private const val JAR_SEPARATOR = "!/"

        fun classify(
            url: String?,
            providedByPlatform: Boolean,
        ): Found? {
            if (url == null) return Found(null)
            if (url.startsWith("jrt:", ignoreCase = true) || providedByPlatform) return null
            if (url.startsWith("file:", ignoreCase = true)) return null
            if (url.startsWith("jar:", ignoreCase = true)) {
                val separator = url.lastIndexOf(JAR_SEPARATOR)
                return Found(if (separator < 0) url else url.substring(0, separator + JAR_SEPARATOR.length))
            }
            return Found(url)
        }
    }
}
