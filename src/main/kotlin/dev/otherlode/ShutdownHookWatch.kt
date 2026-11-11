package dev.otherlode

import dev.otherlode.bootstrap.OtherlodeShutdownHooks
import java.time.Duration

internal class ShutdownHookWatch internal constructor(
    private val hooks: () -> Array<Thread>?,
) {
    fun awaitOthers(
        self: Thread,
        cap: Duration,
    ): Boolean {
        val deadline = System.nanoTime() + cap.toNanos()
        val others = hooks()?.filter { it !== self } ?: return true
        return others.map { awaitEnd(it, deadline) }.all { it }
    }

    private fun awaitEnd(
        hook: Thread,
        deadline: Long,
    ): Boolean {
        val pollEnds = minOf(deadline, System.nanoTime() + NEW_POLL.toNanos())
        // A hook still NEW after NEW_POLL is one the JVM never starts, as JDK 25 leaves a virtual-thread hook it
        // could not start, so it counts as ended.
        while (hook.state == Thread.State.NEW) {
            if (remainingMillis(pollEnds) <= 0L) return true
            sleepQuietly(START_POLL_MILLIS)
        }
        var remaining = remainingMillis(deadline)
        while (hook.isAlive && remaining > 0L) {
            try {
                hook.join(remaining)
            } catch (_: InterruptedException) {
                Thread.interrupted()
            }
            remaining = remainingMillis(deadline)
        }
        return !hook.isAlive
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.interrupted()
        }
    }

    private fun remainingMillis(deadline: Long): Long = maxOf(0L, (deadline - System.nanoTime()) / 1_000_000)

    internal companion object {
        private const val START_POLL_MILLIS = 5L
        private val NEW_POLL: Duration = Duration.ofSeconds(1)

        fun fromSeam(): ShutdownHookWatch = ShutdownHookWatch { OtherlodeShutdownHooks.hooks() }
    }
}
