package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.RoutineKind
import dev.otherlode.export.UnreadShape
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the three outlines of ADR 0054 that no source produces: a jump or switch fed by the
 * coroutine state machine, the collision side of a string switch lowering, and a method of a
 * multi-file facade. Each is exercised on real compiler output with one instruction changed so that
 * the shape the agent reads no longer reads.
 */
class UnreadOutlineAnalysisTest {
    private fun kotlinBytes(simpleName: String): ByteArray =
        File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private fun javaBytes(simpleName: String): ByteArray = File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

    private val fixtureLookup: (String) -> ByteArray? = { internalName ->
        listOf("kotlin", "java")
            .map { File("build/classes/$it/test/$internalName.class") }
            .firstOrNull { it.isFile }
            ?.readBytes()
    }

    private fun analyze(
        bytes: ByteArray,
        methodName: String,
    ) = BranchSiteAnalyzer.analyze(bytes, fixtureLookup, includePackages = listOf("com.example")) { name, _ -> name == methodName }

    /** Each kept site's outcome families of [method], in site order. */
    private fun families(
        analysis: BranchSiteAnalyzer.Analysis,
        method: String,
    ): List<List<UnreadShape>> = kept(analysis, method).map { site -> site.outcomes.map { it.unreadShape } }

    private fun none(count: Int) = List(count) { UnreadShape.NONE }

    private fun machinery(count: Int) = List(count) { UnreadShape.COROUTINE_MACHINERY }

    private val facade = kotlinBytes("CoroutineTargetKt")

    // --- coroutine machinery ---

