package dev.otherlode.memory

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.instrumentation.BootstrapHolder
import java.lang.System.Logger.Level
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

// No type in this file may name a `java.management` class, or the agent fails to start on a JVM
// without that module.
internal interface NativeHeap {
    fun open()

    fun jvmTrimInterval(): String?

    fun trim(): String
}

internal class NoTrimCommand : Exception(null, null, false, false)

internal interface TrimSchedule {
    fun start(
        initialDelay: Duration,
        period: Duration,
        task: Runnable,
    )

    fun stop()
}

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

    fun start() {
        if (interval.isZero || !osName.startsWith("Linux")) return
        schedule.start(interval, interval, ::run)
    }

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
