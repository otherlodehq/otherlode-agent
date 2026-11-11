package dev.otherlode.classlisting

import dev.otherlode.dependencies.TestJars
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

class ClassListingWatchTest {
    @TempDir
    lateinit var dir: Path

    private val includes = listOf("com.acme")
    private val excludes = listOf("com.acme.skipped")

    private fun watch() = ClassListingWatch(includes, excludes)

    private fun jar(
        name: String,
        vararg classNames: String,
    ): Path {
        val path = TestJars.write(dir.resolve(name), classNames.map { TestJars.loadableClassEntry(it) })
        return path.toRealPath()
    }

    private fun listed(
        watch: ClassListingWatch,
        classPath: String,
    ) {
        val names = ClassLister(includes, excludes, classPath, onRoots = watch::listingRead).list()
        assertTrue(names != null, "the listing must be complete")
    }

    private fun loadFrom(
        jar: Path,
        className: String,
    ): Class<*> {
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), null)
        return Class.forName(className, false, loader)
    }

    private fun see(
        watch: ClassListingWatch,
        className: String,
        domain: ProtectionDomain?,
    ) {
        watch.transform(null, className.replace('.', '/'), null, domain, ByteArray(0))
    }

    private fun see(
        watch: ClassListingWatch,
        type: Class<*>,
    ) = see(watch, type.name, type.protectionDomain)

    private fun domainAt(spec: String): ProtectionDomain {
        val handler =
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = throw java.io.IOException("not readable")
            }
        return ProtectionDomain(CodeSource(URL(null, spec, handler), null as Array<Certificate>?), null)
    }

    private fun infoFrom(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(ClassListingWatch::class.java.name)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records.filter { it.level == JulLevel.INFO }
    }

    @Test
    fun `a class from a jar the lister read leaves the flag false`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        listed(watch, read.toString())

        see(watch, loadFrom(read, "com.acme.Read"))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `an included class from a jar outside the classpath sets the flag, once, and the log line names the location`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.Outside", "com.acme.Other")
        val watch = watch()
        listed(watch, read.toString())

        val records =
            infoFrom {
                see(watch, loadFrom(outside, "com.acme.Outside"))
                see(watch, loadFrom(outside, "com.acme.Other"))
            }

        assertTrue(watch.isBlind)
        assertEquals(1, records.size)
        assertTrue(outside.toUri().toURL().toString() in records.single().message, records.single().message)
    }

    @Test
    fun `an excluded class from an unread jar does not set the flag`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.skipped.Hidden", "org.other.Lib")
        val watch = watch()
        listed(watch, read.toString())

        see(watch, loadFrom(outside, "com.acme.skipped.Hidden"))
        see(watch, loadFrom(outside, "org.other.Lib"))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a runtime-generated class from an unread jar does not set the flag`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.Config\$\$EnhancerBySpringCGLIB\$\$1a2b3c4d")
        val watch = watch()
        listed(watch, read.toString())

        see(watch, loadFrom(outside, "com.acme.Config\$\$EnhancerBySpringCGLIB\$\$1a2b3c4d"))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a class with no code source that the listing names says nothing`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        listed(watch, read.toString())

        see(watch, "com.acme.Read", null)
        see(watch, "com.acme.Read", ProtectionDomain(CodeSource(null, null as Array<Certificate>?), null))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a class with no code source that the listing lacks sets the flag`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        listed(watch, read.toString())

        see(watch, "com.acme.Plugin", ProtectionDomain(CodeSource(null, null as Array<Certificate>?), null))

        assertTrue(watch.isBlind)
    }

    @Test
    fun `a class with no code source seen before the listing finished is judged by name once it has`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        see(watch, "com.acme.Plugin", null)
        assertFalse(watch.isBlind)

        listed(watch, read.toString())

        assertTrue(watch.isBlind)
    }

    @Test
    fun `more locations than the watch keeps before the listing finished makes the run blind`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        for (i in 0..4096) {
            val link = Files.createSymbolicLink(dir.resolve("link$i"), dir)
            see(watch, "com.acme.Read", domainAt(link.resolve("read.jar").toUri().toString()))
        }
        assertFalse(watch.isBlind)

        listed(watch, read.toString())

        assertTrue(watch.isBlind)
    }

    @Test
    fun `a class the JVM loaded before the watch was registered is judged when the listing finishes`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.Outside")
        val early = loadFrom(outside, "com.acme.Outside")
        val watch = watch()

        val names = ClassLister(includes, excludes, read.toString(), onRoots = { roots, listed -> watch.listingRead(roots, listed) { arrayOf(early) } }).list()

        assertTrue(names != null)
        assertTrue(watch.isBlind)
    }

    @Test
    fun `a JDK class under a broad include rule says nothing`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = ClassListingWatch(listOf("org", "com", "javax"), emptyList())
        val names = ClassLister(listOf("org", "com", "javax"), emptyList(), read.toString(), onRoots = { roots, listed ->
            watch.listingRead(roots, listed) { arrayOf(org.w3c.dom.Node::class.java, javax.sql.DataSource::class.java) }
        }).list()
        assertTrue(names != null)

        watch.transform(org.w3c.dom.Node::class.java.module, null, "org/w3c/dom/Element", null, org.w3c.dom.Node::class.java.protectionDomain, ByteArray(0))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a class the JVM hands over with no name is named from its bytes`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        listed(watch, read.toString())
        val bytes = TestJars.loadableClassEntry("com.acme.Plugin").second

        watch.transform(null, null, null, null, ProtectionDomain(CodeSource(null, null as Array<Certificate>?), null), bytes)

        assertTrue(watch.isBlind)
    }

    @Test
    fun `a class the JVM hands over with no name and bytes the agent cannot read sets the flag`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        listed(watch, read.toString())

        watch.transform(null, null, null, null, null, byteArrayOf(1, 2, 3))

        assertTrue(watch.isBlind)
    }

    @Test
    fun `one class with no code source seen many times before the listing does not overflow the watch`() {
        val read = jar("read.jar", "com.acme.Read")
        val watch = watch()
        for (i in 0..5000) see(watch, "com.acme.Read", null)

        listed(watch, read.toString())

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a JDK tool class that the application loader defines says nothing under a broad include rule`() {
        val javac = Class.forName("com.sun.tools.javac.Main")
        check(javac.classLoader != null && javac.classLoader !== ClassLoader.getPlatformClassLoader()) { "jdk.compiler is on the application loader here" }
        val read = jar("read.jar", "com.acme.Read")
        val watch = ClassListingWatch(listOf("com"), emptyList())
        val names = ClassLister(listOf("com"), emptyList(), read.toString(), onRoots = { roots, listed -> watch.listingRead(roots, listed) { arrayOf(javac) } }).list()
        assertTrue(names != null)

        watch.transform(javac.module, javac.classLoader, "com/sun/tools/javac/Main", null, javac.protectionDomain, ByteArray(0))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a run with no complete listing never sets the flag`() {
        val outside = jar("plugins/outside.jar", "com.acme.Outside")
        val watch = watch()

        see(watch, loadFrom(outside, "com.acme.Outside"))
        val failed = ClassLister(includes, excludes, classPath = "", onRoots = watch::listingRead).list()

        assertEquals(null, failed)
        assertFalse(watch.isBlind)
    }

    @Test
    fun `a class that loaded before the listing finished is judged once the listing has`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.Outside")
        val watch = watch()
        see(watch, loadFrom(read, "com.acme.Read"))
        see(watch, loadFrom(outside, "com.acme.Outside"))
        assertFalse(watch.isBlind, "nothing is judged without a listing")

        listed(watch, read.toString())

        assertTrue(watch.isBlind)
    }

    @Test
    fun `a class from a read location first and an unread one later sets the flag`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.Outside")
        val watch = watch()
        listed(watch, read.toString())
        see(watch, loadFrom(read, "com.acme.Read"))
        assertFalse(watch.isBlind)

        see(watch, loadFrom(outside, "com.acme.Outside"))

        assertTrue(watch.isBlind)
    }

    @Test
    fun `a class directory the lister read is not blind, and a sibling directory is`() {
        val classes = Files.createDirectories(dir.resolve("classes/com/acme"))
        Files.write(classes.resolve("Read.class"), ByteArray(4))
        val root = dir.resolve("classes").toRealPath()
        val watch = watch()
        listed(watch, root.toString())

        see(watch, "com.acme.Read", domainAt(root.toUri().toString()))
        assertFalse(watch.isBlind)

        see(watch, "com.acme.Elsewhere", domainAt(dir.resolve("plugins").toUri().toString()))
        assertTrue(watch.isBlind)
    }

    @Test
    fun `the transform path the JVM calls reaches the watch`() {
        val read = jar("read.jar", "com.acme.Read")
        val outside = jar("plugins/outside.jar", "com.acme.Outside")
        val watch = watch()
        listed(watch, read.toString())
        val instrumentation = ByteBuddyAgent.install()
        instrumentation.addTransformer(watch)
        try {
            loadFrom(read, "com.acme.Read").getDeclaredConstructor().newInstance()
            assertFalse(watch.isBlind)

            loadFrom(outside, "com.acme.Outside").getDeclaredConstructor().newInstance()

            assertTrue(watch.isBlind)
        } finally {
            instrumentation.removeTransformer(watch)
        }
    }

    private fun bootFatJar(
        name: String = "app.jar",
        classes: String = "BOOT-INF/classes/",
        libs: String = "BOOT-INF/lib/",
    ): Path {
        val lib = TestJars.bytes(listOf(TestJars.classEntry("com.acme.Lib")))
        return TestJars
            .write(
                dir.resolve(name),
                listOf(
                    TestJars.classEntry("org.springframework.boot.loader.launch.JarLauncher"),
                    "${classes}com/acme/App.class" to ByteArray(4),
                    "${libs}lib-1.0.jar" to lib,
                ),
                mapOf("Spring-Boot-Lib" to libs),
            ).toRealPath()
    }

    @Test
    fun `Spring Boot 3_2 nested forms for the application classes and a library compare equal to the lister's roots`() {
        val fat = bootFatJar()
        val watch = watch()
        listed(watch, fat.toString())

        see(watch, "com.acme.App", domainAt("jar:nested:$fat/!BOOT-INF/classes/!/"))
        see(watch, "com.acme.Lib", domainAt("jar:nested:$fat/!BOOT-INF/lib/lib-1.0.jar!/"))
        see(watch, "com.acme.Launcher", domainAt("file:$fat"))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `Spring Boot 2 to 3_1 jar file forms for the application classes and a library compare equal to the lister's roots`() {
        val fat = bootFatJar()
        val watch = watch()
        listed(watch, fat.toString())

        see(watch, "com.acme.App", domainAt("jar:file:$fat!/BOOT-INF/classes!/"))
        see(watch, "com.acme.Lib", domainAt("jar:file:$fat!/BOOT-INF/lib/lib-1.0.jar!/"))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `an executable war's forms compare equal to the lister's roots`() {
        val war = bootFatJar("app.war", classes = "WEB-INF/classes/", libs = "WEB-INF/lib/")
        val watch = watch()
        listed(watch, war.toString())

        see(watch, "com.acme.App", domainAt("jar:nested:$war/!WEB-INF/classes/!/"))
        see(watch, "com.acme.Lib", domainAt("jar:nested:$war/!WEB-INF/lib/lib-1.0.jar!/"))
        see(watch, "com.acme.OldApp", domainAt("jar:file:$war!/WEB-INF/classes!/"))
        see(watch, "com.acme.OldLib", domainAt("jar:file:$war!/WEB-INF/lib/lib-1.0.jar!/"))

        assertFalse(watch.isBlind)
    }

    @Test
    fun `a nested jar the application packs but the lister did not read sets the flag`() {
        val fat = bootFatJar()
        val watch = watch()
        listed(watch, fat.toString())

        see(watch, "com.acme.Stray", domainAt("jar:nested:$fat/!BOOT-INF/lib/stray-1.0.jar!/"))

        assertTrue(watch.isBlind)
    }

    @Test
    fun `an exploded Spring Boot application's file locations compare equal to the lister's roots`() {
        val app = Files.createDirectories(dir.resolve("exploded"))
        Files.createDirectories(app.resolve("META-INF"))
        Files.writeString(app.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSpring-Boot-Lib: BOOT-INF/lib/\n")
        Files.createDirectories(app.resolve("BOOT-INF/classes/com/acme"))
        Files.write(app.resolve("BOOT-INF/classes/com/acme/App.class"), ByteArray(4))
        Files.createDirectories(app.resolve("BOOT-INF/lib"))
        val lib = Files.write(app.resolve("BOOT-INF/lib/lib-1.0.jar"), TestJars.bytes(listOf(TestJars.classEntry("com.acme.Lib"))))
        val root = app.toRealPath()
        val watch = watch()
        listed(watch, root.toString())

        see(watch, "com.acme.App", domainAt(root.resolve("BOOT-INF/classes").toUri().toString()))
        see(watch, "com.acme.Lib", domainAt(lib.toRealPath().toUri().toString()))

        assertFalse(watch.isBlind)
    }
}
