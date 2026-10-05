package dev.otherlode.instrumentation

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A woven class's own class file is read from the code source of its protection domain, and from its
 * loader only when that names nothing readable.
 */
class CodeSourceClassFileTest {
    private class CountingLoader : ClassLoader(getPlatformClassLoader()) {
        val reads = ConcurrentHashMap<String, AtomicInteger>()

        override fun getResourceAsStream(name: String): InputStream? {
            reads.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            return super.getResourceAsStream(name)
        }

        fun readsOf(internalName: String): Int = reads["$internalName.class"]?.get() ?: 0
    }

    private fun generate(
        name: String,
        constant: Int,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode()
            visitLdcInsn(constant)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun transformerOver(): ClassFileTransformer {
        val captured = mutableListOf<ClassFileTransformer>()
        OtherlodeInstrumentation(AgentConfig.parse("includePackages=csrc"), ProbeRegistry(), captureClassBytes = false)
            .install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
        return captured.single()
    }

    private fun domainAt(location: URL?): ProtectionDomain = ProtectionDomain(CodeSource(location, null as Array<Certificate>?), null)

    private fun directoryWith(vararg classes: Pair<String, ByteArray>): File {
        val directory = Files.createTempDirectory("csrc-dir").toFile()
        directory.deleteOnExit()
        for ((name, bytes) in classes) {
            val file = File(directory, "$name.class")
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        return directory
    }

    private fun jarWith(vararg classes: Pair<String, ByteArray>): File {
        val jar = Files.createTempFile("csrc", ".jar").toFile()
        jar.deleteOnExit()
        JarOutputStream(jar.outputStream()).use { out ->
            for ((name, bytes) in classes) {
                out.putNextEntry(JarEntry("$name.class"))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    /** A URL for [spec] whose protocol the JDK has no handler for; Spring Boot's loader registers one in a real launch. */
    private fun withoutOpening(spec: String): URL =
        URL(
            null,
            spec,
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = throw java.io.IOException("not readable")
            },
        )

    @Test
    fun `a directory location resolves the class path against it`() {
        val url = CodeSourceClassFile.urlOf(URL("file:/app/classes/"), "com/acme/Foo\$Bar")
        assertEquals("file:/app/classes/com/acme/Foo\$Bar.class", url.toString())
    }

    @Test
    fun `a jar file location reads the entry through a jar URL`() {
        val url = CodeSourceClassFile.urlOf(URL("file:/app/x.jar"), "com/acme/Foo")
        assertEquals("jar:file:/app/x.jar!/com/acme/Foo.class", url.toString())
    }

    @Test
    fun `a jar location ending in a bang slash resolves the class path relative to it`() {
        assertEquals(
            "jar:file:/app.jar!/BOOT-INF/lib/x.jar!/com/acme/Foo.class",
            CodeSourceClassFile.urlOf(URL("jar:file:/app.jar!/BOOT-INF/lib/x.jar!/"), "com/acme/Foo").toString(),
        )
        assertEquals(
            "jar:file:/app.jar!/BOOT-INF/classes!/com/acme/Foo.class",
            CodeSourceClassFile.urlOf(URL("jar:file:/app.jar!/BOOT-INF/classes!/"), "com/acme/Foo").toString(),
        )
        assertEquals(
            "jar:nested:/app.jar/!BOOT-INF/lib/x.jar!/com/acme/Foo.class",
            CodeSourceClassFile.urlOf(withoutOpening("jar:nested:/app.jar/!BOOT-INF/lib/x.jar!/"), "com/acme/Foo").toString(),
        )
        assertEquals(
            "jar:nested:/app.jar/!BOOT-INF/classes/!/com/acme/Foo.class",
            CodeSourceClassFile.urlOf(withoutOpening("jar:nested:/app.jar/!BOOT-INF/classes/!/"), "com/acme/Foo").toString(),
        )
    }

    @Test
    fun `a name with characters a URL must escape is read from where it is`() {
        val bytes = generate("csrc/100%/Caf\u00e9\$Inner", 1)
        val directory = directoryWith("csrc/100%/Caf\u00e9\$Inner" to bytes)
        val jar = jarWith("csrc/100%/Caf\u00e9\$Inner" to bytes)

        assertContentEquals(bytes, CodeSourceClassFile.read(domainAt(directory.toURI().toURL()), "csrc/100%/Caf\u00e9\$Inner"))
        assertContentEquals(bytes, CodeSourceClassFile.read(domainAt(jar.toURI().toURL()), "csrc/100%/Caf\u00e9\$Inner"))
    }

    @Test
    fun `no location and shapes this does not read give no URL`() {
        assertNull(CodeSourceClassFile.urlOf(null, "com/acme/Foo"))
        assertNull(CodeSourceClassFile.urlOf(URL("http://example.invalid/app.jar"), "com/acme/Foo"))
        assertNull(CodeSourceClassFile.urlOf(URL("jar:http://example.invalid/app.jar!/"), "com/acme/Foo"))
        assertNull(CodeSourceClassFile.urlOf(URL("jar:file:/app.jar!/BOOT-INF/classes"), "com/acme/Foo"))
        assertNull(CodeSourceClassFile.read(null, "com/acme/Foo"))
        assertNull(CodeSourceClassFile.read(domainAt(null), "com/acme/Foo"))
    }

    @Test
    fun `a class in a directory is read without asking its loader for its own file`() {
        val bytes = generate("csrc/InDir", 1)
        val directory = directoryWith("csrc/InDir" to bytes)
        val loader = CountingLoader()

        val woven = transformerOver().transform(loader, "csrc/InDir", null, domainAt(directory.toURI().toURL()), bytes)

        assertNotNull(woven)
        assertEquals(0, loader.readsOf("csrc/InDir"), "${loader.reads}")
    }

    @Test
    fun `a class in a jar is read without asking its loader for its own file`() {
        val bytes = generate("csrc/InJar", 1)
        val jar = jarWith("csrc/InJar" to bytes)
        val loader = CountingLoader()

        val woven = transformerOver().transform(loader, "csrc/InJar", null, domainAt(jar.toURI().toURL()), bytes)

        assertNotNull(woven)
        assertEquals(0, loader.readsOf("csrc/InJar"), "${loader.reads}")
        assertContentEquals(bytes, CodeSourceClassFile.read(domainAt(jar.toURI().toURL()), "csrc/InJar"))
    }

    @Test
    fun `a class whose code source holds a different copy is analysed from that copy`() {
        val received = generate("csrc/Copy", 1)
        val onDisk = generate("csrc/Copy", 2)
        val directory = directoryWith("csrc/Copy" to onDisk)

        val read = CodeSourceClassFile.read(domainAt(directory.toURI().toURL()), "csrc/Copy")

        assertContentEquals(onDisk, read)
        assertTrue(!received.contentEquals(read))
    }

    private fun assertReadThroughLoader(
        domain: ProtectionDomain?,
        name: String,
        bytes: ByteArray,
    ) {
        val loader =
            object : ClassLoader(getPlatformClassLoader()) {
                var reads = 0

                override fun getResourceAsStream(resource: String): InputStream? {
                    if (resource != "$name.class") return super.getResourceAsStream(resource)
                    reads++
                    return ByteArrayInputStream(bytes)
                }
            }

        val woven = transformerOver().transform(loader, name, null, domain, bytes)

        assertNotNull(woven)
        assertTrue(loader.reads >= 1, "the loader was not asked for $name")
    }

    @Test
    fun `without a domain, a code source or a location the loader is read`() {
        assertReadThroughLoader(null, "csrc/NoDomain", generate("csrc/NoDomain", 1))
        assertReadThroughLoader(ProtectionDomain(null, null), "csrc/NoSource", generate("csrc/NoSource", 1))
        assertReadThroughLoader(domainAt(null), "csrc/NoLocation", generate("csrc/NoLocation", 1))
    }

    @Test
    fun `an entry missing at the location reads the loader`() {
        val empty = directoryWith()
        assertReadThroughLoader(domainAt(empty.toURI().toURL()), "csrc/Missing", generate("csrc/Missing", 1))
        val emptyJar = jarWith()
        assertReadThroughLoader(domainAt(emptyJar.toURI().toURL()), "csrc/MissingInJar", generate("csrc/MissingInJar", 1))
    }

    @Test
    fun `an unknown protocol reads the loader and opens nothing`() {
        var opened = false
        val handler =
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    opened = true
                    throw java.io.IOException("not readable")
                }
            }
        val unknown = URL(null, "mystery:/app/classes/", handler)
        assertReadThroughLoader(domainAt(unknown), "csrc/Unknown", generate("csrc/Unknown", 1))
        assertReadThroughLoader(domainAt(URL("http://example.invalid/app.jar")), "csrc/Remote", generate("csrc/Remote", 1))
        assertEquals(false, opened)
    }
}
