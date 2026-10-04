package dev.otherlode.instrumentation.branch

import dev.otherlode.export.KotlinKind
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.CompilerFixtures
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the outlines of ADR 0054 that no source produces, coroutine machinery, string switch
 * lowering and multi-file facade methods, stay read on real output: across every kotlinc release,
 * every javac version and every scalac release in the matrix, no branch outcome is an unread
 * shape, and no facade method is one.
 */
class UnreadOutlineMatrixTest {
    companion object {
        @JvmStatic
        fun kotlincVersions(): List<String> = CompilerFixtures.kotlincVersions

        @JvmStatic
        fun javacVersions(): List<String> = CompilerFixtures.javacVersions

        @JvmStatic
        fun scalacVersions(): List<String> = CompilerFixtures.scalacVersions
    }

    private fun outcomesNotRead(build: CompilerFixtures.Build): List<String> =
        build.classNames().flatMap { name ->
            build
                .analyze(name)
                .keptSites
                .flatMap { site ->
                    site.outcomes.filter { it.unreadShape != UnreadShape.NONE }.map { "$name line ${site.site.line}: ${it.unreadShape}" }
                }
        }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("kotlincVersions")
    fun `no kotlinc outcome and no method is an unread shape, but for a string when whose null shares a branch`(version: String) {
        val build = CompilerFixtures.kotlinc(version)

        assertEquals(emptyList(), outcomesNotRead(build).filterNot { it.startsWith("AdopterShapesKt ") })
        assertTrue(
            build
                .analyze("AdopterShapesKt")
                .keptSites
                .filter { it.site.methodName != "nullSharingWhen" }
                .flatMap { it.outcomes }
                .all { it.unreadShape == UnreadShape.NONE },
            "an adopter's own compares with the marker and own hash switch stay the adopter's",
        )
        val unreadMethods = build.classNames().flatMap { name -> build.unreadEntries(name).map { "$name.${it.first}: ${it.third}" } }
        assertEquals(emptyList(), unreadMethods)
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("kotlincVersions")
    fun `a string when whose null shares a branch is a lowering not read, its collision sides unread shapes`(version: String) {
        val sites =
            CompilerFixtures
                .kotlinc(
                    version,
                ).analyze("AdopterShapesKt")
                .keptSites
                .filter { it.site.methodName == "nullSharingWhen" }
        val unread = sites.flatMap { it.outcomes }.filter { it.unreadShape != UnreadShape.NONE }

        assertTrue(unread.isNotEmpty(), "the lowering is not read, so its collision sides are reported")
        assertTrue(unread.all { it.unreadShape == UnreadShape.STRING_SWITCH })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("kotlincVersions")
    fun `an adopter's compares with the suspended marker held in a local stay kept conditionals`(version: String) {
        val analysis = CompilerFixtures.kotlinc(version).analyze("AdopterShapesKt")
        val kept = analysis.keptSites.filter { it.site.methodName == "ownMarker" }

        assertTrue(kept.size >= 2, "both the equals compare and the reference compare are kept: ${kept.map { it.site.line }}")
        assertTrue(kept.flatMap { it.outcomes }.all { it.unreadShape == UnreadShape.NONE })
    }

    @ParameterizedTest(name = "kotlinc {0}")
    @MethodSource("kotlincVersions")
    fun `every method of a multi-file facade is MULTIFILE_FACADE or one the method tier does not probe`(version: String) {
        val build = CompilerFixtures.kotlinc(version)
        val facades = build.classNames().filter { build.analyze(it).kotlinKind == KotlinKind.MULTIFILE_CLASS_FACADE }

        assertTrue(facades.isNotEmpty(), "the fixtures hold a multi-file facade")
        for (facade in facades) {
            val marks =
                build
                    .markEntries(
                        facade,
                    ).filter { it.first != "<init>" && it.first != "<clinit>" && !it.first.endsWith("\$default") }
            assertTrue(marks.isNotEmpty(), facade)
            marks.forEach { (name, descriptor, mark) ->
                assertEquals(dev.otherlode.export.GeneratedBy.MULTIFILE_FACADE, mark, "$facade.$name$descriptor")
            }
            assertEquals(emptyList(), build.unreadEntries(facade), facade)
        }
    }

    @ParameterizedTest(name = "javac {0}")
    @MethodSource("javacVersions")
    fun `no javac outcome and no method is an unread shape`(version: String) {
        val build = CompilerFixtures.javac(version)

        assertEquals(emptyList(), outcomesNotRead(build))
        val unreadMethods = build.classNames().flatMap { name -> build.unreadEntries(name).map { "$name.${it.first}: ${it.third}" } }
        assertEquals(emptyList(), unreadMethods)
    }

    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scalacVersions")
    fun `no scalac outcome is an unread shape, the string match included`(version: String) {
        assertEquals(emptyList(), outcomesNotRead(CompilerFixtures.scalac(version)))
    }
}
