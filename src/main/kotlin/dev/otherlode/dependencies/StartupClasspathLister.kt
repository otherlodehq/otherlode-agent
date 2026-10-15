package dev.otherlode.dependencies

import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.registry.DependencyOrigin
import dev.otherlode.registry.DependencyRegistry
import java.io.File
import java.lang.System.Logger.Level
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

/**
 * One dependency the startup listing found, or the sweep found at load. [origin] is where its
 * bytes live, kept off the wire. [classNames] are the dotted names of the classes it holds, empty
 * when the listing was not asked to keep them.
 */
data class ListedDependency(
    val identities: List<DependencyIdentity>,
    val identitySource: DependencyIdentitySource,
    val location: String,
    val classCount: Int,
    val origin: DependencyOrigin,
    val classNames: Set<String> = emptySet(),
)

/**
 * Lists the dependencies on the startup classpath.
 *
 * Which files are read, at which paths and in what order is [ClasspathWalker]'s: each entry of
 * [classPath] (split on [File.pathSeparator]), the files a manifest `Class-Path` reaches from one,
 * and an exploded Spring Boot application's libraries, as the JVM's class loaders read them. The
 * order is the search order. A dependency's location is the path the walker read its jar at.
 *
 * A file on the classpath is read as a jar, whatever its extension. A missing entry and a
 * directory are never dependencies. Neither is a jar whose manifest names an agent
 * (`Premain-Class` or `Launcher-Agent-Class`), though its `Class-Path` is still followed.
 *
 * A Spring Boot fat jar or executable war (a manifest with `Spring-Boot-Classpath-Index` or
 * `Spring-Boot-Lib`) is the application, never a dependency. Its dependencies are the nested jars
 * Boot's launcher puts on the classpath (see [nestedJarNames]), each streamed from the outer jar
 * with nothing extracted to disk. An exploded application on the classpath is the same: its own
 * classes are the adopter's, and each jar its own loader reads is judged as a jar stored in a fat
 * jar is, so a library jar that names an agent is still a dependency.
 *
 * A file the walker reaches at two paths is judged once, at the first, and keeps that location.
 *
 * A jar holding any class the include rules admit is the adopter's own and not a dependency.
 * [JarClassifier] holds these rules, shared with the sweep's discovery at load.
 *
 * Two jars with one identity key ([DependencyRegistry.identityKey]) are one dependency, and the
 * first found keeps its location and class count. A jar that cannot be read is skipped with one
 * WARNING and the rest of the listing continues.
 *
 * Every jar read and judged not a dependency (an agent jar, a fat jar, a jar of the adopter's own)
 * goes to [onNotADependency], so the sweep can recognise its classes without reading it again.
 *
 * With [keepClassNames], each dependency carries the names of its classes for the registry's class
 * index, and a jar whose identity key was already found adds its names to that dependency's. A name
 * held by two jars stays with the first in search order, the one a loader would find.
 * Without it, [ListedDependency.classNames] is empty, so nothing holds them past each jar's read.
 */
