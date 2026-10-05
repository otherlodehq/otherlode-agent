package dev.otherlode.instrumentation.branch

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.commons.AnalyzerAdapter
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The frames [BranchProbeMethodVisitor] writes at the labels it inserts (ADR 0061): the one at the
 * continuation label defers to a frame the class file carries at the same offset, and a jump no
 * frame can be derived for fails the transform.
 */
class BranchProbeFramesTest {
    /** Records every frame and every instruction the visitor forwards, in order. */
    private class Recorder : MethodVisitor(Opcodes.ASM9) {
        val events = mutableListOf<String>()

        override fun visitFrame(
            type: Int,
            numLocal: Int,
            local: Array<out Any>?,
            numStack: Int,
            stack: Array<out Any>?,
        ) {
            events += "frame locals=${local?.take(numLocal)} stack=${stack?.take(numStack)}"
        }

        override fun visitInsn(opcode: Int) {
            events += "insn $opcode"
        }
    }

    /**
     * Feeds `static void m(int a, int b) { if (a == 0) goto end; [frame]; nop; end: return }` through an
     * analyser and the visitor, with the class file's own frame at the fall-through offset when
     * [ownFrameAtFallThrough] is given, and returns what reached the writer.
     */
    private fun drive(ownFrameAtFallThrough: Array<Any>?): List<String> {
        val recorder = Recorder()
        val visitor =
            BranchProbeMethodVisitor(recorder, FieldProbeArrayLoad("T"), 0, location = "T#m(II)V", allocateSlots = { 0 })
        val analyzer = AnalyzerAdapter("T", Opcodes.ACC_STATIC, "m", "(II)V", visitor)
        visitor.frameTracker = analyzer
        val end = Label()
        analyzer.visitCode()
        analyzer.visitVarInsn(Opcodes.ILOAD, 0)
        analyzer.visitJumpInsn(Opcodes.IFEQ, end)
        analyzer.visitLabel(Label())
        if (ownFrameAtFallThrough != null) {
            analyzer.visitFrame(Opcodes.F_NEW, ownFrameAtFallThrough.size, ownFrameAtFallThrough, 0, emptyArray())
        }
        analyzer.visitInsn(Opcodes.NOP)
        analyzer.visitLabel(end)
        analyzer.visitFrame(Opcodes.F_NEW, 2, arrayOf<Any>(Opcodes.INTEGER, Opcodes.INTEGER), 0, emptyArray())
        analyzer.visitInsn(Opcodes.RETURN)
        analyzer.visitMaxs(2, 2)
        return recorder.events
    }

    private val ints = "frame locals=[${Opcodes.INTEGER}, ${Opcodes.INTEGER}] stack=[]"

    @Test
    fun `a frame the class file carries at the fall-through offset replaces the inserted one`() {
        val events = drive(arrayOf(Opcodes.INTEGER, Opcodes.TOP))

        val beforeNop = events.subList(0, events.indexOf("insn ${Opcodes.NOP}"))
        val framesBeforeNop = beforeNop.filter { it.startsWith("frame") }
        assertEquals(
            listOf(ints, "frame locals=[${Opcodes.INTEGER}, ${Opcodes.TOP}] stack=[]"),
            framesBeforeNop,
            "the taken edge's frame, then the class file's frame, and no inserted frame between the two edges' joins",
        )
    }

    @Test
    fun `with no frame at the fall-through offset the inserted one is written before the next instruction`() {
        val events = drive(null)

        val beforeNop = events.subList(0, events.indexOf("insn ${Opcodes.NOP}"))
        assertEquals(listOf(ints, ints), beforeNop.filter { it.startsWith("frame") })
        assertEquals(ints, beforeNop.last(), "the inserted frame sits directly before the instruction it covers")
    }

    private class ResourceLoader(
        parent: ClassLoader,
        private val resources: Map<String, ByteArray>,
    ) : ClassLoader(parent) {
        override fun getResourceAsStream(name: String): InputStream? =
            resources[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    /** `static int m(int a)` whose only jump sits after an unconditional return, in code no frame reaches. */
    private fun unreachableJump(name: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Dead.java", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "(I)I", null, null)
        mv.visitCode()
        val first = Label()
        mv.visitLabel(first)
        mv.visitLineNumber(3, first)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        val dead = Label()
        mv.visitLabel(dead)
        mv.visitLineNumber(4, dead)
        val target = Label()
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitJumpInsn(Opcodes.IFEQ, target)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(target)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.ICONST_2)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `a jump no frame can be derived for fails the transform and the class is skipped with that reason`() {
        val name = "com/example/deadcode/Dead"
        val original = unreachableJump(name)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf("com.example.deadcode"), registry)
        val loader = ResourceLoader(BranchProbeFramesTest::class.java.classLoader, mapOf(name to original))

        // Called directly, ByteBuddy's transformer rethrows what the JVM would swallow, leaving the class as it arrived.
        val failure = assertFailsWith<IllegalStateException> { transformer.transform(loader, name, null, null, original) }
        val cause = generateSequence<Throwable>(failure) { it.cause }.last()
        assertTrue("no frame can be derived" in cause.message.orEmpty(), cause.message)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        assertTrue(manifest.probes.none { it.className == "com.example.deadcode.Dead" }, "no probe is published for it")
        val skipped = manifest.skippedClasses.single { it.className == "com.example.deadcode.Dead" }
        assertTrue("no frame can be derived for a jump or switch in com.example.deadcode.Dead#m(I)I" in skipped.reason, skipped.reason)
    }
}
