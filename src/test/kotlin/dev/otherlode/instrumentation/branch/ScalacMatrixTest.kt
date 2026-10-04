package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals

/**
 * Runs the Scala generated-method rules over the fixture sources compiled by each scalac release in
 * the matrix (`fixtures-compilers/scalac`) and compares every method's mark with the one the same
 * method gets in that line's baseline build, 2.13.15 for a 2.x release and 3.3.4 for a 3.x one.
 * One comparison pins both directions: scalac's plumbing marked in the baseline must be marked in
 * every release, and a hand-written lookalike left unmarked in the baseline must stay unmarked.
 */
class ScalacMatrixTest {
    companion object {
        @JvmStatic
        fun versions(): List<String> = CompilerFixtures.scalacVersions

        @JvmStatic
        fun scala2Versions(): List<String> = CompilerFixtures.scalacVersions.filter { !it.startsWith("3.") }

        @JvmStatic
        fun scala3Versions(): List<String> = CompilerFixtures.scalacVersions.filter { it.startsWith("3.") }

        private const val MODULE_READ_RESOLVE = "readResolve()Ljava/lang/Object;"

        /**
         * Scala 3.3.7 and later, and 3.7.3 and later, give the class of an enum singleton case
         * (`Color$$anon$1`) a `hashCode`. That is enum plumbing, which the enum rules own, so the
         * comparison leaves it out and the test only requires that it stays unmarked.
         */
        private val ENUM_SINGLETON_HASH_CODE = Regex("[A-Za-z0-9]+\\\$\\\$anon\\\$\\d+")
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

    /** Whether [method] is one the baseline of this line has and release [version] does not write. */
    private fun absentIn(
        version: String,
        method: Method,
    ): Boolean =
        version.startsWith("2.12.") &&
            (method.name == "productElementName" || method.name == "productElementNames" || method.name == "writeReplace")

    /** Whether [method] is one release [version] writes and the baseline of its line does not. */
    private fun addedIn(
        version: String,
        method: Method,
    ): Boolean =
        (version.startsWith("2.12.") && method.name + method.descriptor == MODULE_READ_RESOLVE && method.owner.endsWith("$")) ||
            (isEnumSingletonHashCode(method) && version.startsWith("3."))

    private fun isEnumSingletonHashCode(method: Method) =
        method.name == "hashCode" && method.descriptor == "()I" && ENUM_SINGLETON_HASH_CODE.matches(method.owner)

    /**
     * The baseline's key for a method as release [version] spells it. Scala 2.12's `Int*` parameter
     * is a `scala.collection.Seq`, where 2.13 made it `scala.collection.immutable.Seq`, so the
     * hand-written `VarargHost.withVararg` has a different descriptor and is still the same method.
     */
    private fun inReleaseTerms(
        version: String,
        method: Method,
    ): Method =
        if (version.startsWith("2.12.")) {
            method.copy(descriptor = method.descriptor.replace("Lscala/collection/immutable/Seq;", "Lscala/collection/Seq;"))
        } else {
            method
        }

    private fun differences(version: String): List<String> {
        val baseline = marksOf(CompilerFixtures.scalaBaseline(version)).mapKeys { inReleaseTerms(version, it.key) }
        val actual = marksOf(CompilerFixtures.scalac(version))
        val findings = mutableListOf<String>()
        for ((method, expected) in baseline) {
            val mark = actual[method]
            when {
                mark == null && !absentIn(version, method) -> findings += "scalac $version: $method is only in the baseline"
                mark != null && mark != expected -> findings += "scalac $version: $method expected $expected, got $mark"
            }
        }
        for ((method, mark) in actual) {
            if (method !in baseline && !addedIn(version, method)) {
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
    fun `an enum singleton case's hashCode is left unmarked`(version: String) {
        val build = CompilerFixtures.scalac(version)
        val hashCodes =
            build
                .classNames()
                .filter { ENUM_SINGLETON_HASH_CODE.matches(it) }
                .flatMap { owner -> build.markEntries(owner).filter { it.first == "hashCode" } }

        assertTrue(hashCodes.all { it.third == GeneratedBy.NONE }, hashCodes.toString())
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

    /** The tooling string of a `.tasty` file: the header's magic, three version numbers, then the string. */
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
