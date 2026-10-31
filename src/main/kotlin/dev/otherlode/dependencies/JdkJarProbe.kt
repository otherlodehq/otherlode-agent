package dev.otherlode.dependencies

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Whether the running JDK's system class loader would load from [jar] although its manifest cannot
 * be read or a `Class-Path` token is not a valid URL. It opens a throwaway `URLClassLoader` on the
 * jar with no parent and asks it for an entry the jar holds. That loader's `URLClassPath` drops the
 * jar or keeps it by the rules of this JDK: the size limit on a manifest, the manifest text it
 * searches, and on JDK 17 a `META-INF/INDEX.LIST`.
 *
 * Nothing is loaded from the jar. Meant for the rare jar that needs it, since it opens the jar
 * again.
 */
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
