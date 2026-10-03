package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BranchRole
import dev.otherlode.export.ConditionPart
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the analyser's generated-method marks, `$default` omission sites, coroutine-machinery drops
 * and switch lowerings over the same Kotlin source set compiled by each kotlinc release in
 * [CompilerFixtures.kotlincVersions], at that release's own default language version and
 * `-jvm-default` mode. The expectations are the ones the rules were written against kotlinc 2.2.21
 * for; a release that changes a shape fails here by name.
 */
class KotlincMatrixTest {
    companion object {
        @JvmStatic
        fun versions(): List<String> = CompilerFixtures.kotlincVersions

        private const val POINT = "Lcom/example/target/kotlinc/Point;"
        private const val GREETER = "Lcom/example/target/kotlinc/Greeter;"

        private val suspendsSource = CompilerFixtures.kotlincSuspendsSource

        /** The 1-based line of the first line in [suspendsSource] carrying `// marker: [marker]`. */
        private fun lineOf(marker: String): Int {
            val index = suspendsSource.readLines().indexOfFirst { it.contains("marker: $marker") }
            check(index >= 0) { "no line in $suspendsSource carries the marker $marker" }
            return index + 1
        }
    }

    private fun code(text: String) = ConditionPart(ConditionPartKind.CODE, text)

    private fun literal(text: String) = ConditionPart(ConditionPartKind.STRING_LITERAL, text)

