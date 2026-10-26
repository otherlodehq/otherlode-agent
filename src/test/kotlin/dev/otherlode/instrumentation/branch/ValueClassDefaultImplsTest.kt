package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.JvmDefaultDisableFixtures
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reads the sub-interface `$DefaultImpls` forwarders kotlinc writes under `-jvm-default=disable` at
 * the build's own release, which writes them on every release. The compiler matrix covers each
 * release's default mode and the stubs.
 */
class ValueClassDefaultImplsTest {
    private companion object {
        const val PACKAGE = "Lcom/example/target/jvmdefaultdisable"
        const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
    }

    private val lookup: (String) -> ByteArray? =
        { internalName -> File(JvmDefaultDisableFixtures.outputDir, "$internalName.class").takeIf { it.isFile }?.readBytes() }

    private fun marks(simpleName: String): Map<Pair<String, String>, GeneratedBy> {
        val bytes = JvmDefaultDisableFixtures.classBytes(simpleName)
        val analysis = BranchSiteAnalyzer.analyze(bytes, lookup) { _, _ -> true }
        val result = mutableMapOf<Pair<String, String>, GeneratedBy>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ) = null.also { result[name to descriptor] = analysis.generatedBy(name, descriptor) }
            },
            ClassReader.SKIP_CODE,
        )
        return result
    }

    private fun Map<Pair<String, String>, GeneratedBy>.withPrefix(prefix: String): List<GeneratedBy> =
        filterKeys { it.first.startsWith(prefix) }.values.toList()

    @Test
    fun `a sub-interface's forwarders that box, unbox or drop a Unit result are DEFAULT_IMPLS`() {
        val ids = marks("DisabledIdStore\$DefaultImpls")
        val units = marks("DisabledUnitStore\$DefaultImpls")

        assertEquals(listOf(GeneratedBy.DEFAULT_IMPLS), ids.withPrefix("put-"))
        assertEquals(listOf(GeneratedBy.DEFAULT_IMPLS), ids.withPrefix("get-"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, units.getValue("put" to "($PACKAGE/DisabledUnitStore;Lkotlin/Unit;)V"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, units.getValue("get" to "($PACKAGE/DisabledUnitStore;)Lkotlin/Unit;"))
    }

    @Test
    fun `a sub-interface's suspend forwarder that returns Unit after the suspended check is DEFAULT_IMPLS`() {
        val descriptor = "($PACKAGE/DisabledSuspendSub;$CONTINUATION)Ljava/lang/Object;"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, marks("DisabledSuspendSub\$DefaultImpls").getValue("run" to descriptor))
    }

    @Test
    fun `an override of a suspend default that calls super has the same tail without a receiver cast and is NONE`() {
        val descriptor = "($PACKAGE/DisabledSuspendOverride;$CONTINUATION)Ljava/lang/Object;"

        assertEquals(GeneratedBy.NONE, marks("DisabledSuspendOverride\$DefaultImpls").getValue("run" to descriptor))
    }
}
