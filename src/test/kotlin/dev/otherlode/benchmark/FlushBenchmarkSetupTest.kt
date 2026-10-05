package dev.otherlode.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps [FlushBenchmark] measuring what its documentation says, since `./gradlew build` never runs the benchmark. */
class FlushBenchmarkSetupTest {
    private val fixture = FlushFixture.of("demo")

    @Test
    fun `the demo fixture registers and confirms every class the transformer woven`() {
        val populated = fixture.populate()
        assertTrue(fixture.classCount > 0)
        assertEquals(fixture.classCount, populated.registry.registeredClassNames().size)
        assertEquals(0, populated.registry.unconfirmedClassCount())
        assertEquals(fixture.probeCount, populated.arrays.sumOf { it.size })
    }

    @Test
    fun `a steady flush sends a tenth of the probes and no manifest`() {
        val state = FlushBenchmark.SteadyState()
        state.size = "demo"
        state.deliver()
        try {
            repeat(2) {
                state.change()
                state.harness.scheduler.flush()
                val exporter = state.harness.exporter
                val share =
                    state.harness.populated.changedProbeCount
                        .toDouble() / fixture.probeCount
                assertTrue(share in 0.09..0.11, "changed share $share")
                assertEquals(
                    state.harness.populated.changedProbeCount
                        .toLong(),
                    exporter.deltaProbeCount,
                )
                assertEquals(0L, exporter.manifestProbeCount)
                assertTrue(exporter.encodedBytes > 0)
            }
        } finally {
            state.stop()
        }
    }

    @Test
    fun `a first flush sends the whole manifest and the changed tenth`() {
        val state = FlushBenchmark.FirstState()
        state.size = "demo"
        repeat(2) {
            state.fresh()
            try {
                state.harness.scheduler.flush()
                val exporter = state.harness.exporter
                assertEquals(fixture.probeCount.toLong(), exporter.manifestProbeCount)
                assertEquals(
                    state.harness.populated.changedProbeCount
                        .toLong(),
                    exporter.deltaProbeCount,
                )
            } finally {
                state.stop()
            }
        }
    }

    @Test
    fun `a heartbeat flush sends an empty delta batch and nothing else`() {
        val state = FlushBenchmark.HeartbeatState()
        state.size = "demo"
        state.deliver()
        try {
            state.harness.exporter.reset()
            state.harness.scheduler.flush()
            val exporter = state.harness.exporter
            assertEquals(1L, exporter.deltaBatchCount)
            assertEquals(0L, exporter.deltaProbeCount)
            assertEquals(0L, exporter.manifestProbeCount)
        } finally {
            state.stop()
        }
    }
}
