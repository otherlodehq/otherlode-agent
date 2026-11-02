package dev.otherlode

import dev.otherlode.bootstrap.OtherlodeShutdownHooks
import java.time.Duration

/**
 * The application's own shutdown hooks, as the JVM runs them. The JVM starts them all at once when
 * it exits and the agent's hook is one of them, so a hook of the application can run code after the
 * agent's hook flushed. [awaitOthers] lets the agent's hook wait for the others.
 *
 * [hooks] returns the hook threads, or null when they are not known. In the agent it reads the
 * table that the weave of `ApplicationShutdownHooks.runHooks` hands to [OtherlodeShutdownHooks].
 */
internal class ShutdownHookWatch internal constructor(
    private val hooks: () -> Array<Thread>?,
) {
    /**
     * Waits until every hook other than [self] has ended or [cap] has passed, and returns false only
     * when a hook that had started was still running at [cap]. The JVM starts hooks one after
     * another and the agent's hook can run before the rest have started, so a hook not yet started
     * is polled for [NEW_POLL] at most. A hook still not started then is one the JVM never starts,
     * as JDK 25 leaves a virtual-thread hook it could not start, and counts as ended. [self] is
     * never joined: it is the caller, and joining it would never end. An interrupt of the caller
     * does not end the wait.
     */
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

        /** The watch over the table the weave of `runHooks` handed to the bootstrap seam. */
        fun fromSeam(): ShutdownHookWatch = ShutdownHookWatch { OtherlodeShutdownHooks.hooks() }
    }
}
