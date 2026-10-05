package dev.otherlode.instrumentation

import dev.otherlode.instrumentation.branch.ScalaFixtures
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScalaClassDetectorTest {
    @Test
    fun `a plain Java class carries neither Scala class attribute`() {
        val bytes = File("build/classes/java/test/com/example/target/SampleTarget.class").readBytes()

        assertFalse(ScalaClassDetector.isScalaClass(bytes))
    }

    @Test
    fun `a Scala 3 module class carries the Scala attribute`() {
        val bytes = ScalaFixtures.classBytes("scala3", "LambdaHost\$")

        assertTrue(ScalaClassDetector.isScalaClass(bytes))
    }

    @Test
    fun `a Scala 2 module class carries the Scala attribute`() {
        val bytes = ScalaFixtures.classBytes("scala2", "LambdaHost\$")

        assertTrue(ScalaClassDetector.isScalaClass(bytes))
    }

    @Test
    fun `a Scala 2 class carries the ScalaSig attribute`() {
        val bytes = ScalaFixtures.classBytes("scala2", "Simple")

        assertTrue(ScalaClassDetector.isScalaClass(bytes))
    }

    private fun readWithAsm(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAttribute(attribute: Attribute) {
                    if (ScalaClassDetector.isScalaAttribute(attribute)) found = true
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
    }

    @Test
    fun `the allocation-free scan agrees with the ASM reader on every class of several compilers`() {
        val roots =
            listOf(
                "build/classes/java/test",
                "build/classes/kotlin/main",
                "build/classes/kotlin/test",
                "fixtures-scala2/build/classes/scala/main",
                "fixtures-scala3/build/classes/scala/main",
            ).map(::File).filter { it.isDirectory }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList() }
        assertTrue(files.size > 500, "only ${files.size} class files found")
        var scala = 0
        for (file in files) {
            val bytes = file.readBytes()
            val expected = readWithAsm(bytes)
            if (expected) scala++
            assertEquals(expected, ScalaClassDetector.isScalaClass(bytes), file.path)
        }
        assertTrue(scala > 0, "no Scala class among ${files.size}")
    }
}
