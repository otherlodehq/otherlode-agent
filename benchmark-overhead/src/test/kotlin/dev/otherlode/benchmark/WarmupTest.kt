package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarmupTest {
    private fun verdict(
        vararg slices: Double,
        min: Int = 90,
        cap: Int = 600,
        drift: Double = 0.03,
    ) = warmupVerdict(slices.toList(), sliceSeconds = 30, minSeconds = min, capSeconds = cap, steadyDrift = drift)

    @Test
    fun `flat slices after the minimum are steady`() {
        assertEquals(WarmupVerdict.STEADY, verdict(1000.0, 1010.0, 1005.0))
    }

    @Test
    fun `flat slices before the minimum are not steady yet`() {
        assertEquals(WarmupVerdict.CONTINUE, verdict(1000.0, 1000.0, 1000.0, min = 120))
        assertEquals(WarmupVerdict.CONTINUE, verdict(1000.0, 1000.0))
    }

    @Test
    fun `an early flat patch in a rising series does not end the warmup`() {
        assertEquals(WarmupVerdict.CONTINUE, verdict(500.0, 500.0, 500.0, 700.0))
    }

    @Test
    fun `a rising series runs to the cap and is not steady`() {
        val rising = List(20) { 500.0 * Math.pow(1.1, it.toDouble()) }
        assertEquals(WarmupVerdict.CONTINUE, warmupVerdict(rising.take(19), 30, 90, 600, 0.03))
        assertEquals(WarmupVerdict.CAPPED, warmupVerdict(rising, 30, 90, 600, 0.03))
    }

    @Test
    fun `a series that settles on the last permitted slice is steady, not capped`() {
        val series = List(17) { 500.0 * (it + 1) } + listOf(9000.0, 9000.0, 9000.0)
        assertEquals(WarmupVerdict.STEADY, warmupVerdict(series, 30, 90, 600, 0.03))
    }

    @Test
    fun `a noisy series is not steady while any of the last two steps exceeds the bound`() {
        assertEquals(WarmupVerdict.CONTINUE, verdict(1000.0, 1100.0, 1000.0, 1100.0))
        assertEquals(WarmupVerdict.CONTINUE, verdict(1000.0, 1000.0, 1000.0, 1100.0))
        assertEquals(WarmupVerdict.STEADY, verdict(1000.0, 1100.0, 1000.0, 1010.0, 1005.0))
    }

    @Test
    fun `a step of exactly the bound is steady and one just over is not`() {
        assertEquals(WarmupVerdict.STEADY, verdict(1000.0, 1030.0, 1030.0 * 1.03, drift = 0.03))
        assertEquals(WarmupVerdict.STEADY, verdict(1000.0, 970.0, 970.0 * 0.97, drift = 0.03))
        assertEquals(WarmupVerdict.CONTINUE, verdict(1000.0, 1031.0, 1031.0, drift = 0.03))
    }

    @Test
    fun `a slice after one that served nothing is not steady`() {
        assertEquals(WarmupVerdict.CONTINUE, verdict(0.0, 0.0, 0.0))
    }

    @Test
    fun `a warmup judged steady tells the reader to tighten the rule, not lengthen the cap`() {
        val m = mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1500.0, "warmupSteady" to 1.0)
        val problem = warmthProblem(m)
        assertNotNull(problem)
        assertTrue("judged steady" in problem!!, problem)
        assertTrue("-PwarmupSteadyDrift" in problem, problem)
        assertTrue("-PwarmupSeconds" !in problem, problem)
    }

    @Test
    fun `a warmup that hit its cap tells the reader to raise the cap`() {
        val capped = warmthProblem(mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1500.0, "warmupSteady" to 0.0))
        assertTrue("-PwarmupSeconds" in capped!!, capped)
        assertTrue("cap" in capped, capped)
        val unrecorded = warmthProblem(mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1500.0))
        assertTrue("-PwarmupSeconds" in unrecorded!!, unrecorded)
        assertNull(warmthProblem(mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1000.0, "warmupSteady" to 1.0)))
    }

    @Test
    fun `the summary shows warmup length per variant as mean and range`() {
        fun run(
            repeat: Int,
            used: Double,
            steady: Double,
        ) = RunRecord(
            Config.HEADLINE,
            Variant.AGENT,
            repeat,
            mapOf("warmupSecondsUsed" to used, "warmupSteady" to steady),
            emptyList(),
        )
        val md = summaryMarkdown(Config.HEADLINE, listOf(run(1, 90.0, 1.0), run(2, 150.0, 1.0), run(3, 600.0, 0.0)))
        assertTrue("- agent: mean 280.0 s (90.0 to 600.0), steady in 2 of 3" in md, md)
    }
}
