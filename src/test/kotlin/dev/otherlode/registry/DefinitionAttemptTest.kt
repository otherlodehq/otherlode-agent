package dev.otherlode.registry

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [DefinitionAttempt] over real threads. [TransformerEntry] stands in for the JVM's
 * transformer entry frame and [Definer] for the frame that starts a definition, so the capture
 * and the stack read run the code that production runs.
 */
class DefinitionAttemptTest {
    private object TransformerEntry {
        fun transform(body: () -> Unit) {
            body()
        }
    }

    private class Definer {
        fun defineClass1(body: () -> Unit) {
            TransformerEntry.transform(body)
        }

        fun otherMethod(body: () -> Unit) {
            body()
        }
    }

    private val entryClass = TransformerEntry::class.java.name

    private fun capture(): DefinitionAttempt? = DefinitionAttempt.capture(entryClass, "transform")

    /** A thread parked inside [Definer.defineClass1], with the attempt captured there. */
    private inner class ParkedDefiner {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val afterFrame = CountDownLatch(1)
        val stayAlive = CountDownLatch(1)
        var attempt: DefinitionAttempt? = null

        val thread =
            Thread({
                Definer().defineClass1 {
                    attempt = capture()
                    captured.countDown()
                    release.await()
                }
                afterFrame.countDown()
                stayAlive.await()
            }, "test-definer").apply { start() }

        fun awaitCaptured(): DefinitionAttempt {
            assertTrue(captured.await(30, TimeUnit.SECONDS))
            return assertNotNull(attempt)
        }

        fun finish() {
            release.countDown()
            stayAlive.countDown()
            thread.join(30_000)
        }
    }

    @Test
    fun `capture records the frame below the transformer entry, and the defining thread's stack holds it`() {
        val definer = ParkedDefiner()
        try {
            val attempt = definer.awaitCaptured()

            assertEquals(Definer::class.java.name, attempt.frameClass)
            assertEquals("defineClass1", attempt.frameMethod)
            assertTrue(attempt.heldBy(definer.thread.stackTrace))
        } finally {
            definer.finish()
        }
    }

    @Test
    fun `capture finds no attempt when no transformer entry frame is on the stack`() {
        assertNull(capture())
        assertNull(DefinitionAttempt.capture())
    }

    @Test
    fun `an attempt reads as ongoing while its thread stays in the definition frame`() {
        val definer = ParkedDefiner()
        try {
            val attempt = definer.awaitCaptured()

            assertFalse(attempt.hasEnded())
            assertFalse(attempt.hasEnded(), "reading it twice changes nothing")
        } finally {
            definer.finish()
        }
    }

    @Test
    fun `an attempt reads as ended once its thread leaves the frame but stays alive`() {
        val definer = ParkedDefiner()
        try {
            val attempt = definer.awaitCaptured()
            definer.release.countDown()
            assertTrue(definer.afterFrame.await(30, TimeUnit.SECONDS))
            waitUntilParkedAfterFrame(definer.thread)

            assertTrue(definer.thread.isAlive)
            assertTrue(attempt.hasEnded())
        } finally {
            definer.finish()
        }
    }

    @Test
    fun `an attempt reads as ended once its thread has terminated`() {
        val definer = ParkedDefiner()
        val attempt = definer.awaitCaptured()
        definer.finish()

        assertFalse(definer.thread.isAlive)
        assertTrue(attempt.hasEnded())
    }

    @Test
    fun `a different method at the same depth reads as ended`() {
        val atSameDepth = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread =
            Thread({
                Definer().otherMethod {
                    atSameDepth.countDown()
                    release.await()
                }
            }, "test-other").apply { start() }
        try {
            assertTrue(atSameDepth.await(30, TimeUnit.SECONDS))
            val depth = depthOf(thread, "otherMethod")
            val stack = thread.stackTrace
            val from = stack.size - 1 - depth
            val attempt =
                DefinitionAttempt(
                    thread,
                    listOf(Definer::class.java.name + "#defineClass1") + stack.drop(from + 1).map { it.className + "#" + it.methodName },
                )

            assertTrue(attempt.hasEnded(), "the frame at that depth is otherMethod, not defineClass1")
        } finally {
            release.countDown()
            thread.join(30_000)
        }
    }

