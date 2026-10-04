package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.CompilerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves that a static baseline scan marks a `-jvm-default=disable` stub and a record's methods
 * the way the transform-time analysis does: the stub as `DEFAULT_IMPLS`, a hand-written record
 * `equals` unmarked and the generated ones `RECORD`.
 */
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
    fun `a scan marks a hand-written record equals unmarked and the generated hashCode and toString RECORD`() {
        val build = CompilerFixtures.javac("21")
        val named = "com.example.target.javac.Named"

        assertEquals(GeneratedBy.NONE, method(build, named, "equals").generatedBy)
        assertEquals(GeneratedBy.RECORD, method(build, named, "hashCode").generatedBy)
        assertEquals(GeneratedBy.RECORD, method(build, named, "toString").generatedBy)
    }
}
