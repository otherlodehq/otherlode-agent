package dev.otherlode.dependencies

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyListingRunTest {
    @Test
    fun `the listing runs once however many times it is asked for`() {
        val runs = AtomicInteger()
        val listing = DependencyListingRun { runs.incrementAndGet() }

        repeat(3) { listing.runOnce() }

        assertEquals(1, runs.get())
    }

    @Test
    fun `two concurrent triggers run the listing exactly once, and the second returns only after it ended`() {
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = AtomicInteger()
        val listing =
            DependencyListingRun {
                runs.incrementAndGet()
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                ended.incrementAndGet()
            }
        val first = thread { listing.runOnce() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        var endedWhenSecondReturned = -1
        val second =
            thread {
                listing.runOnce()
                endedWhenSecondReturned = ended.get()
            }

        second.join(200)
        assertTrue(second.isAlive, "the second trigger must wait for the run in progress")
        release.countDown()
        first.join(10_000)
        second.join(10_000)

        assertEquals(1, runs.get())
        assertEquals(1, endedWhenSecondReturned)
    }

    @Test
    fun `a listing that throws is not run again`() {
        val runs = AtomicInteger()
        val listing = DependencyListingRun { runs.incrementAndGet().also { error("boom") } }

        runCatching { listing.runOnce() }
        listing.runOnce()

        assertEquals(1, runs.get())
    }
}
