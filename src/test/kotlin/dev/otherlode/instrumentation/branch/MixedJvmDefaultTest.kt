package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the stub rule of ADR 0026 over a build that mixes a library compiled with
 * `-jvm-default=disable` and a consumer compiled with `all` or `all-compatibility`, at each kotlinc
 * release in the matrix. There kotlinc writes `MixedIdStoreImpl.keyed-QoVL8wA` with a call to a
 * `$DefaultImpls` method whose hash the library never wrote. From kotlinc 2.2 every such stub is a
 * bridge, which the method tier leaves out.
 */
class MixedJvmDefaultTest {
    companion object {
        @JvmStatic
        fun builds(): List<Array<String>> =
            CompilerFixtures.kotlincVersions.flatMap { version -> listOf("all", "all-compatibility").map { arrayOf(version, it) } }

        private const val PACKAGE = "com/example/target/mixed"
        private const val ID_STORE = "$PACKAGE/MixedIdStore"
        private const val KEYED_STUB = "keyed-QoVL8wA"
        private const val KEYED_DESCRIPTOR = "(II)I"
        private const val PLAIN_STUB = "plain-6pmR1NM"
        private const val PLAIN_DESCRIPTOR = "(I)I"
    }

    private class Declared(
        val access: Int,
        val calls: List<Triple<String, String, String>>,
    )

    private fun read(
        directory: File,
        internalName: String,
    ): ByteArray = File(directory, "$internalName.class").readBytes()

    private fun declared(bytes: ByteArray): Map<Pair<String, String>, Declared> {
        val methods = mutableMapOf<Pair<String, String>, Declared>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val calls = mutableListOf<Triple<String, String, String>>()
                    methods[name to descriptor] = Declared(access, calls)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            callee: String,
                            calleeDescriptor: String,
                            isInterface: Boolean,
                        ) {
                            calls += Triple(owner, callee, calleeDescriptor)
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return methods
    }

    private fun marks(
        version: String,
        mode: String,
        simpleName: String,
    ): BranchSiteAnalyzer.Analysis {
        val root = CompilerFixtures.kotlincMixed(version)
        val lookup: (String) -> ByteArray? = { internalName ->
            listOf(File(root, mode), File(root, "lib"))
                .map { File(it, "$internalName.class") }
                .firstOrNull { it.isFile }
                ?.readBytes()
        }
        return BranchSiteAnalyzer.analyze(
            read(File(root, mode), "$PACKAGE/$simpleName"),
            lookup,
            includePackages = listOf("com.example.target"),
        ) { _, _ -> true }
    }

    private fun stubs(
        version: String,
        mode: String,
    ): Map<Pair<String, String>, Declared> = declared(read(File(CompilerFixtures.kotlincMixed(version), mode), "$PACKAGE/MixedIdStoreImpl"))

    @ParameterizedTest(name = "kotlinc {0} {1}")
    @MethodSource("builds")
    fun `the library holds no DefaultImpls method of the name the consumer's stub calls`(
        version: String,
        mode: String,
    ) {
        val keyed = stubs(version, mode).getValue(KEYED_STUB to KEYED_DESCRIPTOR)
        val (owner, name, descriptor) = keyed.calls.single()
        val library = declared(read(File(CompilerFixtures.kotlincMixed(version), "lib"), owner))

        assertEquals("${ID_STORE}\$DefaultImpls", owner)
        assertEquals(KEYED_STUB.substringBefore('-'), name.substringBefore('-'))
        assertEquals("(L$ID_STORE;II)I", descriptor)
        assertTrue((name to descriptor) !in library, "the library declares $name")
        assertTrue(library.keys.any { it.first == KEYED_STUB && it.second == "(L$ID_STORE;II)I" }, library.keys.toString())
    }

    @ParameterizedTest(name = "kotlinc {0} {1}")
    @MethodSource("builds")
    fun `a stub whose DefaultImpls method does not exist is DEFAULT_IMPLS where kotlinc writes it as a method, and a bridge from 2_2`(
        version: String,
        mode: String,
    ) {
        val keyed = stubs(version, mode).getValue(KEYED_STUB to KEYED_DESCRIPTOR)

        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
            assertTrue(keyed.access and Opcodes.ACC_BRIDGE != 0, "from kotlinc 2.2 the stub is a bridge")
        } else {
            assertEquals(0, keyed.access and Opcodes.ACC_BRIDGE)
            assertEquals(GeneratedBy.DEFAULT_IMPLS, marks(version, mode, "MixedIdStoreImpl").generatedBy(KEYED_STUB, KEYED_DESCRIPTOR))
        }
    }

    @ParameterizedTest(name = "kotlinc {0} {1}")
    @MethodSource("builds")
    fun `a stub whose DefaultImpls method exists stays on the existing rule`(
        version: String,
        mode: String,
    ) {
        if (!CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
            assertEquals(GeneratedBy.DEFAULT_IMPLS, marks(version, mode, "MixedIdStoreImpl").generatedBy(PLAIN_STUB, PLAIN_DESCRIPTOR))
        }
    }

    @ParameterizedTest(name = "kotlinc {0} {1}")
    @MethodSource("builds")
    fun `an adopter's override that calls an existing DefaultImpls method is unchanged`(
        version: String,
        mode: String,
    ) {
        val analysis = marks(version, mode, "MixedStringStoreImpl")

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("plain", "(Ljava/lang/String;)I"))
    }

    @ParameterizedTest(name = "kotlinc {0} {1}")
    @MethodSource("builds")
    fun `an adopter's override that is only a super call on its own line is unchanged`(
        version: String,
        mode: String,
    ) {
        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) return
        val analysis = marks(version, mode, "MixedSuperStoreImpl")

        val call =
            declared(read(File(CompilerFixtures.kotlincMixed(version), mode), "$PACKAGE/MixedSuperStoreImpl"))
                .getValue("plain" to "(Ljava/lang/Object;)I")
                .calls
                .single()
        assertEquals("$PACKAGE/MixedStore\$DefaultImpls", call.first)
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("plain", "(Ljava/lang/Object;)I"))
    }

    @ParameterizedTest(name = "kotlinc {0} {1}")
    @MethodSource("builds")
    fun `a stub is NONE when the consumer's loader cannot read the DefaultImpls class`(
        version: String,
        mode: String,
    ) {
        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) return
        val root = CompilerFixtures.kotlincMixed(version)
        val ownClasses: (String) -> ByteArray? = { internalName ->
            File(File(root, mode), "$internalName.class").takeIf { it.isFile }?.readBytes()
        }

        val analysis =
            BranchSiteAnalyzer.analyze(
                read(File(root, mode), "$PACKAGE/MixedIdStoreImpl"),
                ownClasses,
                includePackages = listOf("com.example.target"),
            ) { _, _ -> true }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy(KEYED_STUB, KEYED_DESCRIPTOR))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy(PLAIN_STUB, PLAIN_DESCRIPTOR))
    }
}
