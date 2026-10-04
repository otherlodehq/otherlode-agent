package dev.otherlode.instrumentation

import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.branch.SitePairing
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubroutineInlinerTest {
    private val subroutineClass = VersionedFixtures.subroutine(49).bytes
    private val method = "f" to "(I)I"

    @Test
    fun `a class below version 51 with a subroutine comes back without jsr or ret`() {
        assertTrue(subroutineInstructions(subroutineClass) > 0, "the fixture holds subroutines")

        val inlined = SubroutineInliner.inline(subroutineClass)

        assertEquals(0, subroutineInstructions(inlined))
    }

    @Test
    fun `a class below version 51 without a subroutine keeps its bytes`() {
        val plain = VersionedFixtures.plain(48).bytes

        assertSame(plain, SubroutineInliner.inline(plain))
    }

    @Test
    fun `a class from version 51 keeps its bytes`() {
        val plain = VersionedFixtures.plain(52).bytes

        assertSame(plain, SubroutineInliner.inline(plain))
    }

    @Test
    fun `inlining twice gives the bytes inlining once did`() {
        val once = SubroutineInliner.inline(subroutineClass)

        assertContentEquals(once, SubroutineInliner.inline(once))
    }

    @Test
    fun `the analysis counts the subroutine's branch once for each place it was called from`() {
        val analysis = analyse(SubroutineInliner.inline(subroutineClass))

        val sites = analysis.sites.filter { it.methodName == "f" }
        assertEquals(3, sites.size, "the branch outside the subroutine, and two copies of the one inside it")
    }

    @Test
    fun `an inlined class file pairs with received bytes that differ from it but hold the same instructions`() {
        val classFile = SubroutineInliner.inline(subroutineClass)
        val received = SubroutineInliner.inline(withOtherSourceName(subroutineClass))
        assertFalse(classFile.contentEquals(received), "the received bytes differ from the class file")

        val pairing = SitePairing.of(classFile, received, listOf(method))

        assertTrue(pairing.isPaired(method.first, method.second))
        assertTrue(pairing.swappedOrdinalsOf(method.first, method.second).isEmpty())
    }

    @Test
    fun `bytes with their subroutines still in them do not pair with the inlined class file`() {
        val classFile = SubroutineInliner.inline(subroutineClass)

        val pairing = SitePairing.of(classFile, subroutineClass, listOf(method))

        assertFalse(pairing.isPaired(method.first, method.second), "which is why both sides are inlined before they are paired")
    }

    @Test
    fun `the wrapper inlines the subroutines of a class it rewrites below version 51`() {
        assertNotEquals(0, subroutineInstructions(subroutineClass))

        assertEquals(0, subroutineInstructions(rewrittenThroughWrapper(subroutineClass)))
    }

    @Test
    fun `the wrapper and the byte rewrite leave the same instructions`() {
        val byBytes = SubroutineInliner.inline(subroutineClass)
        val byWrapper = rewrittenThroughWrapper(subroutineClass)

        assertContentEquals(instructionsOf(byBytes), instructionsOf(byWrapper))
    }

    /** The wrapper never touches the implementation context, so a stand-in that answers nothing will do. */
    private val unusedContext =
        java.lang.reflect.Proxy.newProxyInstance(
            Implementation.Context::class.java.classLoader,
            arrayOf(Implementation.Context::class.java),
        ) { _, _, _ -> null } as Implementation.Context

    private fun analyse(bytes: ByteArray): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            bytes,
            { null },
            listOf(VersionedFixtures.PACKAGE),
            emptyList(),
            BranchSiteAnalyzer.CrossClassTableCache(16),
        ) { _, _ -> true }

    /** The class run through the wrapper's visitor into a writer, as ByteBuddy runs it. */
    private fun rewrittenThroughWrapper(bytes: ByteArray): ByteArray {
        val writer =
            net.bytebuddy.jar.asm
                .ClassWriter(0)
        val visitor =
            SubroutineInliner.wrapper().wrap(
                TypeDescription.ForLoadedType.of(Any::class.java),
                writer,
                unusedContext,
                TypePool.Empty.INSTANCE,
                FieldList.Empty<FieldDescription.InDefinedShape>(),
                MethodList.Empty<MethodDescription>(),
                0,
                0,
            )
        ClassReader(bytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** [bytes] with another source file name, so its bytes differ and its code does not. */
    private fun withOtherSourceName(bytes: ByteArray): ByteArray {
        val writer =
            net.bytebuddy.jar.asm
                .ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitSource(
                    source: String?,
                    debug: String?,
                ) = super.visitSource("Other.java", debug)
            },
            0,
        )
        return writer.toByteArray()
    }

    private fun subroutineInstructions(bytes: ByteArray): Int {
        var count = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitJumpInsn(
                            opcode: Int,
                            label: Label,
                        ) {
                            if (opcode == Opcodes.JSR) count++
                        }

                        override fun visitVarInsn(
                            opcode: Int,
                            varIndex: Int,
                        ) {
                            if (opcode == Opcodes.RET) count++
                        }
                    }
            },
            0,
        )
        return count
    }

    /** The opcodes of method `f`, in order. */
    private fun instructionsOf(bytes: ByteArray): IntArray {
        val opcodes = mutableListOf<Int>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != "f") return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitInsn(opcode: Int) {
                            opcodes += opcode
                        }

                        override fun visitJumpInsn(
                            opcode: Int,
                            label: Label,
                        ) {
                            opcodes += opcode
                        }

                        override fun visitVarInsn(
                            opcode: Int,
                            varIndex: Int,
                        ) {
                            opcodes += opcode
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) {
                            opcodes += opcode
                        }
                    }
                }
            },
            0,
        )
        return opcodes.toIntArray()
    }
}
