package dev.otherlode.classlisting

import dev.otherlode.dependencies.JarClassifier
import dev.otherlode.dependencies.JarContents
import dev.otherlode.dependencies.bootLibPrefixes
import dev.otherlode.dependencies.classPathEntries
import dev.otherlode.dependencies.nestedJarNames
import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.instrumentation.staticscan.isClassEntry
import dev.otherlode.instrumentation.staticscan.versionedClassPath
import java.io.File
import java.io.IOException
import java.lang.System.Logger.Level
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

/**
 * Lists the classes the startup classpath holds under the include rules, by name, without reading
 * a class file.
 *
 * The roots are each entry of [classPath] (split on [File.pathSeparator]) and every jar a manifest
 * `Class-Path` attribute reaches from one, resolved by [classPathEntries], each file once. As the
 * JDK's system loader does, an entry of [classPath] is read through its canonical path, so a
 * `Class-Path` named by a jar reached through a symbolic link resolves against the link's target.
 * A root that does not exist holds nothing, as the JVM ignores it. Any file is read as a jar,
 * whatever its extension, as the system loader does:
 *
 * - A Spring Boot fat jar or executable war contributes the classes under `BOOT-INF/classes/` or
 *   `WEB-INF/classes/`, named without that prefix, the classes at its own root, and the classes of
 *   each nested jar Boot's launcher puts on the classpath.
 * - Any other jar contributes its classes, a jar that names an agent included: the include rules
 *   already leave out the classes of this agent.
 *
 * A directory is walked. A directory whose `META-INF/MANIFEST.MF` marks an exploded Spring Boot
 * application is read the way its launcher reads it: the classes under `BOOT-INF/classes/` or
 * `WEB-INF/classes/` without that prefix, the classes at its root, and every jar under its library
 * folders.
 *
 * A class counts by the static baseline's rule ([isClassEntry]). In a jar, a multi-release entry
 * under `META-INF/versions/N/` also counts, by its path after that prefix, since a class can exist
 * only there. A name is listed when [TypeMatchPolicy.isIncluded] admits it.
 *
 * The result is complete or absent. A server reads a class's absence from a complete listing as
 * proof that the build lacks it, so a listing with a hole would turn an unreadable jar into removed
 * code. [list] logs one WARNING and returns null when a root that exists, a jar or a nested jar
 * cannot be read, when [classPath] is empty or has an empty element (which the JVM reads as the
 * working directory), and when [modulePath] or [mainModule] is set, since this lister reads
 * neither a module path nor a linked image.
 */
