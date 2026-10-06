package dev.otherlode.benchmark

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps a change from weaving every class while the agent sees less in them. [CodeSizeLimitsTest]
 * and `WovenClassVerificationTest` check that corpus classes weave, not how much the agent
 * identifies. This test weaves every benchmark corpus through the offline transformer, counts what
 * the registry built, and compares the counts with `visibility-baseline.txt`.
 *
 * A drop in a floor metric or a rise in a ceiling metric fails. Any other change prints a note.
 * `-PupdateVisibilityBaseline` rewrites the baseline source file, so an intended change shows as a
 * reviewed diff. The counts come from `ProbeRegistry.manifest`, which holds every registered class
 * because the offline registry does not withhold unconfirmed classes.
 */
class VisibilityFloorTest {
    @Test
    fun `weaving the benchmark corpora sees at least as much as the baseline`() {
        val measured = CorpusWeaving.CORPORA.fold(mapOf<String, Long>()) { all, corpus -> all + VisibilityFloor.measure(corpus) }
        val updatePath = System.getProperty(VisibilityFloor.UPDATE_PROPERTY)
        if (updatePath != null) {
            VisibilityFloor.write(File(updatePath), measured)
            println("visibility baseline: wrote ${measured.size} metrics to $updatePath")
            return
        }
        val comparison = VisibilityFloor.compare(VisibilityFloor.readBaseline(), measured)
        comparison.notes.forEach { println("visibility baseline note: $it") }
        assertTrue(
            comparison.failures.isEmpty(),
            "the agent sees less than the baseline:\n" + comparison.failures.joinToString("\n") { "  $it" } +
                "\nIf the change is intended, regenerate the baseline with: ${VisibilityFloor.REGENERATE_COMMAND}",
        )
    }

    @Test
    fun `a floor that falls below the baseline fails`() {
        val result = VisibilityFloor.compare(mapOf("demo.edges.CALL" to 10), mapOf("demo.edges.CALL" to 9))
        assertEquals(listOf("demo.edges.CALL (floor): baseline 10, measured 9"), result.failures)
    }

    @Test
    fun `a ceiling that rises above the baseline fails`() {
        val result = VisibilityFloor.compare(mapOf("demo.classes.skipped" to 1), mapOf("demo.classes.skipped" to 2))
        assertEquals(listOf("demo.classes.skipped (ceiling): baseline 1, measured 2"), result.failures)
        val shape =
            VisibilityFloor.compare(
                mapOf("scala.methods.unread_shape.CASE_CLASS" to 0),
                mapOf("scala.methods.unread_shape.CASE_CLASS" to 1),
            )
        assertEquals(1, shape.failures.size)
    }

    @Test
    fun `a floor that rises passes with a note`() {
        val result = VisibilityFloor.compare(mapOf("demo.edges.CALL" to 10), mapOf("demo.edges.CALL" to 12))
        assertTrue(result.failures.isEmpty())
        assertEquals(1, result.notes.size)
        assertTrue("demo.edges.CALL" in result.notes.single() && "10" in result.notes.single() && "12" in result.notes.single())
    }

    @Test
    fun `a ceiling that falls passes with a note`() {
        val result = VisibilityFloor.compare(mapOf("demo.classes.skipped" to 3), mapOf("demo.classes.skipped" to 1))
        assertTrue(result.failures.isEmpty())
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `a metric missing from the baseline passes with a note`() {
        val result = VisibilityFloor.compare(emptyMap(), mapOf("demo.edges.CREATES" to 4))
        assertTrue(result.failures.isEmpty())
        assertTrue("demo.edges.CREATES" in result.notes.single())
    }

    @Test
    fun `a ceiling missing from the baseline fails when it is above zero`() {
        val result = VisibilityFloor.compare(emptyMap(), mapOf("scala.methods.unread_shape.NEW_SHAPE" to 2))
        assertEquals(listOf("scala.methods.unread_shape.NEW_SHAPE (ceiling): baseline 0, measured 2"), result.failures)
        val zero = VisibilityFloor.compare(emptyMap(), mapOf("scala.methods.unread_shape.NEW_SHAPE" to 0))
        assertTrue(zero.failures.isEmpty())
    }

    @Test
    fun `a baseline metric missing from the measurement fails when its baseline value is not zero`() {
        val dropped = VisibilityFloor.compare(mapOf("demo.edges.CREATES" to 4), emptyMap())
        assertEquals(listOf("demo.edges.CREATES (floor): baseline 4, measured 0"), dropped.failures)
        val zero = VisibilityFloor.compare(mapOf("demo.edges.CREATES" to 0), emptyMap())
        assertTrue(zero.failures.isEmpty())
    }

    @Test
    fun `the update mode writes a sorted file that reads back to the same metrics`() {
        val dir = createTempDirectory("visibility").toFile()
        try {
            val file = File(dir, "nested/visibility-baseline.txt")
            val measured = mapOf("scala.probes.METHOD" to 7L, "demo.edges.CALL" to 3L, "demo.classes.skipped" to 0L)
            VisibilityFloor.write(file, measured)
            val lines = file.readLines()
            val metricLines = lines.filter { !it.startsWith("#") }
            assertEquals(metricLines.sorted(), metricLines)
            assertTrue(lines.first().startsWith("#"), "the file starts with a header comment")
            assertTrue(lines.any { "-PupdateVisibilityBaseline" in it }, "the header names the regenerate command")
            assertEquals(measured, VisibilityFloor.parse(file.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
