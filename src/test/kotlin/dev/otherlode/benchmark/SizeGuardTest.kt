package dev.otherlode.benchmark

import dev.otherlode.instrumentation.branch.BranchDropReason
import dev.otherlode.instrumentation.branch.BranchSite
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.branch.SizeGuard
import dev.otherlode.instrumentation.branch.SizeGuardReason
import dev.otherlode.instrumentation.branch.SizeGuardResult
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins [SizeGuard]'s constants to what the weave emits, and its arithmetic. The corpus sweep in
 * [CodeSizeLimitsTest] holds the bound above the woven length for real code; these cover the shapes
 * the corpora may not reach: switches, a probe index that needs three bytes, and a jump the weave
 * pushes past 32 KB.
 */
class SizeGuardTest {
    private fun site(
        methodName: String = "m",
        isSwitch: Boolean = false,
        outcomes: Int = 2,
        dropped: Boolean = false,
    ) = BranchSite(
        methodName,
        "()V",
        1,
        0,
        outcomeCount = outcomes,
        dropReason = if (dropped) BranchDropReason.COROUTINE_MACHINERY else null,
        isSwitch = isSwitch,
    )

    @Test
    fun `a method with no site is bounded by its entry probe`() {
        assertEquals(100 + SizeGuard.ENTRY_PROBE, SizeGuard.bound(100, emptyList()) { error("no jumps wanted") })
    }

    @Test
    fun `a two-way site, a switch case and a switch's padding are each counted`() {
        val bound =
            SizeGuard.bound(
                1000,
                listOf(site(), site(isSwitch = true, outcomes = 4), site(isSwitch = true, outcomes = 3, dropped = true)),
            ) {
                error("no jumps wanted")
            }
        assertEquals(
            1000 + SizeGuard.ENTRY_PROBE + SizeGuard.TWO_WAY_SITE + 4 * SizeGuard.SWITCH_CASE + 2 * SizeGuard.SWITCH_PADDING,
            bound,
        )
    }

    @Test
    fun `a dropped two-way site adds nothing`() {
        assertEquals(500 + SizeGuard.ENTRY_PROBE, SizeGuard.bound(500, listOf(site(dropped = true))) { error("no jumps wanted") })
    }

    @Test
    fun `above 32767 bytes every jump the method could hold is counted as widened`() {
        val unwidened = 32740 + SizeGuard.ENTRY_PROBE + SizeGuard.TWO_WAY_SITE
        assertTrue(unwidened > SizeGuard.WIDENING_THRESHOLD)
        val bound = SizeGuard.bound(32740, listOf(site())) { 10 }
        assertEquals(unwidened + SizeGuard.WIDENING_PER_JUMP * (10 + 2), bound)
    }

    @Test
    fun `at 32767 bytes no jump can widen and the jump count is not asked for`() {
        val original = SizeGuard.WIDENING_THRESHOLD - SizeGuard.ENTRY_PROBE - SizeGuard.TWO_WAY_SITE
        assertEquals(SizeGuard.WIDENING_THRESHOLD, SizeGuard.bound(original, listOf(site())) { error("asked for the jump count") })
    }

    @Test
    fun `the method lengths read from the method table match the class writer's`() {
        val bytes = shapesClass("com/example/huge/Shapes")
        val lengths = SizeGuard.codeLengths(bytes)
        assertEquals(setOf("<init>" to "()V", "two" to "(I)I", "table" to "(I)I", "lookup" to "(I)I", "dense" to "(I)I"), lengths.keys)
        // iload_0, ifeq, iinc, iload_0, ireturn.
        assertEquals(1 + 3 + 3 + 1 + 1, lengths.getValue("two" to "(I)I"))
    }

