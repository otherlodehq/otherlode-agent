package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals

class ScalacMatrixTest {
    companion object {
        @JvmStatic
        fun versions(): List<String> = CompilerFixtures.scalacVersions

        @JvmStatic
        fun scala2Versions(): List<String> = CompilerFixtures.scalacVersions.filter { !it.startsWith("3.") }

        @JvmStatic
        fun scala3Versions(): List<String> = CompilerFixtures.scalacVersions.filter { it.startsWith("3.") }

        private fun hasEnumHashCode(version: String): Boolean {
            val (minor, patch) = version.split(".").drop(1).map { it.substringBefore('-').toInt() }
            return when (minor) {
                3 -> patch >= 7
                7 -> patch >= 3
                else -> minor > 7
            }
        }

        private const val ANONYMOUS = "${'$'}${'$'}anon"

        private val ANONYMOUS_NUMBER = Regex(Regex.escape(ANONYMOUS) + "\\${'$'}\\d+")

        private const val MODULE_READ_RESOLVE = "readResolve()Ljava/lang/Object;"

        private val ENUM_SINGLETON_CLASS = Regex(".+\\\$\\\$anon\\\$\\d+")
    }

    private data class Method(
        val owner: String,
        val name: String,
        val descriptor: String,
    ) {
        override fun toString() = "$owner.$name$descriptor"
    }

    private fun marksOf(build: CompilerFixtures.Build): Map<Method, GeneratedBy> =
        build
            .classNames()
            .flatMap { owner -> build.markEntries(owner).map { (name, descriptor, mark) -> Method(owner, name, descriptor) to mark } }
            .toMap()

    private fun absentIn(
        version: String,
        method: Method,
    ): Boolean =
        version.startsWith("2.12.") &&
            (
                method.name == "productElementName" ||
                    method.name == "productElementNames" ||
                    method.name == "productElementName\$extension" ||
                    method.name == "writeReplace"
            )

    private fun addedIn(
        version: String,
        method: Method,
    ): Boolean =
        (version.startsWith("2.12.") && method.name + method.descriptor == MODULE_READ_RESOLVE && method.owner.endsWith("$")) ||
            (isEnumSingletonHashCode(method) && version.startsWith("3."))

    private fun isEnumSingletonHashCode(method: Method) =
        method.name == "hashCode" && method.descriptor == "()I" && ENUM_SINGLETON_CLASS.matches(method.owner)

    private fun inReleaseTerms(
        version: String,
        method: Method,
    ): Method =
        if (version.startsWith("2.12.")) {
            method.copy(descriptor = method.descriptor.replace("Lscala/collection/immutable/Seq;", "Lscala/collection/Seq;"))
        } else {
            method
        }

    private fun overloadedExtension(
        version: String,
        method: Method,
    ): Method =
        if (version.startsWith("2.12.")) method.copy(name = method.name.replace(Regex("\\\$extension\\d+$")) { "\$extension" }) else method

