package dev.otherlode.instrumentation

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SizeGuardLogFailureTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
    private val julLogger = Logger.getLogger(OtherlodeInstrumentation::class.java.name)
    private var handler: Handler? = null
    private var originalLevel: Level? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        handler?.let { julLogger.removeHandler(it) }
        julLogger.level = originalLevel
    }

    private class BytesLoader : ClassLoader(SizeGuardLogFailureTest::class.java.classLoader) {
        fun define(
            name: String,
            bytes: ByteArray,
        ): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun hugeEntryClass(name: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        val ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()
        val pad = writer.visitMethod(Opcodes.ACC_PUBLIC, "pad", "()V", null, null)
        pad.visitCode()
        repeat(7_995) { pad.visitInsn(Opcodes.NOP) }
        pad.visitInsn(Opcodes.RETURN)
        pad.visitMaxs(0, 0)
        pad.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun loadHugeEntry(
        className: String,
        throwOnEveryCall: Boolean,
        failure: () -> Throwable = { IllegalStateException("log backend down") },
    ): Pair<ProbeRegistry, Class<*>> {
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())
        val calls = AtomicInteger()
        val throwing =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level.intValue() >= Level.WARNING.intValue() && (throwOnEveryCall || calls.getAndIncrement() == 0)) {
                        throw failure()
                    }
                }

                override fun flush() {}

                override fun close() {}
            }
        handler = throwing
        originalLevel = julLogger.level
        julLogger.addHandler(throwing)
        julLogger.level = Level.ALL
        val cls = BytesLoader().define(className, hugeEntryClass(className))
        return registry to cls
    }

    private fun assertWovenAndCounted(
        registry: ProbeRegistry,
        cls: Class<*>,
    ) {
        cls.getMethod("pad").invoke(cls.getDeclaredConstructor().newInstance())
        val manifest = registry.manifest(resource)
        val probes = manifest.probes.filter { it.className == cls.name && it.kind == ProbeKind.METHOD }
        val hits = registry.computeDeltaBatch(resource).batch.deltas
        val counted = probes.count { p -> hits.any { it.classId == p.classId && it.probeIndex == p.probeIndex && it.hitsTotal > 0 } }
        assertEquals(2, probes.size)
        assertEquals(2, counted)
        assertTrue(manifest.skippedClasses.none { it.className == cls.name })
    }

    @Test
    fun `a log backend that throws on its first warning still weaves the class and counts its probes`() {
        val (registry, cls) = loadHugeEntry("com.example.target.HugeEntryOnce", throwOnEveryCall = false)

        assertWovenAndCounted(registry, cls)
    }

    @Test
    fun `a log backend that throws on every warning still weaves the class and counts its probes`() {
        val (registry, cls) = loadHugeEntry("com.example.target.HugeEntryAlways", throwOnEveryCall = true)

        assertWovenAndCounted(registry, cls)
    }

    @Test
    fun `a log backend that throws a StackOverflowError on the warning still weaves the class and counts its probes`() {
        val (registry, cls) = loadHugeEntry("com.example.target.HugeEntryOverflow", throwOnEveryCall = true, failure = { StackOverflowError("deep") })

        assertWovenAndCounted(registry, cls)
    }

    @Test
    fun `a log backend that throws while a class that fails to transform is reported still records the class as skipped`() {
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(AbsentTypeFixtures.PACKAGE), registry)
        val throwing =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level.intValue() >= Level.WARNING.intValue()) throw IllegalStateException("log backend down")
                }

                override fun flush() {}

                override fun close() {}
            }
        handler = throwing
        originalLevel = julLogger.level
        julLogger.addHandler(throwing)
        julLogger.level = Level.ALL
        val name = AbsentTypeFixtures.PACKAGE.replace('.', '/') + "/Corrupt"
        val valid = AbsentTypeFixtures.subtype(name, "java/lang/Object")
        val truncated = valid.copyOf(valid.size / 2)

        assertFailsWith<Throwable> { transformer.transform(BytesLoader(), name, null, null, truncated) }

        val skipped = registry.manifest(resource).skippedClasses.filter { it.className == name.replace('/', '.') }
        assertEquals(1, skipped.size)
    }

    @Test
    fun `control the size guard warns for the fixture when the backend is well behaved`() {
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())
        val seen = mutableListOf<String>()
        val recording =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    seen += record.message
                }

                override fun flush() {}

                override fun close() {}
            }
        handler = recording
        originalLevel = julLogger.level
        julLogger.addHandler(recording)
        julLogger.level = Level.ALL

        val cls = BytesLoader().define("com.example.target.HugeEntryControl", hugeEntryClass("com.example.target.HugeEntryControl"))
        cls.getMethod("pad").invoke(cls.getDeclaredConstructor().newInstance())

        assertEquals(1, seen.count { "HugeEntryControl" in it && "7996 bytes" in it }, seen.toString())
        assertWovenAndCounted(registry, cls)
    }

    @Test
    fun `a log backend that throws while a failed transform is reported still records the class as skipped`() {
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(AbsentTypeFixtures.PACKAGE), registry)
        val throwing =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level.intValue() >= Level.WARNING.intValue()) throw IllegalStateException("log backend down")
                }

                override fun flush() {}

                override fun close() {}
            }
        handler = throwing
        originalLevel = julLogger.level
        julLogger.addHandler(throwing)
        julLogger.level = Level.ALL
        val name = AbsentTypeFixtures.PACKAGE.replace('.', '/') + "/Orphan"
        val original = AbsentTypeFixtures.subtype(name, AbsentTypeFixtures.ABSENT_PREFIX + "Parent")
        val loader = BytesLoader()

        assertFailsWith<Throwable> { transformer.transform(loader, name, null, null, original) }

        val skipped = registry.manifest(resource).skippedClasses.filter { it.className == name.replace('/', '.') }
        assertEquals(1, skipped.size)
    }
}
