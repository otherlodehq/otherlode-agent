package dev.otherlode.dependencies

import dev.otherlode.agentLogger
import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.registry.DependencyOrigin
import dev.otherlode.registry.DependencyRegistry
import java.io.File
import java.lang.System.Logger.Level
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * One dependency the startup listing found, or the sweep found at load. [origin] is where its
 * bytes live, kept off the wire. [classNames] are the dotted names of its classes, empty when the
 * listing was not asked to keep them.
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
 * Lists the dependencies on the startup classpath, in the order the JDK would search them: each
 * entry of [classPath] (split on [File.pathSeparator]), the files a manifest `Class-Path` reaches
 * from one, and an exploded Spring Boot application's libraries. A missing entry, a directory, a
 * Spring Boot fat jar or executable war, and a classpath jar whose manifest names an agent are never
 * dependencies, though an agent jar's `Class-Path` is still followed. The nested jars of a fat jar
 * and the library jars of an exploded application are judged without the agent rule. A jar holding
 * a class the include rules admit is the adopter's own. Two jars with one identity key
 * ([DependencyRegistry.identityKey]) are one dependency, and the first found keeps its location and
 * class count. A jar that cannot be read is skipped with one WARNING. Every jar read and judged not a
 * dependency goes to [onNotADependency]. With [keepClassNames], each dependency carries the names of
 * its classes, merged across jars with its identity key, and a name held by two jars stays with the
 * first in search order. Without it, [ListedDependency.classNames] is empty.
 */
class StartupClasspathLister(
    includes: List<String>,
    excludes: List<String>,
    private val classPath: String = System.getProperty("java.class.path").orEmpty(),
    private val onNotADependency: (DependencyOrigin) -> Unit = {},
    private val keepClassNames: Boolean = false,
) {
    private val log by agentLogger(StartupClasspathLister::class.java.name)
    private val classifier = JarClassifier(includes, excludes)

    /** Walks the classpath and returns the dependencies found, in the order the JDK would search them. */
    fun list(): List<ListedDependency> {
        val found = LinkedHashMap<List<String>, ListedDependency>()
        val claimed = HashSet<String>()
        val entries = classPath.split(File.pathSeparator).filter { it.isNotEmpty() }
        ClasspathWalker(classifier).walk(entries, Listing { add(found, claimed, it) })
        return found.values.toList()
    }

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

    private inner class Listing(
        private val emit: (ListedDependency) -> Unit,
    ) : ClasspathVisitor {
        private val judged = HashSet<File>()

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
                    val contents = NestedJarReader.read(jarFile, entry)
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
