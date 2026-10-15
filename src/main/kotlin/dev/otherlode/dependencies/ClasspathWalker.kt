package dev.otherlode.dependencies

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * What a [ClasspathWalker] reports as it meets each file. Every callback runs on the walking
 * thread, and a [JarFile] it hands over is closed when the callback returns.
 */
internal interface ClasspathVisitor {
    /** A jar the system loader reads: an entry of `java.class.path`, or a jar a `Class-Path` names. */
    fun jar(
        jar: File,
        jarFile: JarFile,
    )

    /**
     * A directory read as a plain folder of classes: one on the classpath, or named by a
     * `Class-Path`, whose manifest marks no Spring Boot application, or a folder a classpath index
     * names.
     */
    fun directory(directory: File)

    /** An exploded Spring Boot application's [root], called once before its library jars. */
    fun explodedApplication(
        root: File,
        manifest: Manifest,
    )

    /**
     * A jar the loader of the exploded Spring Boot application at [application] reads: one in its
     * library folders or classpath index, or one their `Class-Path` names.
     */
    fun libraryJar(
        application: File,
        jar: File,
        jarFile: JarFile,
    )

    /**
     * [location] could not be read, or a callback threw while handling it. [cause] says why.
     * Whatever this throws ends the walk and reaches the caller of [ClasspathWalker.walk]. When it
     * returns, the walk goes on. A file that could not be opened, or whose manifest could not be read,
     * adds nothing more, and nor does an exploded application whose library folders or classpath
     * index could not be read. After a callback throws, the file's `Class-Path` or library
     * jars are still read.
     */
    fun unreadable(
        location: String,
        cause: Exception,
    )
}

/**
 * Walks the startup classpath in the order and by the rules the JVM's class loaders read it, and
 * reports each file to a [ClasspathVisitor]. It owns which files are read and in what order. What
 * to do with a file, and what to do when one cannot be read, are the visitor's.
 *
 * The roots are the entries of `java.class.path` in order. The files a jar's manifest
 * `Class-Path` names, resolved by [classPathEntries], are read straight after that jar and before
 * anything else, in attribute order, depth first, as `URLClassPath` searches them. A loader opens
 * each URL once, so a file is read once for each path it is reached at, and its `Class-Path`
 * resolves against that path.
 *
 * Paths resolve as the JDK resolves them:
 * - An entry of `java.class.path` is read through its canonical path (`URLClassPath.toFileURL`,
 *   through `File.getCanonicalFile`): links are followed as far as the path resolves, and any
 *   rest, `..` included, is collapsed as text with no link in it followed. An entry with no file
 *   at that path is skipped. The visitor gets the canonical path, and the entry's `Class-Path`
 *   resolves against it.
 * - A file a `Class-Path` names is read at the path given, with no link followed
 *   (`URLClassPath`), so its own `Class-Path` resolves against the folder that holds the link. A
 *   URL that ends in `/` is read only as a directory and any other only as a jar, and a target
 *   that is not what its URL says is skipped, as the JDK skips it.
 *
 * A regular file on the classpath is a jar whatever its extension. A directory is read as an
 * exploded Spring Boot application when its `META-INF/MANIFEST.MF` marks one
 * ([JarClassifier.isBootApplication]), and as a plain directory otherwise. The JDK never reads a
 * directory's manifest for a `Class-Path`, so neither does this.
 *
 * The rest of an exploded application is left to the visitor, apart from its library jars, which
 * [libraries] lists in the order Boot's launcher loads them. Boot loads them through its own
 * `URLClassLoader`, which asks the system loader first, so they are read after the whole system
 * classpath, one application at a time. That loader follows each one's `Class-Path` straight after
 * it, and keeps its own record of the URLs it has opened, apart from the system loader's.
 */
