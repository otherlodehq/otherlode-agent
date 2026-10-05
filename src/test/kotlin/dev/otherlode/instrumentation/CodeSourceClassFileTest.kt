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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
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
    /** Warms on the calling thread, so a jar shape is read on its first call; the warm-up tests use [ManualWarmer]. */
    private val reader = CodeSourceClassFile(Executor { it.run() })

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
        OtherlodeInstrumentation(
            AgentConfig.parse("includePackages=csrc"),
            ProbeRegistry(),
            captureClassBytes = false,
            codeSourceClassFile = reader,
        ).install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
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

        assertContentEquals(bytes, reader.read(domainAt(directory.toURI().toURL()), "csrc/100%/Caf\u00e9\$Inner"))
        assertContentEquals(bytes, reader.read(domainAt(jar.toURI().toURL()), "csrc/100%/Caf\u00e9\$Inner"))
    }

    @Test
    fun `no location and shapes this does not read give no URL`() {
        assertNull(CodeSourceClassFile.urlOf(null, "com/acme/Foo"))
        assertNull(CodeSourceClassFile.urlOf(URL("http://example.invalid/app.jar"), "com/acme/Foo"))
        assertNull(CodeSourceClassFile.urlOf(URL("jar:http://example.invalid/app.jar!/"), "com/acme/Foo"))
        assertNull(CodeSourceClassFile.urlOf(URL("jar:file:/app.jar!/BOOT-INF/classes"), "com/acme/Foo"))
        assertNull(reader.read(null, "com/acme/Foo"))
        assertNull(reader.read(domainAt(null), "com/acme/Foo"))
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
        assertContentEquals(bytes, reader.read(domainAt(jar.toURI().toURL()), "csrc/InJar"))
    }

    @Test
    fun `a class whose code source holds a different copy is analysed from that copy`() {
        val received = generate("csrc/Copy", 1)
        val onDisk = generate("csrc/Copy", 2)
        val directory = directoryWith("csrc/Copy" to onDisk)

        val read = reader.read(domainAt(directory.toURI().toURL()), "csrc/Copy")

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

    /**
     * Records the threads that parse and open URLs through it. With a [gate], an open waits for the
     * gate before answering, which holds a warm-up on its own thread until the read that started it
     * has answered: a warm-up that finishes first marks the key warm, and that read then opens the
     * location itself.
     */
    private class RecordingHandler(
        private val bytes: ByteArray?,
        private val gate: CountDownLatch? = null,
    ) : URLStreamHandler() {
        val openedOn = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val parsedOn = java.util.Collections.synchronizedList(mutableListOf<Thread>())

        override fun parseURL(
            u: URL,
            spec: String,
            start: Int,
            limit: Int,
        ) {
            parsedOn.add(Thread.currentThread())
            super.parseURL(u, spec, start, limit)
        }

        override fun openConnection(url: URL): URLConnection {
            openedOn.add(Thread.currentThread())
            gate?.await(10, TimeUnit.SECONDS)
            val content = bytes ?: throw java.io.IOException("no entry")
            return object : URLConnection(url) {
                override fun connect() = Unit

                override fun getInputStream(): InputStream = ByteArrayInputStream(content)
            }
        }
    }

    private class ManualWarmer : Executor {
        val pending = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            pending.add(command)
        }
    }

    private fun nestedDomain(handler: URLStreamHandler) = domainAt(URL(null, "jar:nested:/app.jar/!BOOT-INF/lib/x.jar!/", handler))

    @Test
    fun `a nested location is read through the loader until its warm-up has run`() {
        val bytes = generate("csrc/Nested", 1)
        val handler = RecordingHandler(bytes)
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)
        val domain = nestedDomain(handler)
        val parsedBefore = handler.parsedOn.size

        assertNull(reader.read(domain, "csrc/Nested"))
        assertEquals(CodeSourceClassFile.State.WARMING, reader.stateOf("jar:nested"))
        assertNull(reader.read(domain, "csrc/Nested"))
        assertEquals(1, warmer.pending.size, "one warm-up per shape")
        assertTrue(handler.openedOn.isEmpty(), "nothing opened before the warm-up")
        assertEquals(parsedBefore, handler.parsedOn.size, "no class file URL built before the warm-up")

        warmer.pending.single().run()

        assertEquals(CodeSourceClassFile.State.WARMED, reader.stateOf("jar:nested"))
        assertContentEquals(bytes, reader.read(domain, "csrc/Nested"))
    }

    @Test
    fun `a warm-up that finds nothing lets a later class try again, until enough misses leave the shape on the loader`() {
        val handler = RecordingHandler(null)
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)
        val domain = nestedDomain(handler)

        repeat(CodeSourceClassFile.MAX_WARM_UP_ATTEMPTS - 1) { attempt ->
            assertNull(reader.read(domain, "csrc/Gone$attempt"))
            warmer.pending.last().run()
            assertEquals(CodeSourceClassFile.State.NOT_WARMED, reader.stateOf("jar:nested"), "a miss may be one missing class")
        }
        assertNull(reader.read(domain, "csrc/GoneLast"))
        warmer.pending.last().run()

        assertEquals(CodeSourceClassFile.State.FAILED, reader.stateOf("jar:nested"))
        assertNull(reader.read(domain, "csrc/GoneAfter"))
        assertEquals(CodeSourceClassFile.MAX_WARM_UP_ATTEMPTS, warmer.pending.size, "a shape left on the loader is not warmed again")
    }

    @Test
    fun `a warm-up that succeeds after a miss warms the shape`() {
        val bytes = generate("csrc/Present", 1)
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)
        val missing = nestedDomain(RecordingHandler(null))
        val present = nestedDomain(RecordingHandler(bytes))

        assertNull(reader.read(missing, "csrc/Gone"))
        warmer.pending.last().run()
        assertNull(reader.read(present, "csrc/Present"))
        warmer.pending.last().run()

        assertEquals(CodeSourceClassFile.State.WARMED, reader.stateOf("jar:nested"))
        assertContentEquals(bytes, reader.read(present, "csrc/Present"))
    }

    @Test
    fun `the warm-up opens the code source on a thread other than the caller's`() {
        val bytes = generate("csrc/Threaded", 1)
        val opened = CountDownLatch(1)
        val firstReadAnswered = CountDownLatch(1)
        val handler =
            object : URLStreamHandler() {
                @Volatile var thread: Thread? = null

                override fun openConnection(url: URL): URLConnection {
                    thread = Thread.currentThread()
                    opened.countDown()
                    firstReadAnswered.await(10, TimeUnit.SECONDS)
                    return object : URLConnection(url) {
                        override fun connect() = Unit

                        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
                    }
                }
            }
        val reader = CodeSourceClassFile()
        val domain = nestedDomain(handler)

        assertNull(reader.read(domain, "csrc/Threaded"))
        firstReadAnswered.countDown()
        assertTrue(opened.await(10, TimeUnit.SECONDS))

        val warmThread = assertNotNull(handler.thread)
        assertTrue(warmThread !== Thread.currentThread())
        assertTrue(warmThread.isDaemon)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (reader.stateOf("jar:nested") != CodeSourceClassFile.State.WARMED && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(CodeSourceClassFile.State.WARMED, reader.stateOf("jar:nested"))
    }

    @Test
    fun `the warm-up thread copies no inheritable thread-local of the transforming thread`() {
        val copies = AtomicInteger()
        val local =
            object : InheritableThreadLocal<String>() {
                override fun childValue(parentValue: String?): String? {
                    copies.incrementAndGet()
                    return parentValue
                }
            }
        local.set("transform")
        try {
            val bytes = generate("csrc/Inherited", 1)
            val reader = CodeSourceClassFile()
            val firstReadAnswered = CountDownLatch(1)
            val domain = nestedDomain(RecordingHandler(bytes, firstReadAnswered))

            assertNull(reader.read(domain, "csrc/Inherited"))
            firstReadAnswered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reader.stateOf("jar:nested") != CodeSourceClassFile.State.WARMED && System.nanoTime() < deadline) Thread.sleep(5)

            assertEquals(CodeSourceClassFile.State.WARMED, reader.stateOf("jar:nested"))
            assertEquals(0, copies.get(), "childValue is application code and must not run in a transform")
        } finally {
            local.remove()
        }
    }

    @Test
    fun `a location with a fragment is not read`() {
        val bytes = generate("csrc/Fragment", 1)
        val directory = directoryWith("csrc/Fragment" to bytes)
        val warmer = ManualWarmer()

        assertNull(CodeSourceClassFile(warmer).read(domainAt(URL(directory.toURI().toURL(), "#part")), "csrc/Fragment"))
        assertTrue(warmer.pending.isEmpty())
    }

    @Test
    fun `a directory is read on the first call, and jar shapes only after their warm-up`() {
        val bytes = generate("csrc/Plain", 1)
        val directory = directoryWith("csrc/Plain" to bytes)
        val jar = jarWith("csrc/Plain" to bytes)
        val jarLocation = URL("jar:" + jar.toURI().toURL() + "!/")
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)

        assertContentEquals(bytes, reader.read(domainAt(directory.toURI().toURL()), "csrc/Plain"))
        assertTrue(warmer.pending.isEmpty(), "the file handler is always the JDK's")

        assertNull(reader.read(domainAt(jar.toURI().toURL()), "csrc/Plain"))
        assertNull(reader.read(domainAt(jarLocation), "csrc/Plain"))
        assertEquals(2, warmer.pending.size, "a jar file and a jar location are warmed apart")
        warmer.pending.toList().forEach { it.run() }

        assertContentEquals(bytes, reader.read(domainAt(jar.toURI().toURL()), "csrc/Plain"))
        assertContentEquals(bytes, reader.read(domainAt(jarLocation), "csrc/Plain"))
        val other = generate("csrc/Other", 2)
        val secondJar = jarWith("csrc/Other" to other)
        assertContentEquals(
            other,
            reader.read(domainAt(secondJar.toURI().toURL()), "csrc/Other"),
            "a second jar file reuses the warmed handler",
        )
        assertEquals(2, warmer.pending.size)
    }

    @Test
    fun `a shape this does not read starts no warm-up`() {
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)
        val handler = RecordingHandler(generate("csrc/Odd", 1))

        assertNull(reader.read(domainAt(URL(null, "jar:http://example.invalid/app.jar!/", handler)), "csrc/Odd"))
        assertNull(reader.read(domainAt(URL(null, "jar:nested:/app.jar/!BOOT-INF/lib/x.jar", handler)), "csrc/Odd"))

        assertTrue(warmer.pending.isEmpty())
        assertTrue(handler.openedOn.isEmpty())
    }

    @Test
    fun `an executor that refuses a warm-up counts as a miss, not a failure`() {
        val reader = CodeSourceClassFile { throw IllegalStateException("unable to create a thread") }

        assertNull(reader.read(nestedDomain(RecordingHandler(generate("csrc/Refused", 1))), "csrc/Refused"))

        assertEquals(CodeSourceClassFile.State.NOT_WARMED, reader.stateOf("jar:nested"))
    }

    @Test
    fun `an error inside a warm-up leaves the shape able to warm again`() {
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)
        val failing =
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = throw LinkageError("a handler class did not link")
            }

        assertNull(reader.read(nestedDomain(failing), "csrc/Broken"))
        warmer.pending.single().run()

        assertEquals(CodeSourceClassFile.State.NOT_WARMED, reader.stateOf("jar:nested"))
    }

    @Test
    fun `a transform of a class in a nested location opens no code source on its own thread`() {
        val bytes = generate("csrc/InNested", 1)
        val handler = RecordingHandler(bytes)
        val warmer = ManualWarmer()
        val reader = CodeSourceClassFile(warmer)
        val captured = mutableListOf<ClassFileTransformer>()
        OtherlodeInstrumentation(
            AgentConfig.parse("includePackages=csrc"),
            ProbeRegistry(),
            captureClassBytes = false,
            codeSourceClassFile = reader,
        ).install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
        val loader = CountingLoader()
        val domain = nestedDomain(handler)
        val parsedBefore = handler.parsedOn.size

        val woven = captured.single().transform(loader, "csrc/InNested", null, domain, bytes)

        assertNotNull(woven)
        assertTrue(handler.openedOn.isEmpty(), "the transforming thread opened the nested location")
        assertEquals(parsedBefore, handler.parsedOn.size, "the transforming thread built a URL through the nested handler")
        assertEquals(1, warmer.pending.size)
    }
}
