package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SinkTest {
    @Test
    fun `counts are read one path to a line`() {
        val counts = SinkCounts.parse("/v1/logs 12 3400\n/v1/metrics 2 9000\n/v1/traces 7 123456\n")
        assertEquals(SinkCount(7, 123456), counts.byPath["/v1/traces"])
        assertEquals(SinkCount(2, 9000), counts.byPath["/v1/metrics"])
        assertEquals(SinkCount(12, 3400), counts.byPath["/v1/logs"])
        assertEquals(3, counts.byPath.size)
    }

    @Test
    fun `an empty reply is no counts`() {
        assertEquals(emptyMap<String, SinkCount>(), SinkCounts.parse("").byPath)
        assertEquals(emptyMap<String, SinkCount>(), SinkCounts.parse("\n\n").byPath)
    }

    @Test
    fun `a line that is not a path and two numbers is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SinkCounts.parse("/v1/traces seven 5\n") }
        assertThrows(IllegalArgumentException::class.java) { SinkCounts.parse("/v1/traces 7\n") }
    }

    @Test
    fun `the deltas are the requests per signal and all the bytes`() {
        val before = SinkCounts.parse("/v1/traces 3 300\n/v1/logs 1 10\n")
        val after = SinkCounts.parse("/v1/traces 10 1300\n/v1/metrics 2 500\n/v1/logs 1 10\n/v1/other 1 7\n")
        val m = sinkMetrics(before, after)
        assertEquals(7.0, m["sinkTraceExports"])
        assertEquals(2.0, m["sinkMetricExports"])
        assertEquals(0.0, m["sinkLogExports"])
        assertEquals(1000.0 + 500.0 + 7.0, m["sinkBytes"])
    }

    @Test
    fun `a signal the sink never saw counts zero`() {
        val m = sinkMetrics(SinkCounts.parse(""), SinkCounts.parse(""))
        assertEquals(0.0, m["sinkTraceExports"])
        assertEquals(0.0, m["sinkBytes"])
    }

    @Test
    fun `a run that exported no trace is a problem`() {
        val problem = sinkTraceProblem(mapOf("sinkTraceExports" to 0.0))
        assertNotNull(problem)
        assertTrue(problem!!.contains("the OpenTelemetry agent exported no trace"), problem)
    }

    @Test
    fun `a run that exported a trace has no sink problem`() {
        assertNull(sinkTraceProblem(mapOf("sinkTraceExports" to 1.0)))
    }

    @Test
    fun `a run with no sink reading is a problem`() {
        assertNotNull(sinkTraceProblem(emptyMap()))
    }
}
