package dev.otherlode.instrumentation

import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.SizeGuard
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The entry and omission probes are ASM instructions at a method's first instruction. These tests
 * count real calls through woven `$default` shapes at the versions where the array is reached
 * differently, and read the woven instructions of an entry probe.
 */
class MethodProbeWeavingTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    private companion object {
        /** Parameters 0 to 30: the analysis reads a mask test's constant as a positive power of two, which leaves bit 31 out. */
        const val COUNTED = 31

        /** The load, the slot, `dup2`, `laload`, `lconst_1`, `ladd` and `lastore`. */
        const val PROBE_INSTRUCTIONS = 7
    }

    private enum class Shape { MEMBER, STATIC, SUSPEND }

    private class Loader(
        parent: ClassLoader,
        private val resources: Map<String, ByteArray>,
        val definitions: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val bytes = definitions[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            resources[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    private class Woven(
        val name: String,
        val bytes: ByteArray,
        val original: ByteArray,
        val loader: Loader,
        val registry: ProbeRegistry,
    )

    private fun weave(
        internalName: String,
        original: ByteArray,
    ): Woven {
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(VersionedFixtures.PACKAGE), registry)
        val loader = Loader(javaClass.classLoader, mapOf(internalName to original))
        val bytes = checkNotNull(transformer.transform(loader, internalName, null, null, original)) { "$internalName was not woven" }
        loader.definitions[internalName] = bytes
        return Woven(internalName.replace('/', '.'), bytes, original, loader, registry)
    }

    private fun line(
        visitor: MethodVisitor,
        number: Int,
    ) {
        val label = Label()
        visitor.visitLabel(label)
        visitor.visitLineNumber(number, label)
    }

    private fun push(
        visitor: MethodVisitor,
        value: Int,
    ) {
        if (value in -1..5) visitor.visitInsn(Opcodes.ICONST_0 + value) else visitor.visitLdcInsn(value)
    }

    /**
     * A class with `f`, taking [optional] int parameters, and the `f$default` kotlinc writes for it:
     * a mask test and a store of the default for each parameter, then a call to `f`. A [Shape.SUSPEND]
     * target takes a trailing `Continuation` that the mask follows.
     */
    private fun defaultsFixture(
        version: Int,
        shape: Shape,
        optional: Int,
    ): Pair<String, ByteArray> {
        val name = "${VersionedFixtures.PACKAGE.replace('.', '/')}/omission/V${version}${shape.name}$optional"
        val ints = "I".repeat(optional)
        val continuation = if (shape == Shape.SUSPEND) "Lkotlin/coroutines/Continuation;" else ""
        val result = if (shape == Shape.SUSPEND) "Ljava/lang/Object;" else "I"
        val targetDescriptor = "($ints$continuation)$result"
        val masks = (optional + 31) / 32
        val receiver = if (shape == Shape.MEMBER) "L$name;" else ""
        val defaultDescriptor = "($receiver$ints$continuation${"I".repeat(masks)}Ljava/lang/Object;)$result"
        val targetAccess = Opcodes.ACC_PUBLIC or if (shape == Shape.MEMBER) 0 else Opcodes.ACC_STATIC

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(version, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Omission.kt", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(targetAccess, "f", targetDescriptor, null, null).apply {
            visitCode()
            line(this, 5)
            if (shape == Shape.SUSPEND) {
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
            } else {
                push(this, 0)
                visitInsn(Opcodes.IRETURN)
            }
            visitMaxs(0, 0)
            visitEnd()
        }
        val defaultAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        cw.visitMethod(defaultAccess, "f\$default", defaultDescriptor, null, null).apply {
            visitCode()
            line(this, 7)
            val first = if (shape == Shape.MEMBER) 1 else 0
            val continuationSlots = if (shape == Shape.SUSPEND) 1 else 0
            val maskSlot = first + optional + continuationSlots
            for (bit in 0 until optional) {
                val skip = Label()
                visitVarInsn(Opcodes.ILOAD, maskSlot + bit / 32)
                push(this, 1 shl (bit % 32))
                visitInsn(Opcodes.IAND)
                visitJumpInsn(Opcodes.IFEQ, skip)
                push(this, 0)
                visitVarInsn(Opcodes.ISTORE, first + bit)
                visitLabel(skip)
            }
            if (shape == Shape.MEMBER) visitVarInsn(Opcodes.ALOAD, 0)
            for (slot in first until first + optional) visitVarInsn(Opcodes.ILOAD, slot)
            if (shape == Shape.SUSPEND) visitVarInsn(Opcodes.ALOAD, first + optional)
            val invoke = if (shape == Shape.MEMBER) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC
            visitMethodInsn(invoke, name, "f", targetDescriptor, false)
            visitInsn(if (shape == Shape.SUSPEND) Opcodes.ARETURN else Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return name to cw.toByteArray()
    }

    private fun omissionCounts(woven: Woven): Map<Int, Long> {
        val probes =
            woven.registry
                .manifest(resource)
                .probes
                .filter { it.className == woven.name && it.kind == ProbeKind.OPTIONAL_ARGUMENT }
        val hits =
            woven.registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return probes.associate { checkNotNull(it.parameterIndex) to (hits[it.classId to it.probeIndex] ?: 0L) }
    }

    private fun call(
        woven: Woven,
        shape: Shape,
        optional: Int,
        masks: List<Int>,
    ) {
        val type = Class.forName(woven.name, true, woven.loader)
        val maskCount = (optional + 31) / 32
        val parameterTypes =
            buildList {
                if (shape == Shape.MEMBER) add(type)
                repeat(optional) { add(Int::class.javaPrimitiveType) }
                if (shape == Shape.SUSPEND) add(Class.forName("kotlin.coroutines.Continuation"))
                repeat(maskCount) { add(Int::class.javaPrimitiveType) }
                add(Any::class.java)
            }
        val method = type.getDeclaredMethod("f\$default", *parameterTypes.toTypedArray())
        val receiver = if (shape == Shape.MEMBER) type.getDeclaredConstructor().newInstance() else null
        for (mask in masks) {
            val arguments =
                buildList<Any?> {
                    if (shape == Shape.MEMBER) add(receiver)
                    repeat(optional) { add(7) }
                    if (shape == Shape.SUSPEND) add(null)
                    add(mask)
                    repeat(maskCount - 1) { add(1) }
                    add(null)
                }
            method.invoke(null, *arguments.toTypedArray())
        }
    }

    @Test
    fun `an omission probe counts a parameter left out and not one supplied, in both array forms`() {
        for (version in listOf(Opcodes.V1_8, Opcodes.V11, Opcodes.V17)) {
            for (shape in Shape.entries) {
                for (optional in listOf(1, 2, 31, 33)) {
                    val label = "v$version $shape $optional"
                    val (name, bytes) = defaultsFixture(version, shape, optional)
                    val woven = weave(name, bytes)
                    val top = minOf(optional, COUNTED) - 1
                    // The first call omits only parameter 0, the second every parameter, the third none.
                    call(woven, shape, optional, listOf(1, -1, 0))
                    val counts = omissionCounts(woven)
                    assertEquals((0..top).toSet(), counts.keys, "$label: one probe per optional parameter the analysis counts")
                    assertEquals(2L, counts[0], "$label: parameter 0 is omitted by two calls")
                    for (index in 1..top) assertEquals(1L, counts[index], "$label: parameter $index is omitted by one call")
                }
            }
        }
    }

    /** What the visitor saw of one method's code, in order. */
    private class Trace(
        val instructions: MutableList<String> = mutableListOf(),
        var frames: Int = 0,
        var labels: Int = 0,
        var jumps: Int = 0,
        var firstLabelAfter: Int = -1,
        var firstFrameAfter: Int = -1,
    )

    private fun traceOf(
        bytes: ByteArray,
        method: String,
    ): Trace {
        val trace = Trace()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != method) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitInsn(opcode: Int) {
                            trace.instructions += "insn $opcode"
                        }

                        override fun visitIntInsn(
                            opcode: Int,
                            operand: Int,
                        ) {
                            trace.instructions += "int $opcode"
                        }

                        override fun visitLdcInsn(value: Any?) {
                            trace.instructions += "ldc ${value?.javaClass?.simpleName}"
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            trace.instructions += "invoke $name"
                        }

                        override fun visitJumpInsn(
                            opcode: Int,
                            label: Label,
                        ) {
                            trace.jumps++
                        }

                        override fun visitLabel(label: Label) {
                            trace.labels++
                            if (trace.firstLabelAfter < 0) trace.firstLabelAfter = trace.instructions.size
                        }

                        override fun visitFrame(
                            type: Int,
                            numLocal: Int,
                            local: Array<out Any>?,
                            numStack: Int,
                            stack: Array<out Any>?,
                        ) {
                            trace.frames++
                            if (trace.firstFrameAfter < 0) trace.firstFrameAfter = trace.instructions.size
                        }
                    }
                }
            },
            0,
        )
        return trace
    }

    @Test
    fun `an entry probe is the increment alone with no label, jump, frame or nop`() {
        for (version in listOf(50, 52, 55, 61)) {
            val fixture = VersionedFixtures.straight(version)
            val woven = weave(fixture.internalName, fixture.bytes)
            val trace = traceOf(woven.bytes, "seven")
            val load =
                if (version >=
                    ProbeArrayForm.DYNAMIC_CONSTANT_VERSION
                ) {
                    "ldc ConstantDynamic"
                } else {
                    "invoke ${ProbeArrayForm.PROBE_ARRAY_ACCESSOR}"
                }
            val body = trace.instructions
            val increment = listOf(Opcodes.DUP2, Opcodes.LALOAD, Opcodes.LCONST_1, Opcodes.LADD, Opcodes.LASTORE).map { "insn $it" }
            assertEquals(load, body.first(), "v$version")
            assertEquals(increment, body.subList(2, 7), "v$version: the slot is pushed, then the increment")
            assertEquals(listOf("int ${Opcodes.BIPUSH}", "insn ${Opcodes.IRETURN}"), body.drop(7), "v$version: the original body follows")
            assertEquals(1, trace.labels, "v$version: only the original line label")
            assertEquals(PROBE_INSTRUCTIONS, trace.firstLabelAfter, "v$version: the original label follows the probe")
            assertEquals(0, trace.jumps, "v$version")
            assertEquals(0, trace.frames, "v$version")
            val grown =
                SizeGuard.codeLengths(woven.bytes).getValue("seven" to "()I") -
                    SizeGuard.codeLengths(woven.original).getValue("seven" to "()I")
            assertEquals(if (version >= ProbeArrayForm.DYNAMIC_CONSTANT_VERSION) 8 else 9, grown, "v$version")
            assertTrue(grown <= SizeGuard.ENTRY_PROBE, "v$version: the size guard's bound holds")
        }
    }

    /** `static int countdown(int n)`: its first instruction is the target of the loop's backward jump. */
    private fun countdown(version: Int): Pair<String, ByteArray> {
        val name = "${VersionedFixtures.PACKAGE.replace('.', '/')}/omission/Countdown$version"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(version, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        cw.visitSource("Countdown.java", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "countdown", "(I)I", null, null).apply {
            visitCode()
            val top = Label()
            visitLabel(top)
            visitLineNumber(3, top)
            visitIincInsn(0, -1)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitJumpInsn(Opcodes.IFGT, top)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return name to cw.toByteArray()
    }

    @Test
    fun `a method whose first instruction is a jump target keeps its frame and counts once per call`() {
        for (version in listOf(Opcodes.V1_8, Opcodes.V11, Opcodes.V17)) {
            val (name, bytes) = countdown(version)
            val woven = weave(name, bytes)
            val trace = traceOf(woven.bytes, "countdown")
            assertEquals(
                PROBE_INSTRUCTIONS,
                trace.firstFrameAfter,
                "v$version: the loop head's own frame follows the probe, with none between",
            )
            val type = Class.forName(woven.name, true, woven.loader)
            val countdown = type.getMethod("countdown", Int::class.javaPrimitiveType)
            assertEquals(0, countdown.invoke(null, 3), "v$version")
            assertEquals(0, countdown.invoke(null, 5), "v$version")
            val probe =
                woven.registry
                    .manifest(resource)
                    .probes
                    .single { it.className == woven.name && it.kind == ProbeKind.METHOD }
            val hits =
                woven.registry.computeDeltaBatch(resource).batch.deltas.single {
                    it.classId == probe.classId &&
                        it.probeIndex == probe.probeIndex
                }
            assertEquals(2L, hits.hitsTotal, "v$version: one count per call, not per iteration")
        }
    }
}
