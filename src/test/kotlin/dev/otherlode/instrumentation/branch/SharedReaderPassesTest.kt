package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what the analysis takes from the one [ClassReader] a transform shares between its passes: the
 * tracked-instruction sequences the weave plan stores, the code lengths [SizeGuard] judges, and the
 * results of an analysis that reads through a reader another pass already walked.
 */
class SharedReaderPassesTest {
    private val classFiles: List<Pair<String, ByteArray>> by lazy {
        val directories = listOf("build/classes/java/test/com/example/target", "build/classes/kotlin/test/com/example/target")
        val fixtures =
            directories.flatMap { directory ->
                File(directory).listFiles { file -> file.extension == "class" }.orEmpty().sortedBy { it.name }.map {
                    it.name to
                        it.readBytes()
                }
            }
        val analyser = BranchSiteAnalyzer::class.java.name.replace('.', '/') + ".class"
        val own = checkNotNull(BranchSiteAnalyzer::class.java.classLoader.getResourceAsStream(analyser)).use { analyser to it.readBytes() }
        fixtures + own
    }

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

    @Test
    fun `the analysis records the tracked instructions SitePairing would read again`() {
        var withSites = 0
        for ((name, bytes) in classFiles) {
            val analysis = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }
            val methods = methodsOf(bytes)
            val expected = SitePairing.encodedSequences(bytes, methods)
            for ((methodName, descriptor) in methods) {
                val recorded = analysis.trackedSequenceOf(methodName, descriptor)
                assertContentEquals(expected[methodName to descriptor] ?: IntArray(0), recorded, "$name $methodName$descriptor")
                if (recorded.isNotEmpty()) withSites++
            }
        }
        assertTrue(withSites > 100, "the fixtures hold few tracked methods: $withSites")
    }

    @Test
    fun `a switch is recorded with its bounds or keys and which entries go to the default`() {
        var tableSwitches = 0
        var lookupSwitches = 0
        for ((_, bytes) in classFiles) {
            val analysis = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }
            for ((name, descriptor) in methodsOf(bytes)) {
                val sequence = analysis.trackedSequenceOf(name, descriptor)
                if (Opcodes.TABLESWITCH in sequence) tableSwitches++
                if (Opcodes.LOOKUPSWITCH in sequence) lookupSwitches++
            }
        }
        assertTrue(tableSwitches > 0 && lookupSwitches > 0, "table=$tableSwitches lookup=$lookupSwitches")
    }

    @Test
    fun `an analysis through a reader that already walked the class equals one through a fresh reader`() {
        for ((name, bytes) in classFiles) {
            val walked = ClassReader(bytes)
            walked.accept(object : ClassVisitor(Opcodes.ASM9) {}, ClassReader.SKIP_DEBUG)
            val shared =
                BranchSiteAnalyzer.analyzeThrough(
                    walked,
                    bytes,
                    { null },
                    emptyList(),
                    emptyList(),
                    null,
                    emptySet(),
                    { null },
                    null,
                ) { _, _ -> true }
            val fresh = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }
            assertEquals(fresh.sites, shared.sites, name)
            assertEquals(fresh.sizeGuard.bounds, shared.sizeGuard.bounds, name)
            for ((methodName, descriptor) in methodsOf(bytes)) {
                assertContentEquals(
                    fresh.trackedSequenceOf(methodName, descriptor),
                    shared.trackedSequenceOf(methodName, descriptor),
                    "$name $methodName$descriptor",
                )
            }
        }
    }

    @Test
    fun `code lengths read through a walked reader equal those read from the bytes`() {
        for ((name, bytes) in classFiles) {
            val walked = ClassReader(bytes)
            walked.accept(object : ClassVisitor(Opcodes.ASM9) {}, ClassReader.SKIP_FRAMES)
            assertEquals(SizeGuard.codeLengths(bytes), SizeGuard.codeLengths(walked), name)
        }
    }
}
