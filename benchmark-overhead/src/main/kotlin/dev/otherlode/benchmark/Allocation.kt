package dev.otherlode.benchmark

import java.time.Duration
import java.time.Instant

/** One `jdk.ThreadAllocationStatistics` reading: bytes the thread has allocated since it started. */
data class AllocationSample(
    val threadId: Long,
    val time: Instant,
    val allocated: Long,
)

/**
 * Bytes allocated inside the recording, summed over threads.
 *
 * The event reports each thread's cumulative total, so summing the readings counts early
 * allocation again at every sample. Each thread contributes its last reading minus its first. A
 * thread whose first reading comes after the recording began started inside it, so everything it
 * allocated counts.
 */
fun allocatedInWindow(
    samples: List<AllocationSample>,
    tolerance: Duration = Duration.ofSeconds(1),
): Long {
    if (samples.isEmpty()) return 0
    val windowStart = samples.minOf { it.time }
    return samples
        .groupBy { it.threadId }
        .values
        .sumOf { thread ->
            val sorted = thread.sortedBy { it.time }
            val first = sorted.first()
            val baseline = if (first.time <= windowStart.plus(tolerance)) first.allocated else 0L
            (sorted.last().allocated - baseline).coerceAtLeast(0L)
        }
}
