package dev.otherlode.memory

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHeapTrimmerTest {
    private class FakeSchedule : TrimSchedule {
        var initialDelay: Duration? = null
        var period: Duration? = null
        var task: Runnable? = null
        var starts = 0
        var stopped = false

        override fun start(
            initialDelay: Duration,
            period: Duration,
            task: Runnable,
        ) {
            starts++
            this.initialDelay = initialDelay
            this.period = period
            this.task = task
        }

        override fun stop() {
            stopped = true
        }

        fun tick() {
            if (!stopped) task!!.run()
        }
    }

    private class FakeHeap(
        var option: String? = null,
        var answers: () -> String = { "Trim native heap: RSS+Swap: 118M->109M (-9548K)" },
    ) : NativeHeap {
        var opens = 0
        var optionReads = 0
        var trims = 0

        override fun open() {
            opens++
        }

        override fun jvmTrimInterval(): String? {
            optionReads++
            return option
        }

        override fun trim(): String {
            trims++
            return answers()
        }
    }

    private val schedule = FakeSchedule()
    private val heap = FakeHeap()
    private var gateWaits = 0

    private fun trimmer(
        interval: Duration = Duration.ofMinutes(60),
        os: String = "Linux",
        heap: NativeHeap = this.heap,
        schedule: TrimSchedule = this.schedule,
    ) = NativeHeapTrimmer(interval, os, { heap }, schedule, awaitLoggingGate = { gateWaits++ })

    @Test
    fun `a zero interval schedules nothing`() {
        trimmer(interval = Duration.ZERO).start()

        assertEquals(0, schedule.starts)
        assertEquals(0, heap.trims)
        assertEquals(0, heap.optionReads)
    }

    @Test
    fun `an operating system that is not Linux schedules nothing`() {
        for (os in listOf("Mac OS X", "Windows 11", "FreeBSD", "linux")) {
            trimmer(os = os).start()
        }

        assertEquals(0, schedule.starts)
        assertEquals(0, heap.trims)
    }

    @Test
    fun `nothing is trimmed when the trimmer starts, and the first trim waits one full interval`() {
        trimmer(interval = Duration.ofMinutes(45)).start()

        assertEquals(1, schedule.starts)
        assertEquals(Duration.ofMinutes(45), schedule.initialDelay)
        assertEquals(Duration.ofMinutes(45), schedule.period)
        assertEquals(0, heap.trims)
        assertEquals(0, heap.optionReads)
        assertEquals(0, gateWaits)
    }

    @Test
    fun `each interval runs one trim`() {
        trimmer().start()

        repeat(3) { schedule.tick() }

        assertEquals(3, heap.trims)
        assertFalse(schedule.stopped)
    }

    @Test
    fun `the first run waits for the logging gate before it touches the JVM, and the gate and the beans are readied once`() {
        val order = mutableListOf<String>()
        val recording =
            object : NativeHeap {
                override fun open() {
                    order += "open"
                }

                override fun jvmTrimInterval(): String? {
                    order += "option"
                    return null
                }

                override fun trim(): String {
                    order += "trim"
                    return "ok"
                }
            }
        NativeHeapTrimmer(Duration.ofMinutes(1), "Linux", { recording }, schedule, awaitLoggingGate = { order += "gate" }).start()

        schedule.tick()
        schedule.tick()

        assertEquals(listOf("gate", "open", "option", "trim", "trim"), order)
    }

    @Test
    fun `a JVM that trims by itself ends the trimmer without a trim`() {
        heap.option = "300000"
        trimmer().start()

        schedule.tick()

        assertEquals(0, heap.trims)
        assertTrue(schedule.stopped)
    }

    @Test
    fun `a JVM option set to zero leaves the trimmer running`() {
        heap.option = "0"
        trimmer().start()

        schedule.tick()
        schedule.tick()

        assertEquals(2, heap.trims)
        assertEquals(1, heap.optionReads)
        assertFalse(schedule.stopped)
    }

    @Test
    fun `a JVM option that is not a number ends the trimmer`() {
        heap.option = "soon"
        trimmer().start()

        schedule.tick()

        assertEquals(0, heap.trims)
        assertTrue(schedule.stopped)
    }

    @Test
    fun `a missing JVM option leaves the trimmer running`() {
        heap.option = null
        trimmer().start()

        schedule.tick()

        assertEquals(1, heap.trims)
        assertFalse(schedule.stopped)
    }

    @Test
    fun `a missing trim command or management module ends the trimmer for good`() {
        val failures =
            listOf<() -> Throwable>(
                { NoTrimCommand() },
                { NoClassDefFoundError("com/sun/management/HotSpotDiagnosticMXBean") },
            )
        for (failure in failures) {
            val failing = FakeHeap(answers = { throw failure() })
            val failingSchedule = FakeSchedule()
            trimmer(heap = failing, schedule = failingSchedule).start()

            failingSchedule.task!!.run()
            failingSchedule.task!!.run()

            assertEquals(1, failing.trims)
            assertTrue(failingSchedule.stopped)
        }
    }

    @Test
    fun `a missing class outside the management modules still ends the trimmer`() {
        heap.answers = { throw NoClassDefFoundError("dev/otherlode/shaded/Missing") }
        trimmer().start()

        schedule.task!!.run()
        schedule.task!!.run()

        assertEquals(1, heap.trims)
        assertTrue(schedule.stopped)
    }

    @Test
    fun `an answer that says the trim is not available ends the trimmer for good`() {
        for (text in listOf("Not available", "Trim native heap is NOT AVAILABLE on this platform")) {
            val unavailable = FakeHeap(answers = { text })
            val unavailableSchedule = FakeSchedule()
            trimmer(heap = unavailable, schedule = unavailableSchedule).start()

            unavailableSchedule.task!!.run()
            unavailableSchedule.task!!.run()

            assertEquals(1, unavailable.trims)
            assertTrue(unavailableSchedule.stopped)
        }
    }

    @Test
    fun `another exception ends the trimmer after one trim and is not thrown`() {
        heap.answers = { throw IllegalStateException("boom") }
        trimmer().start()

        schedule.task!!.run()
        schedule.task!!.run()

        assertEquals(1, heap.trims)
        assertTrue(schedule.stopped)
    }

    @Test
    fun `an error from the logging gate ends the trimmer without a trim`() {
        NativeHeapTrimmer(Duration.ofMinutes(1), "Linux", { heap }, schedule, awaitLoggingGate = { throw IllegalStateException("gate") }).start()

        schedule.task!!.run()
        schedule.task!!.run()

        assertEquals(0, heap.trims)
        assertTrue(schedule.stopped)
    }

    @Test
    fun `stop ends the schedule and a late run trims nothing`() {
        val trimmer = trimmer()
        trimmer.start()

        trimmer.stop()
        schedule.task!!.run()

        assertTrue(schedule.stopped)
        assertEquals(0, heap.trims)
    }

    @Test
    fun `stop before start is harmless`() {
        trimmer().stop()

        assertEquals(0, heap.trims)
    }

    @Test
    fun `the real schedule runs a task on a daemon thread named otherlode-trim, and stops`() {
        val real = ThreadTrimSchedule()
        var thread: Thread? = null
        val ran = CountDownLatch(1)

        real.start(Duration.ofMillis(1), Duration.ofHours(1)) {
            thread = Thread.currentThread()
            ran.countDown()
        }

        assertTrue(ran.await(10, TimeUnit.SECONDS))
        assertEquals("otherlode-trim", thread!!.name)
        assertTrue(thread!!.isDaemon)
        real.stop()
        thread!!.join(10_000)
        assertFalse(thread!!.isAlive)
    }

    @Test
    fun `the real JVM seam trims the live JVM on Linux`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "needs Linux")

        JmxNativeHeap.open()
        val text = JmxNativeHeap.trim()

        assertTrue(text.isNotBlank(), "the JDK answered no text")
    }
}
