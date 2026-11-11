package dev.otherlode.benchmark

import com.example.hotpath.BranchyFixture
import com.example.hotpath.DefaultArgumentFixture
import com.example.hotpath.EntryOnlyFixture
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The probeAllocationTest task runs this with escape analysis off, so an object that only stays
// inside the call still counts.
class ProbeAllocationTest {
    private val weaver = HotPathWeaver()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun window(
        shape: HotPathShape,
        calls: Int,
    ): Long {
        val id = Thread.currentThread().id
        var sink = 0
        val before = threads.getThreadAllocatedBytes(id)
        for (i in 0 until calls) sink += shape.call(i)
        val after = threads.getThreadAllocatedBytes(id)
        check(sink != Int.MIN_VALUE) { "the results are read so the calls cannot be dropped" }
        return after - before
    }

    // The JVM can allocate a few hundred bytes once as it moves the loop between compiler tiers. An allocation
    // per call shows in every window and a one-off in at most one, so this takes the smallest.
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
