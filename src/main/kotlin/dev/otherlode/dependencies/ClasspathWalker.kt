package dev.otherlode.dependencies

import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest

// The walker closes a JarFile it hands to a callback when the callback returns.
internal interface ClasspathVisitor {
    fun jar(
        jar: File,
        jarFile: JarFile,
    )

    fun jarWithUnreadableManifest(
        jar: File,
        jarFile: JarFile,
        cause: Exception,
    ) = unreadable(jar.path, cause)

    fun jarWithInvalidClassPath(
        jar: File,
        jarFile: JarFile,
        cause: Exception,
    ) = unreadable(jar.path, cause)

    fun jarWithIgnoredClassPath(
        jar: File,
        jarFile: JarFile,
    ) = jar(jar, jarFile)

    fun libraryJarWithIgnoredClassPath(
        application: File,
        jar: File,
        jarFile: JarFile,
    ) = libraryJar(application, jar, jarFile)

    fun directoryWithUnreadableManifest(
        directory: File,
        cause: Exception,
    ) = unreadable(directory.path, cause)

    fun directory(directory: File)

    fun libraryDirectory(
        application: File,
        directory: File,
    ) = directory(directory)

    fun explodedApplication(
        root: File,
        manifest: Manifest,
    )

    fun libraryJar(
        application: File,
        jar: File,
        jarFile: JarFile,
    )

    fun unreadable(
        location: String,
        cause: Exception,
    )
}

