package dev.otherlode.dependencies

import dev.otherlode.instrumentation.staticscan.isClassEntry
import dev.otherlode.instrumentation.staticscan.versionedClassPath
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

internal class JarContents(
    val classNames: Set<String>,
    val pomProperties: List<Properties>,
    val manifest: Manifest?,
    val versionedClassNames: Set<String> = emptySet(),
) {
    companion object {
        internal val POM_PROPERTIES = Regex("^META-INF/maven/[^/]+/[^/]+/pom\\.properties$")
        internal const val MANIFEST_ENTRY = "META-INF/MANIFEST.MF"

        fun of(jarFile: JarFile): JarContents {
            val classNames = linkedSetOf<String>()
            val versioned = linkedSetOf<String>()
            val poms = mutableListOf<Properties>()
            var entries = 0
            for (entry in jarFile.entries()) {
                entries++
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    isClassEntry(name) -> classNames += dottedName(name)
                    POM_PROPERTIES.matches(name) -> poms += jarFile.getInputStream(entry).use { loadProperties(it.readBytes()) }
                    else -> versionedClassPath(name)?.let { versioned += dottedName(it) }
                }
            }
            if (entries == 0) throw ZipException("${jarFile.name} holds no zip entries")
            return JarContents(classNames, poms, jarFile.manifest, versioned)
        }

        fun of(stream: ZipInputStream): JarContents {
            val classNames = linkedSetOf<String>()
            val versioned = linkedSetOf<String>()
            val poms = mutableListOf<Properties>()
            var manifest: Manifest? = null
            var entries = 0
            while (true) {
                val entry = stream.nextEntry ?: break
                entries++
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    isClassEntry(name) -> classNames += dottedName(name)
                    POM_PROPERTIES.matches(name) -> poms += loadProperties(stream.readBytes())
                    name.equals(MANIFEST_ENTRY, ignoreCase = true) -> manifest = Manifest(ByteArrayInputStream(stream.readBytes()))
                    else -> versionedClassPath(name)?.let { versioned += dottedName(it) }
                }
            }
            if (entries == 0) throw ZipException("the stream holds no zip entries")
            return JarContents(classNames, poms, manifest, versioned)
        }

        internal fun dottedName(entryName: String): String = entryName.removeSuffix(".class").replace('/', '.')

        internal fun loadProperties(bytes: ByteArray): Properties = Properties().apply { load(ByteArrayInputStream(bytes)) }
    }
}
