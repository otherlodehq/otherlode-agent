package dev.otherlode.dependencies

import dev.otherlode.instrumentation.staticscan.isClassEntry
import dev.otherlode.instrumentation.staticscan.versionedClassPath
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * What the dependency listing needs from one jar: the names of the classes it holds, its
 * `pom.properties` files and its manifest. Read in one pass over the jar's entries, from a
 * [JarFile] for a jar on disk. [NestedJarReader] reads a jar stored inside another from its central
 * directory, and streams one the outer jar compresses through a [ZipInputStream].
 *
 * [classNames] are dotted, distinct, and counted by [isClassEntry], the same rule the static
 * baseline scan uses. [versionedClassNames] are the dotted names of the classes a multi-release
 * jar keeps under `META-INF/versions/N/`, named by their path after that prefix; the class listing
 * reads them, since a class can live only there. [pomProperties] holds every entry at
 * `META-INF/maven/<group>/<artifact>/pom.properties`, in entry order.
 *
 * A jar with no entries at all is rejected with a [ZipException]. [ZipInputStream] reads bytes
 * that are not a zip as an empty stream rather than failing, so without this check a corrupt
 * nested jar would be listed by its filename with no classes.
 */
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

        /**
         * Reads every entry of [stream] and leaves it open. The manifest is read from its entry by
         * name rather than through [java.util.jar.JarInputStream], which finds it only when it is
         * among the first entries.
         */
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
