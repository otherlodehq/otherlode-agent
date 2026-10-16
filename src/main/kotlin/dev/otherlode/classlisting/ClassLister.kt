package dev.otherlode.classlisting

import dev.otherlode.dependencies.ClasspathVisitor
import dev.otherlode.dependencies.ClasspathWalker
import dev.otherlode.dependencies.JarClassifier
import dev.otherlode.dependencies.NestedJarReader
import dev.otherlode.dependencies.bootLibPrefixes
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

/**
 * Lists the classes the startup classpath holds under the include rules, by name, without reading
 * a class file.
 *
 * Which files are read, and at which paths, is [ClasspathWalker]'s: each entry of [classPath]
 * (split on [File.pathSeparator]), every file a manifest `Class-Path` attribute reaches from one,
 * and an exploded Spring Boot application's libraries, as the JVM's loaders read them. A root that
 * does not exist holds nothing, as the JVM ignores it. Any file on the classpath is read as a jar,
 * whatever its extension, as the system loader does:
 *
 * - A Spring Boot fat jar or executable war the system loader reads contributes the classes under `BOOT-INF/classes/` or
 *   `WEB-INF/classes/`, named without that prefix, the classes at its own root, and the classes of
 *   each nested jar Boot's launcher puts on the classpath.
 * - Any other jar contributes its classes, a jar that names an agent included: the include rules
 *   already leave out the classes of this agent.
 *
 * A directory is walked. A directory whose `META-INF/MANIFEST.MF` marks an exploded Spring Boot
 * application is read the way its launcher reads it: the classes under `BOOT-INF/classes/` or
 * `WEB-INF/classes/` without that prefix, the classes at its root, and the classes of every
 * library the walker finds for it.
 *
 * A class counts by the static baseline's rule ([isClassEntry]). In a jar, a multi-release entry
 * under `META-INF/versions/N/` also counts, by its path after that prefix, since a class can exist
 * only there. A name is listed when [TypeMatchPolicy.isIncluded] admits it.
 *
 * The result is complete or absent. A server reads a class's absence from a complete listing as
 * proof that the build lacks it, so a listing with a hole would turn an unreadable jar into removed
 * code. [list] logs one WARNING and returns null when a root that exists, a jar or a nested jar
 * cannot be read, when an exploded application's classpath index is malformed (Boot does not
 * launch then), when [classPath] is empty or has an empty element (which the JVM reads as the
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
        try {
            ClasspathWalker(classifier).walk(elements, Listing(names))
        } catch (e: ListingFailed) {
            log.log(
                Level.WARNING,
                "otherlode: could not read ${e.location} for the class listing, so no class listing is sent: ${e.cause.message ?: e.cause}",
                e.cause,
            )
            return null
        } catch (e: Exception) {
            // The walker hands a failure to read a file to the visitor, so what reaches here failed outside any one file.
            log.log(
                Level.WARNING,
                "otherlode: could not walk the classpath for the class listing, so no class listing is sent: ${e.message ?: e}",
                e,
            )
            return null
        }
        return names.toList()
    }

    private fun refuse(reason: String): List<String>? {
        log.log(Level.WARNING, "otherlode: $reason, so no class listing is sent")
        return null
    }

    /** Adds to [names] what each file the walker reaches holds, and ends the walk at the first file it cannot read. */
    private inner class Listing(
        private val names: MutableSet<String>,
    ) : ClasspathVisitor {
        override fun jar(
            jar: File,
            jarFile: JarFile,
        ) {
            val manifest = jarFile.manifest
            if (classifier.isBootApplication(manifest)) {
                for (entry in jarFile.entries()) {
                    if (!entry.isDirectory) addApplicationEntry(entry.name, names)
                }
                listNestedJars(jar, jarFile, manifest?.mainAttributes ?: Attributes(), names)
            } else {
                addJarClasses(jarFile, names)
            }
        }

        override fun directory(directory: File) {
            walk(directory.toPath()) { relative -> if (isClassEntry(relative)) addIfIncluded(relative, names) }
        }

        override fun explodedApplication(
            root: File,
            manifest: Manifest,
        ) {
            val libPrefixes = bootLibPrefixes(manifest.mainAttributes)
            // The walker reads the library folders, so they are not walked here.
            walk(root.toPath(), skip = { folder -> libPrefixes.any { folder.startsWith(it) } }) { relative ->
                addApplicationEntry(relative, names)
            }
        }

        override fun libraryJar(
            application: File,
            jar: File,
            jarFile: JarFile,
        ) = addJarClasses(jarFile, names)

        override fun unreadable(
            location: String,
            cause: Exception,
        ) {
            val nested = cause as? NestedJarException
            throw ListingFailed(nested?.location ?: location, nested?.cause ?: cause)
        }
    }

    /**
     * Calls [visit] with the root-relative, `/`-separated path of every file under [root], leaving
     * out each folder whose path, ending in `/`, [skip] accepts.
     */
    private fun walk(
        root: Path,
        skip: (String) -> Boolean = { false },
        visit: (String) -> Unit,
    ) {
        Files.walkFileTree(
            root,
            setOf(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    if (dir != root &&
                        skip(root.relativize(dir).joinToString("/") + "/")
                    ) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

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
                val contents = NestedJarReader.read(jarFile, entry)
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

    /** Carries the file the listing could not read and why out of the walk. */
    private class ListingFailed(
        val location: String,
        override val cause: Throwable,
    ) : Exception(cause)

    /** A nested jar [location], written `outer!/entry`, that could not be read. */
    private class NestedJarException(
        val location: String,
        cause: Exception,
    ) : Exception(cause)

    private companion object {
        val APPLICATION_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
    }
}
