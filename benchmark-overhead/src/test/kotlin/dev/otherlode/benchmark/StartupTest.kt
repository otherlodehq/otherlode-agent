package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupTest {
    private fun stat(vararg steal: Long) =
        buildString {
            appendLine("cpu  ${steal.sum() + 1000} 0 500 9000 10 0 5 ${steal.sum()} 0 0")
            steal.forEachIndexed { i, s -> appendLine("cpu$i 100 0 50 900 1 0 1 $s 0 0") }
            appendLine("intr 12345 0 0")
            appendLine("ctxt 999")
        }

    @Test
    fun `the steal parse picks the pinned core's line and field 8`() {
        assertEquals(30L, ProcStat.stealTicks(stat(10, 30, 70, 90), listOf(1)))
        assertEquals(10L, ProcStat.stealTicks(stat(10, 30, 70, 90), listOf(0)))
    }

    @Test
    fun `steal over several pinned cores is summed`() {
        assertEquals(40L, ProcStat.stealTicks(stat(10, 30, 70), listOf(0, 1)))
    }

    @Test
    fun `steal between two readings is converted with the clock tick`() {
        assertEquals(200.0, ProcStat.stealMs(stat(10, 30), stat(10, 50), listOf(1), 100), 1e-9)
        assertEquals(100.0, ProcStat.stealMs(stat(10, 30), stat(10, 50), listOf(1), 200), 1e-9)
        assertEquals(200.0, ProcStat.stealMs(stat(10, 30), stat(10, 50), listOf(1)), 1e-9)
    }

    @Test
    fun `a core with no line in the reading is an error`() {
        assertThrows(IllegalStateException::class.java) { ProcStat.stealTicks(stat(10), listOf(3)) }
    }

    @Test
    fun `a cpuset names its cores`() {
        assertEquals(listOf(0), ProcStat.cores("0"))
        assertEquals(listOf(0, 1), ProcStat.cores("0-1"))
        assertEquals(listOf(0, 2, 3), ProcStat.cores("0,2-3"))
    }

    @Test
    fun `startup CPU is the cgroup's usage in milliseconds`() {
        val cpu = CgroupCpu.parse("usage_usec 18250000\nuser_usec 17000000\nsystem_usec 1250000\nnr_throttled 0\nthrottled_usec 0\n")
        assertEquals(18250.0, startupCpuMs(cpu), 1e-9)
    }

    @Test
    fun `the CPU model is the first model name and the count is the processors`() {
        val info =
            "processor\t: 0\nmodel name\t: AMD EPYC 7763 64-Core Processor\ncpu MHz\t: 2445.4\n\n" +
                "processor\t: 1\nmodel name\t: AMD EPYC 7763 64-Core Processor\n"
        assertEquals(CpuInfo("AMD EPYC 7763 64-Core Processor", 2), CpuInfo.parse(info))
    }

    @Test
    fun `a cpuinfo with no model name has no CPU info`() {
        assertNull(CpuInfo.parse("processor : 0\nCPU part : 0xd0c\n"))
    }

    @Test
    fun `quartiles interpolate between the values`() {
        val q = quartiles(listOf(1.0, 2.0, 3.0, 4.0, 5.0))!!
        assertEquals(Quartiles(2.0, 3.0, 4.0), q)
        assertEquals(Quartiles(1.75, 2.5, 3.25), quartiles(listOf(1.0, 2.0, 3.0, 4.0)))
        assertNull(quartiles(emptyList()))
    }

    private fun run(
        variant: Variant,
        repeat: Int,
        startupMs: Double,
        stealMs: Double? = null,
    ) = RunRecord(
        Config.HEADLINE,
        variant,
        repeat,
        listOfNotNull("startupMs" to startupMs, stealMs?.let { "startupStealMs" to it }).toMap(),
        emptyList(),
    )

    @Test
    fun `the startup summary gives median, interquartile range and the change against none`() {
        val runs =
            listOf(10000.0, 11000.0, 12000.0, 13000.0, 14000.0).mapIndexed { i, ms -> run(Variant.NONE, i + 1, ms) } +
                listOf(12500.0, 13750.0, 15000.0, 16250.0, 17500.0).mapIndexed { i, ms -> run(Variant.AGENT, i + 1, ms) }
        val md = startupSummaryMarkdown(Config.HEADLINE, runs)
        val row = md.lines().single { it.startsWith("| startupMs") }
        assertTrue("12000.0 (11000.0 to 13000.0)" in row, row)
        assertTrue("15000.0 (13750.0 to 16250.0)" in row, row)
        assertTrue("+25.0% (1.25x)" in row, row)
        assertTrue(md.startsWith("## Startup, headline"), md)
        assertTrue("Runs per variant: none 5, agent 5" in md, md)
    }

    @Test
    fun `a startup metric nobody recorded has no row and a zero baseline has no change`() {
        val runs =
            listOf(
                run(Variant.NONE, 1, 10000.0, stealMs = 0.0),
                run(Variant.AGENT, 1, 12000.0, stealMs = 40.0),
            )
        val md = startupSummaryMarkdown(Config.HEADLINE, runs)
        assertTrue(md.lines().none { it.startsWith("| startupCpuMs") }, md)
        val steal = md.lines().single { it.startsWith("| startupStealMs") }
        assertTrue(steal.endsWith("| n/a |"), steal)
    }
}
