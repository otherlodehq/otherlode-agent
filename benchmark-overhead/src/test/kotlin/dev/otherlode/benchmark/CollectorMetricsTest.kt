package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectorMetricsTest {
    private val text =
        """
        # HELP otherlode_collector_ingest_accepted_total Decoded, valid, handed to the sink
        # TYPE otherlode_collector_ingest_accepted_total counter
        otherlode_collector_ingest_accepted_total{payload="deltas"} 7
        otherlode_collector_ingest_accepted_total{payload="manifest"} 2
        # HELP otherlode_collector_ingest_rejected_total Turned away
        # TYPE otherlode_collector_ingest_rejected_total counter
        otherlode_collector_ingest_rejected_total{payload="deltas",reason="busy"} 1
        otherlode_collector_ingest_rejected_total{payload="manifest",reason="invalid"} 3
        otherlode_collector_rate_limited_total 4
        """.trimIndent()

    @Test
    fun `accepted counters are read per payload`() {
        val m = CollectorMetrics.parse(text)
        assertEquals(7, m.accepted("deltas"))
        assertEquals(2, m.accepted("manifest"))
        assertEquals(0, m.accepted("static_baseline"))
    }

    @Test
    fun `rejected counters are summed over payload and reason`() {
        assertEquals(4, CollectorMetrics.parse(text).rejectedTotal())
    }

    @Test
    fun `an empty exposition reads as zeros`() {
        val m = CollectorMetrics.parse("")
        assertEquals(0, m.accepted("deltas"))
        assertEquals(0, m.rejectedTotal())
    }

    @Test
    fun `a clean agent run is valid`() {
        val before = CollectorMetrics.parse(text)
        val after =
            CollectorMetrics.parse(
                text
                    .replace(
                        "payload=\"deltas\"} 7",
                        "payload=\"deltas\"} 9",
                    ).replace("payload=\"manifest\"} 2", "payload=\"manifest\"} 3"),
            )
        // The rejected series are unchanged, so the diff is clean.
        assertEquals(emptyList<String>(), collectorProblems(before, after, expectBaseline = false))
    }

    @Test
    fun `a run with no accepted deltas, manifest, or baseline is reported`() {
        val m = CollectorMetrics.parse(text)
        val problems = collectorProblems(m, m, expectBaseline = true)
        assertEquals(3, problems.size, problems.toString())
    }

    @Test
    fun `a rejected payload during the run is reported`() {
        val before = CollectorMetrics.parse(text)
        val after =
            CollectorMetrics.parse(
                text
                    .replace(
                        "payload=\"deltas\"} 7",
                        "payload=\"deltas\"} 9",
                    ).replace("payload=\"manifest\"} 2", "payload=\"manifest\"} 3") +
                    "\notherlode_collector_ingest_rejected_total{payload=\"deltas\",reason=\"too_large\"} 1",
            )
        assertEquals(1, collectorProblems(before, after, expectBaseline = false).size)
    }
}
