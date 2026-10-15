package dev.otherlode.dependencies

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks [ClasspathWalker] against the JDK itself. The walker's unit tests check it against a
 * reading of the JDK's rules. This test builds a tree of jars, directories and links, starts a
 * real JVM on it, and asks the system loader which file each class loads from. Each file holds one
 * class with a unique name, so a class's code source names the file the JVM read it from, at the
 * path the JVM used.
 * The first file the walker reports that holds the class must be that file at that path, and a
 * class the JVM cannot find must never be reported.
 *
 * A `Class-Path` token that is not a valid URL makes the JVM skip the whole jar, while the walker
 * skips only that token ([classPathEntries]), so the tree holds no such token.
 *
 * It creates symbolic links, which Windows does not allow without a privilege.
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(5, unit = TimeUnit.MINUTES)
class ClasspathWalkerJvmTest {
    @TempDir
    lateinit var tempDir: Path

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val ABSENT = ClassOriginProbe.ABSENT

        /** The classes the tree holds where the JVM never looks, one for each rule that hides one. */
        val NEVER_LOADED =
            setOf(
                "fixture.LinkedRootDecoy",
                "fixture.HopNextStored",
                "fixture.SlashDirectoryWithoutSlash",
                "fixture.SlashJarWithSlash",
                "fixture.ManifestNamed",
                "fixture.UpThroughLink",
                "fixture.CanonSiblingBesideTarget",
            )
    }

    /** What was built: the `java.class.path` entries and the class each file holds. */
    private class Tree(
        val base: Path,
    ) {
        val entries = mutableListOf<String>()
        val classes = mutableListOf<String>()
        private val classOfFile = mutableMapOf<Path, String>()

        fun jar(
            relative: String,
            className: String,
            classPath: String? = null,
        ): Path {
            val path =
                TestJars.write(
                    base.resolve(relative),
                    listOf(TestJars.loadableClassEntry(className)),
                    classPath?.let { mapOf("Class-Path" to it) },
                )
            return holds(path, className)
        }

        /** A folder at [relative] holding the class file of [className]. */
        fun folder(
            relative: String,
            className: String,
        ): Path {
            val (name, bytes) = TestJars.loadableClassEntry(className)
            val file = base.resolve(relative).resolve(name)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
            return holds(base.resolve(relative), className)
        }

        fun link(
            relative: String,
            target: Path,
        ): Path {
            val link = base.resolve(relative)
            Files.createDirectories(link.parent)
            return Files.createSymbolicLink(link, target)
        }

        fun classpath(vararg paths: Path) {
            entries += paths.map { it.toString() }
        }

        /** The class held by the file at [path], reached by whatever path. */
        fun classAt(path: Path): String? = runCatching { classOfFile[path.toRealPath()] }.getOrNull()

        private fun holds(
            path: Path,
            className: String,
        ): Path {
            classes += className
            classOfFile[path.toRealPath()] = className
            return path
        }
    }

    /** Records the jars and folders the walker reports, in order. It fails on anything else. */
    private class Recorder : ClasspathVisitor {
        val reported = mutableListOf<File>()

        override fun jar(
            jar: File,
            jarFile: JarFile,
        ) {
            reported += jar
        }

        override fun directory(directory: File) {
            reported += directory
        }

        override fun explodedApplication(
            root: File,
            manifest: Manifest,
        ) = fail("The tree holds no exploded application, but the walker reported $root")

        override fun libraryJar(
            application: File,
            jar: File,
            jarFile: JarFile,
        ) = fail("The tree holds no exploded application, but the walker reported the library $jar")

        override fun unreadable(
            location: String,
            cause: Exception,
        ) = fail("The walker could not read $location: $cause")
    }

    private fun buildTree(): Tree {
        val tree = Tree(tempDir.toRealPath())

        // A linked root. The JVM reads main.jar at its real path, so its Class-Path reaches
        // real/sibling.jar, not the sibling.jar beside the link.
        val realMain = tree.jar("real/main.jar", "fixture.LinkedRootMain", classPath = "sibling.jar")
        tree.jar("real/sibling.jar", "fixture.LinkedRootSibling")
        tree.jar("links/sibling.jar", "fixture.LinkedRootDecoy")
        tree.classpath(tree.link("links/main.jar", realMain))

        // A link reached through a Class-Path hop. The JVM reads hop.jar at the link's path, so
        // next.jar is the one beside the link.
        val storedHop = tree.jar("store/hop.jar", "fixture.HopTarget", classPath = "next.jar")
        tree.jar("store/next.jar", "fixture.HopNextStored")
        tree.jar("hops/next.jar", "fixture.HopNextBesideLink")
        tree.link("hops/hop.jar", storedHop)
        tree.classpath(tree.jar("hopper/hopper.jar", "fixture.HopEntry", classPath = "../hops/hop.jar"))

        // One jar at two paths. The Class-Path reaches it at the link's path first, and the
        // classpath lists it at its real path later. Each path has its own rel.jar.
        val shared = tree.jar("shared/shared.jar", "fixture.SharedClass", classPath = "rel.jar")
        tree.jar("shared/rel.jar", "fixture.SharedRelAtRealPath")
        tree.jar("sharedlinks/rel.jar", "fixture.SharedRelAtLinkPath")
        tree.link("sharedlinks/shared.jar", shared)
        tree.classpath(
            tree.jar("sharedfirst/first.jar", "fixture.SharedFirst", classPath = "../sharedlinks/shared.jar"),
            shared,
        )

        // A Class-Path token ends in a slash only for a directory. A directory without one is
        // read as a jar and a jar file with one is read as a directory, so both are skipped.
        tree.classpath(
            tree.jar("slash/holder.jar", "fixture.SlashHolder", classPath = "classes lib.jar/ folder/ plain.jar"),
        )
        tree.folder("slash/classes", "fixture.SlashDirectoryWithoutSlash")
        tree.jar("slash/lib.jar", "fixture.SlashJarWithSlash")
        tree.folder("slash/folder", "fixture.SlashFolder")
        tree.jar("slash/plain.jar", "fixture.SlashPlain")

        // A missing classpath entry, a dangling link, and a Class-Path target that is missing.
        tree.classpath(tree.base.resolve("missing/entry.jar"))
        tree.classpath(tree.link("missing/dangling.jar", tree.base.resolve("missing/nowhere.jar")))
        tree.classpath(tree.jar("gone/holder.jar", "fixture.GoneHolder", classPath = "absent.jar after.jar"))
        tree.jar("gone/after.jar", "fixture.GoneAfter")

        // A Class-Path cycle.
        tree.classpath(tree.jar("cycle/a.jar", "fixture.CycleA", classPath = "b.jar"))
        tree.jar("cycle/b.jar", "fixture.CycleB", classPath = "a.jar")

        // A classpath folder, reached through a link.
        tree.classpath(tree.link("links/folder", tree.folder("folders/classes", "fixture.PlainFolder")))

        // A classpath entry with `..` after a folder that does not exist: the JVM collapses it.
        tree.jar("dotdot/x.jar", "fixture.DotDot")
        tree.classpath(Path.of("${tree.base}/nonexistent/../dotdot/x.jar"))

        // There the JVM follows no link in the collapsed rest, so a link stays a link: l.jar is read
        // at the link's path, and its Class-Path reaches the sib.jar beside the link.
        val canonTarget = tree.jar("canonstore/t.jar", "fixture.CanonLinked", classPath = "sib.jar")
        tree.jar("canonstore/sib.jar", "fixture.CanonSiblingBesideTarget")
        tree.jar("canonlinks/sib.jar", "fixture.CanonSiblingBesideLink")
        tree.link("canonlinks/l.jar", canonTarget)
        tree.classpath(Path.of("${tree.base}/nonexistent/../canonlinks/l.jar"))

        // A classpath folder's manifest Class-Path is never read.
        val manifestDir = tree.folder("manifestdir", "fixture.ManifestDir")
        Files.createDirectories(manifestDir.resolve("META-INF"))
        Files.write(manifestDir.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nClass-Path: ../named/named.jar\n\n".toByteArray())
        tree.jar("named/named.jar", "fixture.ManifestNamed")
        tree.classpath(manifestDir)

        // A classpath file is a jar whatever its extension.
        tree.classpath(tree.jar("ext/library.zipx", "fixture.OddExtension"))

        // A linked folder a Class-Path names with a slash is read at the link's path.
        tree.link("linkdirs/linkdir", tree.folder("linkdirs-store/classes", "fixture.LinkedFolder"))
        tree.classpath(tree.jar("linkdirs/holder.jar", "fixture.LinkedFolderHolder", classPath = "linkdir/"))

        // A `..` in a Class-Path token resolves by text, not through a link: holder.jar is reached at
        // the link's path, so `../up.jar` is the one beside the link, not the one beside the target.
        val deepHolder = tree.jar("deep/realfolder/holder.jar", "fixture.UpHolder", classPath = "../up.jar")
        tree.jar("deep/up.jar", "fixture.UpThroughLink")
        tree.jar("up.jar", "fixture.UpTextual")
        tree.link("linkedfolder", deepHolder.parent)
        tree.classpath(tree.jar("upstart/start.jar", "fixture.UpStart", classPath = "../linkedfolder/holder.jar"))

        // A percent-encoded Class-Path token names the decoded file.
        tree.jar("pct/with space.jar", "fixture.Percent")
        tree.classpath(tree.jar("pct/holder.jar", "fixture.PercentHolder", classPath = "with%20space.jar"))
        return tree
    }

    @Test
    fun `the walker reads each class from the file the JVM's system loader reads it from`() {
        val tree = buildTree()
        val jvm = runProbe(tree)
        val walker = firstWalkerPaths(tree)
        val table =
            tree.classes.map { Triple(it, jvm.getValue(it), walker[it] ?: ABSENT) }
        val rendered =
            table.joinToString("\n", prefix = "class / JVM / walker\n") { (name, fromJvm, fromWalker) ->
                "$name\t$fromJvm\t$fromWalker"
            }
        // Each scenario still tells the rules apart only while the JVM skips exactly these.
        assertEquals(NEVER_LOADED, jvm.filterValues { it == ABSENT }.keys, "The classes the JVM cannot find.\n$rendered")
        val reportedAbsent = table.filter { (_, fromJvm, fromWalker) -> fromJvm == ABSENT && fromWalker != ABSENT }
        assertEquals(emptyList(), reportedAbsent, "The walker reported a class the JVM cannot find.\n$rendered")
        val mismatches = table.filter { (_, fromJvm, fromWalker) -> fromJvm != fromWalker }
        assertTrue(mismatches.isEmpty(), "The walker disagrees with the JVM on ${mismatches.map { it.first }}.\n$rendered")
    }

    /** For each class, the first path the walker reports whose file holds it. */
    private fun firstWalkerPaths(tree: Tree): Map<String, String> {
        val recorder = Recorder()
        ClasspathWalker(JarClassifier(emptyList(), emptyList())).walk(tree.entries, recorder)
        // A loader opens each URL once, and equal URLs give equal paths.
        val repeated =
            recorder.reported
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        assertEquals(emptySet(), repeated, "The walker reported a path twice")
        val first = linkedMapOf<String, String>()
        for (file in recorder.reported) {
            val className = tree.classAt(file.toPath()) ?: fail("The walker reported $file, which holds no class of the tree")
            first.putIfAbsent(className, file.path)
        }
        return first
    }

    /** The path the JVM reads each class of [tree] from, or [ABSENT]. */
    private fun runProbe(tree: Tree): Map<String, String> {
        val probeClasses =
            Path
                .of(
                    ClassOriginProbe::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toString()
        // The probe's folder goes last so it changes nothing ahead of it.
        val classpath = (tree.entries + probeClasses).joinToString(File.pathSeparator)
        val command =
            listOf(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                // The output is read as UTF-8 whatever the locale, since a temp path can hold any character.
                "-Dstdout.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-cp",
                classpath,
                ClassOriginProbe::class.java.name,
            ) + tree.classes
        val stdout = tempDir.resolve("probe.out").toFile()
        val stderr = tempDir.resolve("probe.err").toFile()
        val builder = ProcessBuilder(command).redirectOutput(stdout).redirectError(stderr)
        // Options from the developer's shell could add to the classpath or load an agent.
        builder.environment().keys.removeAll(setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
        val process = builder.start()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("The probe did not exit within $PROCESS_TIMEOUT_SECONDS seconds. Output:\n${stdout.readText()}\n${stderr.readText()}")
        }
        assertEquals(0, process.exitValue(), "The probe's exit code. Output:\n${stdout.readText()}\n${stderr.readText()}")
        val origins =
            stdout
                .readLines()
                .filter { '\t' in it }
                .associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals(tree.classes.toSet(), origins.keys, "The probe answered for every class. Output:\n${stdout.readText()}")
        return origins
    }
}