class StartupClasspathLister(
    includes: List<String>,
    excludes: List<String>,
    private val classPath: String = System.getProperty("java.class.path").orEmpty(),
    private val onNotADependency: (DependencyOrigin) -> Unit = {},
    private val keepClassNames: Boolean = false,
) {
    private val log = System.getLogger(StartupClasspathLister::class.java.name)
    private val classifier = JarClassifier(includes, excludes)

    /** Walks the classpath and returns the dependencies found, in the order the JDK would search them. */
    fun list(): List<ListedDependency> {
        val found = LinkedHashMap<List<String>, ListedDependency>()
        val claimed = HashSet<String>()
        val entries = classPath.split(File.pathSeparator).filter { it.isNotEmpty() }
        ClasspathWalker(classifier).walk(entries, Listing { add(found, claimed, it) })
        return found.values.toList()
    }

    /**
     * Adds [listed] to [found], merging it into an earlier jar with the same identity key. Each kept
     * class name goes only to the first jar in search order holding it, tracked in [claimed], so a
     * name stays with that jar even when a later jar's identity was first seen earlier still.
     */
    private fun add(
        found: LinkedHashMap<List<String>, ListedDependency>,
        claimed: MutableSet<String>,
        listed: ListedDependency,
    ) {
        val names = if (keepClassNames) listed.classNames.filterTo(LinkedHashSet()) { claimed.add(it) } else emptySet()
        val kept = listed.copy(classNames = names)
        val key = DependencyRegistry.identityKey(kept.identities)
        val first = found.putIfAbsent(key, kept) ?: return
        if (keepClassNames) found[key] = first.copy(classNames = first.classNames + kept.classNames)
    }

    /** Reports to [emit] each dependency the walker's files yield, and skips a file it cannot read with one WARNING. */
    private inner class Listing(
        private val emit: (ListedDependency) -> Unit,
    ) : ClasspathVisitor {
        /** The real path of each file already judged. */
        private val judged = HashSet<File>()

        /**
         * Whether [jar] is judged for the first time. The walker reads a file once for each path and
         * loader it is reached by, as the JVM does. The first read in search order is where the
         * file's classes load from, so it decides. A second judgement could only add a record that
         * never counts a load, or contradict the first.
         */
        private fun firstJudgement(jar: File): Boolean =
            judged.add(runCatching { jar.toPath().toRealPath().toFile() }.getOrDefault(jar.absoluteFile))

        override fun jar(
            jar: File,
            jarFile: JarFile,
        ) {
            if (!firstJudgement(jar)) return
            val manifest = jarFile.manifest
            val origin = DependencyOrigin.FlatJar(jar.toPath())
            when (classifier.kindOf(manifest)) {
                JarClassifier.Kind.AGENT -> {
                    onNotADependency(origin)
                }

                JarClassifier.Kind.BOOT_APPLICATION -> {
                    onNotADependency(origin)
                    listNestedJars(jar, jarFile, manifest?.mainAttributes ?: Attributes())
                }

                JarClassifier.Kind.ORDINARY -> {
                    val listed = classifier.classifyFlat(JarContents.of(jarFile), jar.name, jar.path, origin)
                    if (listed != null) emit(listed) else onNotADependency(origin)
                }
            }
        }

        override fun directory(directory: File) = Unit

        override fun explodedApplication(
            root: File,
            manifest: Manifest,
        ) = Unit

        override fun libraryJar(
            application: File,
            jar: File,
            jarFile: JarFile,
        ) {
            if (!firstJudgement(jar)) return
            val origin = DependencyOrigin.FlatJar(jar.toPath())
            val listed = classifier.classifyNested(JarContents.of(jarFile), jar.name, jar.path, jar.path, origin)
            if (listed != null) emit(listed) else onNotADependency(origin)
        }

        override fun unreadable(
            location: String,
            cause: Exception,
        ) {
            log.log(Level.WARNING, "otherlode: could not read $location for the dependency listing, skipping it", cause)
        }

        private fun listNestedJars(
            outer: File,
            jarFile: JarFile,
            attributes: Attributes,
        ) {
            for (entryName in nestedJarNames(jarFile, attributes)) {
                val entry = jarFile.getJarEntry(entryName) ?: continue
                try {
                    val contents = ZipInputStream(jarFile.getInputStream(entry)).use(JarContents::of)
                    val origin = DependencyOrigin.NestedJar(outer.toPath(), entryName)
                    val listed =
                        classifier.classifyNested(contents, entryName.substringAfterLast('/'), entryName, "$outer!/$entryName", origin)
                    if (listed != null) emit(listed) else onNotADependency(origin)
                } catch (e: Exception) {
                    unreadable("$outer!/$entryName", e)
                }
            }
        }
    }
}
