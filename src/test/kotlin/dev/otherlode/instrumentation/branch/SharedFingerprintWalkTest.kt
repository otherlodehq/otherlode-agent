package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the fingerprints [BranchSiteAnalyzer] takes from its own walk equal the ones a separate
 * [ConditionFingerprinter.analyze] pass produces, over every compiled test fixture, and that a
 * fingerprinting failure leaves the analysis itself intact.
 */
class SharedFingerprintWalkTest {
    private val fixtureDirs: List<Pair<File, SourceLanguage>> =
        listOf(
            File("build/classes/kotlin/test") to SourceLanguage.KOTLIN,
            File("build/classes/java/test") to SourceLanguage.JAVA,
            ScalaFixtures.outputDir("scala3") to SourceLanguage.SCALA,
            ScalaFixtures.outputDir("scala2") to SourceLanguage.SCALA,
        )

    private val lookup: (String) -> ByteArray? = { internalName ->
        fixtureDirs
            .map { File(it.first, "$internalName.class") }
            .firstOrNull { it.isFile }
            ?.readBytes()
    }

    private fun fixtureClasses(): List<Pair<ByteArray, SourceLanguage>> =
        fixtureDirs.flatMap { (dir, language) ->
            dir
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.readBytes() to language }
                .toList()
        }

    private fun analyzeWith(
        bytes: ByteArray,
        visitor: ((onResult: (ConditionFingerprinter.MethodResult) -> Unit) -> MethodVisitor)?,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyzeThrough(
            ClassReader(bytes),
            bytes,
            lookup,
            listOf("com.example"),
            emptyList(),
            null,
            emptySet(),
            { null },
            null,
            visitor,
        ) { _, _ -> true }

    private fun describe(result: ConditionFingerprinter.MethodResult): List<Any?> =
        listOf(
            result.fingerprints,
            result.caseKeys,
            result.fingerprints.indices.map { result.conditionOf(it) },
            result.loweredSwitches.map {
                listOf(
                    it.loweringOrdinals,
                    it.rebuiltOrdinal,
                    it.caseLabels,
                    it.throwingDefault,
                    it.fingerprint,
                    it.condition,
                    it.caseConditions,
                    it.collisionOutcomes,
                )
            },
            result.unreadCollisionOutcomes,
            result.stringHashCodeSwitches,
        )

    @Test
    fun `a collection fed by a shared walk equals the separate pass over every fixture class`() {
        var compared = 0
        for ((bytes, language) in fixtureClasses()) {
            val enumMappings = EnumSwitchMappings(lookup)
            val separate = ConditionFingerprinter.analyze(bytes, language, { false }, enumMappings)

            val reader = ClassReader(bytes)
            val collection = ConditionFingerprinter.FingerprintCollection(reader.className, language, { false }, enumMappings)
            val walk =
                object : net.bytebuddy.jar.asm.ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor = collection.methodVisitor(name, descriptor, object : MethodVisitor(Opcodes.ASM9) {})
                }
            reader.accept(walk, ClassReader.SKIP_FRAMES)
            val shared = assertNotNull(collection.results(), "fingerprinting failed on ${reader.className}")

            assertEquals(separate.keys, shared.keys, reader.className)
            for ((key, expected) in separate) {
                assertEquals(describe(expected), describe(shared.getValue(key)), "${reader.className}.${key.first}")
                compared++
            }
        }
        assertTrue(compared > 500, "only $compared methods compared")
    }

    @Test
    fun `a fingerprinting visitor that throws costs the fingerprints and leaves the analysis intact`() {
        val bytes = File("build/classes/java/test/com/example/target/ConditionJavaTarget.class").readBytes()
        val normal = analyzeWith(bytes, null)
        val failing =
            analyzeWith(bytes) {
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitJumpInsn(
                        opcode: Int,
                        label: net.bytebuddy.jar.asm.Label,
                    ): Unit = throw IllegalStateException("fingerprinter failure")
                }
            }

        assertTrue(normal.sites.any { it.conditionFingerprint != null }, "the fixture must have fingerprinted sites")
        assertEquals(normal.sites.size, failing.sites.size)
        for ((expected, actual) in normal.sites.zip(failing.sites)) {
            assertNull(actual.conditionFingerprint)
            assertNull(actual.caseKeys)
            assertTrue(actual.condition.isEmpty())
            assertEquals(
                expected.copy(conditionFingerprint = null, caseKeys = null, condition = emptyList(), caseLabels = null),
                actual.copy(caseLabels = null),
            )
        }
        assertEquals(normal.className, failing.className)
        assertEquals(normal.hasLineNumbers, failing.hasLineNumbers)
    }

    @Test
    fun `a failure in a later method drops the fingerprints of the earlier ones too`() {
        val bytes = File("build/classes/java/test/com/example/target/ConditionJavaTarget.class").readBytes()
        var methods = 0
        val failing =
            analyzeWith(bytes) { onResult ->
                val index = methods++
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitEnd() {
                        if (index == 3) throw IllegalStateException("fingerprinter failure")
                        onResult(ConditionFingerprinter.MethodResult(emptyList(), emptyList()))
                    }
                }
            }

        assertEquals(4, methods, "no visitor is built once fingerprinting has failed")
        assertTrue(failing.sites.isNotEmpty())
        assertTrue(failing.sites.all { it.conditionFingerprint == null })
    }
}
