package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.SimpleRemapper
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [SitePairing]: how a method's tracked instructions in the class file line up with the
 * received bytes an earlier transformer left, and when they do not.
 */
class SitePairingTest {
    private fun javaFixture(simpleName: String): ByteArray =
        File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

    private fun kotlinFixture(simpleName: String): ByteArray =
        File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    /** Every method the class declares, by name and descriptor, in class-file order. */
    private fun methodsOf(bytes: ByteArray): List<Pair<String, String>> {
        val methods = mutableListOf<Pair<String, String>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    methods += name to descriptor
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return methods
    }

    /** Each method's tracked opcodes, a switch as its own opcode, in encounter order. */
    private fun trackedOpcodes(bytes: ByteArray): Map<Pair<String, String>, List<Int>> {
        val result = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val opcodes = result.getOrPut(name to descriptor) { mutableListOf() }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitJumpInsn(
                            opcode: Int,
                            label: Label,
                        ) {
                            if (ConditionalJump.isTracked(opcode)) opcodes += opcode
                        }

                        override fun visitTableSwitchInsn(
                            min: Int,
                            max: Int,
                            dflt: Label,
                            vararg labels: Label,
                        ) {
                            opcodes += Opcodes.TABLESWITCH
                        }

                        override fun visitLookupSwitchInsn(
                            dflt: Label,
                            keys: IntArray,
                            labels: Array<out Label>,
                        ) {
                            opcodes += Opcodes.LOOKUPSWITCH
                        }
                    }
                }
            },
            0,
        )
        return result
    }

    /** [bytes] with [methodName]'s body passed through [rewrite], everything else unchanged. */
    private fun rewritingMethod(
        bytes: ByteArray,
        methodName: String,
        rewrite: (MethodVisitor) -> MethodVisitor,
    ): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return if (name == methodName) rewrite(delegate) else delegate
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    @Test
    fun `a class file paired with identical bytes pairs every method and swaps nothing`() {
        val bytes = javaFixture("ConditionJavaTarget")

        val pairing = SitePairing.of(bytes, bytes, methodsOf(bytes))

        assertEquals(emptySet(), pairing.unpairedMethods)
        methodsOf(bytes).forEach { (name, descriptor) -> assertEquals(emptySet(), pairing.swappedOrdinalsOf(name, descriptor)) }
    }

    @Test
    fun `JaCoCo's output pairs every method, with exactly the jumps it inverted swapped`() {
        for (fixture in listOf("ConditionJavaTarget", "RoutineJavaTarget", "SwitchJavaTarget")) {
            val classFile = javaFixture(fixture)
            assertJacocoPairs(classFile, "com/example/target/$fixture")
        }
        assertJacocoPairs(kotlinFixture("GuardTarget"), "com/example/target/GuardTarget")
    }

    private fun assertJacocoPairs(
        classFile: ByteArray,
        internalName: String,
    ) {
        val received = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(classFile, internalName)
        val methods = methodsOf(classFile)

        val pairing = SitePairing.of(classFile, received, methods)

        assertEquals(emptySet(), pairing.unpairedMethods, "$internalName: JaCoCo adds no conditional or switch inside a method")
        val fromClassFile = trackedOpcodes(classFile)
        val fromReceived = trackedOpcodes(received)
        var swaps = 0
        for ((name, descriptor) in methods) {
            val expected = fromClassFile.getValue(name to descriptor)
            val actual = fromReceived.getValue(name to descriptor)
            val inverted = expected.indices.filter { expected[it] != actual[it] }.toSet()
            inverted.forEach { assertEquals(SitePairing.inverse(expected[it]), actual[it], "$internalName#$name ordinal $it") }
            assertEquals(inverted, pairing.swappedOrdinalsOf(name, descriptor), "$internalName#$name$descriptor")
            swaps += inverted.size
        }
        assertTrue(swaps > 0, "$internalName: JaCoCo inverted at least one jump, so the swap is actually exercised")
    }

    @Test
    fun `an extra conditional in one method fails that method only`() {
        val classFile = javaFixture("BranchTarget")
        val writer = ClassWriter(0)
        val remapper = SimpleRemapper("com/example/target/BranchTargetWithExtraBranches", "com/example/target/BranchTarget")
        ClassReader(javaFixture("BranchTargetWithExtraBranches")).accept(ClassRemapper(writer, remapper), 0)

        val pairing = SitePairing.of(classFile, writer.toByteArray(), methodsOf(classFile))

        assertEquals(setOf("classify" to "(I)Ljava/lang/String;"), pairing.unpairedMethods)
        assertTrue(pairing.isPaired("classifyDense", "(I)I"))
        assertTrue(pairing.isPaired("classifySparse", "(I)I"))
    }

    @Test
    fun `a conditional replaced by an opcode that is neither the same nor its inverse fails the method`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingMethod(classFile, "classify") { delegate ->
                object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitJumpInsn(
                        opcode: Int,
                        label: Label,
                    ) {
                        // javac writes `value > 0` as IFLE; IFLT is neither IFLE nor IFGT.
                        super.visitJumpInsn(if (opcode == Opcodes.IFLE) Opcodes.IFLT else opcode, label)
                    }
                }
            }

        val pairing = SitePairing.of(classFile, received, methodsOf(classFile))

        assertEquals(setOf("classify" to "(I)Ljava/lang/String;"), pairing.unpairedMethods)
    }

    @Test
    fun `a switch with different keys fails the method`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingMethod(classFile, "classifySparse") { delegate ->
                object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitLookupSwitchInsn(
                        dflt: Label,
                        keys: IntArray,
                        labels: Array<out Label>,
                    ) {
                        super.visitLookupSwitchInsn(dflt, keys.map { if (it == 1000) 999 else it }.toIntArray(), labels)
                    }
                }
            }

        val pairing = SitePairing.of(classFile, received, methodsOf(classFile))

        assertEquals(setOf("classifySparse" to "(I)I"), pairing.unpairedMethods)
    }

    @Test
    fun `a table switch with different bounds fails the method`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingMethod(classFile, "classifyDense") { delegate ->
                object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitTableSwitchInsn(
                        min: Int,
                        max: Int,
                        dflt: Label,
                        vararg labels: Label,
                    ) {
                        super.visitTableSwitchInsn(min + 1, max + 1, dflt, *labels)
                    }
                }
            }

        val pairing = SitePairing.of(classFile, received, methodsOf(classFile))

        assertEquals(setOf("classifyDense" to "(I)I"), pairing.unpairedMethods)
    }

    @Test
    fun `a method the received bytes lack fails to pair`() {
        val classFile = javaFixture("BranchTarget")
        val writer = ClassWriter(0)
        ClassReader(classFile).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name == "classifyDense") null else super.visitMethod(access, name, descriptor, signature, exceptions)
            },
            0,
        )

        val pairing = SitePairing.of(classFile, writer.toByteArray(), methodsOf(classFile))

        assertEquals(setOf("classifyDense" to "(I)I"), pairing.unpairedMethods)
    }

    @Test
    fun `a method only the received bytes declare is not paired at all`() {
        val classFile = javaFixture("BranchTarget")
        val received = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(classFile, "com/example/target/BranchTarget")

        val pairing = SitePairing.of(classFile, received, methodsOf(classFile))

        assertEquals(emptySet(), pairing.unpairedMethods, "JaCoCo's \$jacocoInit is not one of the class file's methods")
    }

    /** [classFile]'s `BranchTarget` method [methodName] with each switch passed through [rewrite]. */
    private fun rewritingSwitches(
        classFile: ByteArray,
        methodName: String,
        table: (
            min: Int,
            max: Int,
            dflt: Label,
            labels: Array<out Label>,
            emit: (Int, Int, Label, Array<out Label>) -> Unit,
        ) -> Unit = { min, max, dflt, labels, emit ->
            emit(min, max, dflt, labels)
        },
        lookup: (
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
            emit: (Label, IntArray, Array<out Label>) -> Unit,
        ) -> Unit = { dflt, keys, labels, emit ->
            emit(dflt, keys, labels)
        },
    ): ByteArray =
        rewritingMethod(classFile, methodName) { delegate ->
            object : MethodVisitor(Opcodes.ASM9, delegate) {
                override fun visitTableSwitchInsn(
                    min: Int,
                    max: Int,
                    dflt: Label,
                    vararg labels: Label,
                ) = table(min, max, dflt, labels) { a, b, d, l -> super.visitTableSwitchInsn(a, b, d, *l) }

                override fun visitLookupSwitchInsn(
                    dflt: Label,
                    keys: IntArray,
                    labels: Array<out Label>,
                ) = lookup(dflt, keys, labels) { d, k, l -> super.visitLookupSwitchInsn(d, k, l) }
            }
        }

    /**
     * Pairing against the class file's tracked instructions stored by [SitePairing.encodedSequences]
     * gives what pairing against the class file itself gives, for [received].
     */
    private fun assertStoredPairsLikeClassFile(
        classFile: ByteArray,
        received: ByteArray,
    ): SitePairing {
        val methods = methodsOf(classFile)
        val direct = SitePairing.of(classFile, received, methods)
        val stored = SitePairing.ofStored(SitePairing.encodedSequences(classFile, methods), received)
        assertEquals(direct.unpairedMethods, stored.unpairedMethods)
        for ((name, descriptor) in methods) {
            assertEquals(direct.swappedOrdinalsOf(name, descriptor), stored.swappedOrdinalsOf(name, descriptor), "$name$descriptor")
        }
        return stored
    }

    @Test
    fun `stored instructions pair a table switch with an entry retargeted to the default as the class file does`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingSwitches(classFile, "classifyDense", table = { min, max, dflt, labels, emit ->
                emit(min, max, dflt, labels.mapIndexed { index, label -> if (index == 1) dflt else label }.toTypedArray())
            })

        val stored = assertStoredPairsLikeClassFile(classFile, received)

        assertEquals(setOf("classifyDense" to "(I)I"), stored.unpairedMethods)
    }

    @Test
    fun `stored instructions pair a lookup switch with an entry retargeted to the default as the class file does`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingSwitches(classFile, "classifySparse", lookup = { dflt, keys, labels, emit ->
                emit(dflt, keys, labels.mapIndexed { index, label -> if (index == 1) dflt else label }.toTypedArray())
            })

        val stored = assertStoredPairsLikeClassFile(classFile, received)

        assertEquals(setOf("classifySparse" to "(I)I"), stored.unpairedMethods)
    }

    @Test
    fun `stored instructions pair a lookup switch with a changed key as the class file does`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingSwitches(classFile, "classifySparse", lookup = { dflt, keys, labels, emit ->
                emit(dflt, keys.map { if (it == 1000) 999 else it }.toIntArray(), labels)
            })

        val stored = assertStoredPairsLikeClassFile(classFile, received)

        assertEquals(setOf("classifySparse" to "(I)I"), stored.unpairedMethods)
    }

    @Test
    fun `stored instructions pair a table switch with changed bounds as the class file does`() {
        val classFile = javaFixture("BranchTarget")
        val received =
            rewritingSwitches(classFile, "classifyDense", table = {
                min,
                max,
                dflt,
                labels,
                emit,
                ->
                emit(min + 1, max + 1, dflt, labels)
            })

        val stored = assertStoredPairsLikeClassFile(classFile, received)

        assertEquals(setOf("classifyDense" to "(I)I"), stored.unpairedMethods)
    }

    @Test
    fun `stored instructions pair identical bytes and JaCoCo's output as the class file does`() {
        val classFile = javaFixture("BranchTarget")

        assertEquals(emptySet(), assertStoredPairsLikeClassFile(classFile, classFile).unpairedMethods)
        val jacoco = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(classFile, "com/example/target/BranchTarget")
        assertEquals(emptySet(), assertStoredPairsLikeClassFile(classFile, jacoco).unpairedMethods)
    }

    @Test
    fun `with no received bytes every stored method is unpaired`() {
        val classFile = javaFixture("BranchTarget")
        val stored = SitePairing.encodedSequences(classFile, methodsOf(classFile))

        assertEquals(stored.keys, SitePairing.ofStored(stored, null).unpairedMethods)
    }
}