    private fun depthOf(
        thread: Thread,
        method: String,
    ): Int {
        val stack = thread.stackTrace
        return stack.size - 1 - stack.indexOfFirst { it.methodName == method }
    }

    private fun waitUntilParkedAfterFrame(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (thread.stackTrace.any { it.methodName == "defineClass1" }) {
            check(System.nanoTime() < deadline) { "the thread never left the definition frame" }
            Thread.sleep(1)
        }
    }

    /** Calls [Runnable.run] on [body] through `Method.invoke`, as a launcher calls `main`. */
    class ReflectiveLauncher {
        fun launch(body: Runnable) {
            Runnable::class.java.getMethod("run").invoke(body)
        }
    }

    @Test
    fun `an attempt below a reflective call reads as ongoing while its thread stays in the definition frame`() {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        var attempt: DefinitionAttempt? = null
        val thread =
            Thread({
                ReflectiveLauncher().launch {
                    Definer().defineClass1 {
                        attempt = capture()
                        captured.countDown()
                        release.await()
                    }
                }
            }, "test-reflective-definer").apply { start() }
        try {
            assertTrue(captured.await(30, TimeUnit.SECONDS))
            val recorded = assertNotNull(attempt)
            assertTrue(thread.stackTrace.any { it.methodName == "invoke" && it.className.startsWith("java.lang.reflect.") })
            assertFalse(recorded.hasEnded(), "the thread is still in the definition frame")
        } finally {
            release.countDown()
            thread.join(30_000)
        }
    }

    private fun descend(
        frames: Int,
        body: () -> Unit,
    ) {
        if (frames == 0) body() else descend(frames - 1, body)
    }

    @Test
    fun `an attempt on a stack deeper than a stack read can show reads as ongoing`() {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        var attempt: DefinitionAttempt? = null
        val thread =
            Thread({
                descend(1500) {
                    Definer().defineClass1 {
                        attempt = capture()
                        captured.countDown()
                        release.await()
                    }
                }
            }, "test-deep-definer").apply { start() }
        try {
            assertTrue(captured.await(30, TimeUnit.SECONDS))
            val recorded = assertNotNull(attempt)
            assertFalse(recorded.hasEnded(), "a cut-short read proves nothing")
        } finally {
            release.countDown()
            thread.join(30_000)
        }
    }

    private fun element(key: String) = StackTraceElement(key.substringBefore('#'), key.substringAfter('#'), null, -1)

    private fun stack(vararg topFirst: String): Array<StackTraceElement> = topFirst.map(::element).toTypedArray()

    private val held = DefinitionAttempt(Thread.currentThread(), listOf("L#defineClass1", "L#loadClass", "App#main", "T#run"))

    @Test
    fun `a read with extra frames among the held ones still holds the attempt`() {
        val read = stack("S#resolve", "L#defineClass1", "Lambda#run", "L#loadClass", "MH#invoke", "App#main", "T#run")

        assertTrue(held.heldBy(read))
    }

    @Test
    fun `a read that lost the definition frame no longer holds the attempt`() {
        val read = stack("X#other", "L#loadClass", "App#main", "T#run")

        assertFalse(held.heldBy(read))
    }

    @Test
    fun `a read whose bottom frame differs proves nothing and holds the attempt`() {
        val cutShort = stack("Deep#frame", "Deep#frame")

        assertTrue(held.heldBy(cutShort))
        assertTrue(held.heldBy(emptyArray()))
    }

    /** A thread whose bottom method recurses, so a cut-short read ends in a frame named like its bottom. */
    private inner class RecursingThread(
        private val onDeepest: () -> Unit,
    ) : Thread("test-recursing-definer") {
        private var depth = 0

        override fun run() {
            if (depth++ < 1500) run() else Definer().defineClass1 { onDeepest() }
        }
    }

    @Test
    fun `an attempt on a deep stack whose bottom method recurses reads as ongoing`() {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        var attempt: DefinitionAttempt? = null
        val thread =
            RecursingThread {
                attempt = capture()
                captured.countDown()
                release.await()
            }.apply { start() }
        try {
            assertTrue(captured.await(30, TimeUnit.SECONDS))
            assertFalse(assertNotNull(attempt).hasEnded(), "a read as long as the cut proves nothing")
        } finally {
            release.countDown()
            thread.join(30_000)
        }
    }
}
