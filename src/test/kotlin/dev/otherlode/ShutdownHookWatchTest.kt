package dev.otherlode

import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShutdownHookWatchTest {
    private fun watch(vararg hooks: Thread): ShutdownHookWatch = ShutdownHookWatch { arrayOf(*hooks) }

    private fun daemon(body: () -> Unit) = Thread(body).apply { isDaemon = true }

    @Test
    fun `it waits for a hook that is still running and returns true when it ends`() {
        val release = CountDownLatch(1)
        val hook = daemon { release.await() }.apply { start() }
        val releaser = daemon { Thread.sleep(200).also { release.countDown() } }.apply { start() }

        val ended = watch(hook).awaitOthers(Thread.currentThread(), Duration.ofSeconds(10))

        assertTrue(ended)
        assertFalse(hook.isAlive)
        releaser.join()
    }

    @Test
    fun `it waits for a hook the JVM has not started yet`() {
        val ran = CountDownLatch(1)
        val hook = daemon { Thread.sleep(100).also { ran.countDown() } }
        val starter = daemon { Thread.sleep(150).also { hook.start() } }.apply { start() }

        val ended = watch(hook).awaitOthers(Thread.currentThread(), Duration.ofSeconds(10))

        assertTrue(ended)
        assertEquals(0L, ran.count, "the hook started late and was waited for")
        starter.join()
    }

    @Test
    fun `it gives up at the cap on a hook that never ends`() {
        val never = CountDownLatch(1)
        val hook = daemon { never.await() }.apply { start() }
        var ended = true

        val elapsed = measureTimeMillis { ended = watch(hook).awaitOthers(Thread.currentThread(), Duration.ofMillis(300)) }
        never.countDown()

        assertFalse(ended)
        assertTrue(elapsed in 250..5_000, "returned at the cap, took ${elapsed}ms")
    }

    @Test
    fun `a hook that is never started is polled for one second at most and counts as ended`() {
        val hook = daemon { }
        var ended = false

        val elapsed = measureTimeMillis { ended = watch(hook).awaitOthers(Thread.currentThread(), Duration.ofSeconds(30)) }

        assertTrue(ended, "a hook the JVM never starts has nothing to wait for")
        assertTrue(elapsed in 900..3_000, "took ${elapsed}ms")
    }

    @Test
    fun `the poll for a hook that is not started ends at the cap when the cap is shorter`() {
        val hook = daemon { }

        val elapsed = measureTimeMillis { watch(hook).awaitOthers(Thread.currentThread(), Duration.ofMillis(300)) }

        assertTrue(elapsed in 250..2_000, "took ${elapsed}ms")
    }

    @Test
    fun `several hooks that are never started share nothing, each is polled for one second at most`() {
        val hooks = Array(3) { daemon { } }

        val elapsed = measureTimeMillis { watch(*hooks).awaitOthers(Thread.currentThread(), Duration.ofSeconds(30)) }

        assertTrue(elapsed < 6_000, "took ${elapsed}ms")
    }

    @Test
    fun `an interrupt of the waiting thread does not end the wait`() {
        val release = CountDownLatch(1)
        val hook = daemon { release.await() }.apply { start() }
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        val waiter = daemon { result.complete(watch(hook).awaitOthers(Thread.currentThread(), Duration.ofSeconds(30))) }.apply { start() }
        Thread.sleep(200)

        waiter.interrupt()
        Thread.sleep(300)
        assertFalse(result.isDone, "the wait went on after the interrupt")
        release.countDown()

        assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `hooks that are not known leave nothing to wait for`() {
        assertTrue(ShutdownHookWatch { null }.awaitOthers(Thread.currentThread(), Duration.ofSeconds(30)))
    }

    @Test
    fun `it never joins the caller's own hook`() {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        lateinit var self: Thread
        self = daemon { result.complete(watch(self).awaitOthers(self, Duration.ofSeconds(30))) }
        self.start()

        assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS), "a map holding only the caller has nothing to wait for")
    }

    @Test
    fun `a hook that has already ended does not delay it`() {
        val hook = daemon { }.apply { start() }
        hook.join()

        val elapsed = measureTimeMillis { assertTrue(watch(hook).awaitOthers(Thread.currentThread(), Duration.ofSeconds(30))) }

        assertTrue(elapsed < 2_000, "took ${elapsed}ms")
    }
}
