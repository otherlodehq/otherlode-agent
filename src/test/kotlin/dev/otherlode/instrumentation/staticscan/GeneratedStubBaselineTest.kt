package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedStubBaselineTest {
    private fun method(
        build: CompilerFixtures.Build,
        className: String,
        name: String,
    ): DeclaredMethod =
        StaticBaselineScanner(listOf("com.example.target"))
            .scan(listOf(build.directory))
            .declaredClasses
            .single { it.className == className }
            .methods
            .single { it.methodName == name }

    @Test
    fun `a scan marks a disable-mode stub DEFAULT_IMPLS and the class's own method unmarked`() {
        val build = CompilerFixtures.kotlinc("2.1.21")
        val impl = "com.example.target.kotlinc.DescriberImpl"

        assertEquals(GeneratedBy.DEFAULT_IMPLS, method(build, impl, "plain").generatedBy)
        assertEquals(GeneratedBy.DEFAULT_IMPLS, method(build, impl, "describe").generatedBy)
        assertEquals(GeneratedBy.NONE, method(build, impl, "id").generatedBy)
    }

    @Test
    fun `a scan marks a stub whose DefaultImpls method does not exist DEFAULT_IMPLS, as the live analysis does`() {
        val versions = CompilerFixtures.kotlincVersions.filterNot(CompilerFixtures::kotlincDefaultsToJvmDefaultEnable)
        assertTrue(versions.isNotEmpty())
        for (version in versions) {
            for (mode in listOf("all", "all-compatibility")) {
                val root = CompilerFixtures.kotlincMixed(version)
                val scanned =
                    StaticBaselineScanner(listOf("com.example.target"))
                        .scan(listOf(File(root, mode), File(root, "lib")))
                        .declaredClasses
                        .single { it.className == "com.example.target.mixed.MixedIdStoreImpl" }
                        .methods
                        .associateBy { it.methodName }

                assertEquals(GeneratedBy.DEFAULT_IMPLS, scanned.getValue("keyed-QoVL8wA").generatedBy, "$version $mode")
                assertEquals(GeneratedBy.DEFAULT_IMPLS, scanned.getValue("plain-6pmR1NM").generatedBy, "$version $mode")
            }
        }
    }

    @Test
    fun `a scan marks a hand-written record equals unmarked and the generated hashCode and toString RECORD`() {
        val build = CompilerFixtures.javac("21")
        val named = "com.example.target.javac.Named"

        assertEquals(GeneratedBy.NONE, method(build, named, "equals").generatedBy)
        assertEquals(GeneratedBy.RECORD, method(build, named, "hashCode").generatedBy)
        assertEquals(GeneratedBy.RECORD, method(build, named, "toString").generatedBy)
    }
}
