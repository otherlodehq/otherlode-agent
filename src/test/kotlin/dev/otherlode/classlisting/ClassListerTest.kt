package dev.otherlode.classlisting

import dev.otherlode.dependencies.TestJars
import dev.otherlode.dependencies.TestJars.classEntry
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

class ClassListerTest {
    @TempDir
    lateinit var dir: Path

    private fun classPath(vararg paths: Path): String = paths.joinToString(File.pathSeparator) { it.toString() }

    private fun lister(
        classPath: String,
        includes: List<String> = listOf("com.acme"),
        excludes: List<String> = emptyList(),
    ) = ClassLister(includes, excludes, classPath)

    private fun nestedJar(
        vararg entries: Pair<String, ByteArray>,
        manifest: Map<String, String>? = null,
    ): ByteArray = TestJars.bytes(entries.toList(), manifest)

    private fun bootJar(
        name: String,
        entries: List<Pair<String, ByteArray>>,
        manifest: Map<String, String> = mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
    ): Path = TestJars.write(dir.resolve(name), entries, manifest)

    /** The WARNING records [ClassLister] logs while [block] runs. */
    private fun warningsFrom(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(ClassLister::class.java.name)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records.filter { it.level == JulLevel.WARNING }
    }

    @Test
    fun `a class directory is walked, naming each class by its path and keeping nested class names`() {
        val classes = Files.createDirectories(dir.resolve("classes/com/acme/sub")).parent
        Files.write(classes.resolve("App.class"), ByteArray(4))
        Files.write(classes.resolve("App\$Inner.class"), ByteArray(4))
        Files.write(classes.resolve("sub/Deep.class"), ByteArray(4))
        Files.write(classes.resolve("notes.txt"), ByteArray(4))
        Files.write(classes.resolve("package-info.class"), ByteArray(4))
        Files.createDirectories(dir.resolve("classes/META-INF"))
        Files.write(dir.resolve("classes/META-INF/Hidden.class"), ByteArray(4))
        Files.write(dir.resolve("classes/module-info.class"), ByteArray(4))

        val listed = lister(classPath(dir.resolve("classes"))).list()

        assertEquals(listOf("com.acme.App", "com.acme.App\$Inner", "com.acme.sub.Deep"), listed)
    }

    @Test
    fun `a flat jar lists its classes, whatever its extension`() {
        val jar = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A"), classEntry("org.other.B")))
        val renamed = TestJars.write(dir.resolve("lib.zip"), listOf(classEntry("com.acme.Z")))

        assertEquals(listOf("com.acme.A", "com.acme.Z"), lister(classPath(jar, renamed)).list())
    }

