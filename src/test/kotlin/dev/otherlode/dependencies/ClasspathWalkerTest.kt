package dev.otherlode.dependencies

import dev.otherlode.dependencies.TestJars.classEntry
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ClasspathWalkerTest {
    @TempDir
    lateinit var tempDir: Path

    /** The real path of the temp folder, so expected paths match the canonical paths the walker hands out. */
    private val dir: Path get() = tempDir.toRealPath()

    private class Recorder(
        private val failIn: Set<String> = emptySet(),
        private val onUnreadable: (String, Exception) -> Unit = { _, _ -> },
    ) : ClasspathVisitor {
        val events = mutableListOf<String>()

        private fun record(
            kind: String,
            file: File,
        ) {
            events += "$kind:${file.path}"
            if (file.path in failIn) throw IllegalStateException("visitor failed on ${file.path}")
        }

        override fun jar(
            jar: File,
            jarFile: JarFile,
        ) = record("jar", jar)

        override fun directory(directory: File) = record("dir", directory)

        override fun explodedApplication(
            root: File,
            manifest: Manifest,
        ) = record("app", root)

        override fun libraryJar(
            application: File,
            jar: File,
            jarFile: JarFile,
        ) = record("lib", jar)

        override fun unreadable(
            location: String,
            cause: Exception,
        ) {
            events += "unreadable:$location"
            onUnreadable(location, cause)
        }
    }

    private fun walk(
        vararg entries: Path,
        visitor: Recorder = Recorder(),
    ): List<String> {
        ClasspathWalker(JarClassifier(emptyList(), emptyList())).walk(entries.map { it.toString() }, visitor)
        return visitor.events.map { it.replace("$dir/", "") }
    }

    private fun jar(
        relative: String,
        classPath: String? = null,
    ): Path = TestJars.write(dir.resolve(relative), listOf(classEntry("a.A")), classPath?.let { mapOf("Class-Path" to it) })

    private fun explodedApplication(
        relative: String,
        libFolder: String = "BOOT-INF/lib/",
    ): Path {
        val app = Files.createDirectories(dir.resolve(relative))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: $libFolder\n\n".toByteArray())
        return app
    }

    @Test
    fun `a linked classpath jar is read at its real path and its Class-Path resolves against the target's folder`() {
        jar("real/sibling.jar")
        val target = jar("real/main.jar", "sibling.jar")
        jar("links/sibling.jar")
        val link = Files.createSymbolicLink(dir.resolve("links/main.jar"), target)

        assertEquals(listOf("jar:real/main.jar", "jar:real/sibling.jar"), walk(link))
    }

    @Test
    fun `a link reached through a Class-Path is read as given and its Class-Path resolves against the link's folder`() {
        val main = jar("app/main.jar", "../links/hop.jar")
        jar("real/next.jar")
        val hop = jar("real/hop.jar", "next.jar")
        jar("links/next.jar")
        Files.createSymbolicLink(dir.resolve("links/hop.jar"), hop)

        assertEquals(listOf("jar:app/main.jar", "jar:links/hop.jar", "jar:links/next.jar"), walk(main))
    }

    @Test
    fun `a jar reached at two paths is read at each, and each Class-Path resolves against its own path, as a loader opens each URL`() {
        val main = jar("app/main.jar", "../links/hop.jar")
        val hop = jar("real/hop.jar", "next.jar")
        jar("real/next.jar")
        jar("links/next.jar")
        Files.createSymbolicLink(dir.resolve("links/hop.jar"), hop)

        assertEquals(
            listOf("jar:app/main.jar", "jar:links/hop.jar", "jar:links/next.jar", "jar:real/hop.jar", "jar:real/next.jar"),
            walk(main, hop),
        )
    }

    @Test
    fun `a Class-Path cycle reads each jar once`() {
        val a = jar("a.jar", "b.jar")
        jar("b.jar", "a.jar")

        assertEquals(listOf("jar:a.jar", "jar:b.jar"), walk(a))
    }

    @Test
    fun `a Class-Path URL ending in a slash is read only as a directory, and any other only as a jar`() {
        Files.createDirectories(dir.resolve("classes"))
        Files.createDirectories(dir.resolve("folder"))
        jar("lib.jar")
        jar("real.jar")
        val main = jar("main.jar", "classes lib.jar/ folder/ real.jar")

        assertEquals(listOf("jar:main.jar", "dir:folder", "jar:real.jar"), walk(main))
    }

    @Test
    fun `the jars a Class-Path names are read straight after it, depth first, before the next classpath entry`() {
        val a = jar("a.jar", "b.jar c.jar")
        jar("b.jar", "d.jar")
        jar("c.jar")
        jar("d.jar")
        val e = jar("e.jar")

        assertEquals(listOf("jar:a.jar", "jar:b.jar", "jar:d.jar", "jar:c.jar", "jar:e.jar"), walk(a, e))
    }

    @Test
    fun `a jar named by two classpath entries is read once and its Class-Path followed once`() {
        val main = jar("main.jar", "dep.jar")
        jar("dep.jar")

        assertEquals(listOf("jar:main.jar", "jar:dep.jar"), walk(main, main))
    }

    @Test
    fun `a jar on the classpath and also named by a Class-Path is read once, where the Class-Path reaches it`() {
        val main = jar("main.jar", "other.jar")
        val other = jar("other.jar", "third.jar")
        jar("third.jar")

        assertEquals(listOf("jar:main.jar", "jar:other.jar", "jar:third.jar"), walk(main, other))
    }

    @Test
    fun `a jar and a link to it are one file`() {
        val target = jar("real/main.jar", "dep.jar")
        jar("real/dep.jar")
        val link = Files.createSymbolicLink(dir.resolve("main-link.jar"), target)

        assertEquals(listOf("jar:real/main.jar", "jar:real/dep.jar"), walk(link, target))
    }

    @Test
    fun `a classpath entry with dot-dot after a missing folder is read at its canonical path, as the JVM collapses it`() {
        jar("dotdot/x.jar")

        assertEquals(listOf("jar:dotdot/x.jar"), walk(dir.resolve("nonexistent/../dotdot/x.jar")))
    }

    @Test
    fun `a missing classpath entry and a missing Class-Path target are skipped without a report`() {
        val main = jar("main.jar", "gone.jar present.jar")
        jar("present.jar")

        assertEquals(listOf("jar:main.jar", "jar:present.jar"), walk(dir.resolve("missing.jar"), main))
    }

    @Test
    fun `a plain directory is reported as a directory and its manifest Class-Path is not followed`() {
        val classes = Files.createDirectories(dir.resolve("classes/META-INF"))
        Files.write(classes.resolve("MANIFEST.MF"), "Manifest-Version: 1.0\nClass-Path: ../extra.jar\n\n".toByteArray())
        jar("extra.jar")

        assertEquals(listOf("dir:classes"), walk(dir.resolve("classes")))
    }

    @Test
    fun `an exploded application comes before its library jars, and a library jar's Class-Path comes straight after it`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/z.jar")
        jar("app/BOOT-INF/lib/c.jar", dir.resolve("extra.jar").toUri().toString())
        jar("app/BOOT-INF/lib/nested/deep/b.dat")
        Files.createDirectories(app.resolve("BOOT-INF/classes"))
        Files.write(app.resolve("BOOT-INF/classes/App.class"), ByteArray(4))
        jar("extra.jar")

        assertEquals(
            listOf(
                "app:app",
                "lib:app/BOOT-INF/lib/c.jar",
                "lib:extra.jar",
                "lib:app/BOOT-INF/lib/nested/deep/b.dat",
                "lib:app/BOOT-INF/lib/z.jar",
            ),
            walk(app),
        )
    }

    @Test
    fun `library jars come in Boot's order, each folder's children sorted by name and walked depth first`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        jar("app/BOOT-INF/lib/a-x.jar")
        jar("app/BOOT-INF/lib/a/b.jar")
        jar("app/BOOT-INF/lib/B.jar")

        assertEquals(
            listOf(
                "app:app",
                "lib:app/BOOT-INF/lib/B.jar",
                "lib:app/BOOT-INF/lib/a/b.jar",
                "lib:app/BOOT-INF/lib/a-x.jar",
                "lib:app/BOOT-INF/lib/a.jar",
            ),
            walk(app),
        )
    }

    @Test
    fun `an exploded executable war gives its lib jars, then its lib-provided jars`() {
        val app = explodedApplication("war", "WEB-INF/lib/")
        jar("war/WEB-INF/lib/l.jar")
        jar("war/WEB-INF/lib-provided/p.jar")
        jar("war/WEB-INF/classes/notajar.jar")

        assertEquals(listOf("app:war", "lib:war/WEB-INF/lib/l.jar", "lib:war/WEB-INF/lib-provided/p.jar"), walk(app))
    }

    @Test
    fun `jars the classpath index names come after the others, in the index's order, and a named file that is missing is skipped`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        jar("app/BOOT-INF/lib/m.jar")
        jar("app/BOOT-INF/lib/z.jar")
        Files.write(
            app.resolve("BOOT-INF/classpath.idx"),
            "- \"BOOT-INF/lib/z.jar\"\n\n- \"BOOT-INF/lib/ghost.jar\"\n- \"BOOT-INF/lib/a.jar\"\n".toByteArray(),
        )

        assertEquals(
            listOf("app:app", "lib:app/BOOT-INF/lib/m.jar", "lib:app/BOOT-INF/lib/z.jar", "lib:app/BOOT-INF/lib/a.jar"),
            walk(app),
        )
    }

    @Test
    fun `an exploded executable war reads its classpath index from WEB-INF`() {
        val app = explodedApplication("war", "WEB-INF/lib/")
        jar("war/WEB-INF/lib/a.jar")
        jar("war/WEB-INF/lib/b.jar")
        Files.write(app.resolve("WEB-INF/classpath.idx"), "- \"WEB-INF/lib/a.jar\"\n".toByteArray())

        assertEquals(listOf("app:war", "lib:war/WEB-INF/lib/b.jar", "lib:war/WEB-INF/lib/a.jar"), walk(app))
    }

    @Test
    fun `a classpath index line naming a folder is read as a folder of classes, and a line of spaces is ignored`() {
        val app = explodedApplication("app")
        Files.createDirectories(app.resolve("BOOT-INF/extra"))
        Files.write(app.resolve("BOOT-INF/classpath.idx"), "  \n- \"BOOT-INF/extra\"\n".toByteArray())

        assertEquals(listOf("app:app", "dir:app/BOOT-INF/extra"), walk(app))
    }

    @Test
    fun `a classpath index line with a leading space is malformed`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        Files.write(app.resolve("BOOT-INF/classpath.idx"), " - \"BOOT-INF/lib/a.jar\"\n".toByteArray())

        assertEquals(listOf("unreadable:app"), walk(app))
    }

    @Test
    fun `a linked library jar is read at the path found, and its Class-Path resolves against that path`() {
        val app = explodedApplication("app")
        val target = jar("store/core.jar", "sibling.jar")
        jar("store/sibling.jar")
        jar("app/BOOT-INF/lib/sibling.jar")
        Files.createSymbolicLink(app.resolve("BOOT-INF/lib/core.jar"), target)

        assertEquals(
            // Boot's loader has opened sibling.jar's URL through the Class-Path, so the library entry adds nothing.
            listOf("app:app", "lib:app/BOOT-INF/lib/core.jar", "lib:app/BOOT-INF/lib/sibling.jar"),
            walk(app),
        )
    }

    @Test
    fun `a folder a Class-Path names is read as an exploded application when its manifest marks one`() {
        val first = jar("first.jar", "app/")
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/l.jar")

        assertEquals(listOf("jar:first.jar", "app:app", "lib:app/BOOT-INF/lib/l.jar"), walk(first, app))
    }

    @Test
    fun `a file the classpath index names outside the library folders is read`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        jar("app/extra/x.jar")
        Files.write(app.resolve("BOOT-INF/classpath.idx"), "- \"extra/x.jar\"\n".toByteArray())

        assertEquals(listOf("app:app", "lib:app/BOOT-INF/lib/a.jar", "lib:app/extra/x.jar"), walk(app))
    }

    @Test
    fun `a link in the library folders to a sibling folder is followed`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a/x.jar")
        Files.createSymbolicLink(app.resolve("BOOT-INF/lib/b"), app.resolve("BOOT-INF/lib/a"))

        assertEquals(listOf("app:app", "lib:app/BOOT-INF/lib/a/x.jar", "lib:app/BOOT-INF/lib/b/x.jar"), walk(app))
    }

    @Test
    fun `an exploded application whose callback throws still has its library jars read`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")

        assertEquals(
            listOf("app:app", "unreadable:app", "lib:app/BOOT-INF/lib/a.jar"),
            walk(app, visitor = Recorder(failIn = setOf(app.toString()))),
        )
    }

    @Test
    fun `a library folder the agent cannot list makes the application unreadable, not empty`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        val next = jar("next.jar")
        val visitor = Recorder()
        val failing: (File) -> Array<File>? = { folder -> if (folder.name == "lib") null else folder.listFiles() }

        ClasspathWalker(JarClassifier(emptyList(), emptyList()), failing).walk(listOf(app.toString(), next.toString()), visitor)

        assertEquals(listOf("unreadable:app", "jar:next.jar"), visitor.events.map { it.replace("$dir/", "") })
    }

    @Test
    fun `the classpath index is read from the file the manifest names`() {
        val app = Files.createDirectories(dir.resolve("app"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(
            app.resolve("META-INF/MANIFEST.MF"),
            "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\nSpring-Boot-Classpath-Index: META-INF/order.idx\n\n".toByteArray(),
        )
        jar("app/BOOT-INF/lib/a.jar")
        jar("app/BOOT-INF/lib/b.jar")
        Files.write(app.resolve("META-INF/order.idx"), "- \"BOOT-INF/lib/a.jar\"\n".toByteArray())
        Files.write(app.resolve("BOOT-INF/classpath.idx"), "- \"BOOT-INF/lib/b.jar\"\n".toByteArray())

        assertEquals(listOf("app:app", "lib:app/BOOT-INF/lib/b.jar", "lib:app/BOOT-INF/lib/a.jar"), walk(app))
    }

    @Test
    fun `a malformed classpath index makes the application unreadable, since Boot's launch fails on it`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        Files.write(app.resolve("BOOT-INF/classpath.idx"), "BOOT-INF/lib/a.jar\n".toByteArray())
        val next = jar("next.jar")

        assertEquals(listOf("unreadable:app", "jar:next.jar"), walk(app, next))
    }

    @Test
    fun `a link in the library folders back to its own parent is not followed again`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        Files.createSymbolicLink(app.resolve("BOOT-INF/lib/loop"), app.resolve("BOOT-INF/lib"))

        assertEquals(listOf("app:app", "lib:app/BOOT-INF/lib/a.jar"), walk(app))
    }

    @Test
    fun `a library jar that is also on the classpath is read by each loader, the system loader's first`() {
        val app = explodedApplication("app")
        val lib = jar("app/BOOT-INF/lib/core.jar")

        assertEquals(listOf("app:app", "jar:app/BOOT-INF/lib/core.jar", "lib:app/BOOT-INF/lib/core.jar"), walk(app, lib))
    }

    @Test
    fun `an exploded application's library jars come after the whole system classpath, since Boot's loader asks it first`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/core.jar", "../../../extra/cp.jar")
        jar("extra/cp.jar")
        val after = jar("after.jar", "named.jar")
        jar("named.jar")

        assertEquals(
            listOf("app:app", "jar:after.jar", "jar:named.jar", "lib:app/BOOT-INF/lib/core.jar", "lib:extra/cp.jar"),
            walk(app, after),
        )
    }

    @Test
    fun `a root that is not a zip is reported, whatever follows is still read, and its report ends the walk when it throws`() {
        val first = jar("first.jar")
        val garbage = Files.write(dir.resolve("garbage.bin"), "not a zip".toByteArray())
        val last = jar("last.jar")

        assertEquals(listOf("jar:first.jar", "unreadable:garbage.bin", "jar:last.jar"), walk(first, garbage, last))

        val ended = IllegalArgumentException("stop")
        val visitor = Recorder(onUnreadable = { _, _ -> throw ended })
        val thrown = assertFailsWith<IllegalArgumentException> { walk(first, garbage, last, visitor = visitor) }
        assertSame(ended, thrown)
        assertEquals(listOf("jar:first.jar", "unreadable:garbage.bin"), visitor.events.map { it.replace("$dir/", "") })
    }

    @Test
    fun `a library jar that is not a zip is reported by its path and the other library jars are still read`() {
        val app = explodedApplication("app")
        jar("app/BOOT-INF/lib/a.jar")
        Files.write(app.resolve("BOOT-INF/lib/b.jar"), "not a zip".toByteArray())
        jar("app/BOOT-INF/lib/c.jar")

        assertEquals(
            listOf("app:app", "lib:app/BOOT-INF/lib/a.jar", "unreadable:app/BOOT-INF/lib/b.jar", "lib:app/BOOT-INF/lib/c.jar"),
            walk(app),
        )
    }

    @Test
    fun `an exception a visitor callback throws is reported as the root's unreadable and its Class-Path is still followed`() {
        val bad = jar("bad.jar", "dep.jar")
        jar("dep.jar")
        val next = jar("next.jar")
        var cause: Exception? = null
        val reporting = Recorder(failIn = setOf(bad.toString()), onUnreadable = { _, e -> cause = e })

        assertEquals(listOf("jar:bad.jar", "unreadable:bad.jar", "jar:dep.jar", "jar:next.jar"), walk(bad, next, visitor = reporting))
        assertEquals("visitor failed on $bad", cause?.message)
    }

    @Test
    fun `an exception thrown while reading a library jar is reported by that jar's path`() {
        val app = explodedApplication("app")
        val first = jar("app/BOOT-INF/lib/a.jar")
        jar("app/BOOT-INF/lib/b.jar")

        assertEquals(
            listOf("app:app", "lib:app/BOOT-INF/lib/a.jar", "unreadable:app/BOOT-INF/lib/a.jar", "lib:app/BOOT-INF/lib/b.jar"),
            walk(app, visitor = Recorder(failIn = setOf(first.toString()))),
        )
    }
}