class ClassLister(
    private val includes: List<String>,
    private val excludes: List<String>,
    private val classPath: String = System.getProperty("java.class.path").orEmpty(),
    private val modulePath: String = System.getProperty("jdk.module.path").orEmpty(),
    private val mainModule: String = System.getProperty("jdk.module.main").orEmpty(),
) {
    private val log = System.getLogger(ClassLister::class.java.name)
    private val classifier = JarClassifier(includes, excludes)

    /** The sorted, distinct, dotted names of the listed classes, or null when the classpath could not be read whole. */
    fun list(): List<String>? {
        if (modulePath.isNotBlank() || mainModule.isNotBlank()) {
            return refuse("this JVM runs from a module path or a linked image, which the class listing does not read")
        }
        val elements = classPath.split(File.pathSeparator)
        if (elements.any { it.isEmpty() }) {
            return refuse("java.class.path is empty or has an empty element, which the JVM reads as the working directory")
        }
        val names = sortedSetOf<String>()
        val visited = mutableSetOf<File>()
        val pending = ArrayDeque(elements.filter { it.isNotEmpty() }.map { Root(File(it), fromClassPath = true) })
        var location: File? = null
        try {
            while (pending.isNotEmpty()) {
                val (given, fromClassPath) = pending.removeFirst()
                location = given
                if (!given.exists()) continue
                val canonical = given.canonicalFile
                if (!visited.add(canonical)) continue
                val root = if (fromClassPath) canonical else given.absoluteFile
                val referenced = if (root.isDirectory) listDirectory(root, names) else listJar(root, names)
                referenced.asReversed().forEach { pending.addFirst(Root(it, fromClassPath = false)) }
            }
        } catch (e: Exception) {
            val where = (e as? NestedJarException)?.location ?: location
            val cause = (e as? NestedJarException)?.cause ?: e
            log.log(
                Level.WARNING,
                "otherlode: could not read $where for the class listing, so no class listing is sent: ${cause.message ?: cause}",
                cause,
            )
            return null
        }
        return names.toList()
    }

    private fun refuse(reason: String): List<String>? {
        log.log(Level.WARNING, "otherlode: $reason, so no class listing is sent")
        return null
    }

    /**
     * Lists a directory root, as an exploded Spring Boot application when its manifest says so,
     * and returns the jars the `Class-Path` of its library jars names: Boot's launcher loads an
     * exploded application's library jars through a `URLClassLoader`, which follows them.
     */
    private fun listDirectory(
        root: File,
        names: MutableSet<String>,
    ): List<File> {
        val manifestFile = File(root, "META-INF/MANIFEST.MF")
        val manifest = if (manifestFile.isFile) manifestFile.inputStream().use(::Manifest) else null
        if (!classifier.isBootApplication(manifest)) {
            walk(root.toPath()) { relative -> if (isClassEntry(relative)) addIfIncluded(relative, names) }
            return emptyList()
        }
        val libPrefixes = bootLibPrefixes(manifest?.mainAttributes ?: Attributes())
        val referenced = mutableListOf<File>()
        walk(root.toPath()) { relative ->
            when {
                libPrefixes.any { relative.startsWith(it) } -> referenced += listLibraryJar(File(root, relative), names)
                else -> addApplicationEntry(relative, names)
            }
        }
        return referenced
    }

    /** Calls [visit] with the root-relative, `/`-separated path of every file under [root]. */
    private fun walk(
        root: Path,
        visit: (String) -> Unit,
    ) {
        Files.walkFileTree(
            root,
            setOf(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    visit(root.relativize(file).joinToString("/"))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = throw exc
            },
        )
    }

    /**
     * Adds an entry of a Spring Boot application, packed or exploded, by its path: under
     * `BOOT-INF/classes/` or `WEB-INF/classes/` it is named without that prefix, and anywhere
     * else by its whole path.
     */
    private fun addApplicationEntry(
        path: String,
        names: MutableSet<String>,
    ) {
        val prefix = APPLICATION_CLASSES_PREFIXES.firstOrNull { path.startsWith(it) }
        // A multi-release jar packed under BOOT-INF/classes keeps its variants at
        // BOOT-INF/classes/META-INF/versions/N/, which only the stripped name shows.
        val name = if (prefix != null) path.removePrefix(prefix) else path
        jarClassPath(name)?.let { addIfIncluded(it, names) }
    }

    /**
     * The class path of a jar entry: the entry itself by the static baseline's rule, or a
     * multi-release jar's versioned entry by its path after `META-INF/versions/N/`. Naming a
     * versioned copy of a class that also has a base entry adds nothing, and a class that exists
     * only as a versioned entry is listed, since the JVM loads it.
     */
    private fun jarClassPath(entryName: String): String? = if (isClassEntry(entryName)) entryName else versionedClassPath(entryName)

    /** Adds [jar]'s classes to [names] and returns the jars its `Class-Path` names. */
    private fun listJar(
        jar: File,
        names: MutableSet<String>,
    ): List<File> {
        JarFile(jar).use { jarFile ->
            val manifest = jarFile.manifest
            if (classifier.isBootApplication(manifest)) {
                for (entry in jarFile.entries()) {
                    if (!entry.isDirectory) addApplicationEntry(entry.name, names)
                }
                listNestedJars(jar, jarFile, manifest?.mainAttributes ?: Attributes(), names)
            } else {
                addJarClasses(jarFile, names)
            }
            return classPathEntries(jar, manifest)
        }
    }

    /**
     * Adds the classes of a library jar an exploded Spring Boot application keeps on disk, and
     * returns the jars its `Class-Path` names.
     */
    private fun listLibraryJar(
        jar: File,
        names: MutableSet<String>,
    ): List<File> {
        try {
            return JarFile(jar).use {
                addJarClasses(it, names)
                classPathEntries(jar, it.manifest)
            }
        } catch (e: Exception) {
            throw NestedJarException(jar.path, e)
        }
    }

    private fun addJarClasses(
        jarFile: JarFile,
        names: MutableSet<String>,
    ) {
        for (entry in jarFile.entries()) {
            if (entry.isDirectory) continue
            jarClassPath(entry.name)?.let { addIfIncluded(it, names) }
        }
    }

    private fun listNestedJars(
        outer: File,
        jarFile: JarFile,
        attributes: Attributes,
        names: MutableSet<String>,
    ) {
        for (entryName in nestedJarNames(jarFile, attributes)) {
            val entry = jarFile.getJarEntry(entryName) ?: continue
            try {
                val contents = ZipInputStream(jarFile.getInputStream(entry)).use(JarContents::of)
                (contents.classNames + contents.versionedClassNames).filterTo(names) {
                    TypeMatchPolicy.isIncluded(it, includes, excludes)
                }
            } catch (e: Exception) {
                throw NestedJarException("$outer!/$entryName", e)
            }
        }
    }

    /** [entryName] is a class entry's path relative to its root, with `/` separators. */
    private fun addIfIncluded(
        entryName: String,
        names: MutableSet<String>,
    ) {
        val name = entryName.removeSuffix(".class").replace('/', '.')
        if (TypeMatchPolicy.isIncluded(name, includes, excludes)) names += name
    }

    /** A root to list. [fromClassPath] is true for an entry of `java.class.path` itself. */
    private data class Root(
        val file: File,
        val fromClassPath: Boolean,
    )

    private class NestedJarException(
        val location: String,
        cause: Exception,
    ) : Exception(cause)

    private companion object {
        val APPLICATION_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
    }
}
