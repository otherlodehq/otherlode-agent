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
 * Class-file reads that go through the loader when classes are woven through the real transformer.
 * The classes are generated, so the loader's read counts are the whole story: a supertype shared by
 * two woven classes is read once, and each class's own file is still read for its own analysis.
 */
class CachedClassFileReadsTest {
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

    private fun transformerOver(): ClassFileTransformer {
        val captured = mutableListOf<ClassFileTransformer>()
        OtherlodeInstrumentation(AgentConfig.parse("includePackages=cached"), ProbeRegistry(), captureClassBytes = false)
            .install(HotPathWeaver.capturing(ByteBuddyAgent.install(), captured))
        return captured.single()
    }

    @Test
    fun `two woven classes sharing a supertype read its class file through the loader once`() {
        val files =
            mapOf(
                "cached/Base.class" to generate("cached/Base", "java/lang/Object"),
                "cached/A.class" to generate("cached/A", "cached/Base"),
                "cached/B.class" to generate("cached/B", "cached/Base"),
            )
        val loader = CountingLoader(files)
        val transformer = transformerOver()

        assertNotNull(transformer.transform(loader, "cached/A", null, null, files.getValue("cached/A.class")))
        assertNotNull(transformer.transform(loader, "cached/B", null, null, files.getValue("cached/B.class")))

        assertEquals(1, loader.readsOf("cached/Base"), "${loader.reads}")
        assertTrue(loader.readsOf("cached/A") >= 1, "A's own class file is read for its own analysis")
        assertTrue(loader.readsOf("cached/B") >= 1, "B's own class file is read for its own analysis")
    }
}