    @Test
    fun `the bound is not below the woven length for a two-way site, both switches and a dense method`() {
        val name = "com/example/huge/Shapes"
        val original = shapesClass(name)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf("com.example.huge"), registry)
        val woven = checkNotNull(transformer.transform(InMemoryLoader(mapOf(name to original)), name, null, null, original))
        val before = SizeGuard.codeLengths(original)
        val after = SizeGuard.codeLengths(woven)
        val analysis =
            BranchSiteAnalyzer.analyze(original, { null }, listOf("com.example.huge")) { _, descriptor -> descriptor == "(I)I" }
        for ((key, length) in before) {
            if (key.second != "(I)I") continue
            val bound = assertNotNull(analysis.sizeGuard.bounds[key], "no bound for $key").bound
            val actual = checkNotNull(after[key])
            assertTrue(bound >= actual, "$key: bound $bound is below the woven length $actual ($length before)")
            val outcomes = analysis.sites.filter { it.methodName == key.first }.sumOf { it.outcomeCount }
            // A one-byte index, a short entry probe and a switch's padding are what the bound rounds up.
            assertTrue(bound - actual <= SLACK_BASE + 2 * outcomes, "$key: bound $bound is far above the woven length $actual")
        }
        // Probe indexes past 127 take a sipush, past 32767 an ldc; the dense method crosses the first.
        assertTrue(analysis.sites.count { it.methodName == "dense" } > 64)
    }

    @Test
    fun `a jump the weave pushes past 32 KB is widened and the bound covers it`() {
        val name = "com/example/huge/Wide"
        val original = wideJumpClass(name)
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf("com.example.huge"), registry)
        val woven = checkNotNull(transformer.transform(InMemoryLoader(mapOf(name to original)), name, null, null, original))
        val key = "big" to "(I)I"
        val originalLength = checkNotNull(SizeGuard.codeLengths(original)[key])
        val wovenLength = checkNotNull(SizeGuard.codeLengths(woven)[key])
        assertTrue(originalLength <= SizeGuard.WIDENING_THRESHOLD, "the method starts at $originalLength bytes")
        // Exact growth, read from javap: the entry probe is 12 bytes (a 2-byte `ldc` of the constant, `iconst`,
        // five for the increment, and the advice's `goto; nop`), each of the two sites adds 22 (two probes of
        // 8 and two `goto`s), and the outer site's `goto` to the far target is a `goto_w`, 2 bytes more.
        val unwidened = originalLength + SizeGuard.ENTRY_PROBE + 2 * SizeGuard.TWO_WAY_SITE
        assertEquals(originalLength + 12 + 2 * 22 + 2, wovenLength)
        assertTrue("goto_w" in javap(woven, name), "the woven method holds no goto_w")
        assertFalse("goto_w" in javap(original, name), "the original already held a goto_w")
        val analysis = BranchSiteAnalyzer.analyze(original, { null }, listOf("com.example.huge")) { n, _ -> n == "big" }
        val bound = checkNotNull(analysis.sizeGuard.bounds[key]).bound
        assertTrue(bound >= wovenLength, "bound $bound is below the woven length $wovenLength")
        assertTrue(bound > unwidened, "the bound $bound does not add the widening to $unwidened")
        assertFalse(key in analysis.sizeGuard.guarded, "the method fits, so it keeps its probes")
        val loaded = InMemoryLoader(mapOf(name to woven)).loadClass(name.replace('/', '.'))
        assertEquals(WIDE_ADDS, loaded.getMethod("big", Int::class.javaPrimitiveType).invoke(null, 0))
        assertEquals(0, loaded.getMethod("big", Int::class.javaPrimitiveType).invoke(null, 1))
    }

    private fun apply(
        sites: List<BranchSite>,
        classBytes: ByteArray,
        receivedBytes: ByteArray? = null,
    ): Triple<List<BranchSite>, Map<Pair<String, String>, Set<Int>>, SizeGuardResult> {
        val mutable = sites.toMutableList()
        val dropped = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
        val result = SizeGuard.apply(mutable, classBytes, receivedBytes, { _, _ -> true }, dropped) { null }
        return Triple(mutable, dropped, result)
    }

    private val key = "m" to "()V"

    @Test
    fun `apply drops a method's sites when the compile limit would be crossed and the entry probe alone fits`() {
        val (sites, dropped, result) = apply(List(5) { site() }, nopClass(7980))
        assertTrue(sites.all { it.dropReason == BranchDropReason.SIZE_GUARD })
        assertEquals(setOf(0, 1, 2, 3, 4), dropped.getValue(key))
        assertEquals(SizeGuardReason.NOT_COMPILED, result.guarded.getValue(key).reason)
        assertTrue(result.entryPastLimit.isEmpty())
    }

    @Test
    fun `apply keeps the sites and names the method when the entry probe alone passes the compile limit`() {
        val (sites, dropped, result) = apply(List(5) { site() }, nopClass(7990))
        assertTrue(sites.none { it.dropReason != null })
        assertTrue(dropped.isEmpty())
        assertTrue(result.guarded.isEmpty())
        val verdict = result.entryPastLimit.getValue(key)
        assertEquals(SizeGuardReason.ENTRY_PROBE_ALONE, verdict.reason)
        assertEquals(7990 + SizeGuard.ENTRY_PROBE, verdict.bound)
    }

    @Test
    fun `apply counts a dropped switch's padding in what the entry probe alone weighs`() {
        // 7983 + 15 = 7998 fits, and the switch's 3 bytes of padding make 8001.
        val (sites, _, result) = apply(listOf(site(isSwitch = true, outcomes = 3, dropped = true), site()), nopClass(7983))
        assertEquals(BranchDropReason.COROUTINE_MACHINERY, sites[0].dropReason)
        assertEquals(null, sites[1].dropReason)
        assertEquals(SizeGuardReason.ENTRY_PROBE_ALONE, result.entryPastLimit.getValue(key).reason)
    }

    @Test
    fun `apply names a method with no site when its entry probe takes it past the compile limit`() {
        val (_, _, result) = apply(emptyList(), nopClass(7986))
        assertEquals(SizeGuardReason.ENTRY_PROBE_ALONE, result.entryPastLimit.getValue(key).reason)
        val (_, _, fits) = apply(emptyList(), nopClass(7985))
        assertTrue(fits.entryPastLimit.isEmpty())
        val (_, _, huge) = apply(emptyList(), nopClass(8001))
        assertTrue(huge.entryPastLimit.isEmpty(), "a method already over the limit is not named")
    }

    @Test
    fun `apply leaves a method above the compile limit alone while its bound fits a class file`() {
        val (sites, _, result) = apply(List(10) { site() }, nopClass(9000))
        assertTrue(sites.none { it.dropReason != null })
        assertTrue(result.guarded.isEmpty() && result.entryPastLimit.isEmpty())
        assertEquals(9000 + SizeGuard.ENTRY_PROBE + 10 * SizeGuard.TWO_WAY_SITE, result.bounds.getValue(key).bound)
    }

    @Test
    fun `apply gives a method whose sites were all dropped already no verdict`() {
        val (sites, dropped, result) = apply(List(3) { site(dropped = true) }, nopClass(5000))
        assertTrue(sites.all { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
        assertTrue(dropped.isEmpty())
        assertTrue(result.guarded.isEmpty() && result.entryPastLimit.isEmpty())
    }

    @Test
    fun `apply drops for the class file limit and counts the entry probe and padding in the escape`() {
        val (sites, _, result) = apply(List(2) { site() }, nopClass(65500))
        assertTrue(sites.all { it.dropReason == BranchDropReason.SIZE_GUARD })
        assertEquals(SizeGuardReason.CLASS_FILE_LIMIT, result.guarded.getValue(key).reason)
        // 65520 + 15 is past the limit: the entry probe alone overflows, so nothing is dropped.
        val (overflowing, _, skipped) = apply(List(2) { site() }, nopClass(65521))
        assertTrue(overflowing.none { it.dropReason != null })
        assertTrue(skipped.guarded.isEmpty())
        // The switch's padding is part of what the entry probe alone weighs.
        val (padded, _, paddedResult) = apply(listOf(site(isSwitch = true, outcomes = 2, dropped = true), site()), nopClass(65518))
        assertTrue(padded[1].dropReason == null && paddedResult.guarded.isEmpty())
    }

    @Test
    fun `apply tests the class file limit against the received bytes and the compile limit against the class file`() {
        val classFile = nopClass(1000)
        val received = nopClass(65000)
        val (sites, _, result) = apply(List(30) { site() }, classFile, received)
        assertTrue(sites.all { it.dropReason == BranchDropReason.SIZE_GUARD })
        assertEquals(SizeGuardReason.CLASS_FILE_LIMIT, result.guarded.getValue(key).reason)
        // A received method of 7900 bytes, whose own bound with 10 sites crosses 8000 while its entry probe alone
        // does not, changes nothing about the compile limit, which the class file decides.
        val (kept, _, keptResult) = apply(List(10) { site() }, classFile, nopClass(7900))
        assertTrue(kept.none { it.dropReason != null })
        assertTrue(keptResult.guarded.isEmpty() && keptResult.entryPastLimit.isEmpty())
    }

    @Test
    fun `reweaveGuarded names a method whose stored sites no longer fit the received bytes`() {
        val sequence = intArrayOf(Opcodes.IFEQ, Opcodes.IFNE)
        val received = nopClass(65500)
        assertEquals(setOf(key), SizeGuard.reweaveGuarded(received, listOf(key), { sequence }, { emptySet() }))
        assertTrue(SizeGuard.reweaveGuarded(nopClass(1000), listOf(key), { sequence }, { emptySet() }).isEmpty())
        assertTrue(SizeGuard.reweaveGuarded(received, listOf(key), { sequence }, { setOf(0, 1) }).isEmpty(), "nothing is kept to drop")
        assertTrue(
            SizeGuard.reweaveGuarded(nopClass(65521), listOf(key), { sequence }, { emptySet() }).isEmpty(),
            "the entry probe overflows",
        )
    }

    private fun javap(
        bytes: ByteArray,
        internalName: String,
    ): String {
        val dir =
            java.nio.file.Files
                .createTempDirectory("otherlode-javap")
        val file = dir.resolve(internalName.substringAfterLast('/') + ".class")
        java.nio.file.Files
            .write(file, bytes)
        val tool =
            java.util.spi.ToolProvider
                .findFirst("javap")
                .orElseThrow { AssertionError("no javap in this JDK") }
        val out = java.io.StringWriter()
        tool.run(java.io.PrintWriter(out), java.io.PrintWriter(out), "-c", "-p", file.toString())
        file.toFile().delete()
        dir.toFile().delete()
        return out.toString()
    }

    private class InMemoryLoader(
        private val classes: Map<String, ByteArray>,
    ) : ClassLoader(SizeGuardTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            classes[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    private companion object {
        const val SLACK_BASE = 7
        const val WIDE_ADDS = 10912

        /** A class whose `static void m()` is [length] bytes of code: `nop`s and a `return`. */
        fun nopClass(length: Int): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/huge/Nops", null, "java/lang/Object", null)
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()V", null, null)
            mv.visitCode()
            repeat(length - 1) { mv.visitInsn(Opcodes.NOP) }
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        fun ClassWriter.constructor() {
            val ctor = visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            ctor.visitCode()
            ctor.visitVarInsn(Opcodes.ALOAD, 0)
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            ctor.visitInsn(Opcodes.RETURN)
            ctor.visitMaxs(0, 0)
            ctor.visitEnd()
        }

        /** Four static `(I)I` methods: one `if`, a tableswitch, a lookupswitch, and 100 `if (x == k) y++` blocks. */
        fun shapesClass(internalName: String): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            cw.visitSource("Shapes.java", null)
            cw.constructor()

            fun method(
                name: String,
                body: net.bytebuddy.jar.asm.MethodVisitor.() -> Unit,
            ) {
                val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "(I)I", null, null)
                mv.visitCode()
                val start = Label()
                mv.visitLabel(start)
                mv.visitLineNumber(1, start)
                mv.body()
                mv.visitMaxs(0, 0)
                mv.visitEnd()
            }
            method("two") {
                val skip = Label()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitJumpInsn(Opcodes.IFEQ, skip)
                visitIincInsn(0, 1)
                visitLabel(skip)
                visitVarInsn(Opcodes.ILOAD, 0)
                visitInsn(Opcodes.IRETURN)
            }
            method("table") {
                val a = Label()
                val b = Label()
                val c = Label()
                val d = Label()
                val end = Label()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitTableSwitchInsn(0, 3, end, a, b, c, d)
                for (label in listOf(a, b, c, d)) {
                    visitLabel(label)
                    visitIincInsn(0, 1)
                }
                visitLabel(end)
                visitVarInsn(Opcodes.ILOAD, 0)
                visitInsn(Opcodes.IRETURN)
            }
            method("lookup") {
                val a = Label()
                val b = Label()
                val c = Label()
                val end = Label()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitLookupSwitchInsn(end, intArrayOf(1, 100, 10000), arrayOf(a, b, c))
                for (label in listOf(a, b, c)) {
                    visitLabel(label)
                    visitIincInsn(0, 1)
                }
                visitLabel(end)
                visitVarInsn(Opcodes.ILOAD, 0)
                visitInsn(Opcodes.IRETURN)
            }
            method("dense") {
                visitInsn(Opcodes.ICONST_0)
                visitVarInsn(Opcodes.ISTORE, 1)
                repeat(100) { i ->
                    val skip = Label()
                    visitVarInsn(Opcodes.ILOAD, 0)
                    visitIntInsn(Opcodes.SIPUSH, 200 + i)
                    visitJumpInsn(Opcodes.IF_ICMPNE, skip)
                    visitIincInsn(1, 1)
                    visitLabel(skip)
                }
                visitVarInsn(Opcodes.ILOAD, 1)
                visitInsn(Opcodes.IRETURN)
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        /**
         * `static int big(int x)` that jumps over [WIDE_ADDS] `iinc` instructions and one conditional when `x != 0`.
         * The jump's offset fits in the class file as written, and the inner conditional's probes push it past 32767.
         */
        fun wideJumpClass(internalName: String): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            cw.visitSource("Wide.java", null)
            cw.constructor()
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "big", "(I)I", null, null)
            mv.visitCode()
            val start = Label()
            mv.visitLabel(start)
            mv.visitLineNumber(1, start)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitVarInsn(Opcodes.ISTORE, 1)
            val end = Label()
            mv.visitVarInsn(Opcodes.ILOAD, 0)
            mv.visitJumpInsn(Opcodes.IFNE, end)
            repeat(WIDE_ADDS / 2) { mv.visitIincInsn(1, 1) }
            val skip = Label()
            mv.visitVarInsn(Opcodes.ILOAD, 0)
            mv.visitIntInsn(Opcodes.SIPUSH, 5)
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, skip)
            mv.visitIincInsn(1, 0)
            mv.visitLabel(skip)
            repeat(WIDE_ADDS - WIDE_ADDS / 2) { mv.visitIincInsn(1, 1) }
            mv.visitLabel(end)
            mv.visitVarInsn(Opcodes.ILOAD, 1)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }
    }
}