internal class ClasspathWalker(
    private val classifier: JarClassifier,
    private val listFolder: (File) -> Array<File>? = File::listFiles,
) {
    /** [entries] are the elements of `java.class.path`, with empty ones already removed. */
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
                        attempt(file, visitor) { visitor.directory(file) }
                        emptyList()
                    }

                    root.application != null -> readJar(file, root.application, visitor) { visitor.libraryJar(root.application, file, it) }

                    else -> readJar(file, null, visitor) { visitor.jar(file, it) }
                }
            // The first of them is read first, so it goes on the front last.
            next.asReversed().forEach(pending::addFirst)
        }
    }

    /** The file [root] reads, or null when the JVM would not read it. */
    private fun resolve(root: Root): File? =
        when (root.kind) {
            Root.Kind.CLASS_PATH -> canonicalFile(root.file)?.takeIf { it.exists() }
            Root.Kind.REFERENCED -> root.file.absoluteFile.takeIf { if (root.isDirectory) it.isDirectory else it.isFile }
            // A classpath index can name a file that does not exist, which Boot's loader then finds nothing in.
            Root.Kind.LIBRARY -> root.file.takeIf { it.exists() }
        }

    /**
     * Reads [jar] through [read] and returns the roots its `Class-Path` names, which the loader of
     * [application] (the system loader when null) searches next. A jar that cannot be opened, or
     * whose manifest cannot be read, names none. When [read] fails, the `Class-Path` is still
     * followed, since the JDK needs only the manifest to follow it.
     */
    private fun readJar(
        jar: File,
        application: File?,
        visitor: ClasspathVisitor,
        read: (JarFile) -> Unit,
    ): List<Root> {
        val jarFile = attempt(jar, visitor) { JarFile(jar) } ?: return emptyList()
        try {
            val named = attempt(jar, visitor) { classPathEntries(jar, jarFile.manifest) } ?: return emptyList()
            attempt(jar, visitor) { read(jarFile) }
            return named.map { entry -> Root(entry.file, Root.Kind.REFERENCED, application, entry.isDirectory) }
        } finally {
            // Everything has been read by then, so a failure to close loses nothing.
            runCatching { jarFile.close() }
        }
    }

    /**
     * Reads the directory [root], on the classpath or named by a `Class-Path`. Returns the
     * libraries of an exploded application as roots for its own loader, or null for a plain
     * directory or one whose layout cannot be read. A failing callback leaves the libraries read.
     */
    private fun readDirectory(
        root: File,
        visitor: ClasspathVisitor,
    ): List<Root>? {
        val layout =
            attempt(root, visitor) {
                val manifestFile = File(root, "META-INF/MANIFEST.MF")
                val manifest = if (manifestFile.isFile) manifestFile.inputStream().use(::Manifest) else null
                if (manifest != null &&
                    classifier.isBootApplication(manifest)
                ) {
                    Layout(manifest, libraries(root, manifest))
                } else {
                    Layout(null, emptyList())
                }
            } ?: return null
        val manifest = layout.manifest
        if (manifest == null) {
            attempt(root, visitor) { visitor.directory(root) }
            return null
        }
        attempt(root, visitor) { visitor.explodedApplication(root, manifest) }
        return layout.libraries.map { Root(File(root, it), Root.Kind.LIBRARY, application = root) }
    }

    /** A directory's Boot [manifest] and [libraries], or a null manifest for a plain directory. */
    private class Layout(
        val manifest: Manifest?,
        val libraries: List<String>,
    )

    /**
     * The root-relative, `/`-separated paths of an exploded application's libraries, in the order
     * Boot's launcher puts them on its class loader (`ExplodedArchive`, `Launcher` and
     * `ClassPathIndexFile` in Boot 3.2 to 4.1). First comes every file under the library folders
     * ([bootLibPrefixes]) that the classpath index does not name, at any depth and whatever the
     * extension, in a depth-first walk that visits each folder's children sorted by name. Then
     * come the paths the index names, in its order. A path the index names that is a folder is read
     * as a folder of classes. The index is read from the file the `Spring-Boot-Classpath-Index`
     * attribute names, or else `classpath.idx` in `BOOT-INF/` or `WEB-INF/`. A line that is not
     * `- "<path>"` stops Boot's launch, so it throws here.
     */
    private fun libraries(
        root: File,
        manifest: Manifest,
    ): List<String> {
        val prefixes = bootLibPrefixes(manifest.mainAttributes)
        val index = classPathIndex(root, manifest.mainAttributes.getValue(CLASSPATH_INDEX) ?: "${entryFolder(prefixes)}classpath.idx")
        val walked = mutableListOf<String>()
        // Boot sorts sibling paths as strings, so `lib` comes before `lib-provided`.
        for (prefix in prefixes.sortedBy { it.trimEnd('/') }) {
            val folder = File(root, prefix)
            if (folder.isDirectory) collectFiles(folder, prefix, walked, mutableSetOf())
        }
        val indexed = index.toSet()
        return walked.filterNot { it in indexed } + index
    }

    /**
     * Adds to [found] each file under [folder], named [prefix] plus its path, depth first with
     * children sorted by name. A link back to a folder already open on this branch is not followed
     * again: Boot would follow it until the system refuses the path, reading the same files at ever
     * longer paths. A folder that cannot be listed, or whose real path cannot be read, throws. Boot
     * reads such a folder as empty, but the agent cannot tell a failure Boot also had from one only
     * its own read had, and reading it as empty in the second case would hide jars the application
     * loads.
     */
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

    /** The paths [location], relative to [root], names as an index, or none when it is not a file. */
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

    /** `WEB-INF/` for an executable war's library folders, else `BOOT-INF/`. */
    private fun entryFolder(prefixes: List<String>): String = prefixes.first().substringBefore('/') + "/"

    /**
     * Runs [block], and on an exception hands it to [ClasspathVisitor.unreadable] with [location]
     * and returns null. No call is nested in another `attempt` or in a `try` that catches, so an
     * exception that `unreadable` throws is not caught a second time.
     */
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

    /** The canonical path of [entry], or null when it cannot be formed, as the JDK drops such an entry. */
    private fun canonicalFile(entry: File): File? =
        try {
            entry.canonicalFile
        } catch (_: IOException) {
            null
        }

    /**
     * A file to read. [application] is the exploded application whose loader reads it, or null for
     * the system loader. [isDirectory] says how a [Kind.REFERENCED] URL reads it.
     */
    private data class Root(
        val file: File,
        val kind: Kind,
        val application: File? = null,
        val isDirectory: Boolean = false,
    ) {
        enum class Kind {
            /** An entry of `java.class.path`, read through its canonical path. */
            CLASS_PATH,

            /** A file a `Class-Path` names, read as given. */
            REFERENCED,

            /** A file in an exploded application's library folders or classpath index, read as given. */
            LIBRARY,
        }
    }

    private companion object {
        const val CLASSPATH_INDEX = "Spring-Boot-Classpath-Index"
    }
}
