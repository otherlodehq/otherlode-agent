package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BranchRole
import dev.otherlode.export.CallEdge
import dev.otherlode.export.ConditionPart
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes
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
        private const val DESCRIBER = "Lcom/example/target/kotlinc/Describer;"
        private const val DESCRIBER_IMPL = "com/example/target/kotlinc/DescriberImpl"
        private const val STRING = "Ljava/lang/String;"
        private const val PLAIN_DESCRIBER = "com/example/target/kotlinc/PlainDescriber"

        /**
         * Where a call to a `Describer.plain` stub leads once passed through: the `$DefaultImpls`
         * method before kotlinc 2.2, and the interface's own method from 2.2, whose stub is a bridge.
         */
        private fun stubBody(version: String): CallEdge =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
                CallEdge("com.example.target.kotlinc.Describer", "plain", "()$STRING", virtual = false)
            } else {
                CallEdge("com.example.target.kotlinc.Describer\$DefaultImpls", "plain", "($DESCRIBER)$STRING", virtual = false)
            }

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
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.DEFAULT_IMPLS else GeneratedBy.NONE

        assertEquals(expected, marks.of("greet", "($GREETER)Ljava/lang/String;"), "greet")
        assertEquals(expected, marks.of("tag", "(${GREETER}Ljava/lang/String;)Ljava/lang/String;"), "tag")
        assertEquals(expected, marks.of("getLabel", "($GREETER)Ljava/lang/String;"), "getLabel")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a DefaultImpls forwarder is DEFAULT_IMPLS under jvm-default enable whatever its parameters, the null-checked one included`(
        version: String,
    ) {
        val marks = CompilerFixtures.kotlinc(version).marks("Describer\$DefaultImpls")
        val expected =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.DEFAULT_IMPLS else GeneratedBy.NONE

        assertEquals(expected, marks.of("describe", "($DESCRIBER$STRING)$STRING"), "describe takes a non-null String")
        assertEquals(expected, marks.of("maybe", "($DESCRIBER$STRING)$STRING"), "maybe takes a nullable String")
        assertEquals(expected, marks.of("plain", "($DESCRIBER)$STRING"), "plain takes nothing")
        assertEquals(expected, marks.of("getTitle", "($DESCRIBER)$STRING"), "getTitle is a property getter")
    }

    private fun accessOf(
        version: String,
        simpleName: String,
        name: String,
    ): Int {
        var access = -1
        ClassReader(CompilerFixtures.kotlinc(version).classBytes(simpleName)).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access0: Int,
                    name0: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ) = null.also { if (name0 == name) access = access0 }
            },
            ClassReader.SKIP_CODE,
        )
        return access
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `an implementing class's stub is DEFAULT_IMPLS under jvm-default disable and an unmarked bridge from 2_2`(version: String) {
        val marks = CompilerFixtures.kotlinc(version).marks("DescriberImpl")
        val stubs = listOf("describe", "maybe", "plain", "getTitle")

        assertEquals(GeneratedBy.NONE, marks.only("id"), "id is the class's own")
        assertEquals(GeneratedBy.NONE, marks.only("<init>"))
        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
            stubs.forEach {
                assertEquals(GeneratedBy.NONE, marks.only(it), it)
                assertTrue(accessOf(version, "DescriberImpl", it) and Opcodes.ACC_BRIDGE != 0, "$it is a bridge")
            }
        } else {
            stubs.forEach {
                assertEquals(GeneratedBy.DEFAULT_IMPLS, marks.only(it), it)
                assertEquals(
                    0,
                    accessOf(version, "DescriberImpl", it) and (Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC),
                    "$it is a plain method",
                )
            }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a stub for a generic interface's default is DEFAULT_IMPLS though its parameter and return types are the type argument`(
        version: String,
    ) {
        val marks = CompilerFixtures.kotlinc(version).marks("StringStore")
        val expected =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.NONE else GeneratedBy.DEFAULT_IMPLS

        assertEquals(expected, marks.of("load", "()Ljava/lang/String;"), "load returns the type argument through a checkcast")
        assertEquals(expected, marks.of("save", "(Ljava/lang/String;)Ljava/lang/String;"), "save takes the type argument")
        assertEquals(expected, marks.of("count", "(I)J"), "count's types are not the type parameter")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a stub in a class that reaches the default only through a sub-interface forwards to that interface's DefaultImpls and is marked`(
        version: String,
    ) {
        val marks = CompilerFixtures.kotlinc(version).marks("SubDescriberImpl")
        val expected =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.NONE else GeneratedBy.DEFAULT_IMPLS

        listOf("describe", "maybe", "plain", "getTitle").forEach { assertEquals(expected, marks.only(it), it) }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `an ordinary method that only forwards to a hand-written function is unmarked`(version: String) {
        assertEquals(GeneratedBy.NONE, CompilerFixtures.kotlinc(version).marks("HandForwarder").only("forwardOnly"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a call to a disable-mode stub reaches the DefaultImpls method, and under enable the bridge passes through to the interface`(
        version: String,
    ) {
        val calls = CompilerFixtures.kotlinc(version).analyze("DescriberCaller").callsOf("call", "(L$DESCRIBER_IMPL;)$STRING")
        val defaultImpls = "com.example.target.kotlinc.Describer\$DefaultImpls"

        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
            assertTrue(CallEdge("com.example.target.kotlinc.Describer", "plain", "()$STRING", virtual = false) in calls, "calls: $calls")
            assertTrue(
                calls.none { it.className.endsWith("DescriberImpl") && it.methodName != "<clinit>" },
                "the bridge is passed through: $calls",
            )
        } else {
            assertTrue(CallEdge(defaultImpls, "plain", "($DESCRIBER)$STRING", virtual = false) in calls, "calls: $calls")
            assertTrue(CallEdge(defaultImpls, "describe", "($DESCRIBER$STRING)$STRING", virtual = false) in calls, "calls: $calls")
            assertTrue(
                calls.none { it.className.endsWith("DescriberImpl") && it.methodName != "<clinit>" },
                "the stub is passed through: $calls",
            )
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a stub that boxes a primitive type argument before calling DefaultImpls is DEFAULT_IMPLS`(version: String) {
        val marks = CompilerFixtures.kotlinc(version).marks("IntTally")
        val expected =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.NONE else GeneratedBy.DEFAULT_IMPLS

        assertEquals(expected, marks.of("add", "(I)$STRING"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a virtual call to an open class's stub passes through and keeps its edge to the stub, so a subclass override stays reachable`(
        version: String,
    ) {
        val calls =
            CompilerFixtures
                .kotlinc(version)
                .analyze("OpenDescriberCaller")
                .callsOf("call", "(Lcom/example/target/kotlinc/OpenDescriber;)$STRING")

        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
            assertTrue(CallEdge("com.example.target.kotlinc.OpenDescriber", "plain", "()$STRING", virtual = true) in calls, "calls: $calls")
        } else {
            assertTrue(
                CallEdge("com.example.target.kotlinc.Describer\$DefaultImpls", "plain", "($DESCRIBER)$STRING", virtual = false) in calls,
                "the stub is passed through: $calls",
            )
            assertTrue(
                CallEdge("com.example.target.kotlinc.OpenDescriber", "plain", "()$STRING", virtual = true) in calls,
                "the virtual edge to the stub is kept for the override: $calls",
            )
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a call to a stub through a class that only inherits it passes through the stub and keeps its edge to the class called`(
        version: String,
    ) {
        val build = CompilerFixtures.kotlinc(version)
        val fromCaller = build.analyzeAsMethodTier("PlainDescriberCaller").callsOf("call", "(L$PLAIN_DESCRIBER;)$STRING")
        val fromItself = build.analyzeAsMethodTier("PlainDescriber").callsOf("inherited", "()$STRING")

        listOf(fromCaller, fromItself).forEach { calls ->
            assertTrue(stubBody(version) in calls, "the inherited stub is passed through: $calls")
            assertTrue(
                CallEdge("com.example.target.kotlinc.PlainDescriber", "plain", "()$STRING", virtual = true) in calls,
                "the edge to the class called is kept: $calls",
            )
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `an open class's call to its own stub passes through and keeps the virtual edge, so an override stays reachable`(version: String) {
        val calls = CompilerFixtures.kotlinc(version).analyzeAsMethodTier("OpenDescriber").callsOf("own", "()$STRING")

        assertTrue(stubBody(version) in calls, "the stub is passed through: $calls")
        assertTrue(
            CallEdge("com.example.target.kotlinc.OpenDescriber", "plain", "()$STRING", virtual = true) in calls,
            "the virtual edge to the stub is kept: $calls",
        )
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a call through the interface type reaches the DefaultImpls body under jvm-default disable, the interface method from 2_2`(
        version: String,
    ) {
        val calls = CompilerFixtures.kotlinc(version).analyzeAsMethodTier("InterfaceCaller").callsOf("call", "($DESCRIBER)$STRING")
        val viaInterface = CallEdge("com.example.target.kotlinc.Describer", "plain", "()$STRING", virtual = true)
        val defaultImplsBody =
            CallEdge("com.example.target.kotlinc.Describer\$DefaultImpls", "plain", "($DESCRIBER)$STRING", virtual = false)

        assertTrue(viaInterface in calls, "calls: $calls")
        if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) {
            assertTrue(defaultImplsBody !in calls, "the interface method holds the body: $calls")
        } else {
            assertTrue(defaultImplsBody in calls, "the abstract interface method's code is reached: $calls")
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a stub inherited from two levels up is passed through, and an inherited method that is no stub adds nothing`(version: String) {
        val analysis = CompilerFixtures.kotlinc(version).analyzeAsMethodTier("DeepDescriberCaller")
        val deep = "Lcom/example/target/kotlinc/DeepDescriber;"
        val stubCalls = analysis.callsOf("call", "($deep)$STRING")
        val ordinaryCalls = analysis.callsOf("callOrdinary", "($deep)$STRING")

        assertTrue(stubBody(version) in stubCalls, "calls: $stubCalls")
        assertTrue(CallEdge("com.example.target.kotlinc.DeepDescriber", "plain", "()$STRING", virtual = true) in stubCalls)
        assertEquals(
            listOf(CallEdge("com.example.target.kotlinc.DeepDescriber", "id", "()$STRING", virtual = true)),
            ordinaryCalls,
            "an inherited ordinary method keeps only its verbatim edge",
        )
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `the walk up stops at a superclass outside the include rules`(version: String) {
        val calls =
            CompilerFixtures
                .kotlinc(version)
                .analyzeAsMethodTier("InsideDescriberCaller")
                .callsOf("call", "(Lcom/example/target/kotlinc/InsideDescriber;)$STRING")

        assertEquals(listOf(CallEdge("com.example.target.kotlinc.InsideDescriber", "plain", "()$STRING", virtual = true)), calls)
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a super call to an inherited stub passes through with no virtual edge`(version: String) {
        val calls = CompilerFixtures.kotlinc(version).analyzeAsMethodTier("SuperCallingDescriber").callsOf("viaSuper", "()$STRING")

        assertTrue(stubBody(version) in calls, "calls: $calls")
        assertTrue(calls.none { it.virtual && it.methodName == "plain" }, "invokespecial runs exactly the stub: $calls")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a sub-interface's DefaultImpls methods for inherited defaults are DEFAULT_IMPLS, erased and boxed ones included`(
        version: String,
    ) {
        val build = CompilerFixtures.kotlinc(version)
        val sub = build.marks("SubDescriber\$DefaultImpls")
        val strings = build.marks("StringStoreApi\$DefaultImpls")
        val ints = build.marks("IntTallyApi\$DefaultImpls")
        val subDescriber = "Lcom/example/target/kotlinc/SubDescriber;"

        listOf("describe", "maybe").forEach {
            assertEquals(GeneratedBy.DEFAULT_IMPLS, sub.of(it, "($subDescriber$STRING)$STRING"), it)
        }
        assertEquals(GeneratedBy.DEFAULT_IMPLS, sub.of("plain", "($subDescriber)$STRING"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, sub.of("getTitle", "($subDescriber)$STRING"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, strings.of("load", "(Lcom/example/target/kotlinc/StringStoreApi;)$STRING"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, strings.of("save", "(Lcom/example/target/kotlinc/StringStoreApi;$STRING)$STRING"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, strings.of("count", "(Lcom/example/target/kotlinc/StringStoreApi;I)J"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, ints.of("add", "(Lcom/example/target/kotlinc/IntTallyApi;I)$STRING"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `forwarders that box a primitive result or a suspend argument are DEFAULT_IMPLS`(version: String) {
        val build = CompilerFixtures.kotlinc(version)
        val forwarders = build.marks("IntEcho\$DefaultImpls")
        val stubs = build.marks("IntEchoImpl")
        val intEcho = "Lcom/example/target/kotlinc/IntEcho;"
        val continuation = "Lkotlin/coroutines/Continuation;"
        val stubExpected =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.NONE else GeneratedBy.DEFAULT_IMPLS

        assertEquals(GeneratedBy.DEFAULT_IMPLS, forwarders.of("echo", "(${intEcho}I)Ljava/lang/Integer;"))
        assertEquals(GeneratedBy.DEFAULT_IMPLS, forwarders.of("later", "(${intEcho}I$continuation)Ljava/lang/Object;"))
        assertEquals(stubExpected, stubs.of("echo", "(I)Ljava/lang/Integer;"))
        assertEquals(stubExpected, stubs.of("later", "(I$continuation)Ljava/lang/Object;"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `an override that only calls super is the stub's body under jvm-default disable and is marked like one`(version: String) {
        val marks = CompilerFixtures.kotlinc(version).marks("SuperDescriber")
        val expected =
            if (CompilerFixtures.kotlincDefaultsToJvmDefaultEnable(version)) GeneratedBy.NONE else GeneratedBy.DEFAULT_IMPLS

        assertEquals(expected, marks.of("plain", "()$STRING"), "under enable, super.plain() is an invokespecial on the interface")
        assertEquals(GeneratedBy.NONE, marks.of("id", "()$STRING"))
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
