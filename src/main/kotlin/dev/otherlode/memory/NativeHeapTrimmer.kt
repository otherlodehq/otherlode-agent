package dev.otherlode.memory

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.instrumentation.BootstrapHolder
import java.lang.System.Logger.Level
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * What the trimmer asks of the JVM. The real one is [JmxNativeHeap]; a test supplies its own.
 * No type in this file names a `java.management` class, so the agent starts on a JVM without that
 * module; the real heap loads it on the trim thread.
 */
internal interface NativeHeap {
    /** Readies the JVM's management beans. Its first call initialises `java.util.logging`. */
    fun open()

    /**
     * The value of the JVM option `TrimNativeHeapInterval`, or null when this JVM has no such option.
     */
    fun jvmTrimInterval(): String?

    /** Runs the JDK's native heap trim and returns the text it answers. Throws [NoTrimCommand] when the JVM has none. */
    fun trim(): String
}

/** Thrown by a [NativeHeap] whose JVM has no native heap trim command. */
internal class NoTrimCommand : Exception(null, null, false, false)

/** Runs [NativeHeapTrimmer]'s trim on a timer. A test supplies its own to run the trim by hand. */
internal interface TrimSchedule {
    /** Runs [task] after [initialDelay], then again [period] after each run ends. */
    fun start(
        initialDelay: Duration,
        period: Duration,
        task: Runnable,
    )

    /** Ends the schedule. A later run does not start. */
    fun stop()
}

/** [TrimSchedule] on one daemon thread named `otherlode-trim`, which exists only once [start] runs. */
internal class ThreadTrimSchedule : TrimSchedule {
    @Volatile
    private var executor: ScheduledThreadPoolExecutor? = null

    override fun start(
        initialDelay: Duration,
        period: Duration,
        task: Runnable,
    ) {
        val created =
            ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "otherlode-trim").apply { isDaemon = true } }
        executor = created
        created.scheduleWithFixedDelay(task, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        executor?.shutdown()
    }
}

/**
 * Returns the native memory the JVM has freed to the operating system, every [interval], on Linux.
 * glibc keeps freed memory in its arenas, and the resident size of a long-running JVM grows by
 * what C2 compilation leaves there. The JDK's `System.trim_native_heap` command returns it.
 *
 * It does nothing when [interval] is zero or [osName] does not start with `Linux`. The first trim
 * runs one [interval] after [start]. The first run waits for the agent's logging gate, then reads
 * the JVM option `TrimNativeHeapInterval`; a JVM that already trims on its own ends this trimmer.
 * The trimmer also ends when the JVM has no trim command, including a JVM without the
 * `java.management` or `jdk.management` module, when it answers that the trim is not available,
 * and when a trim throws. An ended trimmer never trims again. Nothing here throws into the host.
 */
internal class NativeHeapTrimmer(
    private val interval: Duration,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val heap: () -> NativeHeap = { JmxNativeHeap },
    private val schedule: TrimSchedule = ThreadTrimSchedule(),
    private val awaitLoggingGate: () -> Unit = { if (BootstrapHolder.isInstalled()) OtherlodeLoggers.awaitGate() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val log by agentLogger(NativeHeapTrimmer::class.java.name)

    @Volatile
    private var ended = false
    private var firstRunDone = false
    private var jvm: NativeHeap? = null

    /** Schedules the trims, or does nothing when [interval] is zero or the OS is not Linux. */
    fun start() {
        if (interval.isZero || !osName.startsWith("Linux")) return
        schedule.start(interval, interval, ::run)
    }

    /** Ends the schedule. */
    fun stop() {
        ended = true
        schedule.stop()
    }

    private fun run() {
        if (ended) return
        try {
            if (!firstRunDone) {
                awaitLoggingGate()
                firstRunDone = true
                jvm = heap().also { it.open() }
                val jvmInterval = jvm!!.jvmTrimInterval()
                if (jvmInterval != null && jvmInterval.toLongOrNull() != 0L) {
                    end(Level.INFO, "the JVM already trims by itself (TrimNativeHeapInterval=$jvmInterval)")
                    return
                }
            }
            val started = nanoTime()
            val text = jvm!!.trim()
            val millis = (nanoTime() - started) / 1_000_000.0
            val line = text.trim().replace(LINE_BREAKS, " ")
            if (line.contains("not available", ignoreCase = true)) {
                end(Level.INFO, "this JVM answers that it cannot trim ($line)")
                return
            }
            log.log(Level.DEBUG, "otherlode: trimmed the native heap in ${String.format(Locale.ROOT, "%.3f", millis)} ms: $line")
        } catch (e: NoTrimCommand) {
            end(Level.INFO, "this JVM has no trim command")
        } catch (e: NoClassDefFoundError) {
            if (MANAGEMENT_CLASS.containsMatchIn(e.message.orEmpty())) {
                end(Level.INFO, "this JVM has no trim command")
            } else {
                end(Level.WARNING, "the trim failed", e)
            }
        } catch (t: Throwable) {
            end(Level.WARNING, "the trim failed", t)
        }
    }

    private fun end(
        level: Level,
        reason: String,
        cause: Throwable? = null,
    ) {
        ended = true
        schedule.stop()
        val message = "otherlode: native heap trim is off for this JVM, because $reason"
        if (cause == null) log.log(level, message) else log.log(level, message, cause)
    }

    private companion object {
        val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")
        val MANAGEMENT_CLASS = Regex("^(javax/management|java/lang/management|com/sun/management)/")
    }
}
