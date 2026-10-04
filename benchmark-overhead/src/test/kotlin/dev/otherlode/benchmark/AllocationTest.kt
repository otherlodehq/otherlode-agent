package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class AllocationTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun sample(
        thread: Long,
        secondsIn: Long,
        allocated: Long,
    ) = AllocationSample(thread, t0.plusSeconds(secondsIn), allocated)

    @Test
    fun `sums each thread's last value minus its first`() {
        val samples =
            listOf(
                sample(1, 0, 100),
                sample(2, 0, 1_000),
                sample(1, 60, 400),
                sample(2, 60, 1_500),
                sample(1, 120, 900),
                sample(2, 120, 1_700),
            )
        assertEquals((900 - 100) + (1_700 - 1_000), allocatedInWindow(samples))
    }

    @Test
    fun `a cumulative total summed over samples would be wrong`() {
        val samples = listOf(sample(1, 0, 1_000), sample(1, 60, 1_100), sample(1, 120, 1_200))
        assertEquals(200, allocatedInWindow(samples))
    }

    @Test
    fun `a thread that starts inside the window counts from zero`() {
        val samples = listOf(sample(1, 0, 100), sample(1, 120, 300), sample(2, 120, 50))
        assertEquals(200 + 50, allocatedInWindow(samples))
    }

    @Test
    fun `a thread seen once at the start allocated nothing in the window`() {
        assertEquals(0, allocatedInWindow(listOf(sample(1, 0, 500), sample(2, 0, 700))))
    }

    @Test
    fun `no samples is zero`() {
        assertEquals(0, allocatedInWindow(emptyList()))
    }
}
