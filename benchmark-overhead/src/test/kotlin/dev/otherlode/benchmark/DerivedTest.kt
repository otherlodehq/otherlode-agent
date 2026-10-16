package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DerivedTest {
    @Test
    fun `CPU figures come from the difference of two readings over the elapsed time`() {
        val m = cpuMetrics(CgroupCpu(1_000_000, 10, 5_000), CgroupCpu(61_000_000, 25, 9_000), 30.0)
        assertEquals(60.0, m["cpuSeconds"]!!, 1e-9)
        assertEquals(2.0, m["cpuCoresAvg"]!!, 1e-9)
        assertEquals(15.0, m["cpuThrottledPeriods"]!!, 1e-9)
        assertEquals(4.0, m["cpuThrottledMs"]!!, 1e-9)
    }

    @Test
    fun `a run at or above 90 percent of its pinned cores is saturated`() {
        assertNull(saturationProblem(1.0, 1))
        assertNull(saturationProblem(0.9, 1))
        assertNull(saturationProblem(3.6, 4))
    }

    @Test
    fun `a run below 90 percent of its pinned cores is not saturated`() {
        val problem = saturationProblem(0.85, 1)
        assertNotNull(problem)
        assertTrue("0.85" in problem!! && "1 pinned" in problem, problem)
        assertNotNull(saturationProblem(3.5, 4))
    }

    @Test
    fun `per-request figures divide by the requests k6 completed`() {
        val m = perRequest(mapOf("k6Requests" to 1000.0, "cpuSeconds" to 2.0, "allocatedMiB" to 10.0, "gcPauseTotalMs" to 50.0))
        assertEquals(2.0, m["cpuMillisPerRequest"]!!, 1e-9)
        assertEquals(10.24, m["allocatedKiBPerRequest"]!!, 1e-9)
        assertEquals(50.0, m["gcPauseMsPer1kRequests"]!!, 1e-9)
    }

    @Test
    fun `no requests gives no per-request figures`() {
        assertTrue(perRequest(mapOf("k6Requests" to 0.0, "cpuSeconds" to 2.0)).isEmpty())
        assertTrue(perRequest(mapOf("cpuSeconds" to 2.0)).isEmpty())
    }

    @Test
    fun `a window whose last third is within the drift of its first is warm`() {
        val m = mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1050.0)
        assertNull(warmthProblem(m))
        assertEquals(1.05, m["warmupDrift"]!!, 1e-9)
        assertNull(warmthProblem(mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 950.0)))
    }

    @Test
    fun `a single run whose last third serves far more than its first is not a problem and records its drift`() {
        val m = mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1500.0)
        assertNull(warmthProblem(m))
        assertEquals(1.5, m["warmupDrift"]!!, 1e-9)
    }

    @Test
    fun `a single run whose last third serves far fewer than its first is not a problem and records its drift`() {
        val m = mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 800.0)
        assertNull(warmthProblem(m))
        assertEquals(0.8, m["warmupDrift"]!!, 1e-9)
    }

    @Test
    fun `a window without the thirds' counts is reported, not passed`() {
        assertNotNull(warmthProblem(mutableMapOf("k6LastThirdRequests" to 1.0)))
        assertNotNull(warmthProblem(mutableMapOf("k6FirstThirdRequests" to 0.0, "k6LastThirdRequests" to 1.0)))
    }

    private fun drifted(
        vararg drifts: Double,
        steady: Double = 0.0,
    ) = drifts.mapIndexed { i, d ->
        RunRecord(Config.HEADLINE, Variant.AGENT, i + 1, mapOf("warmupDrift" to d, "warmupSteady" to steady), emptyList())
    }

    @Test
    fun `a variant whose median drift is inside the bound passes although one repeat drifted far`() {
        assertNull(variantWarmthProblem(Variant.AGENT, drifted(1.17, 1.02, 0.99, 1.04, 1.01)))
    }

    @Test
    fun `a variant whose median drift is above the bound names the rise and the steady count`() {
        val steady = variantWarmthProblem(Variant.AGENT, drifted(1.12, 1.15, 1.20, steady = 1.0))
        assertNotNull(steady)
        assertTrue("agent" in steady!! && "15.0% more" in steady && "3 of 3 warmups ended steady" in steady, steady)
        val capped = variantWarmthProblem(Variant.AGENT, drifted(1.12, 1.15, 1.20))
        assertNotNull(capped)
        assertTrue("raise -PwarmupSeconds" in capped!!, capped)
    }

    @Test
    fun `a variant whose median drift is below the bound names the degradation`() {
        val problem = variantWarmthProblem(Variant.AGENT, drifted(0.85, 0.80, 1.00))
        assertNotNull(problem)
        assertTrue("15.0% fewer" in problem!! && "degraded" in problem, problem)
    }

    @Test
    fun `a variant with an even count of repeats is judged by the mean of the middle two`() {
        assertNull(variantWarmthProblem(Variant.AGENT, drifted(1.00, 1.04, 1.14, 1.30)))
        assertNotNull(variantWarmthProblem(Variant.AGENT, drifted(1.00, 1.08, 1.14, 1.30)))
    }

    @Test
    fun `with two repeats the median is their mean, so one drifted run can fail its variant`() {
        assertNotNull(variantWarmthProblem(Variant.AGENT, drifted(1.00, 1.25)))
        assertNull(variantWarmthProblem(Variant.AGENT, drifted(1.00, 1.25, 1.01)))
    }

    @Test
    fun `the dirty check leaves out a collector checked out inside the agent repository`() {
        val repo = Paths.get("/work/agent")
        assertEquals(
            listOf("status", "--porcelain", "--", ".", ":(exclude,literal)otherlode-collector"),
            dirtyCheck(repo, repo.resolve("otherlode-collector")),
        )
        assertEquals(listOf("status", "--porcelain"), dirtyCheck(repo, Paths.get("/work/otherlode-collector")))
    }

    @Test
    fun `a median exactly on the bound passes`() {
        assertNull(variantWarmthProblem(Variant.AGENT, drifted(1.10)))
        assertNull(variantWarmthProblem(Variant.AGENT, drifted(0.90)))
    }

    @Test
    fun `a variant with no runs has no problem`() {
        assertNull(variantWarmthProblem(Variant.AGENT, emptyList()))
    }

    @Test
    fun `a run that recorded no drift is reported, not left out of the median`() {
        val runs = drifted(1.0, 1.0) + RunRecord(Config.HEADLINE, Variant.AGENT, 3, emptyMap(), emptyList())
        val problem = variantWarmthProblem(Variant.AGENT, runs)
        assertNotNull(problem)
        assertTrue("1 of 3 runs recorded no warmupDrift" in problem!!, problem)
    }

    @Test
    fun `every variant with runs is judged, and only the unsteady ones are reported`() {
        fun runs(
            variant: Variant,
            vararg drifts: Double,
        ) = drifts.mapIndexed { i, d -> RunRecord(Config.HEADLINE, variant, i + 1, mapOf("warmupDrift" to d), emptyList()) }
        val oneUnsteady = variantWarmthProblems(runs(Variant.NONE, 1.0, 1.01) + runs(Variant.AGENT, 1.2, 1.3))
        assertEquals(1, oneUnsteady.size, oneUnsteady.toString())
        assertTrue(oneUnsteady.single().startsWith("agent:"), oneUnsteady.toString())
        val allUnsteady = variantWarmthProblems(runs(Variant.NONE, 0.8) + runs(Variant.AGENT, 1.2) + runs(Variant.AGENT_BASELINE, 1.3))
        assertEquals(3, allUnsteady.size, allUnsteady.toString())
        assertEquals(emptyList<String>(), variantWarmthProblems(emptyList()))
    }

    @Test
    fun `a drift is shown only when it lies past the bound, to one decimal place`() {
        assertEquals("+10.0%", driftBeyondBound(1.1004))
        assertEquals("-10.0%", driftBeyondBound(0.8996))
        assertNull(driftBeyondBound(1.10))
        assertNull(driftBeyondBound(0.95))
    }

    @Test
    fun `manifest counts sum the collector's manifest lines and ignore the rest`() {
        val log =
            """
            {"time":"t","level":"INFO","msg":"received delta batch","deltas":3}
            {"time":"t","level":"INFO","msg":"received probe manifest","probes":120,"endpoints":17,"skipped_classes":2,"disabled_endpoint_modules":0}
            not json at all
            {"time":"t","level":"INFO","msg":"received probe manifest","probes":5,"endpoints":0,"skipped_classes":1,"disabled_endpoint_modules":1}
            """.trimIndent()
        assertEquals(ManifestCounts(2, 125, 17, 3, 1), ManifestCounts.parse(log))
        assertEquals(ManifestCounts(0, 0, 0, 0, 0), ManifestCounts.parse(""))
    }

    @Test
    fun `the agent's error lines are the SEVERE lines that name it`() {
        val log =
            "INFO: otherlode: installing\nSEVERE: otherlode: endpoint instrumentation failed to install\nSEVERE: something else\n" +
                "WARN  OtherlodeInstrumentation - otherlode: instrumentation failed for a.B, class will run uninstrumented\n" +
                "ERROR ExportScheduler - otherlode: flush failed outside its own send guards\n"
        assertEquals(
            listOf(
                "SEVERE: otherlode: endpoint instrumentation failed to install",
                "ERROR ExportScheduler - otherlode: flush failed outside its own send guards",
            ),
            agentErrors(log),
        )
    }

    @Test
    fun `cpu stat throttling fields are read when present and zero when not`() {
        val stat = "usage_usec 100\nuser_usec 60\nsystem_usec 40\nnr_periods 9\nnr_throttled 4\nthrottled_usec 2500\n"
        assertEquals(CgroupCpu(100, 4, 2500), CgroupCpu.parse(stat))
        assertEquals(CgroupCpu(100, 0, 0), CgroupCpu.parse("usage_usec 100\n"))
    }

    @Test
    fun `k6's summary yields the request count and both thirds`() {
        val json =
            """
            {"metrics":{"http_reqs":{"count":300,"rate":10.0},"http_reqs{third:first}":{"count":90,"rate":3.0},
            "http_reqs{third:last}":{"count":110,"rate":3.6},"checks":{"value":1}}}
            """.trimIndent()
        val m = K6Summary.parse(json).metrics
        assertEquals(300.0, m["k6Requests"])
        assertEquals(90.0, m["k6FirstThirdRequests"])
        assertEquals(110.0, m["k6LastThirdRequests"])
    }
}
