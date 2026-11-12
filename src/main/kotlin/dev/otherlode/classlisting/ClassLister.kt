package dev.otherlode.classlisting

import dev.otherlode.agentLogger
import dev.otherlode.dependencies.ClasspathVisitor
import dev.otherlode.dependencies.ClasspathWalker
import dev.otherlode.dependencies.CodeSourceLocation
import dev.otherlode.dependencies.JarClassifier
import dev.otherlode.dependencies.NestedJarReader
import dev.otherlode.dependencies.bootLibPrefixes
import dev.otherlode.dependencies.nestedJarNames
import dev.otherlode.dependencies.rootKey
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
 * It lists what [ClasspathWalker] reaches from [classPath], including Spring Boot fat jars and
 * exploded applications. A class counts by [isClassEntry], or as a multi-release entry by its path
 * after `META-INF/versions/N/`, and is listed when [TypeMatchPolicy.isIncluded] admits it. A
 * complete listing hands the keys of the roots it read and the listed names to [onRoots].
 */
class ClassLister(
    private val includes: List<String>,
    private val excludes: List<String>,
    private val classPath: String = System.getProperty("java.class.path").orEmpty(),
    private val modulePath: String = System.getProperty("jdk.module.path").orEmpty(),
    private val mainModule: String = System.getProperty("jdk.module.main").orEmpty(),
    private val upgradeModulePath: String = System.getProperty("jdk.module.upgrade.path").orEmpty(),
    private val jvmArguments: () -> List<String>? = ::inputArguments,
    private val onRoots: (Set<String>, Set<String>) -> Unit = { _, _ -> },
) {
    private val log by agentLogger(ClassLister::class.java.name)
    private val classifier = JarClassifier(includes, excludes)

    /**
     * The sorted, distinct, dotted names of the listed classes. Returns null and logs one WARNING
     * when the listing would be partial: a root that exists, a jar or a nested jar cannot be read, an
     * exploded application's classpath index is malformed, [classPath] is empty or has an empty
     * element, [modulePath] or [mainModule] is set, [upgradeModulePath] is set, [jvmArguments]
     * returns null, or `--patch-module` is among [jvmArguments].
     */
    fun list(): List<String>? {
        if (modulePath.isNotBlank() || mainModule.isNotBlank()) {
            return refuse("this JVM runs from a module path or a linked image, which the class listing does not read")
        }
        val arguments = jvmArguments()
            ?: return refuse("this JVM has no java.management module, so the agent cannot tell whether it patches modules")
        if (upgradeModulePath.isNotBlank() || arguments.any { it == "--patch-module" || it.startsWith("--patch-module=") }) {
            return refuse("this JVM upgrades or patches modules, which the class listing does not read")
        }
        val elements = classPath.split(File.pathSeparator)
        if (elements.any { it.isEmpty() }) {
            return refuse("java.class.path is empty or has an empty element, which the JVM reads as the working directory")
        }
        val names = sortedSetOf<String>()
        val roots = mutableSetOf<String>()
        try {
            ClasspathWalker(classifier).walk(elements, Listing(names, roots))
        } catch (e: ListingFailed) {
            log.log(
                Level.WARNING,
                "otherlode: could not read ${e.location} for the class listing, so no class listing is sent: ${e.cause.message ?: e.cause}",
                e.cause,
            )
            return null
        } catch (e: Exception) {
            log.log(
                Level.WARNING,
                "otherlode: could not walk the classpath for the class listing, so no class listing is sent: ${e.message ?: e}",
                e,
            )
            return null
        }
        onRoots(roots, names)
        return names.toList()
    }

    private fun refuse(reason: String): List<String>? {
        log.log(Level.WARNING, "otherlode: $reason, so no class listing is sent")
        return null
    }

    private inner class Listing(
        private val names: MutableSet<String>,
        private val roots: MutableSet<String>,
    ) : ClasspathVisitor {
        override fun jar(
            jar: File,
            jarFile: JarFile,
        ) {
            val manifest = jarFile.manifest
            addRoot(CodeSourceLocation.OnDisk(jar.toPath()))
            if (classifier.isBootApplication(manifest)) {
                val classesFolders = mutableSetOf<String>()
                for (entry in jarFile.entries()) {
                    if (entry.isDirectory) continue
                    addApplicationEntry(entry.name, names)
                    APPLICATION_CLASSES_PREFIXES.firstOrNull { entry.name.startsWith(it) }?.let { classesFolders += it }
                }
                classesFolders.forEach { addRoot(CodeSourceLocation.InJar(jar.toPath(), it)) }
                listNestedJars(jar, jarFile, manifest?.mainAttributes ?: Attributes(), names, roots)
            } else {
                addJarClasses(jarFile, names)
            }
        }

        private fun addRoot(location: CodeSourceLocation) {
            location.rootKey()?.let { roots += it }
        }

        override fun jarWithInvalidClassPath(
            jar: File,
            jarFile: JarFile,
            cause: Exception,
        ) {
            addRoot(CodeSourceLocation.OnDisk(jar.toPath()))
            addJarClasses(jarFile, names)
        }

        override fun jarWithIgnoredClassPath(
            jar: File,
            jarFile: JarFile,
        ) {
            refuseIndex(jar, jarFile)
            jar(jar, jarFile)
        }

        override fun libraryJarWithIgnoredClassPath(
            application: File,
            jar: File,
            jarFile: JarFile,
        ) {
            refuseIndex(jar, jarFile)
            libraryJar(application, jar, jarFile)
        }

        private fun refuseIndex(
            jar: File,
            jarFile: JarFile,
        ) {
            if (jarFile.getEntry(INDEX_LIST) != null) {
                throw ListingFailed(jar.path, IOException("the jar holds $INDEX_LIST, which the listing does not follow"))
            }
        }

        override fun directory(directory: File) {
            addRoot(CodeSourceLocation.OnDisk(directory.toPath()))
            walk(directory.toPath()) { relative -> if (isClassEntry(relative)) addIfIncluded(relative, names) }
        }

        override fun explodedApplication(
            root: File,
            manifest: Manifest,
        ) {
            val libPrefixes = bootLibPrefixes(manifest.mainAttributes)
            addRoot(CodeSourceLocation.OnDisk(root.toPath()))
            APPLICATION_CLASSES_PREFIXES.forEach { addRoot(CodeSourceLocation.OnDisk(root.toPath().resolve(it))) }
            walk(root.toPath(), skip = { folder -> libPrefixes.any { folder.startsWith(it) } }) { relative ->
                addApplicationEntry(relative, names)
            }
        }

        override fun libraryJar(
            application: File,
            jar: File,
            jarFile: JarFile,
        ) {
            addRoot(CodeSourceLocation.OnDisk(jar.toPath()))
            addJarClasses(jarFile, names)
        }

        override fun unreadable(
            location: String,
            cause: Exception,
        ) {
            val nested = cause as? NestedJarException
            throw ListingFailed(nested?.location ?: location, nested?.cause ?: cause)
        }
    }

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

    private fun addApplicationEntry(
        path: String,
        names: MutableSet<String>,
    ) {
        val prefix = APPLICATION_CLASSES_PREFIXES.firstOrNull { path.startsWith(it) }
        val name = if (prefix != null) path.removePrefix(prefix) else path
        jarClassPath(name)?.let { addIfIncluded(it, names) }
    }

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
        roots: MutableSet<String>,
    ) {
        for (entryName in nestedJarNames(jarFile, attributes)) {
            val entry = jarFile.getJarEntry(entryName) ?: continue
            try {
                val contents = NestedJarReader.read(jarFile, entry)
                roots.addAll(listOfNotNull(CodeSourceLocation.InJar(outer.toPath(), entryName).rootKey()))
                (contents.classNames + contents.versionedClassNames).filterTo(names) {
                    TypeMatchPolicy.isIncluded(it, includes, excludes)
                }
            } catch (e: Exception) {
                throw NestedJarException("$outer!/$entryName", e)
            }
        }
    }

    private fun addIfIncluded(
        entryName: String,
        names: MutableSet<String>,
    ) {
        val name = entryName.removeSuffix(".class").replace('/', '.')
        if (TypeMatchPolicy.isIncluded(name, includes, excludes)) names += name
    }

    private class ListingFailed(
        val location: String,
        override val cause: Throwable,
    ) : Exception(cause)

    private class NestedJarException(
        val location: String,
        cause: Exception,
    ) : Exception(cause)

    private companion object {
        const val INDEX_LIST = "META-INF/INDEX.LIST"

        val APPLICATION_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
    }
}

private fun inputArguments(): List<String>? {
    if (ModuleLayer.boot().findModule("java.management").isEmpty) return null
    return java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
}