    private fun differences(version: String): List<String> {
        val baseline = marksOf(CompilerFixtures.scalaBaseline(version)).mapKeys { inReleaseTerms(version, it.key) }
        val actual = marksOf(CompilerFixtures.scalac(version)).mapKeys { overloadedExtension(version, it.key) }
        val findings = mutableListOf<String>()
        for ((method, expected) in baseline) {
            val mark = actual[method]
            when {
                mark == null && expected != GeneratedBy.NONE && !absentIn(version, method) -> {
                    findings += "scalac $version: $method is only in the baseline, marked $expected"
                }

                mark != null && mark != expected -> {
                    findings += "scalac $version: $method expected $expected, got $mark"
                }
            }
        }
        for ((method, mark) in actual) {
            if (method !in baseline && mark != GeneratedBy.NONE && !addedIn(version, method)) {
                findings += "scalac $version: $method is only in this build, marked $mark"
            }
        }
        return findings
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("versions")
    fun `every method of every fixture class carries the mark it has in the baseline build`(version: String) {
        val findings = differences(version)

        assertTrue(
            marksOf(CompilerFixtures.scalaBaseline(version)).values.count { it != GeneratedBy.NONE } > 500,
            "the baseline marks little",
        )
        assertTrue(findings.isEmpty(), "${findings.size} differences:\n" + findings.joinToString("\n"))
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("versions")
    fun `the build ran its own compiler`(version: String) {
        assertTrue("version $version " in CompilerFixtures.scalacVersionOutput(version), CompilerFixtures.scalacVersionOutput(version))
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Versions")
    fun `a Scala 3 build's tasty header names the release that wrote it`(version: String) {
        val tasty = File(CompilerFixtures.scalac(version).directory, "com/example/scalatarget/Cc.tasty").readBytes()

        assertEquals("Scala $version", tastyTooling(tasty))
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Versions")
    fun `an enum singleton case's hashCode is ENUM in every build that has one`(version: String) {
        val build = CompilerFixtures.scalac(version)
        val hashCodes =
            build
                .classNames()
                .filter { ENUM_SINGLETON_CLASS.matches(it) }
                .flatMap { owner -> build.markEntries(owner).filter { it.first == "hashCode" } }

        assertTrue(hashCodes.all { it.third == GeneratedBy.ENUM }, hashCodes.toString())
        assertEquals(hasEnumHashCode(version), hashCodes.isNotEmpty(), "scalac $version")
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Versions")
    fun `a Scala 2 module class's readResolve is SCALA_OBJECT where the release writes one`(version: String) {
        val build = CompilerFixtures.scalac(version)
        val marks =
            build.classNames().filter { it.endsWith("$") }.flatMap { owner ->
                build.markEntries(owner).filter { it.first + it.second == MODULE_READ_RESOLVE }.map { owner to it.third }
            }

        if (version.startsWith("2.12.")) {
            assertTrue(marks.isNotEmpty(), "2.12 writes readResolve on every module class")
        }
        assertTrue(marks.all { it.second == GeneratedBy.SCALA_OBJECT }, marks.toString())
    }

    private fun stable(owner: String): String = owner.replace(ANONYMOUS_NUMBER, Regex.escapeReplacement(ANONYMOUS))

    private fun unreadOf(build: CompilerFixtures.Build): List<String> =
        build.classNames().flatMap { owner ->
            build.unreadEntries(owner).map { (name, descriptor, family) -> "${stable(owner)}.$name$descriptor $family" }
        }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Versions")
    fun `a Scala 3 build has no unread shapes on a read release`(version: String) {
        assertEquals(emptyList(), unreadOf(CompilerFixtures.scalac(version).withScalaLibrary(version)).sorted(), "scalac $version")
    }

    private val scala3VersionBlindUnread =
        setOf(
            "BodyVal.toString",
            "E3.equals",
            "FZC.canEqual",
            "HandCopy.hashCode",
            "HandCopy.toString",
            "Lie\$.fromProduct",
            "Lie._1",
            "Lie.equals",
            "Lie.hashCode",
            "Lie.productArity",
            "Lie.productElement",
            "Lie.productElementName",
            "Almost\$.hashCode\$extension",
            "Almost.twice",
            "Label\$.toString\$extension",
            "WrappingGreeter.greet",
            "WrappingHandler.reversed",
            "Lie2\$.fromProduct",
            "Lie2.equals",
            "Lie2.hashCode",
            "Lie2.productArity",
            "Lie2.productElement",
            "Lie2.productElementName",
            "LocalObjectHost\$Door\$3\$.ordinal",
            "Multi.toString",
            "NoCanEqual.equals",
            "Svc.toString",
            "UC2.canEqual",
            "UC2.equals",
            "Written.toString",
        )

    private val scala2Unread =
        setOf(
            "BodyVal.toString",
            "E3.equals",
            "FZC.canEqual",
            "HandCopy.toString",
            "HandCopy.hashCode",
            "Almost\$.hashCode\$extension",
            "Almost.twice",
            "Label\$.toString\$extension",
            "WrappingGreeter.greet",
            "WrappingHandler.reversed",
            "Lie.productArity",
            "Lie.productElement",
            "Lie.productElementName",
            "Lie.hashCode",
            "Lie.equals",
            "Lie\$.unapply",
            "Lie2.productArity",
            "Lie2.productElement",
            "Lie2.productElementName",
            "Lie2.hashCode",
            "Lie2.equals",
            "Lie2\$.unapply",
            "Multi.toString",
            "NoCanEqual.equals",
            "Q.productArity",
            "Q.productElement",
            "Q.productElementName",
            "Q.hashCode",
            "Q.equals",
            "Q\$.unapply",
            "Svc.toString",
            "U0\$.unapply",
            "UC2.canEqual",
            "UC2.equals",
            "Written.toString",
        )

    private fun assertForwardersSurviveWithoutLibrary(
        version: String,
        readable: CompilerFixtures.Build,
        unreadable: CompilerFixtures.Build,
    ) {
        for (owner in readable.classNames()) {
            val marks = unreadable.markEntries(owner).associate { it.first + it.second to it.third }
            val unread = unreadable.unreadEntries(owner).associate { it.first + it.second to it.third }
            for ((name, descriptor, mark) in readable.markEntries(owner)) {
                if (mark != GeneratedBy.TRAIT_FORWARDER) continue
                val method = name + descriptor
                assertTrue(
                    marks[method] == GeneratedBy.TRAIT_FORWARDER || unread[method] == UnreadShape.TRAIT_FORWARDER,
                    "scalac $version $owner.$method reads as ordinary code without the library",
                )
            }
        }
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Versions")
    fun `a Scala 2 build has unread shapes only on the fixtures' hand-written lookalikes and Q`(version: String) {
        val build = CompilerFixtures.scalac(version).withScalaLibrary(version)
        val unread =
            build.classNames().flatMap { owner -> build.unreadEntries(owner).map { (name, _, _) -> "$owner.$name" } }.toSet()
        assertForwardersSurviveWithoutLibrary(version, build, CompilerFixtures.scalac(version))
        val expected =
            if (version.startsWith(
                    "2.12.",
                )
            ) {
                scala2Unread.filterNot { it.endsWith(".productElementName") }.toSet()
            } else {
                scala2Unread
            }

        assertEquals(expected.sorted(), unread.sorted(), "scalac $version")
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Versions")
    fun `a Scala 3 build read with no tasty files reports the outline methods the rules leave unmarked`(version: String) {
        val unread =
            CompilerFixtures.scalac(version).withScalaLibrary(version).withoutResources().let { build ->
                build.classNames().flatMap { owner -> build.unreadEntries(owner).map { (name, _, _) -> "${stable(owner)}.$name" } }.toSet()
            }
        assertForwardersSurviveWithoutLibrary(
            version,
            CompilerFixtures.scalac(version).withScalaLibrary(version).withoutResources(),
            CompilerFixtures.scalac(version).withoutResources(),
        )

        assertEquals(scala3VersionBlindUnread.sorted(), unread.sorted(), "scalac $version")
    }

    @Test
    fun `every Scala 3 release in the matrix is on the read list`() {
        val unread = CompilerFixtures.scalacVersions.filter { it.startsWith("3.") && !ScalaReleases.isRead(it) }

        assertEquals(
            emptyList(),
            unread,
            "Scala 3 releases compiled in the matrix but not read: sweep each one (run the matrix with " +
                "-Potherlode.matrix.scalac=<releases>, Scala 2 releases kept in the list) and, if it passes, add it to " +
                "scala3-read-releases.txt; if it fails, the release writes a shape the agent has not read",
        )
    }

    private fun tastyTooling(bytes: ByteArray): String {
        var at = 4

        fun nat(): Int {
            var value = 0
            while (true) {
                val b = bytes[at++].toInt()
                value = (value shl 7) or (b and 0x7f)
                if (b and 0x80 != 0) return value
            }
        }
        repeat(3) { nat() }
        val length = nat()
        return String(bytes, at, length, Charsets.UTF_8)
    }
}