    @Test
    fun `the include and exclude rules filter by package prefix and class prefix`() {
        val jar =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    classEntry("com.acme.A"),
                    classEntry("com.acme.internal.B"),
                    classEntry("com.acme.Keep\$Inner"),
                    classEntry("com.acmeextra.C"),
                    classEntry("org.other.D"),
                    classEntry("dev.otherlode.Agent"),
                ),
            )

        val listed =
            lister(classPath(jar), includes = listOf("com.acme", "dev.otherlode"), excludes = listOf("com.acme.internal")).list()

        assertEquals(listOf("com.acme.A", "com.acme.Keep\$Inner"), listed, "the agent's own package is never listed")
    }

    @Test
    fun `an empty include list lists nothing`() {
        val jar = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A")))

        assertEquals(emptyList(), lister(classPath(jar), includes = emptyList()).list())
    }

    @Test
    fun `a class a multi-release jar holds only as a versioned entry is listed by its own name`() {
        val jar =
            TestJars.write(
                dir.resolve("multi.jar"),
                listOf(
                    classEntry("com.acme.A"),
                    "META-INF/versions/11/com/acme/A.class" to ByteArray(4),
                    "META-INF/versions/17/com/acme/OnlyNew.class" to ByteArray(4),
                ),
            )

        assertEquals(listOf("com.acme.A", "com.acme.OnlyNew"), lister(classPath(jar)).list())
    }

    @Test
    fun `a versioned-only class in a nested jar is listed`() {
        val nested = nestedJar("META-INF/versions/21/com/acme/lib/New.class" to ByteArray(4))
        val boot = bootJar("app.jar", listOf("BOOT-INF/lib/lib.jar" to nested))

        assertEquals(listOf("com.acme.lib.New"), lister(classPath(boot)).list())
    }

    @Test
    fun `a Spring Boot fat jar lists its own classes and the classes of its nested jars`() {
        val fat =
            bootJar(
                "app.jar",
                listOf(
                    "BOOT-INF/classes/com/acme/App.class" to ByteArray(4),
                    "BOOT-INF/classes/application.properties" to ByteArray(4),
                    "BOOT-INF/classes/META-INF/versions/17/com/acme/App.class" to ByteArray(4),
                    "BOOT-INF/lib/acme-core-1.0.jar" to nestedJar(classEntry("com.acme.core.Core"), classEntry("org.lib.Skipped")),
                    "BOOT-INF/lib/other-1.0.jar" to nestedJar(classEntry("org.lib.Other")),
                    "org/springframework/boot/loader/launch/JarLauncher.class" to ByteArray(4),
                ),
            )

        val listed = lister(classPath(fat), includes = listOf("com.acme", "org.springframework.boot.loader")).list()

        assertEquals(
            listOf("com.acme.App", "com.acme.core.Core", "org.springframework.boot.loader.launch.JarLauncher"),
            listed,
        )
    }

    @Test
    fun `an executable war lists WEB-INF classes, lib and lib-provided`() {
        val war =
            bootJar(
                "app.war",
                listOf(
                    "WEB-INF/classes/com/acme/web/Controller.class" to ByteArray(4),
                    "WEB-INF/lib/acme-core-1.0.jar" to nestedJar(classEntry("com.acme.core.Core")),
                    "WEB-INF/lib-provided/acme-api-1.0.jar" to nestedJar(classEntry("com.acme.api.Api")),
                    "BOOT-INF/lib/ignored.jar" to nestedJar(classEntry("com.acme.ignored.Ignored")),
                ),
                manifest = mapOf("Spring-Boot-Lib" to "WEB-INF/lib/"),
            )

        assertEquals(listOf("com.acme.api.Api", "com.acme.core.Core", "com.acme.web.Controller"), lister(classPath(war)).list())
    }

    @Test
    fun `a jar whose manifest names an agent is read like any other, since the system loader loads it`() {
        val agent = TestJars.write(dir.resolve("agent.jar"), listOf(classEntry("com.acme.AgentClass")), mapOf("Premain-Class" to "x.Agent"))
        val launcher =
            TestJars.write(dir.resolve("launcher.jar"), listOf(classEntry("com.acme.Launched")), mapOf("Launcher-Agent-Class" to "y.Agent"))
        val own =
            TestJars.write(
                dir.resolve("otherlode.jar"),
                listOf(classEntry("dev.otherlode.Agent")),
                mapOf("Premain-Class" to "dev.otherlode.Agent"),
            )
        val app = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A")))

        assertEquals(
            listOf("com.acme.A", "com.acme.AgentClass", "com.acme.Launched"),
            lister(classPath(agent, launcher, own, app), includes = listOf("com.acme", "dev.otherlode")).list(),
        )
    }

    @Test
    fun `a Spring Boot jar that also names an agent is still read by Boot's layout`() {
        val nested = nestedJar(classEntry("com.acme.lib.Lib"))
        val boot =
            bootJar(
                "app.jar",
                listOf(classEntry("BOOT-INF/classes/com.acme.App"), "BOOT-INF/lib/lib.jar" to nested),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/", "Launcher-Agent-Class" to "y.Agent"),
            )

        assertEquals(listOf("com.acme.App", "com.acme.lib.Lib"), lister(classPath(boot)).list())
    }

    @Test
    fun `an exploded Spring Boot application is read by its launcher's layout`() {
        val app = Files.createDirectories(dir.resolve("workspace"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n\n".toByteArray())
        Files.createDirectories(app.resolve("BOOT-INF/classes/com/acme"))
        Files.write(app.resolve("BOOT-INF/classes/com/acme/App.class"), ByteArray(4))
        Files.createDirectories(app.resolve("BOOT-INF/lib"))
        TestJars.write(app.resolve("BOOT-INF/lib/core.jar"), listOf(classEntry("com.acme.core.Core")))
        Files.createDirectories(app.resolve("org/springframework/boot/loader"))
        Files.write(app.resolve("org/springframework/boot/loader/JarLauncher.class"), ByteArray(4))

        assertEquals(listOf("com.acme.App", "com.acme.core.Core"), lister(classPath(app)).list())
    }

    @Test
    fun `an unreadable jar in an exploded Spring Boot application gives no listing`() {
        val app = Files.createDirectories(dir.resolve("workspace"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n\n".toByteArray())
        Files.createDirectories(app.resolve("BOOT-INF/lib"))
        Files.write(app.resolve("BOOT-INF/lib/broken.jar"), "not a zip".toByteArray())

        val warnings = warningsFrom { assertNull(lister(classPath(app)).list()) }

        assertEquals(1, warnings.size)
        assertTrue("broken.jar" in warnings.single().message, warnings.single().message)
    }

    @Test
    fun `a Class-Path entry resolves against the real path of a symbolic-link jar, as the JVM resolves it`() {
        val real = Files.createDirectories(dir.resolve("real"))
        TestJars.write(real.resolve("lib/dep.jar"), listOf(classEntry("com.acme.dep.Dep")))
        val jar = TestJars.write(real.resolve("main.jar"), listOf(classEntry("com.acme.Main")), mapOf("Class-Path" to "lib/dep.jar"))
        val other = Files.createDirectories(dir.resolve("other"))
        val link = Files.createSymbolicLink(other.resolve("main.jar"), jar)

        assertEquals(listOf("com.acme.Main", "com.acme.dep.Dep"), lister(classPath(link)).list())
    }

    @Test
    fun `a symbolic-link jar reached through a Class-Path resolves its own Class-Path against the link's folder`() {
        val main =
            TestJars.write(
                dir.resolve("app/main.jar"),
                listOf(classEntry("com.acme.Main")),
                mapOf("Class-Path" to "../links/hop.jar"),
            )
        TestJars.write(dir.resolve("real/next.jar"), listOf(classEntry("com.acme.target.Next")))
        val hop = TestJars.write(dir.resolve("real/hop.jar"), listOf(classEntry("com.acme.Hop")), mapOf("Class-Path" to "next.jar"))
        TestJars.write(dir.resolve("links/next.jar"), listOf(classEntry("com.acme.link.Next")))
        Files.createSymbolicLink(dir.resolve("links/hop.jar"), hop)

        assertEquals(listOf("com.acme.Hop", "com.acme.Main", "com.acme.link.Next"), lister(classPath(main)).list())
    }

    @Test
    fun `a Class-Path entry the JDK accepts is followed, brackets and tab separators included`() {
        TestJars.write(dir.resolve("lib/a[1].jar"), listOf(classEntry("com.acme.a.A")))
        TestJars.write(dir.resolve("lib/b.jar"), listOf(classEntry("com.acme.b.B")))
        val jar = TestJars.write(dir.resolve("main.jar"), emptyList(), mapOf("Class-Path" to "lib/a[1].jar\tlib/b.jar"))

        assertEquals(listOf("com.acme.a.A", "com.acme.b.B"), lister(classPath(jar)).list())
    }

    @Test
    fun `a Class-Path directory URL with a host is read, since the JDK reads its local path`() {
        val classes = Files.createDirectories(dir.resolve("dir/com/acme"))
        Files.write(classes.resolve("D.class"), ByteArray(4))
        TestJars.write(dir.resolve("j/k.jar"), listOf(classEntry("com.acme.K")))
        val main =
            TestJars.write(
                dir.resolve("main.jar"),
                emptyList(),
                mapOf(
                    "Class-Path" to
                        "file://remotehost${dir.resolve("dir").toUri().path} file://remotehost${dir.resolve("j/k.jar").toUri().path}",
                ),
            )

        assertEquals(listOf("com.acme.D"), lister(classPath(main)).list())
    }

    @Test
    fun `the Class-Path of an exploded Spring Boot application's library jar is followed`() {
        val app = Files.createDirectories(dir.resolve("workspace"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n\n".toByteArray())
        TestJars.write(dir.resolve("extra/extra.jar"), listOf(classEntry("com.acme.extra.Extra")))
        TestJars.write(
            app.resolve("BOOT-INF/lib/core.jar"),
            listOf(classEntry("com.acme.core.Core")),
            mapOf("Class-Path" to dir.resolve("extra/extra.jar").toUri().toString()),
        )

        assertEquals(listOf("com.acme.core.Core", "com.acme.extra.Extra"), lister(classPath(app)).list())
    }

    @Test
    fun `an empty classpath element gives no listing, since the JVM reads it as the working directory`() {
        val jar = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A")))

        val warnings = warningsFrom { assertNull(lister(jar.toString() + File.pathSeparator).list()) }

        assertEquals(1, warnings.size)
        assertTrue("empty element" in warnings.single().message, warnings.single().message)
    }

    @Test
    fun `a JVM with a module path gives no listing, since the listing does not read it`() {
        val jar = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A")))

        val warnings = warningsFrom { assertNull(ClassLister(listOf("com.acme"), emptyList(), classPath(jar), modulePath = "mods").list()) }

        assertEquals(1, warnings.size)
        assertTrue("module path" in warnings.single().message, warnings.single().message)
    }

    @Test
    fun `a Class-Path entry is followed, relative to the jar naming it, from an agent jar too`() {
        TestJars.write(dir.resolve("libs/dep-1.0.jar"), listOf(classEntry("com.acme.dep.Dep")))
        TestJars.write(dir.resolve("libs/with space.jar"), listOf(classEntry("com.acme.space.Spaced")))
        val agent =
            TestJars.write(
                dir.resolve("agent.jar"),
                emptyList(),
                mapOf(
                    "Premain-Class" to "x.Agent",
                    "Class-Path" to "libs/dep-1.0.jar libs/with%20space.jar libs/missing.jar http://example.com/x.jar",
                ),
            )

        assertEquals(listOf("com.acme.dep.Dep", "com.acme.space.Spaced"), lister(classPath(agent)).list())
    }

    @Test
    fun `a missing classpath entry is skipped, since the JVM ignores it`() {
        val jar = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A")))

        val warnings = warningsFrom { assertEquals(listOf("com.acme.A"), lister(classPath(dir.resolve("gone.jar"), jar)).list()) }

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `an empty classpath gives no listing, since the JVM reads it as the working directory`() {
        val warnings = warningsFrom { assertNull(lister("").list()) }

        assertEquals(1, warnings.size)
    }

    @Test
    fun `a JVM that runs a linked image's main module gives no listing`() {
        val jar = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.A")))

        assertNull(ClassLister(listOf("com.acme"), emptyList(), classPath(jar), mainModule = "com.acme.app").list())
    }

    @Test
    fun `two roots holding one name give it once, and the result is sorted`() {
        val first = TestJars.write(dir.resolve("a.jar"), listOf(classEntry("com.acme.Z"), classEntry("com.acme.Shared")))
        val second = TestJars.write(dir.resolve("b.jar"), listOf(classEntry("com.acme.Shared"), classEntry("com.acme.A")))
        val sameJarAgain = first

        assertEquals(listOf("com.acme.A", "com.acme.Shared", "com.acme.Z"), lister(classPath(first, second, sameJarAgain)).list())
    }

    @Test
    fun `a jar that cannot be read gives no listing and one WARNING naming it and the cause`() {
        val good = TestJars.write(dir.resolve("good.jar"), listOf(classEntry("com.acme.A")))
        val garbage = Files.write(dir.resolve("garbage.jar"), "not a zip file at all".toByteArray())
        var listed: List<String>? = listOf("unset")

        val warnings = warningsFrom { listed = lister(classPath(good, garbage)).list() }

        assertNull(listed)
        assertEquals(1, warnings.size, warnings.toString())
        val message = warnings.single().message
        assertTrue(garbage.toString() in message, message)
        assertNotNull(warnings.single().thrown, "the cause is attached")
    }

    @Test
    fun `a corrupt nested jar gives no listing and a WARNING naming it`() {
        val fat =
            bootJar(
                "app.jar",
                listOf(
                    "BOOT-INF/classes/com/acme/App.class" to ByteArray(4),
                    "BOOT-INF/lib/broken-1.0.jar" to "garbage".toByteArray(),
                ),
            )
        var listed: List<String>? = listOf("unset")

        val warnings = warningsFrom { listed = lister(classPath(fat)).list() }

        assertNull(listed)
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("${fat.toRealPath()}!/BOOT-INF/lib/broken-1.0.jar" in warnings.single().message, warnings.single().message)
    }

    @Test
    fun `a link loop in an exploded Spring Boot application's library folder still gives a listing`() {
        val app = Files.createDirectories(dir.resolve("workspace"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n\n".toByteArray())
        TestJars.write(app.resolve("BOOT-INF/lib/core.jar"), listOf(classEntry("com.acme.core.Core")))
        Files.createSymbolicLink(app.resolve("BOOT-INF/lib/loop"), app.resolve("BOOT-INF/lib"))

        assertEquals(listOf("com.acme.core.Core"), lister(classPath(app)).list())
    }

    @Test
    fun `an exploded Spring Boot application a Class-Path also names is read by its launcher's layout`() {
        val app = Files.createDirectories(dir.resolve("app"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n\n".toByteArray())
        Files.createDirectories(app.resolve("BOOT-INF/classes/com/acme"))
        Files.write(app.resolve("BOOT-INF/classes/com/acme/App.class"), ByteArray(4))
        TestJars.write(app.resolve("BOOT-INF/lib/lib.jar"), listOf(classEntry("com.acme.lib.L")))
        val first = TestJars.write(dir.resolve("first.jar"), listOf(classEntry("com.acme.first.F")), mapOf("Class-Path" to "app/"))

        assertEquals(listOf("com.acme.App", "com.acme.first.F", "com.acme.lib.L"), lister(classPath(first, app)).list())
    }

    @Test
    fun `an exploded Spring Boot application with a malformed classpath index gives no listing`() {
        val app = Files.createDirectories(dir.resolve("workspace"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.write(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n\n".toByteArray())
        TestJars.write(app.resolve("BOOT-INF/lib/core.jar"), listOf(classEntry("com.acme.core.Core")))
        Files.write(app.resolve("BOOT-INF/classpath.idx"), "BOOT-INF/lib/core.jar\n".toByteArray())

        val warnings = warningsFrom { assertNull(lister(classPath(app)).list()) }

        assertEquals(1, warnings.size)
        assertTrue("Malformed classpath index" in warnings.single().message, warnings.single().message)
    }

    @Test
    fun `an empty nested jar gives no listing`() {
        val fat = bootJar("app.jar", listOf("BOOT-INF/lib/empty-1.0.jar" to ByteArray(0)))

        assertNull(lister(classPath(fat)).list())
    }

    @Test
    fun `a root that is a file but not a zip gives no listing, even after readable roots`() {
        val classes = Files.createDirectories(dir.resolve("classes/com/acme"))
        Files.write(classes.resolve("App.class"), ByteArray(4))
        val garbage = Files.write(dir.resolve("data.bin"), ByteArray(16) { it.toByte() })

        assertNull(lister(classPath(dir.resolve("classes"), garbage)).list())
    }
}
