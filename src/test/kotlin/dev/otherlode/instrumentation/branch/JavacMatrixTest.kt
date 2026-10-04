package dev.otherlode.instrumentation.branch

import dev.otherlode.export.ConditionPart
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the analyser's record marks and switch lowerings over the same Java source set compiled by
 * javac 17, 21 and 25, each at its own default target, and the pattern-switch sources over the two
 * that can compile them.
 */
class JavacMatrixTest {
    companion object {
        @JvmStatic
        fun versions(): List<String> = CompilerFixtures.javacVersions

        @JvmStatic
        fun patternVersions(): List<String> = CompilerFixtures.javacPatternVersions
    }

    private fun code(text: String) = ConditionPart(ConditionPartKind.CODE, text)

    /** The exception types [method] of fixture class [simpleName] instantiates, by internal name. */
    private fun instantiatedTypes(
        version: String,
        simpleName: String,
        method: String,
    ): Set<String> {
        val types = mutableSetOf<String>()
        ClassReader(CompilerFixtures.javac(version).classBytes(simpleName)).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name != method) {
                        null
                    } else {
                        object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitTypeInsn(
                                opcode: Int,
                                type: String,
                            ) {
                                if (opcode == Opcodes.NEW) types += type
                            }
                        }
                    }
            },
            0,
        )
        return types
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("versions")
    fun `a record's equals, hashCode and toString are RECORD, its accessors, constructor and extra method are not`(version: String) {
        val marks = CompilerFixtures.javac(version).marks("Point")

        assertEquals(GeneratedBy.RECORD, marks.only("equals"))
        assertEquals(GeneratedBy.RECORD, marks.only("hashCode"))
        assertEquals(GeneratedBy.RECORD, marks.only("toString"))
        listOf("i", "s", "l", "d", "f", "b", "o", "extra", "<init>").forEach {
            assertEquals(GeneratedBy.NONE, marks.only(it), it)
        }
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("versions")
    fun `a record with a hand-written equals leaves it unmarked and still marks the generated hashCode and toString`(version: String) {
        val marks = CompilerFixtures.javac(version).marks("Named")

        assertEquals(GeneratedBy.NONE, marks.only("equals"))
        assertEquals(GeneratedBy.RECORD, marks.only("hashCode"))
        assertEquals(GeneratedBy.RECORD, marks.only("toString"))
        listOf("name", "age", "<init>").forEach { assertEquals(GeneratedBy.NONE, marks.only(it), it) }
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("versions")
    fun `an enum's values and valueOf are ENUM, its constructor is not`(version: String) {
        val marks = CompilerFixtures.javac(version).marks("Shade")

        assertEquals(GeneratedBy.ENUM, marks.only("values"))
        assertEquals(GeneratedBy.ENUM, marks.only("valueOf"))
        assertEquals(GeneratedBy.NONE, marks.only("<init>"))
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("versions")
    fun `an enum switch statement is rebuilt with each constant as a label, the default last and the subject as condition`(
        version: String,
    ) {
        val site = kept(CompilerFixtures.javac(version).analyzeBranches("Switches"), "enumStatement").single()

        assertEquals(listOf("RED", "BLUE", "default"), outcomes(site))
        assertEquals(listOf(code("shade")), site.site.condition)
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("versions")
    fun `a string switch is rebuilt with each literal, and the hash switch and equals checks are dropped`(version: String) {
        val analysis = CompilerFixtures.javac(version).analyzeBranches("Switches")
        val site = kept(analysis, "stringStatement").single()

        assertEquals(listOf("\"open\"", "\"closed\"", "\"done\"", "\"Aa\"", "\"BB\"", "default"), outcomes(site))
        assertEquals(listOf(code("status")), site.site.condition)
        val lowering = dropped(analysis, "stringStatement")
        assertEquals(6, lowering.size, "the hash switch and five equals checks")
        assertTrue(lowering.all { it.dropReason == BranchDropReason.SWITCH_LOWERING })
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("versions")
    fun `an exhaustive enum switch expression's throwing default is not listed, and throws ICCE at target 17 and MatchException from 21`(
        version: String,
    ) {
        val site = kept(CompilerFixtures.javac(version).analyzeBranches("Switches"), "enumExpression").single()

        assertEquals(listOf("RED", "BLUE", "GREEN"), outcomes(site))
        assertTrue(site.site.throwingDefault)
        val expected = if (version == "17") "java/lang/IncompatibleClassChangeError" else "java/lang/MatchException"
        assertEquals(setOf(expected), instantiatedTypes(version, "Switches", "enumExpression"))
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("patternVersions")
    fun `a typeSwitch over an Object selector reads each class pattern, and the subject is the condition`(version: String) {
        val site = kept(CompilerFixtures.javac(version).analyzeBranches("PatternSwitches"), "objectPattern").single()

        assertEquals(listOf("String", "Integer", "default"), outcomes(site))
        assertEquals(listOf(code("value")), site.site.condition)
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("patternVersions")
    fun `a typeSwitch over a sealed selector reads each permitted class, the MatchException default not listed`(version: String) {
        val site = kept(CompilerFixtures.javac(version).analyzeBranches("PatternSwitches"), "sealedPattern").single()

        assertEquals(listOf("Square", "Circle"), outcomes(site))
        assertTrue(site.site.throwingDefault)
    }
}
