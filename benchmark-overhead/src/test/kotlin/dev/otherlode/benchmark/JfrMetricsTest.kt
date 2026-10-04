package dev.otherlode.benchmark

import jdk.jfr.Recording
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JfrMetricsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads allocation and thread counts from a real recording`() {
        val file = dir.resolve("r.jfr")
        var sink: Array<ByteArray>
        Recording().use { rec ->
            rec.enable("jdk.ThreadAllocationStatistics").with("period", "everyChunk")
            rec.enable("jdk.JavaThreadStatistics").withPeriod(java.time.Duration.ofMillis(100))
            rec.enable("jdk.CPULoad").withPeriod(java.time.Duration.ofMillis(100))
            rec.start()
            sink = Array(40) { ByteArray(1024 * 1024) }
            Thread.sleep(500)
            rec.stop()
            rec.dump(file)
        }
        assertEquals(40, sink.size)
        val m = JfrMetrics.read(file)
        assertTrue(m.getValue("allocatedMiB") >= 30.0, m.toString())
        assertTrue(m.getValue("peakThreads") >= 1.0, m.toString())
        assertTrue(m.containsKey("cpuJvmUserAvgPct"), m.toString())
        assertTrue(m.getValue("cpuJvmUserMaxPct") >= m.getValue("cpuJvmUserAvgPct"))
    }
}
