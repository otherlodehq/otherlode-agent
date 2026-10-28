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

    @Test
    fun `a sub-interface's forwarder that casts an argument to the bound of its type variable is DEFAULT_IMPLS`() {
        val descriptor = "($PACKAGE/DisabledMidApi;Ljava/lang/String;)I"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, marks("DisabledMidApi\$DefaultImpls").getValue("f" to descriptor))
    }

    @Test
    fun `an override that calls super and casts the same way is NONE`() {
        val descriptor = "($PACKAGE/DisabledMidOverride;Ljava/lang/String;)I"

        assertEquals(GeneratedBy.NONE, marks("DisabledMidOverride\$DefaultImpls").getValue("f" to descriptor))
    }

    @Test
    fun `a sub-interface's forwarder that boxes or unboxes null-safely at a value class over a reference is DEFAULT_IMPLS`() {
        val holds = marks("DisabledLabelHoldsApi\$DefaultImpls")
        val takes = marks("DisabledLabelTakesApi\$DefaultImpls")

        assertEquals(listOf(GeneratedBy.DEFAULT_IMPLS), holds.withPrefix("get-"))
        assertEquals(listOf(GeneratedBy.DEFAULT_IMPLS), holds.withPrefix("getItem-"))
        assertEquals(listOf(GeneratedBy.DEFAULT_IMPLS), holds.withPrefix("put-"))
        assertEquals(listOf(GeneratedBy.DEFAULT_IMPLS), takes.withPrefix("take-"))
    }

    @Test
    fun `an override that calls super and unboxes null-safely is NONE`() {
        val overrides = marks("DisabledLabelHoldsOverride\$DefaultImpls")

        assertEquals(listOf(GeneratedBy.NONE), overrides.withPrefix("get-"))
    }

    @Test
    fun `a value class's static for an inherited default that casts, boxes or unboxes null-safely is VALUE_CLASS`() {
        val mid = marks("DisabledMidValue")
        val holds = marks("DisabledLabelHoldsValue")
        val takes = marks("DisabledLabelTakesValue")

        assertEquals(GeneratedBy.VALUE_CLASS, mid.getValue("f-impl" to "(ILjava/lang/String;)I"))
        assertEquals(
            GeneratedBy.VALUE_CLASS,
            holds
                .filterKeys {
                    it.first.startsWith("get-") && it.second == "(I)Ljava/lang/String;"
                }.values
                .single(),
        )
        assertEquals(
            GeneratedBy.VALUE_CLASS,
            holds
                .filterKeys {
                    it.first.startsWith("getItem-") && it.second == "(I)Ljava/lang/String;"
                }.values
                .single(),
        )
        assertEquals(
            GeneratedBy.VALUE_CLASS,
            takes.filterKeys { it.first.startsWith("take-") && it.second == "(ILjava/lang/String;)I" }.values.single(),
        )
    }
}
