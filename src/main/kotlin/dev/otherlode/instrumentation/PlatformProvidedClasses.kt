package dev.otherlode.instrumentation

import java.util.concurrent.ConcurrentHashMap

internal class PlatformProvidedClasses(
    private val platformLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
) {
    private val answers = ConcurrentHashMap<String, Boolean>()

    fun provides(className: String): Boolean =
        answers.getOrPut(className) {
            try {
                platformLoader.getResource(className.replace('.', '/') + ".class") != null
            } catch (_: Exception) {
                false
            }
        }
}