    // --- generated methods ---

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a data class's componentN, copy, equals, hashCode and toString are DATA_CLASS, its constructor and getters are not`(
        version: String,
    ) {
        val marks = CompilerFixtures.kotlinc(version).marks("Point")

        (1..7).forEach { assertEquals(GeneratedBy.DATA_CLASS, marks.only("component$it"), "component$it") }
        assertEquals(GeneratedBy.DATA_CLASS, marks.of("copy", "(ILjava/lang/String;Ljava/lang/String;JDZF)$POINT"))
        assertEquals(GeneratedBy.DATA_CLASS, marks.only("equals"))
        assertEquals(GeneratedBy.DATA_CLASS, marks.only("hashCode"))
        assertEquals(GeneratedBy.DATA_CLASS, marks.only("toString"))
        assertEquals(GeneratedBy.NONE, marks.only("<init>"))
        assertEquals(GeneratedBy.NONE, marks.only("getI"))
        assertEquals(GeneratedBy.NONE, marks.only("getS"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a generic data class's members are DATA_CLASS`(version: String) {
        val marks = CompilerFixtures.kotlinc(version).marks("Box")

        listOf("component1", "component2", "component3", "copy", "equals", "hashCode", "toString").forEach {
            assertEquals(GeneratedBy.DATA_CLASS, marks.only(it), it)
        }
        assertEquals(GeneratedBy.NONE, marks.only("getT"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a data class with a hand-written equals, hashCode and toString keeps them unmarked and still marks componentN and copy`(
        version: String,
    ) {
        val marks = CompilerFixtures.kotlinc(version).marks("Named")

        assertEquals(GeneratedBy.NONE, marks.only("equals"))
        assertEquals(GeneratedBy.NONE, marks.only("hashCode"))
        assertEquals(GeneratedBy.NONE, marks.only("toString"))
        assertEquals(GeneratedBy.DATA_CLASS, marks.only("component1"))
        assertEquals(GeneratedBy.DATA_CLASS, marks.only("component2"))
        assertEquals(GeneratedBy.DATA_CLASS, marks.only("copy"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `an enum's values, valueOf and getEntries are ENUM, its constructor is not`(version: String) {
        listOf("Color", "Coded").forEach { enum ->
            val marks = CompilerFixtures.kotlinc(version).marks(enum)

            assertEquals(GeneratedBy.ENUM, marks.only("values"), "$enum.values")
            assertEquals(GeneratedBy.ENUM, marks.only("valueOf"), "$enum.valueOf")
            assertEquals(GeneratedBy.ENUM, marks.only("getEntries"), "$enum.getEntries")
            assertEquals(GeneratedBy.NONE, marks.only("<init>"), "$enum.<init>")
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `JvmOverloads forwarders are JVM_OVERLOADS as a top-level function, a member and a constructor, the full signature is not`(
        version: String,
    ) {
        val build = CompilerFixtures.kotlinc(version)
        val topLevel = build.marks("DefaultsKt")
        val overloaded = build.marks("Overloaded")

        assertEquals(GeneratedBy.JVM_OVERLOADS, topLevel.of("overloads", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.JVM_OVERLOADS, topLevel.of("overloads", "(ILjava/lang/String;)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, topLevel.of("overloads", "(ILjava/lang/String;I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.JVM_OVERLOADS, overloaded.of("mem", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, overloaded.of("mem", "(ILjava/lang/String;)Ljava/lang/String;"))
        assertEquals(GeneratedBy.JVM_OVERLOADS, overloaded.of("<init>", "(I)V"))
        assertEquals(GeneratedBy.JVM_OVERLOADS, overloaded.of("<init>", "(ILjava/lang/String;)V"))
        assertEquals(GeneratedBy.NONE, overloaded.of("<init>", "(ILjava/lang/String;J)V"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a multi-file facade's forwarders are MULTIFILE_FACADE and the parts' functions are unmarked`(version: String) {
        val build = CompilerFixtures.kotlinc(version)
        val facade = build.marks("Utils")

        assertEquals(GeneratedBy.MULTIFILE_FACADE, facade.of("util1", "(I)I"))
        assertEquals(GeneratedBy.MULTIFILE_FACADE, facade.of("util1d", "(II)I"))
        assertEquals(GeneratedBy.MULTIFILE_FACADE, facade.of("util2", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(GeneratedBy.MULTIFILE_FACADE, facade.of("getUtilProp", "()I"))
        assertEquals(GeneratedBy.NONE, facade.of("util1d\$default", "(IIILjava/lang/Object;)I"))
        listOf("Utils__Utils1Kt", "Utils__Utils2Kt").forEach { part ->
            val marks = build.marks(part)
            val functions = marks.all("util1") + marks.all("util1d") + marks.all("util2") + marks.all("getUtilProp")
            assertTrue(functions.isNotEmpty(), "$part holds some of the facade's functions")
            functions.forEach { assertEquals(GeneratedBy.NONE, it, part) }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a DefaultImpls method is DEFAULT_IMPLS when it forwards under jvm-default enable, and unmarked when it holds the body (disable)`(
        version: String,
    ) {
        val marks = CompilerFixtures.kotlinc(version).marks("Greeter\$DefaultImpls")
        val expected =
            if (version in CompilerFixtures.kotlincJvmDefaultEnableVersions) GeneratedBy.DEFAULT_IMPLS else GeneratedBy.NONE

        assertEquals(expected, marks.of("greet", "($GREETER)Ljava/lang/String;"), "greet")
        assertEquals(expected, marks.of("tag", "(${GREETER}Ljava/lang/String;)Ljava/lang/String;"), "tag")
        assertEquals(expected, marks.of("getLabel", "($GREETER)Ljava/lang/String;"), "getLabel")
    }

    // --- default-filling methods ---

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a function's optional parameters each get an omission bit, named from the local variable table`(version: String) {
        val build = CompilerFixtures.kotlinc(version)
        val topLevel = BranchSiteAnalyzer.analyze(build.classBytes("DefaultsKt")) { _, _ -> false }
        val member = BranchSiteAnalyzer.analyze(build.classBytes("Member")) { _, _ -> false }

        val defaults = topLevel.defaultSites.single { it.defaultName == "defaults\$default" }
        assertEquals("defaults", defaults.targetName)
        assertEquals(0b1110, defaults.optionalBits, "b, c and d are optional, a is required")
        assertEquals(mapOf(1 to "b", 2 to "c", 3 to "d"), defaults.parameterNames)
        assertEquals(false, defaults.overridable)

        val memberDefaults = member.defaultSites.single { it.defaultName == "memberDefaults\$default" }
        assertEquals("memberDefaults", memberDefaults.targetName)
        assertEquals(0b10, memberDefaults.optionalBits)
        assertEquals(mapOf(1 to "b"), memberDefaults.parameterNames)
        assertEquals(false, memberDefaults.overridable, "a final class's method")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a JvmOverloads constructor and function resolve their default-filling twins`(version: String) {
        val build = CompilerFixtures.kotlinc(version)
        val overloaded = BranchSiteAnalyzer.analyze(build.classBytes("Overloaded")) { _, _ -> false }
        val topLevel = BranchSiteAnalyzer.analyze(build.classBytes("DefaultsKt")) { _, _ -> false }

        assertEquals(0b110, overloaded.defaultSites.single { it.defaultName == "<init>" }.optionalBits)
        assertEquals(0b10, overloaded.defaultSites.single { it.defaultName == "mem\$default" }.optionalBits)
        assertEquals(0b110, topLevel.defaultSites.single { it.defaultName == "overloads\$default" }.optionalBits)
    }

    // --- coroutine machinery ---

    private fun suspendKept(
        version: String,
        owner: String,
        method: String,
    ): Pair<List<BranchSite>, List<BranchSite>> {
        val sites =
            CompilerFixtures
                .kotlinc(version)
                .analyzeBranches(owner)
                .sites
                .filter { it.methodName == method }
        return sites.filter { it.dropReason == null } to sites.filter { it.dropReason != null }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a top-level suspend function keeps only its own conditional`(version: String) {
        val (kept, dropped) = suspendKept(version, "SuspendsKt", "topLevel")

        assertEquals(listOf(lineOf("topLevel-if")), kept.map { it.line }, "sites: ${kept + dropped}")
        assertTrue(dropped.any { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a member suspend function keeps only its own conditional`(version: String) {
        val (kept, dropped) = suspendKept(version, "Holder", "member")

        assertEquals(listOf(lineOf("member-if")), kept.map { it.line }, "sites: ${kept + dropped}")
        assertTrue(dropped.any { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a suspend lambda's invokeSuspend keeps only its own conditional`(version: String) {
        val (kept, dropped) = suspendKept(version, "SuspendsKt\$lam\$1", "invokeSuspend")

        assertEquals(listOf(lineOf("lambda-if")), kept.map { it.line }, "sites: ${kept + dropped}")
        assertTrue(dropped.any { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a function suspending through suspendCoroutine drops the stack-form suspended compare as machinery and keeps no site`(
        version: String,
    ) {
        val (kept, dropped) = suspendKept(version, "SuspendsKt", "leaf")

        assertEquals(emptyList(), kept.map { it.line }, "sites: ${kept + dropped}")
        assertEquals(listOf(BranchDropReason.COROUTINE_MACHINERY), dropped.map { it.dropReason })
    }

    // --- switch lowerings ---

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a when over an enum is rebuilt with constant labels, else last and the subject as condition`(version: String) {
        val analysis = CompilerFixtures.kotlinc(version).analyzeBranches("Switches")

        val withElse = kept(analysis, "enumWithElse").single()
        assertEquals(listOf("RED", "BLUE", "default"), outcomes(withElse))
        assertEquals(listOf(code("color")), withElse.site.condition)

        val exhaustive = kept(analysis, "enumExhaustive").single()
        assertEquals(
            listOf("RED", "BLUE", "GREEN"),
            outcomes(exhaustive),
            "labels follow the map class, and the throwing default is not listed",
        )
        assertTrue(exhaustive.site.throwingDefault)
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a when over a string drops the hash switch and reads each equals check, the Aa and BB collision included`(version: String) {
        val analysis = CompilerFixtures.kotlinc(version).analyzeBranches("Switches")
        val sites = kept(analysis, "stringWhen")

        assertEquals(
            listOf(
                listOf(code("status != "), literal("Aa")),
                listOf(code("status != "), literal("BB")),
                listOf(code("status != "), literal("closed")),
                listOf(code("status != "), literal("done")),
                listOf(code("status == "), literal("open")),
            ),
            sites.map { it.site.condition },
        )
        assertEquals(listOf(BranchDropReason.SWITCH_LOWERING), dropped(analysis, "stringWhen").map { it.dropReason })
        // Aa and BB share a hash: Aa's not-equal side reaches BB's check, which a real "BB" takes.
        // Every other not-equal side is reached only by a different string with the same hash.
        assertEquals(
            listOf(
                listOf(BranchRole.TAKEN, BranchRole.FALL_THROUGH),
                listOf(BranchRole.TAKEN),
                listOf(BranchRole.TAKEN),
                listOf(BranchRole.TAKEN),
                listOf(BranchRole.FALL_THROUGH),
            ),
            sites.map { site -> site.outcomes.map { it.role } },
        )
    }
}
