package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class OpenLoopTest {
    @Test
    fun `the load shape is read by id`() {
        assertEquals(Load.CLOSED, Load.parse("closed"))
        assertEquals(Load.OPEN, Load.parse("open"))
        assertThrows(IllegalStateException::class.java) { Load.parse("burst") }
    }

    @Test
    fun `the arrival rate is whole iterations a minute, rounded`() {
        val load = OpenLoopLoad.of(iterationsPerSecond = 6.25, durationSeconds = 300, petclinicCores = 1)
        assertEquals(375, load.iterationsPerMinute)
        assertEquals(300, load.durationSeconds)
        assertEquals(375, OpenLoopLoad.of(6.2501, 300, 1).iterationsPerMinute)
        assertEquals(7, OpenLoopLoad.of(0.1166, 300, 1).iterationsPerMinute)
    }

    @Test
    fun `a rate that rounds to no iteration a minute is refused`() {
        assertThrows(IllegalArgumentException::class.java) { OpenLoopLoad.of(0.001, 300, 1) }
    }

    @Test
    fun `a low rate keeps the core-based virtual users and the 200 ceiling`() {
        assertEquals(16, OpenLoopLoad.of(5.0, 300, 1).preAllocatedVus)
        assertEquals(32, OpenLoopLoad.of(5.0, 300, 2).preAllocatedVus)
        assertEquals(200, OpenLoopLoad.of(5.0, 300, 1).maxVus)
        assertEquals(200, OpenLoopLoad.of(5.0, 300, 2).maxVus)
    }

    @Test
    fun `a rate whose one second stall outruns the core-based virtual users pre-allocates one per iteration in the stall`() {
        val load = OpenLoopLoad.of(123.5, 300, 1)
        assertEquals(7410, load.iterationsPerMinute)
        assertEquals(124, load.preAllocatedVus)
        assertEquals(248, load.maxVus)
    }

    @Test
    fun `a pre-allocation above 100 raises the ceiling to twice it`() {
        val load = OpenLoopLoad.of(150.0, 300, 1)
        assertEquals(150, load.preAllocatedVus)
        assertEquals(300, load.maxVus)
    }

    @Test
    fun `the script is given the rate, duration and virtual users through the environment`() {
        val env = OpenLoopLoad.of(6.25, 300, 1).env()
        assertEquals(
            mapOf(
                "OPEN_RATE_PER_MIN" to "375",
                "OPEN_DURATION_S" to "300",
                "OPEN_PRE_ALLOCATED_VUS" to "16",
                "OPEN_MAX_VUS" to "200",
            ),
            env,
        )
    }

    private fun after(
        args: List<String>,
        flag: String,
    ) = args[args.indexOf(flag) + 1]

    @Test
    fun `a closed loop run passes its users and duration`() {
        val args = k6Arguments(seconds = 30, virtualUsers = 4, open = null)
        assertEquals("4", after(args, "-u"))
        assertEquals("30s", after(args, "--duration"))
        assertEquals("avg,med,p(95),p(99),max", after(args, "--summary-trend-stats"))
        assertEquals("/app/overhead.js", args.last())
        assertEquals("run", args.first())
    }

    @Test
    fun `an open loop run passes neither users nor duration, which would replace its scenario`() {
        val args = k6Arguments(seconds = 300, virtualUsers = 4, open = OpenLoopLoad.of(5.0, 300, 1))
        assertFalse("-u" in args, args.toString())
        assertFalse("--duration" in args, args.toString())
        assertEquals("/tmp/k6.json", after(args, "--summary-export"))
        assertEquals("/app/overhead.js", args.last())
    }

    @Test
    fun `a given rate is recorded as given`() {
        val rate = OpenRate.given(6.0)
        assertEquals(6.0, rate.perSecond)
        assertEquals(OpenRate.Source.GIVEN, rate.source)
        assertNull(rate.measuredPerSecond)
        assertEquals(
            mapOf("openIterationRate" to "6.0", "openIterationsPerMinute" to "360", "openIterationRateSource" to "given"),
            rate.properties(),
        )
    }

    @Test
    fun `a calibrated rate is the measured closed loop rate times the fraction`() {
        val rate = OpenRate.calibrated(measuredPerSecond = 12.5, fraction = 0.5)
        assertEquals(6.25, rate.perSecond, 1e-12)
        assertEquals(OpenRate.Source.CALIBRATED, rate.source)
        assertEquals(12.5, rate.measuredPerSecond)
        assertEquals(
            mapOf(
                "openIterationRate" to "6.25",
                "openIterationsPerMinute" to "375",
                "openIterationRateSource" to "calibrated",
                "openIterationRateMeasured" to "12.5",
                "openRateFraction" to "0.5",
            ),
            rate.properties(),
        )
    }

    @Test
    fun `the open loop runs at a quarter of none's closed loop rate unless told otherwise`() {
        assertEquals(0.25, BenchSettings.DEFAULT_OPEN_RATE_FRACTION)
    }

    @Test
    fun `a rate of nothing or a fraction outside zero to one is refused`() {
        assertThrows(IllegalArgumentException::class.java) { OpenRate.given(0.0) }
        assertThrows(IllegalArgumentException::class.java) { OpenRate.calibrated(0.0, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { OpenRate.calibrated(10.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { OpenRate.calibrated(10.0, 1.5) }
        assertEquals(10.0, OpenRate.calibrated(10.0, 1.0).perSecond)
    }

    @Test
    fun `the summary says how the rate was obtained`() {
        val calibrated = openRateSection(OpenRate.calibrated(12.5, 0.5))
        assertTrue("6.25 iterations/s" in calibrated, calibrated)
        assertTrue("375 a minute" in calibrated, calibrated)
        assertTrue("calibrated" in calibrated, calibrated)
        assertTrue("12.5" in calibrated, calibrated)
        val given = openRateSection(OpenRate.given(4.0))
        assertTrue("4.0 iterations/s" in given, given)
        assertTrue("given" in given, given)
        assertEquals("", openRateSection(null))
    }

    @Test
    fun `an open run that dropped an iteration is invalid and is not an environmental problem`() {
        val problem = loadProblem(Load.OPEN, mapOf("k6DroppedIterations" to 3.0), pinnedCores = 1)
        assertNotNull(problem)
        assertTrue("dropped 3 iteration" in problem!!, problem)
        assertFalse(isEnvironmental(listOf(problem)))
    }

    @Test
    fun `an open run that dropped none, or whose k6 reported none, passes`() {
        assertNull(loadProblem(Load.OPEN, mapOf("k6DroppedIterations" to 0.0), 1))
        assertNull(loadProblem(Load.OPEN, emptyMap(), 1))
    }

    @Test
    fun `an open run is not judged for saturation`() {
        assertNull(loadProblem(Load.OPEN, mapOf("cpuCoresAvg" to 0.3, "k6DroppedIterations" to 0.0), 1))
    }

    @Test
    fun `an open run whose PetClinic used 80 percent or more of its pinned cores queued and is invalid`() {
        val problem = loadProblem(Load.OPEN, mapOf("cpuCoresAvg" to 1.6, "k6DroppedIterations" to 0.0), pinnedCores = 2)
        assertNotNull(problem)
        assertTrue("1.60 of its 2" in problem!!, problem)
        assertFalse(isEnvironmental(listOf(problem)))
        assertNull(loadProblem(Load.OPEN, mapOf("cpuCoresAvg" to 1.59, "k6DroppedIterations" to 0.0), pinnedCores = 2))
    }

    @Test
    fun `the rate the window runs at is the one the results record`() {
        val rate = OpenRate.calibrated(12.51, 0.5)
        assertEquals(OpenLoopLoad.of(rate.perSecond, 300, 1).iterationsPerMinute, rate.iterationsPerMinute)
    }

    @Test
    fun `a closed run is judged for saturation and not for dropped iterations`() {
        assertNotNull(loadProblem(Load.CLOSED, mapOf("cpuCoresAvg" to 0.3), 1))
        assertNull(loadProblem(Load.CLOSED, mapOf("cpuCoresAvg" to 1.0, "k6DroppedIterations" to 5.0), 1))
    }

    private fun settings(
        variants: List<Variant> = DEFAULT_VARIANTS,
        otelJar: java.nio.file.Path? = null,
        startupOnly: Boolean = false,
        load: Load = Load.CLOSED,
        rate: Double? = null,
        fraction: Double = 0.5,
    ) = BenchSettings(
        config = Config.HEADLINE,
        repeats = 1,
        warmupSeconds = 600,
        warmupSliceSeconds = 30,
        warmupMinSeconds = 90,
        warmupSteadyDrift = 0.03,
        windowSeconds = 300,
        agentJar = Paths.get("agent.jar"),
        collectorDir = Paths.get("collector"),
        resultsDir = Paths.get("results"),
        projectDir = Paths.get("project"),
        agentRepoDir = Paths.get("repo"),
        startupOnly = startupOnly,
        variants = variants,
        otelAgentJar = otelJar,
        load = load,
        iterationRate = rate,
        openRateFraction = fraction,
    )

    @Test
    fun `results go to a directory per load shape`() {
        assertEquals(Paths.get("results", "headline"), settings().configDir)
        assertEquals(Paths.get("results", "headline-open"), settings(load = Load.OPEN).configDir)
        assertEquals(Paths.get("results", "headline-startup"), settings(startupOnly = true).configDir)
    }

    @Test
    fun `the otel variant needs its jar`() {
        val chosen = listOf(Variant.NONE, Variant.OTEL)
        val e = assertThrows(IllegalArgumentException::class.java) { settings(variants = chosen) }
        assertTrue(e.message!!.contains("otelAgentJar"), e.message)
        settings(variants = chosen, otelJar = Paths.get("otel.jar"))
        settings(otelJar = Paths.get("otel.jar"))
    }

    @Test
    fun `an open loop has no startup-only mode`() {
        assertThrows(IllegalArgumentException::class.java) { settings(startupOnly = true, load = Load.OPEN) }
    }

    @Test
    fun `a given rate that rounds to no iteration a minute is refused before any run`() {
        assertThrows(IllegalArgumentException::class.java) { settings(load = Load.OPEN, rate = 0.005) }
    }

    @Test
    fun `a given rate needs the open loop and must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { settings(rate = 5.0) }
        assertThrows(IllegalArgumentException::class.java) { settings(load = Load.OPEN, rate = 0.0) }
        settings(load = Load.OPEN, rate = 5.0)
    }

    @Test
    fun `the calibration fraction lies above zero and up to one`() {
        assertThrows(IllegalArgumentException::class.java) { settings(fraction = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { settings(fraction = 1.1) }
        settings(fraction = 1.0)
    }
}
