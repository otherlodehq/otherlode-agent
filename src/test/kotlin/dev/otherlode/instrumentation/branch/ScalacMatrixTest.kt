package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

        /**
         * Whether the 3.x release [version] gives an enum singleton case a `hashCode`. scalac writes
         * one from 3.3.7 on the 3.3 line and from 3.7.3, so 3.3.3, 3.3.6, 3.4.x to 3.6.x, 3.7.0 and
         * 3.7.2 have none.
         */
        private fun hasEnumHashCode(version: String): Boolean {
            val (minor, patch) = version.split(".").drop(1).map { it.substringBefore('-').toInt() }
            return when (minor) {
                3 -> patch >= 7
                7 -> patch >= 3
                else -> minor > 7
            }
        }

        private const val SCALA_PACKAGE = "com/example/scalatarget"

        /** scalac's infix for an anonymous class, `$$anon`. */
        private const val ANONYMOUS = "${'$'}${'$'}anon"

        /** An anonymous class's infix and the number scalac gives it. */
        private val ANONYMOUS_NUMBER = Regex(Regex.escape(ANONYMOUS) + "\\${'$'}\\d+")

        private const val MODULE_READ_RESOLVE = "readResolve()Ljava/lang/Object;"

        /**
         * An anonymous class's name, the shape of an enum singleton case's class (`Color$$anon$1`,
         * `EnumHost$Mode$$anon$6`). Scala 3.3.7 and later on the 3.3 line, and 3.7.3 and later, give
         * such a class a `hashCode` the baseline does not have. The fixtures declare no other
         * anonymous class with a `hashCode`, so every one this matches is an enum case's.
         */
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
        method.name == "hashCode" && method.descriptor == "()I" && ENUM_SINGLETON_CLASS.matches(method.owner)

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
        // A method only one build has is a finding only when it is marked: releases name lambdas and
        // anonymous classes' constructors differently (3.3.3 and 3.4.0 against 3.3.4), and an
        // unmarked method has no mark to lose.
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

    /** An owner's name with an anonymous class's number dropped, since scalac numbers them in source order. */
    private fun stable(owner: String): String = owner.replace(ANONYMOUS_NUMBER, Regex.escapeReplacement(ANONYMOUS))

    private fun unreadOf(build: CompilerFixtures.Build): List<String> =
        build.classNames().flatMap { owner ->
            build.unreadEntries(owner).map { (name, descriptor, family) -> "${stable(owner)}.$name$descriptor $family" }
        }

    /**
     * The only unread shapes in a Scala 3 build, every release in the matrix being read: the
     * plumbing of an enum declared inside a class (`EnumHolder.Inner`), whose companion has no
     * `MODULE$` and so matches none of the read shapes, and which scalac refuses to let the adopter
     * write. Its `ordinal(Inner)` can be written by hand and so is not among them.
     */
    private val scala3ReadUnread =
        listOf(
            "EnumHolder\$\$anon.readResolve()Ljava/lang/Object; SCALA_ENUM",
            "EnumHolder\$Inner\$.values()[L$SCALA_PACKAGE/EnumHolder\$Inner; SCALA_ENUM",
            "EnumHolder\$Inner\$.valueOf(Ljava/lang/String;)L$SCALA_PACKAGE/EnumHolder\$Inner; SCALA_ENUM",
            "EnumHolder\$Inner\$.\$new(ILjava/lang/String;)L$SCALA_PACKAGE/EnumHolder\$Inner; SCALA_ENUM",
            "EnumHolder\$Inner\$.fromOrdinal(I)L$SCALA_PACKAGE/EnumHolder\$Inner; SCALA_ENUM",
            "EnumHolder\$Inner\$.ordinal(Ljava/lang/Object;)I SCALA_ENUM",
        )

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Versions")
    fun `a Scala 3 build's only unread shapes are the plumbing of an enum declared inside a class`(version: String) {
        assertEquals(scala3ReadUnread.sorted(), unreadOf(CompilerFixtures.scalac(version)).sorted(), "scalac $version")
    }

    /**
     * The methods every Scala 3 build reports as unread shapes when no `.tasty` file can be read,
     * as `Owner.name`: the fixtures' hand-written methods inside an outline (`toString` of
     * `Written`, `Multi`, `Svc`, `BodyVal` and `HandCopy`, `HandCopy.hashCode`, and the lookalikes
     * of `CaseLookalikes.scala`), which with no compiler named cannot be told from scalac's, and the
     * plumbing of the enum declared inside `EnumHolder`.
     */
    private val scala3VersionBlindUnread =
        setOf(
            "BodyVal.toString",
            "E3.equals",
            "EnumHolder\$\$anon.readResolve",
            "EnumHolder\$Inner\$.\$new",
            "EnumHolder\$Inner\$.fromOrdinal",
            "EnumHolder\$Inner\$.ordinal",
            "EnumHolder\$Inner\$.valueOf",
            "EnumHolder\$Inner\$.values",
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
            "Lie2\$.fromProduct",
            "Lie2.equals",
            "Lie2.hashCode",
            "Lie2.productArity",
            "Lie2.productElement",
            "Lie2.productElementName",
            "Multi.toString",
            "NoCanEqual.equals",
            "Svc.toString",
            "UC2.canEqual",
            "UC2.equals",
            "Written.toString",
        )

    /**
     * The methods a Scala 2 build reports as unread shapes, as `Owner.name`. A Scala 2 class names
     * no compiler, so the rules are version-blind. Three groups:
     *
     * - The adopter's own overrides of plumbing, which scalac 2 cannot be told apart from: `toString`
     *   of `Written`, `Multi`, `Svc`, `BodyVal` and `HandCopy`, `HandCopy.hashCode`, and the
     *   lookalikes of `CaseLookalikes.scala` (`Lie`, `Lie2`, `E3`, `NoCanEqual`, `UC2`, `FZC`, and
     *   `U0$.unapply`), each hand-written in or near scalac's shape.
     * - `Q`, whose element overrides a supertype's `val`: scalac's own plumbing for it, which the
     *   rules do not read because the constructor stores no element.
     */

    private val scala2Unread =
        setOf(
            "BodyVal.toString",
            "E3.equals",
            "FZC.canEqual",
            "HandCopy.toString",
            "HandCopy.hashCode",
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

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala2Versions")
    fun `a Scala 2 build has unread shapes only on the fixtures' hand-written lookalikes and Q`(version: String) {
        val build = CompilerFixtures.scalac(version)
        val unread =
            build.classNames().flatMap { owner -> build.unreadEntries(owner).map { (name, _, _) -> "$owner.$name" } }.toSet()
        // 2.12 writes no productElementName.
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
            CompilerFixtures.scalac(version).withoutResources().let { build ->
                build.classNames().flatMap { owner -> build.unreadEntries(owner).map { (name, _, _) -> "${stable(owner)}.$name" } }.toSet()
            }

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
