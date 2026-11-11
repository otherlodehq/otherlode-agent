package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

class KotlinDelegationMatrixTest {
    companion object {
        @JvmStatic
        fun versions(): List<String> = CompilerFixtures.kotlincVersions

        private val NOT_MEMBERS = setOf("<init>", "<clinit>")
    }

    private fun entries(
        version: String,
        simpleName: String,
    ) = CompilerFixtures.kotlinc(version).markEntries(simpleName)

    private fun assertDelegated(
        version: String,
        simpleName: String,
        byHand: Set<String> = emptySet(),
    ) {
        val members = entries(version, simpleName).filter { it.first !in NOT_MEMBERS }
        assertEquals(
            emptyList(),
            members.filter { (name, _, mark) -> mark != if (name in byHand) GeneratedBy.NONE else GeneratedBy.CLASS_DELEGATION },
            "$simpleName members that are not as expected",
        )
        assertEquals(byHand, members.filter { it.third == GeneratedBy.NONE }.map { it.first }.toSet())
        assertEquals(GeneratedBy.NONE, entries(version, simpleName).single { it.first == "<init>" }.third)
    }

    private fun assertNoneMarked(
        version: String,
        simpleName: String,
    ) {
        assertEquals(
            emptyList(),
            entries(version, simpleName).filter { it.third != GeneratedBy.NONE },
            "$simpleName members that are marked",
        )
    }

    private fun assertProxies(
        version: String,
        simpleName: String,
        proxies: Set<String>,
    ) {
        val members = entries(version, simpleName)
        assertEquals(proxies, members.filter { it.third == GeneratedBy.STATIC_FORWARDER }.map { it.first }.toSet(), "$simpleName proxies")
        assertEquals(emptyList(), members.filter { it.third !in setOf(GeneratedBy.NONE, GeneratedBy.STATIC_FORWARDER) }, simpleName)
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `every member a class delegates is CLASS_DELEGATION, whatever its parameters, return, nullability or suspension`(version: String) {
        assertDelegated(version, "AllDelegated")
        val members = entries(version, "AllDelegated").map { it.first }
        listOf("noArgs", "smalls", "wides", "nonNull", "nullable", "unitReturn", "unitWithArg", "generic", "list", "mixed").forEach {
            assertEquals(true, it in members, it)
        }
        listOf("getTitle", "getLevel", "setLevel", "getLabel", "setLabel", "fetch", "fetchUnit").forEach { assertEquals(true, it in members, it) }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a class that delegates two interfaces has both sets of forwarders marked`(version: String) {
        assertDelegated(version, "TwoDelegates")
        assertEquals(true, entries(version, "TwoDelegates").any { it.first == "other" })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a member overridden by hand stays unmarked and the other forwarders are still marked`(version: String) {
        assertDelegated(version, "OverridesOne", byHand = setOf("noArgs"))
        assertDelegated(version, "OverridesWithCode", byHand = setOf("nonNull"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a delegated interface's default members are forwarded and marked, and a default overridden by hand is not`(version: String) {
        assertDelegated(version, "DelegatesDefaults")
        assertDelegated(version, "DelegatesAndOverridesDefault", byHand = setOf("defaultPlain"))
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `forwarders with a value class in their signature are marked`(version: String) {
        assertDelegated(version, "DelegatesValued")
        assertEquals(true, entries(version, "DelegatesValued").any { it.first.startsWith("takeToken-") })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `forwarders of an abstract class, of an inherited member, of a public property and of an object expression are marked`(version: String) {
        assertDelegated(version, "AbstractDelegating")
        assertDelegated(version, "DelegatesSub")
        assertDelegated(version, "DelegatesPublicProperty", byHand = setOf("getInner"))
        assertDelegated(version, "DelegatesExpression")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a forwarder that casts or boxes for a type argument, and the bridges beside it, are not marked`(version: String) {
        assertNoneMarked(version, "StringFeed")
        assertNoneMarked(version, "IntFeed")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a class that delegates through a property typed as a sub-interface is not marked`(version: String) {
        assertNoneMarked(version, "ViaSub")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `the interfaces a class delegates are not marked`(version: String) {
        listOf("Shaped", "Other", "WithDefaults", "Valued").forEach { assertNoneMarked(version, it) }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a static proxy for a JvmStatic companion function or accessor is STATIC_FORWARDER`(version: String) {
        val members = entries(version, "WithCompanion")
        val proxies = members.filter { it.third == GeneratedBy.STATIC_FORWARDER }.map { it.first }
        val expected =
            listOf(
                "noArgs", "smalls", "wides", "nonNull", "nullable", "unitReturn", "generic", "getStaticValue", "getStaticVar",
                "setStaticVar", "getComputed", "withBranch", "suspending",
            )
        expected.forEach { assertEquals(true, it in proxies, "$it is a proxy") }
        assertEquals(true, proxies.any { it.startsWith("valued-") })
        assertEquals(true, proxies.any { it.startsWith("givesToken-") })
        assertEquals(expected.size + 2, proxies.size)
        members.filter { it.third == GeneratedBy.NONE }.map { it.first }.let { unmarked ->
            listOf("<init>", "<clinit>", "instanceMethod", "access\$getStaticValue\$cp", "access\$getStaticVar\$cp", "access\$setStaticVar\$cp")
                .forEach { assertEquals(true, it in unmarked, "$it stays unmarked") }
        }
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `the companion holds the code and is not marked`(version: String) {
        assertNoneMarked(version, "WithCompanion\$Companion")
        assertNoneMarked(version, "WithNamedCompanion\$Factory")
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a proxy through a named companion, an interface companion, a renamed function and an overload is STATIC_FORWARDER`(version: String) {
        assertProxies(version, "WithNamedCompanion", setOf("create", "none"))
        assertProxies(version, "WithInterfaceCompanion", setOf("make"))
        assertProxies(version, "RenamedStatic", setOf("renamed"))
        assertProxies(version, "OverloadedStatic", setOf("two"))
        assertEquals(listOf(GeneratedBy.STATIC_FORWARDER, GeneratedBy.STATIC_FORWARDER), entries(version, "OverloadedStatic").filter { it.first == "two" }.map { it.third })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("versions")
    fun `a JvmStatic function in an object holds its own body and a private one goes through an accessor, so neither is marked`(version: String) {
        assertNoneMarked(version, "PlainObjectStatics")
        assertNoneMarked(version, "PrivateStatic")
    }
}