internal class ClasspathWalker(
    private val classifier: JarClassifier,
    private val listFolder: (File) -> Array<File>? = File::listFiles,
) {
    fun walk(
        entries: List<String>,
        visitor: ClasspathVisitor,
    ) {
        val opened = mutableSetOf<Pair<File?, File>>()
        val pending = ArrayDeque(entries.map { Root(File(it), Root.Kind.CLASS_PATH) })
        val applications = ArrayDeque<List<Root>>()
        while (true) {
            if (pending.isEmpty()) pending += applications.removeFirstOrNull() ?: break
            val root = pending.removeFirst()
            val file = resolve(root) ?: continue
            if (!opened.add(root.application to file)) continue
            val next =
                when {
                    root.kind != Root.Kind.LIBRARY && file.isDirectory -> {
                        readDirectory(file, visitor)?.takeIf { it.isNotEmpty() }?.let(applications::addLast)
                        emptyList()
                    }

                    file.isDirectory -> {
                        attempt(file, visitor) { visitor.libraryDirectory(checkNotNull(root.application), file) }
                        emptyList()
                    }

                    root.application != null -> {
                        readJar(file, root.application, visitor) { visitor.libraryJar(root.application, file, it) }
                    }

                    else -> {
                        readJar(file, null, visitor) { visitor.jar(file, it) }
                    }
                }
            next.asReversed().forEach(pending::addFirst)
        }
    }

    private fun resolve(root: Root): File? =
        when (root.kind) {
            Root.Kind.CLASS_PATH -> canonicalFile(root.file)?.takeIf { it.exists() }

            Root.Kind.REFERENCED -> root.file.absoluteFile.takeIf { if (root.isDirectory) it.isDirectory else it.isFile }

            Root.Kind.LIBRARY -> root.file.takeIf { it.exists() }
        }

    private fun readJar(
        jar: File,
        application: File?,
        visitor: ClasspathVisitor,
        read: (JarFile) -> Unit,
    ): List<Root> {
        val jarFile = attempt(jar, visitor) { JarFile(jar) } ?: return emptyList()
        try {
            when (val manifest = manifestOf(jar, jarFile)) {
                is ManifestRead.Named -> {
                    attempt(jar, visitor) { read(jarFile) }
                    return manifest.entries.map { entry -> Root(entry.file, Root.Kind.REFERENCED, application, entry.isDirectory) }
                }

                is ManifestRead.KeptInvalidClassPath -> {
                    if (application == null) {
                        attempt(jar, visitor) { visitor.jarWithIgnoredClassPath(jar, jarFile) }
                    } else {
                        attempt(jar, visitor) { visitor.libraryJarWithIgnoredClassPath(application, jar, jarFile) }
                    }
                }

                is ManifestRead.KeptUnreadable -> {
                    if (application == null) {
                        attempt(jar, visitor) { visitor.jarWithUnreadableManifest(jar, jarFile, manifest.cause) }
                    } else {
                        visitor.unreadable(jar.path, manifest.cause)
                    }
                }

                is ManifestRead.DroppedInvalidClassPath -> {
                    if (application == null) {
                        attempt(jar, visitor) { visitor.jarWithInvalidClassPath(jar, jarFile, manifest.cause) }
                    } else {
                        visitor.unreadable(jar.path, manifest.cause)
                    }
                }

                is ManifestRead.Dropped -> {
                    visitor.unreadable(jar.path, manifest.cause)
                }
            }
            return emptyList()
        } finally {
            runCatching { jarFile.close() }
        }
    }

    private sealed interface ManifestRead {
        class Named(
            val entries: List<ClassPathEntry>,
        ) : ManifestRead

        class KeptUnreadable(
            val cause: Exception,
        ) : ManifestRead

        object KeptInvalidClassPath : ManifestRead

        class DroppedInvalidClassPath(
            val cause: Exception,
        ) : ManifestRead

        class Dropped(
            val cause: Exception,
        ) : ManifestRead
    }

    private fun manifestOf(
        jar: File,
        jarFile: JarFile,
    ): ManifestRead {
        var entries: List<ClassPathEntry>? = null
        val failure: Exception? =
            try {
                entries = classPathEntries(jar, jarFile.manifest)
                if ((jarFile.getEntry(MANIFEST_ENTRY)?.size ?: 0L) >
                    LARGE_MANIFEST
                ) {
                    IOException("the manifest is larger than $LARGE_MANIFEST bytes")
                } else {
                    null
                }
            } catch (e: Exception) {
                e
            }
        if (failure == null) return ManifestRead.Named(entries.orEmpty())
        if (failure !is IOException) return ManifestRead.Dropped(failure)
        val kept = jdkLoadsFrom(jar, jarFile)
        return when {
            failure is MalformedURLException -> {
                if (kept) {
                    ManifestRead.KeptInvalidClassPath
                } else {
                    ManifestRead.DroppedInvalidClassPath(
                        failure,
                    )
                }
            }

            !kept -> {
                ManifestRead.Dropped(failure)
            }

            entries != null -> {
                ManifestRead.Named(entries)
            }

            else -> {
                ManifestRead.KeptUnreadable(failure)
            }
        }
    }

    private fun readDirectory(
        root: File,
        visitor: ClasspathVisitor,
    ): List<Root>? {
        var failure: Exception? = null
        val manifest =
            try {
                File(root, "META-INF/MANIFEST.MF").takeIf { it.isFile }?.inputStream()?.use(::Manifest)
            } catch (e: Exception) {
                failure = e
                null
            }
        val unreadable = failure
        if (unreadable != null) {
            attempt(root, visitor) { visitor.directoryWithUnreadableManifest(root, unreadable) }
            return null
        }
        if (manifest == null || !classifier.isBootApplication(manifest)) {
            attempt(root, visitor) { visitor.directory(root) }
            return null
        }
        val libraries = attempt(root, visitor) { libraries(root, manifest) } ?: return null
        attempt(root, visitor) { visitor.explodedApplication(root, manifest) }
        return libraries.map { Root(File(root, it), Root.Kind.LIBRARY, application = root) }
    }

    private fun libraries(
        root: File,
        manifest: Manifest,
    ): List<String> {
        val prefixes = bootLibPrefixes(manifest.mainAttributes)
        val index = classPathIndex(root, manifest.mainAttributes.getValue(CLASSPATH_INDEX) ?: "${entryFolder(prefixes)}classpath.idx")
        val walked = mutableListOf<String>()
        for (prefix in prefixes.sortedBy { it.trimEnd('/') }) {
            val folder = File(root, prefix)
            if (folder.isDirectory) collectFiles(folder, prefix, walked, mutableSetOf())
        }
        val indexed = index.toSet()
        return walked.filterNot { it in indexed } + index
    }

    private fun collectFiles(
        folder: File,
        prefix: String,
        found: MutableList<String>,
        open: MutableSet<Path>,
    ) {
        val real = folder.toPath().toRealPath()
        if (!open.add(real)) return
        val children = listFolder(folder) ?: throw IOException("could not list the folder $folder")
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) collectFiles(child, "$prefix${child.name}/", found, open) else found += prefix + child.name
        }
        open.remove(real)
    }

    private fun classPathIndex(
        root: File,
        location: String,
    ): List<String> {
        val file = File(root, location)
        if (!file.isFile) return emptyList()
        return Files
            .readAllLines(file.toPath())
            .filter { line -> line.any { it > ' ' } }
            .map { line ->
                check(
                    line.length >= 4 && line.startsWith("- \"") && line.endsWith("\""),
                ) { "Malformed classpath index line [$line] in $file" }
                line.substring(3, line.length - 1)
            }.distinct()
    }

    private fun entryFolder(prefixes: List<String>): String = prefixes.first().substringBefore('/') + "/"

    private fun <T : Any> attempt(
        location: File,
        visitor: ClasspathVisitor,
        block: () -> T,
    ): T? {
        val failure =
            try {
                return block()
            } catch (e: Exception) {
                e
            }
        visitor.unreadable(location.path, failure)
        return null
    }

    private fun canonicalFile(entry: File): File? =
        try {
            entry.canonicalFile
        } catch (_: IOException) {
            null
        }

    private data class Root(
        val file: File,
        val kind: Kind,
        val application: File? = null,
        val isDirectory: Boolean = false,
    ) {
        enum class Kind {
            CLASS_PATH,

            REFERENCED,

            LIBRARY,
        }
    }

    private companion object {
        const val MANIFEST_ENTRY = "META-INF/MANIFEST.MF"
        const val LARGE_MANIFEST = 1_000_000L
        const val CLASSPATH_INDEX = "Spring-Boot-Classpath-Index"
    }
}
