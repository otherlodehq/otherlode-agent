package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.branch.BrokenScalaFixtures
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves a static baseline scan declares the unread shapes the transform reports, keyed on the
 * `.tasty` file the scan reads from the class's own root: a broken copy of the Scala 3 fixtures
 * (see [BrokenScalaFixtures]).
 */
class ScalaUnreadShapeBaselineTest {
    @TempDir
    lateinit var tempDir: Path

    private fun methodsOfCc(
        tooling: String?,
        keepRealTasty: Boolean = false,
    ): List<DeclaredMethod> {
        val classes = BrokenScalaFixtures.copyWith(tempDir.resolve("classes-${System.nanoTime()}").toFile(), tooling, keepRealTasty)
        return StaticBaselineScanner(listOf("com.example.scalatarget"))
            .scan(listOf(classes))
            .declaredClasses
            .single { it.className == "com.example.scalatarget.Cc" }
            .methods
    }

    @Test
    fun `a scan declares a broken plumbing method as an unread shape on a release the agent has not read`() {
        val methods = methodsOfCc("Scala 3.10.0")

        for (name in BrokenScalaFixtures.BROKEN) {
            val method = methods.single { it.methodName == name && !it.methodName.contains("\$default") }
            assertEquals(UnreadShape.CASE_CLASS, method.unreadShape, name)
            assertEquals(GeneratedBy.NONE, method.generatedBy, name)
        }
        assertEquals(GeneratedBy.CASE_CLASS, methods.single { it.methodName == "canEqual" }.generatedBy)
        assertEquals(UnreadShape.NONE, methods.single { it.methodName == "canEqual" }.unreadShape)
        assertEquals(UnreadShape.NONE, methods.single { it.methodName == "a" }.unreadShape)
    }

    @Test
    fun `a scan gives a default getter the unread shape of the method it fills a default for`() {
        val methods = methodsOfCc("Scala 3.10.0")

        for (getter in methods.filter { it.methodName.startsWith("copy\$default\$") }) {
            assertEquals(UnreadShape.CASE_CLASS, getter.unreadShape, getter.methodName)
            assertEquals(GeneratedBy.NONE, getter.generatedBy, getter.methodName)
        }
    }

    @Test
    fun `a scan declares a broken plumbing method as an unread shape when the root has no tasty file`() {
        val methods = methodsOfCc(null)

        assertEquals(UnreadShape.CASE_CLASS, methods.single { it.methodName == "hashCode" }.unreadShape)
    }

    @Test
    fun `a scan treats a broken plumbing method as hand-written on a release the agent has read`() {
        val methods = methodsOfCc(null, keepRealTasty = true)

        assertTrue(methods.all { it.unreadShape == UnreadShape.NONE })
        assertEquals(GeneratedBy.NONE, methods.single { it.methodName == "hashCode" }.generatedBy)
    }

    /** The scan's declared methods of `Cc` from a jar of the broken fixtures, its `.tasty` naming [tooling], entries under [prefix]. */
    private fun methodsOfCcInJar(
        tooling: String,
        prefix: String,
    ): List<DeclaredMethod> {
        val classes = BrokenScalaFixtures.copyWith(tempDir.resolve("jar-classes-${prefix.length}").toFile(), tooling)
        val jar = tempDir.resolve("fixtures-${prefix.length}.jar").toFile()
        java.util.jar.JarOutputStream(jar.outputStream()).use { out ->
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(java.util.jar.JarEntry(prefix + file.relativeTo(classes).invariantSeparatorsPath))
                out.write(file.readBytes())
                out.closeEntry()
            }
        }
        return StaticBaselineScanner(listOf("com.example.scalatarget"))
            .scan(listOf(jar))
            .declaredClasses
            .single { it.className == "com.example.scalatarget.Cc" }
            .methods
    }

    @Test
    fun `a scan of a jar reads the tasty file from the jar`() {
        // A release the agent has read: only a .tasty actually read from the jar turns the broken
        // methods back into hand-written ones, where a scan that read none would call them unread.
        val methods = methodsOfCcInJar("Scala 3.3.4", prefix = "")

        assertEquals(UnreadShape.NONE, methods.single { it.methodName == "hashCode" }.unreadShape)
        assertEquals(
            UnreadShape.CASE_CLASS,
            methodsOfCcInJar("Scala 3.10.0", prefix = "").single { it.methodName == "hashCode" }.unreadShape,
        )
    }

    @Test
    fun `a scan of a Spring Boot jar reads the tasty file under BOOT-INF classes`() {
        val methods = methodsOfCcInJar("Scala 3.3.4", prefix = "BOOT-INF/classes/")

        assertEquals(UnreadShape.NONE, methods.single { it.methodName == "hashCode" }.unreadShape)
    }
}
