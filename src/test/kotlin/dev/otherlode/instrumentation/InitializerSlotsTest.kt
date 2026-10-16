package dev.otherlode.instrumentation

import dev.otherlode.ClassFileSupport
import dev.otherlode.benchmark.CodeSizeLimitsTest.Companion.codeLengths
import dev.otherlode.benchmark.HotPathWeaver
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.BranchProbeAsmVisitorWrapper
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `<clinit>` the class file declares gets a returned slot and a threw slot beside its entry
 * probe (ADR 0071). Each test weaves a small ASM class and loads it woven and unwoven.
 */
class InitializerSlotsTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    private enum class Body { RETURNS, MULTI_RETURN, THROWS_RUNTIME, THROWS_ERROR, CATCHES_INTERNALLY, BLOCKS }

    private class Shape(
        val version: Int,
        val body: Body,
        val frames: Boolean = true,
        val withBranch: Boolean = false,
        val asInterface: Boolean = false,
    ) {
        val label get() = "v$version/${body.name}/frames=$frames/branch=$withBranch/interface=$asInterface"
        val internalName get() =
            VersionedFixtures.internalName(
                version,
                "Init${body.name}${if (asInterface) "Itf" else ""}${if (frames) "" else "NoFrames"}",
            )
    }

    private class Loader(
        parent: ClassLoader,
        private val resources: Map<String, ByteArray>,
        val definitions: MutableMap<String, ByteArray>,
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val bytes = definitions[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            resources[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    private class Run(
        val name: String,
        val woven: ByteArray,
        val original: ByteArray,
        val wovenLoader: Loader,
        val plainLoader: Loader,
        val registry: ProbeRegistry,
    )

    private fun build(shape: Shape): ByteArray {
        val flags = if (shape.version >= 50 && shape.frames) ClassWriter.COMPUTE_FRAMES else ClassWriter.COMPUTE_MAXS
        val cw = ClassWriter(flags)
        val name = shape.internalName
        val access =
            if (shape.asInterface) {
                Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
            } else {
                Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
            }
        cw.visit(shape.version, access, name, null, "java/lang/Object", null)
        cw.visitSource("Init.java", null)
        val fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or (if (shape.asInterface) Opcodes.ACC_FINAL else 0)
        cw.visitField(fieldAccess, "counter", "I", null, null).visitEnd()
        if (shape.withBranch && !shape.asInterface) {
            val pick = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "pick", "(I)I", null, null)
            val negative = Label()
            pick.visitCode()
            val start = Label()
            pick.visitLabel(start)
            pick.visitLineNumber(20, start)
            pick.visitVarInsn(Opcodes.ILOAD, 0)
            pick.visitJumpInsn(Opcodes.IFLE, negative)
            pick.visitInsn(Opcodes.ICONST_1)
            pick.visitInsn(Opcodes.IRETURN)
            pick.visitLabel(negative)
            pick.visitInsn(Opcodes.ICONST_M1)
            pick.visitInsn(Opcodes.IRETURN)
            pick.visitMaxs(0, 0)
            pick.visitEnd()
        }
        val mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        val first = Label()
        mv.visitLabel(first)
        mv.visitLineNumber(2, first)
        when (shape.body) {
            Body.RETURNS -> {
                mv.visitIntInsn(Opcodes.BIPUSH, 7)
                mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "counter", "I")
                mv.visitInsn(Opcodes.RETURN)
            }

            Body.MULTI_RETURN -> {
                val other = Label()
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitJumpInsn(Opcodes.IFEQ, other)
                mv.visitIntInsn(Opcodes.BIPUSH, 7)
                mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "counter", "I")
                mv.visitInsn(Opcodes.RETURN)
                mv.visitLabel(other)
                mv.visitInsn(Opcodes.RETURN)
            }

            Body.THROWS_RUNTIME -> {
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn("boom")
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException")
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn("inner")
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/IllegalStateException",
                    "<init>",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    false,
                )
                mv.visitInsn(Opcodes.ATHROW)
            }

            Body.THROWS_ERROR -> {
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/InternalError")
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn("fatal")
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/InternalError", "<init>", "(Ljava/lang/String;)V", false)
                mv.visitInsn(Opcodes.ATHROW)
            }

            Body.CATCHES_INTERNALLY -> {
                val start = Label()
                val end = Label()
                val handler = Label()
                val done = Label()
                mv.visitTryCatchBlock(start, end, handler, "java/lang/NumberFormatException")
                mv.visitLabel(start)
                mv.visitLdcInsn("not a number")
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false)
                mv.visitInsn(Opcodes.POP)
                mv.visitLabel(end)
                mv.visitJumpInsn(Opcodes.GOTO, done)
                mv.visitLabel(handler)
                mv.visitInsn(Opcodes.POP)
                mv.visitIntInsn(Opcodes.BIPUSH, 100)
                mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "counter", "I")
                mv.visitLabel(done)
                mv.visitInsn(Opcodes.RETURN)
            }

            Body.BLOCKS -> {
                val gate = "dev/otherlode/instrumentation/InitializerGate"
                mv.visitFieldInsn(Opcodes.GETSTATIC, gate, "entered", "Ljava/util/concurrent/CountDownLatch;")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CountDownLatch", "countDown", "()V", false)
                mv.visitFieldInsn(Opcodes.GETSTATIC, gate, "release", "Ljava/util/concurrent/CountDownLatch;")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CountDownLatch", "await", "()V", false)
                mv.visitInsn(Opcodes.RETURN)
            }
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun weave(shape: Shape): Run {
        val original = build(shape)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(VersionedFixtures.PACKAGE), registry)
        val resources = mapOf(shape.internalName to original)
        val plainLoader = Loader(javaClass.classLoader, resources, mutableMapOf(shape.internalName to original))
        val wovenLoader = Loader(javaClass.classLoader, resources, mutableMapOf())
        val woven =
            checkNotNull(transformer.transform(wovenLoader, shape.internalName, null, null, original)) { "${shape.label} was not woven" }
        wovenLoader.definitions[shape.internalName] = woven
        return Run(shape.internalName.replace('/', '.'), woven, original, wovenLoader, plainLoader, registry)
    }

    private fun slotCounts(run: Run): Map<ProbeKind, Long> {
        val probes =
            run.registry
                .manifest(resource)
                .probes
                .filter { it.className == run.name && it.methodName == "<clinit>" }
        val hits =
            run.registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return probes.groupBy { it.kind }.mapValues { (_, locations) -> locations.sumOf { hits[it.classId to it.probeIndex] ?: 0L } }
    }

    private fun slotsDeclared(run: Run): List<ProbeKind> =
        run.registry
            .manifest(resource)
            .probes
            .filter { it.className == run.name && it.methodName == "<clinit>" }
            .map { it.kind }

    private fun chain(t: Throwable?): List<String> = generateSequence(t) { it.cause }.map { "${it.javaClass.name}: ${it.message}" }.toList()

    private fun failure(
        loader: ClassLoader,
        name: String,
    ): List<List<String>> {
        val first = runCatching { Class.forName(name, true, loader) }.exceptionOrNull()
        val second = runCatching { Class.forName(name, true, loader) }.exceptionOrNull()
        return listOf(
            chain(first),
            listOf(
                "${second?.javaClass?.name}: ${second?.message}",
                second
                    ?.cause
                    ?.javaClass
                    ?.name
                    .toString(),
            ),
        )
    }

    private fun versions() = listOf(49, 52, 55, 61).filter { it < 55 || ClassFileSupport.canDefine(it) }

    private fun shapes(body: Body): List<Shape> =
        versions().flatMap { version ->
            listOf(false, true).map { branch -> Shape(version, body, withBranch = branch) }
        } + listOf(false, true).map { branch -> Shape(50, body, frames = true, withBranch = branch) } +
            listOf(false, true).map { branch -> Shape(50, body, frames = false, withBranch = branch) }

    @Test
    fun `an initialiser that throws a RuntimeException counts threw and fails as it does unwoven`() {
        for (shape in shapes(Body.THROWS_RUNTIME)) {
            val run = weave(shape)
            val woven = failure(run.wovenLoader, run.name)

            assertEquals(failure(run.plainLoader, run.name), woven, shape.label)
            assertTrue(woven[0].first().startsWith("java.lang.ExceptionInInitializerError"), shape.label)
            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 0L, ProbeKind.INITIALIZER_THREW to 1L),
                slotCounts(run),
                shape.label,
            )
        }
    }

    @Test
    fun `an initialiser that throws an Error counts threw and fails as it does unwoven`() {
        for (shape in shapes(Body.THROWS_ERROR)) {
            val run = weave(shape)
            val woven = failure(run.wovenLoader, run.name)

            assertEquals(failure(run.plainLoader, run.name), woven, shape.label)
            assertEquals("java.lang.InternalError: fatal", woven[0].single(), shape.label)
            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 0L, ProbeKind.INITIALIZER_THREW to 1L),
                slotCounts(run),
                shape.label,
            )
        }
    }

    @Test
    fun `an initialiser that returns counts returned and not threw`() {
        for (shape in shapes(Body.RETURNS)) {
            val run = weave(shape)

            val type = Class.forName(run.name, true, run.wovenLoader)

            assertEquals(7, type.getField("counter").get(null), shape.label)
            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 1L, ProbeKind.INITIALIZER_THREW to 0L),
                slotCounts(run),
                shape.label,
            )
        }
    }

    @Test
    fun `an interface below version 52 and one at it count returned`() {
        for (version in listOf(49, 50, 51, 52)) {
            val shape = Shape(version, Body.RETURNS, asInterface = true)
            val run = weave(shape)

            Class.forName(run.name, true, run.wovenLoader)

            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 1L, ProbeKind.INITIALIZER_THREW to 0L),
                slotCounts(run),
                shape.label,
            )
        }
    }

    @Test
    fun `an initialiser with several returns counts returned once`() {
        for (version in versions()) {
            val shape = Shape(version, Body.MULTI_RETURN, withBranch = true)
            val run = weave(shape)

            val type = Class.forName(run.name, true, run.wovenLoader)

            assertEquals(7, type.getField("counter").get(null), shape.label)
            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 1L, ProbeKind.INITIALIZER_THREW to 0L),
                slotCounts(run),
                shape.label,
            )
        }
    }

    @Test
    fun `an initialiser that catches its own exception does not count threw`() {
        for (shape in shapes(Body.CATCHES_INTERNALLY)) {
            val run = weave(shape)

            val type = Class.forName(run.name, true, run.wovenLoader)

            assertEquals(100, type.getField("counter").get(null), shape.label)
            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 1L, ProbeKind.INITIALIZER_THREW to 0L),
                slotCounts(run),
                shape.label,
            )
        }
    }

    @Test
    fun `an initialiser blocked on a latch counts neither slot until it returns`() {
        for (version in versions()) {
            InitializerGate.reset()
            val shape = Shape(version, Body.BLOCKS)
            val run = weave(shape)
            val loading = Thread { Class.forName(run.name, true, run.wovenLoader) }
            loading.start()
            try {
                assertTrue(InitializerGate.entered.await(10, TimeUnit.SECONDS), shape.label)

                assertEquals(
                    mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 0L, ProbeKind.INITIALIZER_THREW to 0L),
                    slotCounts(run),
                    "${shape.label} while blocked",
                )
            } finally {
                InitializerGate.release.countDown()
                loading.join(10_000)
            }

            assertEquals(
                mapOf(ProbeKind.METHOD to 1L, ProbeKind.INITIALIZER_RETURNED to 1L, ProbeKind.INITIALIZER_THREW to 0L),
                slotCounts(run),
                "${shape.label} after release",
            )
        }
    }

    @Test
    fun `the handler carries a frame exactly when the class carries frames`() {
        for (shape in shapes(Body.THROWS_RUNTIME)) {
            val run = weave(shape)
            val added = frameCount(run.woven) - frameCount(run.original)

            val carries = shape.version > 50 || (shape.version == 50 && BranchProbeAsmVisitorWrapper.carriesFrames(run.original))
            assertEquals(if (carries) 1 else 0, added, shape.label)
        }
    }

    @Test
    fun `a type initialiser grows by its entry, one increment per return and the handler`() {
        for (version in versions().filter { it >= 55 }) {
            for ((body, returns) in listOf(Body.RETURNS to 1, Body.MULTI_RETURN to 2)) {
                val run = weave(Shape(version, body))
                val grown = codeLengths(run.woven).getValue(CLINIT) - codeLengths(run.original).getValue(CLINIT)

                assertEquals(PROBE_BYTES * (returns + 2) + ATHROW_BYTES, grown, "v$version/$body")
            }
        }
    }

    private fun frameCount(bytes: ByteArray): Int {
        var frames = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != "<clinit>") return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitFrame(
                            type: Int,
                            numLocal: Int,
                            local: Array<out Any>?,
                            numStack: Int,
                            stack: Array<out Any>?,
                        ) {
                            frames++
                        }
                    }
                }
            },
            0,
        )
        return frames
    }

    @Test
    fun `the handler is the last entry of the exception table`() {
        val run = weave(Shape(52, Body.CATCHES_INTERNALLY))
        val types = mutableListOf<String?>()
        ClassReader(run.woven).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != "<clinit>") return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitTryCatchBlock(
                            start: Label,
                            end: Label,
                            handler: Label,
                            type: String?,
                        ) {
                            types += type
                        }
                    }
                }
            },
            0,
        )

        assertEquals(listOf("java/lang/NumberFormatException", null), types)
    }

    @Test
    fun `a class that declares no type initialiser gets no slots, and a type initialiser the agent adds gets none`() {
        for (version in listOf(52, 61)) {
            val fixture = VersionedFixtures.straight(version)
            val registry = ProbeRegistry()
            val transformer = HotPathWeaver.offlineTransformer(listOf(VersionedFixtures.PACKAGE), registry)
            val loader = Loader(javaClass.classLoader, mapOf(fixture.internalName to fixture.bytes), mutableMapOf())
            val woven = assertNotNull(transformer.transform(loader, fixture.internalName, null, null, fixture.bytes))

            val kinds =
                registry
                    .manifest(resource)
                    .probes
                    .filter { it.className == fixture.name }
                    .map { it.kind }

            assertFalse(ProbeKind.INITIALIZER_RETURNED in kinds || ProbeKind.INITIALIZER_THREW in kinds, "v$version: $kinds")
            var handlers = 0
            ClassReader(woven).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor =
                        object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitTryCatchBlock(
                                start: Label,
                                end: Label,
                                handler: Label,
                                type: String?,
                            ) {
                                handlers++
                            }
                        }
                },
                0,
            )
            assertEquals(0, handlers, "v$version")
        }
    }

    @Test
    fun `the slots sit after the initialiser's entry probe in the manifest`() {
        val run = weave(Shape(52, Body.RETURNS))

        assertEquals(listOf(ProbeKind.METHOD, ProbeKind.INITIALIZER_RETURNED, ProbeKind.INITIALIZER_THREW), slotsDeclared(run))
    }

    private companion object {
        const val CLINIT = "<clinit>()V"

        /** An increment in these fixtures: `ldc` of the array (2), `iconst_n` of the slot (1), DUP2 LALOAD LCONST_1 LADD LASTORE (5). */
        const val PROBE_BYTES = 8

        const val ATHROW_BYTES = 1
    }
}
