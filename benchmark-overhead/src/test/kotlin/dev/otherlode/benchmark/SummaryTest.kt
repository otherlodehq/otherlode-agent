package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SummaryTest {
    @Test
    fun `median of an odd count is the middle value`() {
        val s = spread(listOf(9.0, 1.0, 5.0))!!
        assertEquals(5.0, s.median)
        assertEquals(1.0, s.min)
        assertEquals(9.0, s.max)
    }

    @Test
    fun `median of an even count is the mean of the middle two`() {
        assertEquals(2.5, spread(listOf(4.0, 1.0, 3.0, 2.0))!!.median)
    }

    @Test
    fun `no values has no spread`() {
        assertNull(spread(emptyList()))
    }

    @Test
    fun `change is relative to the median of none`() {
        assertEquals(10.0, percentChange(baseline = 100.0, value = 110.0)!!, 1e-9)
        assertEquals(-3.0, percentChange(baseline = 200.0, value = 194.0)!!, 1e-9)
        assertNull(percentChange(baseline = 0.0, value = 5.0))
    }

    private fun run(
        variant: Variant,
        repeat: Int,
        throughput: Double,
    ) = RunRecord(Config.HEADLINE, variant, repeat, mapOf("k6ThroughputPerSec" to throughput), emptyList())

    @Test
    fun `the markdown has one row per metric with the change against none`() {
        val runs =
            listOf(
                run(Variant.NONE, 1, 100.0),
                run(Variant.NONE, 2, 102.0),
                run(Variant.NONE, 3, 98.0),
                run(Variant.AGENT, 1, 97.0),
                run(Variant.AGENT, 2, 96.0),
                run(Variant.AGENT, 3, 95.0),
            )
        val md = summaryMarkdown(Config.HEADLINE, runs)
        val row = md.lines().single { it.startsWith("| k6ThroughputPerSec") }
        assertTrue(row.contains("100.0 (98.0 to 102.0)"), row)
        assertTrue(row.contains("96.0 (95.0 to 97.0)"), row)
        assertTrue(row.contains("-4.0%"), row)
    }

    @Test
    fun `skipped classes are listed per variant`() {
        val runs =
            listOf(
                RunRecord(Config.CEILING, Variant.AGENT, 1, emptyMap(), listOf("a.B", "a.C")),
                RunRecord(Config.CEILING, Variant.AGENT, 2, emptyMap(), listOf("a.B")),
            )
        val md = summaryMarkdown(Config.CEILING, runs)
        assertTrue(md.contains("a.B"))
        assertTrue(md.contains("a.C"))
    }

    @Test
    fun `a change inside none's own spread is marked as noise and one outside is not`() {
        val runs =
            listOf(
                run(Variant.NONE, 1, 100.0),
                run(Variant.NONE, 2, 102.0),
                run(Variant.NONE, 3, 98.0),
                run(Variant.AGENT, 1, 99.0),
                run(Variant.AGENT_BASELINE, 1, 90.0),
            )
        val row = summaryMarkdown(Config.HEADLINE, runs).lines().single { it.startsWith("| k6ThroughputPerSec") }
        assertTrue(row.contains("-1.0% *"), row)
        assertTrue(row.contains("-10.0% |"), row)
    }

    @Test
    fun `a run below the CPU limit is warned about`() {
        val runs =
            listOf(
                RunRecord(Config.HEADLINE, Variant.NONE, 1, mapOf("cpuCoresAvg" to 1.99), emptyList()),
                RunRecord(Config.HEADLINE, Variant.AGENT, 1, mapOf("cpuCoresAvg" to 1.5), emptyList()),
            )
        val md = summaryMarkdown(Config.HEADLINE, runs)
        assertTrue(md.contains("below its CPU limit"), md)
        assertTrue(md.contains("agent repeat 1: 1.5 cores"), md)
        assertTrue(!md.contains("none repeat 1"), md)
    }

    @Test
    fun `the skipped count comes from the manifests even when nothing was logged`() {
        val runs = listOf(RunRecord(Config.HEADLINE, Variant.AGENT, 1, mapOf("manifestSkippedClasses" to 1.0), emptyList()))
        val md = summaryMarkdown(Config.HEADLINE, runs)
        assertTrue(md.contains("- agent: 1 per run, from the manifests"), md)
        assertTrue(!md.contains("No class was skipped"), md)
    }
}
