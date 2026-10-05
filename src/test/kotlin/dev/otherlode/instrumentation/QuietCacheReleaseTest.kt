package dev.otherlode.instrumentation

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The release of transform-time caches through the real transformer. A release check drops what a
 * loader holds only when the loader did no transform-time work since the previous check.
 */
class QuietCacheReleaseTest {
    private class CountingLoader(
        private val files: Map<String, ByteArray>,
    ) : ClassLoader(getPlatformClassLoader()) {
        val reads = ConcurrentHashMap<String, AtomicInteger>()

        override fun getResourceAsStream(name: String): InputStream? {
            reads.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            val bytes = files[name] ?: return super.getResourceAsStream(name)
            return ByteArrayInputStream(bytes)
        }

        fun readsOf(internalName: String): Int = reads["$internalName.class"]?.get() ?: 0
    }

    private fun generate(
        name: String,
        superName: String,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, superName, null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private val files =
        mapOf(
            "cached/Base.class" to generate("cached/Base", "java/lang/Object"),
            "cached/A.class" to generate("cached/A", "cached/Base"),
            "cached/B.class" to generate("cached/B", "cached/Base"),
            "cached/C.class" to generate("cached/C", "cached/Base"),
        )

    private class Fixture(
        val instrumentation: OtherlodeInstrumentation,
        val transformer: ClassFileTransformer,
        val cache: ClassFileByteCache,
    )

    private fun fixture(): Fixture {
        val captured = mutableListOf<ClassFileTransformer>()
        val cache = ClassFileByteCache()
        val instrumentation =
            OtherlodeInstrumentation(
                AgentConfig.parse("includePackages=cached"),
                ProbeRegistry(),
                captureClassBytes = false,
                classFileCache = cache,
            )
        instrumentation.install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
        return Fixture(instrumentation, captured.single(), cache)
    }

    private fun Fixture.weave(
        loader: ClassLoader,
        name: String,
    ) = assertNotNull(transformer.transform(loader, "cached/$name", null, null, files.getValue("cached/$name.class")))

    @Test
    fun `a loader that wove since the previous check keeps its caches, and one that did not loses them`() {
        val fixture = fixture()
        val loader = CountingLoader(files)
        fixture.weave(loader, "A")
        assertTrue(fixture.instrumentation.cachedTableCount(loader) > 0, "the weave fills the table cache")
        assertTrue(fixture.cache.size > 0, "the weave fills the byte cache")

        fixture.instrumentation.releaseQuietCaches()
        assertTrue(fixture.instrumentation.cachedTableCount(loader) > 0)
        assertTrue(fixture.cache.size > 0)

        fixture.instrumentation.releaseQuietCaches()
        assertEquals(0, fixture.instrumentation.cachedTableCount(loader))
        assertEquals(0, fixture.cache.size)
    }

    @Test
    fun `a loader that wove between two checks keeps its entries while a quiet one loses them`() {
        val fixture = fixture()
        val quiet = CountingLoader(files)
        val busy = CountingLoader(files)
        fixture.weave(quiet, "A")
        fixture.weave(busy, "A")
        fixture.instrumentation.releaseQuietCaches()

        fixture.weave(busy, "B")
        fixture.instrumentation.releaseQuietCaches()

        assertEquals(0, fixture.instrumentation.cachedTableCount(quiet))
        assertTrue(fixture.instrumentation.cachedTableCount(busy) > 0)
        val quietBaseReads = quiet.readsOf("cached/Base")
        val busyBaseReads = busy.readsOf("cached/Base")
        fixture.weave(quiet, "B")
        fixture.weave(busy, "C")
        assertEquals(quietBaseReads + 1, quiet.readsOf("cached/Base"), "the released loader reads its supertype again")
        assertEquals(busyBaseReads, busy.readsOf("cached/Base"), "the kept loader does not")
    }

    @Test
    fun `a weave after the release refills the caches`() {
        val fixture = fixture()
        val loader = CountingLoader(files)
        fixture.weave(loader, "A")
        fixture.instrumentation.releaseQuietCaches()
        fixture.instrumentation.releaseQuietCaches()
        assertEquals(0, fixture.instrumentation.cachedTableCount(loader))

        fixture.weave(loader, "B")

        assertTrue(fixture.instrumentation.cachedTableCount(loader) > 0)
        assertTrue(fixture.cache.size > 0)
    }
}
