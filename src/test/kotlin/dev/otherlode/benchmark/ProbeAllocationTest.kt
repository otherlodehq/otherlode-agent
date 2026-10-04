package dev.otherlode.benchmark

import com.example.hotpath.BranchyFixture
import com.example.hotpath.DefaultArgumentFixture
import com.example.hotpath.EntryOnlyFixture
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gates the woven probes' allocation: a method with probes allocates exactly as many bytes as the
 * same method without them, which for the hot-path fixtures is none. The `probeAllocationTest` task
 * runs this with escape analysis off, so an object that only stays inside the call, a boxed value
 * or a varargs array passed to an inlined helper, still counts, and a control that boxes per call
 * must read above zero. C2 still removes an allocation whose every use folds away, which no probe
 * makes. The JVM itself can allocate a few hundred bytes on
 * the calling thread once, as it moves the calling loop between compiler tiers, so the check takes
 * the smallest of several windows and requires it to be zero: an allocation per call shows in every
 * window, a one-off event in at most one.
 *
 * The endpoint seam is left out. `EndpointRegistry.lookupKeyOf` compares a `List` key with a
 * for-each loop, and `ImmutableCollections.AbstractImmutableList.equals` calls `iterator()` on the
 * other list, so a request key equal to the registered key but not identical to it allocates unless
 * C2's escape analysis removes the iterator. That is not deterministic, so the JMH suite's GC
 * profiler reports the seam's allocation instead.
 */
class ProbeAllocationTest {
    private val weaver = HotPathWeaver()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Bytes the current thread allocates across [calls] calls of [shape]. */
    private fun window(
        shape: HotPathShape,
        calls: Int,
    ): Long {
        val id = Thread.currentThread().threadId()
        var sink = 0
        val before = threads.getThreadAllocatedBytes(id)
        for (i in 0 until calls) sink += shape.call(i)
        val after = threads.getThreadAllocatedBytes(id)
        check(sink != Int.MIN_VALUE) { "the results are read so the calls cannot be dropped" }
        return after - before
    }

    /** The fewest bytes allocated across any of [WINDOWS] windows of [CALLS] calls, after one unmeasured warm-up window. */
    private fun allocatedBy(shape: HotPathShape): Long {
        window(shape, CALLS)
        return (1..WINDOWS).minOf { window(shape, CALLS) }
    }

    @Test
    fun `the measurement sees an allocation per call`() {
        check(threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled) {
            "this JVM does not account allocation per thread"
        }
        assertTrue(
            allocatedBy(AllocatingShape()) >= CALLS.toLong(),
            "a call that boxes a Long read as allocating nothing; run this through the probeAllocationTest task, which turns escape analysis off",
        )
    }

    @Test
    fun `woven fixtures allocate nothing, and so do their unwoven controls`() {
        for (fixture in FIXTURES) {
            val name = fixture.simpleName
            assertEquals(0L, allocatedBy(weaver.unwoven(fixture)), "$name unwoven allocated: the harness is wrong, not the probes")
            assertEquals(0L, allocatedBy(weaver.woven(fixture)), "$name woven allocated")
        }
    }

    /** Boxes a value outside the `Long` cache on every call, and keeps it only inside the call. */
    private class AllocatingShape : HotPathShape {
        override fun call(x: Int): Int {
            val boxed: Any = x.toLong() + BOXED_OFFSET
            return boxed.hashCode()
        }
    }

    private companion object {
        const val BOXED_OFFSET = 1_000_000L
        const val WINDOWS = 5
        const val CALLS = 100_000
        val FIXTURES = listOf(EntryOnlyFixture::class.java, BranchyFixture::class.java, DefaultArgumentFixture::class.java)
    }
}