    @Test
    fun `real machinery is dropped and the adopter's conditional is plain`() {
        val analysis = analyze(facade, "twoPoints")

        assertEquals(listOf(none(2)), families(analysis, "twoPoints"))
        assertEquals(5, analysis.sites.count { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
    }

    @Test
    fun `a label switch separated from its getfield is kept and every outcome is an unread coroutine shape`() {
        val bytes = OutlineMutations.rewrite(facade, "twoPoints", OutlineMutations::nopBeforeSwitch)

        val analysis = analyze(bytes, "twoPoints")

        assertEquals(
            listOf(machinery(4), none(2)),
            families(analysis, "twoPoints"),
            "the switch's three cases and default, then the adopter's if",
        )
        assertEquals(4, analysis.sites.count { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
    }

    @Test
    fun `a re-entry test whose getfield is no longer adjacent is kept and unread`() {
        val bytes = OutlineMutations.rewrite(facade, "twoPoints", OutlineMutations::nopInReentryTest)

        val analysis = analyze(bytes, "twoPoints")

        assertEquals(listOf(machinery(2), none(2)), families(analysis, "twoPoints"))
    }

    @Test
    fun `a suspended-marker compare whose dup is no longer next to the call reads like an adopter's own compare and stays plain`() {
        val bytes = OutlineMutations.rewrite(facade, "handOff", OutlineMutations::nopBetweenDupAndMarker)

        val analysis = analyze(bytes, "handOff")

        // Without the dup beside it, the call is what `value === COROUTINE_SUSPENDED` compiles to,
        // which the adopter can write, so the compare is not taken for machinery.
        assertEquals(listOf(none(2), none(2), none(2)), families(analysis, "handOff"), "the adopter's if, then both compares")
    }

    @Test
    fun `a suspended-marker compare through its local, pushed out of the instructions the shape reads, is kept and unread`() {
        val bytes = OutlineMutations.rewrite(facade, "twoPoints", OutlineMutations::nopsBeforeReferenceCompare)

        val analysis = analyze(bytes, "twoPoints")

        assertEquals(listOf(machinery(2), none(2), machinery(2)), families(analysis, "twoPoints"))
    }

    @Test
    fun `a suspend lambda's label switch separated from its getfield is unread`() {
        val lambda = kotlinBytes("CoroutineTargetKt\$runLambda\$1")
        val bytes = OutlineMutations.rewrite(lambda, "invokeSuspend", OutlineMutations::nopBeforeSwitch)

        val analysis = analyze(bytes, "invokeSuspend")

        assertEquals(listOf(machinery(3), none(2)), families(analysis, "invokeSuspend"))
    }

    @Test
    fun `a continuation instanceof check that stops naming the continuation is an ordinary conditional`() {
        val bytes = OutlineMutations.rewrite(facade, "twoPoints", OutlineMutations::instanceOfObject)

        val analysis = analyze(bytes, "twoPoints")

        assertEquals(
            listOf(none(2), none(2)),
            families(analysis, "twoPoints"),
            "its operand comes from neither the label field nor the marker",
        )
    }

    private val fixtureFile = File("src/test/kotlin/com/example/target/CoroutineTarget.kt")

    private fun lineOf(marker: String): Int {
        val index = fixtureFile.readLines().indexOfFirst { it.contains("marker: $marker") }
        check(index >= 0) { "no line in $fixtureFile carries the marker $marker" }
        return index + 1
    }

    @Test
    fun `the adopter's own conditionals in suspend functions stay plain`() {
        val cases =
            mapOf(
                "noPoint" to listOf("noPoint-if"),
                "twoPoints" to listOf("twoPoints-if"),
                "handOff" to listOf("handOff-if"),
                "compareRefs" to listOf("compareRefs-compare"),
                "compareAfterMarker" to listOf("compareAfterMarker-compare"),
                "earlyHandOff" to listOf("earlyHandOff-if", "earlyHandOff-compare"),
            )
        for ((method, markers) in cases) {
            val sites = kept(analyze(facade, method), method).filter { it.site.line in markers.map(::lineOf) }
            assertEquals(markers.size, sites.size, method)
            assertTrue(sites.flatMap { it.outcomes }.all { it.unreadShape == UnreadShape.NONE }, method)
        }
    }

    @Test
    fun `an adopter's own compare with the public suspended marker stays the adopter's conditional`() {
        val analysis = analyze(facade, "adopterMarkerCompare")
        val sites = kept(analysis, "adopterMarkerCompare")

        assertTrue(sites.isNotEmpty())
        assertTrue(sites.flatMap { it.outcomes }.all { it.unreadShape == UnreadShape.NONE })
        assertEquals(0, analysis.sites.count { it.dropReason != null })
    }

    @Test
    fun `a same-shape switch in a method that is not suspend-shaped stays plain`() {
        assertEquals(listOf(none(4)), families(analyze(facade, "plainSwitchOnLabel"), "plainSwitchOnLabel"))
    }

    @Test
    fun `a switch on a nested class's label field in a suspend function stays plain`() {
        val bytes = kotlinBytes("NestedLabelOwner")

        assertEquals(listOf(none(4)), families(analyze(bytes, "pick"), "pick"))
    }

    @Test
    fun `an unread outcome is not routine and a routine outcome is not unread`() {
        val bytes = OutlineMutations.rewrite(facade, "twoPoints", OutlineMutations::nopBeforeSwitch)

        val outcomes = kept(analyze(bytes, "twoPoints"), "twoPoints").flatMap { it.outcomes }

        assertTrue(outcomes.filter { it.unreadShape != UnreadShape.NONE }.all { it.routine == RoutineKind.NONE })
        assertFailsWith<IllegalArgumentException> {
            BranchOutcome(
                0,
                dev.otherlode.export.BranchRole.TAKEN,
                routine = RoutineKind.NULL_DEFAULT,
                unreadShape = UnreadShape.COROUTINE_MACHINERY,
            )
        }
    }

    // --- string switch lowering ---

    private val switchTarget = kotlinBytes("SwitchTarget")

    @Test
    fun `a kotlinc string when that reads has no unread outcome`() {
        val analysis = analyze(switchTarget, "stringWhen")

        assertTrue(kept(analysis, "stringWhen").flatMap { it.outcomes }.all { it.unreadShape == UnreadShape.NONE })
    }

    @Test
    fun `an unread kotlinc string when marks the collision side of each bucket's last check and nothing else`() {
        val bytes = OutlineMutations.rewrite(switchTarget, "stringWhen", OutlineMutations::nopAfterFirstStore)

        val analysis = analyze(bytes, "stringWhen")

        val switch = UnreadShape.STRING_SWITCH
        val plain = UnreadShape.NONE
        assertEquals(
            listOf(
                List(5) { plain }, // the hash switch: four buckets and the default, a plain numeric site
                listOf(plain, plain), // Aa, whose not-equal side is the check for BB
                listOf(plain, switch), // BB, last in its bucket, ifne: the fall-through
                listOf(plain, switch), // closed
                listOf(plain, switch), // done
                listOf(switch, plain), // open, last in its bucket, ifeq: the jump
            ),
            families(analysis, "stringWhen"),
        )
        assertEquals(0, analysis.sites.count { it.dropReason == BranchDropReason.SWITCH_LOWERING })
    }

    @Test
    fun `a javac string switch that reads has no unread outcome`() {
        val analysis = analyze(javaBytes("SwitchJavaTarget"), "stringStatement")

        assertTrue(kept(analysis, "stringStatement").flatMap { it.outcomes }.all { it.unreadShape == UnreadShape.NONE })
    }

    @Test
    fun `an unread javac string switch marks the not-equal side of each bucket's last check`() {
        val bytes = OutlineMutations.rewrite(javaBytes("SwitchJavaTarget"), "stringStatement", OutlineMutations::nopAfterFirstStore)

        val analysis = analyze(bytes, "stringStatement")

        val switch = UnreadShape.STRING_SWITCH
        val plain = UnreadShape.NONE
        assertEquals(
            listOf(
                List(5) { plain }, // the hash switch
                listOf(switch, plain), // open
                listOf(switch, plain), // closed
                listOf(switch, plain), // done
                listOf(plain, plain), // BB, which is followed by the check for Aa
                listOf(switch, plain), // Aa, last in its bucket
                List(6) { plain }, // the index switch, a plain numeric site
            ),
            families(analysis, "stringStatement"),
        )
    }

    @Test
    fun `a hand-written switch on a hash code whose literals do not hash to the case keys is not marked`() {
        val bytes = OutlineMutations.rewrite(switchTarget, "stringWhen", OutlineMutations::nopAfterFirstStore)
        val mangled = replaceHashKey(bytes)

        val analysis = analyze(mangled, "stringWhen")

        assertTrue(kept(analysis, "stringWhen").flatMap { it.outcomes }.none { it.unreadShape != UnreadShape.NONE })
    }

    /** [bytes] with `stringWhen`'s hash switch keys shifted by one, which no string's hash code satisfies. */
    private fun replaceHashKey(bytes: ByteArray): ByteArray {
        val reader =
            net.bytebuddy.jar.asm
                .ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(
            object : net.bytebuddy.jar.asm.ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ) = super.visitMethod(access, name, descriptor, signature, exceptions)?.let { next ->
                    if (name != "stringWhen") {
                        next
                    } else {
                        object : net.bytebuddy.jar.asm.MethodVisitor(Opcodes.ASM9, next) {
                            override fun visitLookupSwitchInsn(
                                dflt: net.bytebuddy.jar.asm.Label,
                                keys: IntArray,
                                labels: Array<out net.bytebuddy.jar.asm.Label>,
                            ) {
                                val shifted = keys.map { it + 1 }
                                val order = shifted.indices.sortedBy { shifted[it] }
                                super.visitLookupSwitchInsn(
                                    dflt,
                                    order.map { shifted[it] }.toIntArray(),
                                    order.map { labels[it] }.toTypedArray(),
                                )
                            }
                        }
                    }
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    // --- multi-file facade ---

    private fun facadeWith(
        kind: Int = 4,
        body: (net.bytebuddy.jar.asm.MethodVisitor) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "com/example/target/AsmFacade", null, "java/lang/Object", null)
        writer.visitAnnotation("Lkotlin/Metadata;", true).apply {
            visit("k", kind)
            visitEnd()
        }
        val constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "f", "(I)I", null, null)
        mv.visitCode()
        body(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private val forwarder: (net.bytebuddy.jar.asm.MethodVisitor) -> Unit = { mv ->
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/target/AsmFacade__PartKt", "f", "(I)I", false)
        mv.visitInsn(Opcodes.IRETURN)
    }

    private val holdsCode: (net.bytebuddy.jar.asm.MethodVisitor) -> Unit = { mv ->
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitInsn(Opcodes.IRETURN)
    }

    @Test
    fun `a facade method that is not a forwarder is an unread shape, its constructor and a forwarder are not`() {
        val analysis = BranchSiteAnalyzer.analyze(facadeWith(body = holdsCode)) { _, _ -> true }

        assertEquals(UnreadShape.MULTIFILE_FACADE, analysis.unreadShape("f", "(I)I"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("f", "(I)I"))
        assertEquals(UnreadShape.NONE, analysis.unreadShape("<init>", "()V"))
        assertEquals(UnreadCause.UNREAD_STRUCTURE, analysis.unreadCause)
        assertEquals(null, analysis.unreadRelease)
    }

    @Test
    fun `a facade forwarder is generated and not unread`() {
        val analysis = BranchSiteAnalyzer.analyze(facadeWith(body = forwarder)) { _, _ -> true }

        assertEquals(GeneratedBy.MULTIFILE_FACADE, analysis.generatedBy("f", "(I)I"))
        assertEquals(UnreadShape.NONE, analysis.unreadShape("f", "(I)I"))
        assertEquals(null, analysis.unreadCause)
    }

    @Test
    fun `the same method outside a multi-file facade is the adopter's`() {
        val analysis = BranchSiteAnalyzer.analyze(facadeWith(kind = 2, body = holdsCode)) { _, _ -> true }

        assertEquals(UnreadShape.NONE, analysis.unreadShape("f", "(I)I"))
    }

    @Test
    fun `the real facade's forwarders stay marked and its default twin, which is synthetic, is neither marked nor unread`() {
        val analysis = BranchSiteAnalyzer.analyze(kotlinBytes("MultifileText")) { _, _ -> true }

        assertEquals(GeneratedBy.MULTIFILE_FACADE, analysis.generatedBy("multifileGreeting", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(UnreadShape.NONE, analysis.unreadShape("multifileGreeting", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(
            UnreadShape.NONE,
            analysis.unreadShape("multifileFarewell\$default", "(Ljava/lang/String;IILjava/lang/Object;)Ljava/lang/String;"),
        )
    }

    @Test
    fun `a branch inside an unread facade method is a site of that method`() {
        val bytes =
            facadeWith { mv ->
                val end =
                    net.bytebuddy.jar.asm
                        .Label()
                mv.visitVarInsn(Opcodes.ILOAD, 0)
                mv.visitJumpInsn(Opcodes.IFEQ, end)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitLabel(end)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitInsn(Opcodes.IRETURN)
            }

        val analysis = BranchSiteAnalyzer.analyze(bytes) { name, _ -> name != "<init>" }

        assertEquals(UnreadShape.MULTIFILE_FACADE, analysis.unreadShape("f", "(I)I"))
        assertEquals(1, analysis.sites.size, "the branch is probed, and the registry gives it the method's unread shape")
    }
}
