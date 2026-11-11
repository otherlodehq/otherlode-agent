package dev.otherlode.dependencies

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

internal fun jdkLoadsFrom(
    jar: File,
    jarFile: JarFile,
): Boolean {
    val entry =
        jarFile.entries().asSequence().lastOrNull { it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }
            ?: jarFile.entries().asSequence().firstOrNull { !it.isDirectory }
            ?: return false
    return try {
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { it.findResource(entry.name) != null }
    } catch (_: Exception) {
        false
    }
}
