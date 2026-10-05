package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `a window whose last third serves far more than its first is not warm`() {
        val problem = warmthProblem(mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 1500.0))
        assertNotNull(problem)
        assertTrue("50%" in problem!!, problem)
    }

    @Test
    fun `a window whose last third serves far fewer than its first is not steady`() {
        val problem = warmthProblem(mutableMapOf("k6FirstThirdRequests" to 1000.0, "k6LastThirdRequests" to 800.0))
        assertNotNull(problem)
        assertTrue("20% fewer" in problem!!, problem)
    }

    @Test
    fun `a window without the thirds' counts is reported, not passed`() {
        assertNotNull(warmthProblem(mutableMapOf("k6LastThirdRequests" to 1.0)))
        assertNotNull(warmthProblem(mutableMapOf("k6FirstThirdRequests" to 0.0, "k6LastThirdRequests" to 1.0)))
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
