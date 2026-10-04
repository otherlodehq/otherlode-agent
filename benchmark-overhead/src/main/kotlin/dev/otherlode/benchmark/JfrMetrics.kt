package dev.otherlode.benchmark

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path

/** Reads the harness's metrics out of a measurement recording. */
object JfrMetrics {
    private const val MIB = 1024.0 * 1024.0

    /** Metrics from the `.jfr` at [file]. A metric whose event never fired is left out. */
    fun read(file: Path): Map<String, Double> {
        val cpuUser = mutableListOf<Double>()
        val cpuMachine = mutableListOf<Double>()
        val gcPause = mutableListOf<Double>()
        val g1 = mutableListOf<Double>()
        val heap = mutableListOf<Double>()
        val peakThreads = mutableListOf<Double>()
        val rss = mutableListOf<Double>()
        val metaspace = mutableListOf<Double>()
        val allocation = mutableListOf<AllocationSample>()
        val networkRead = mutableMapOf<java.time.Instant, Long>()
        val networkWrite = mutableMapOf<java.time.Instant, Long>()

        fun millis(e: RecordedEvent) = e.duration.toNanos() / 1_000_000.0
        RecordingFile(file).use { rf ->
            while (rf.hasMoreEvents()) {
                val e = rf.readEvent()
                when (e.eventType.name) {
                    "jdk.CPULoad" -> {
                        cpuUser += e.getFloat("jvmUser") * 100.0
                        cpuMachine += e.getFloat("machineTotal") * 100.0
                    }

                    "jdk.GCPhasePause" -> {
                        gcPause += millis(e)
                    }

                    "jdk.G1GarbageCollection" -> {
                        g1 += millis(e)
                    }

                    "jdk.GCHeapSummary" -> {
                        heap += e.getLong("heapUsed") / MIB
                    }

                    "jdk.JavaThreadStatistics" -> {
                        peakThreads += e.getLong("peakCount").toDouble()
                    }

                    "jdk.ResidentSetSize" -> {
                        rss += e.getLong("size") / MIB
                    }

                    "jdk.MetaspaceSummary" -> {
                        metaspace += e.getLong("metaspace.used") / MIB
                    }

                    "jdk.NetworkUtilization" -> {
                        // One event per interface per period: summed per period, so a period's total covers every interface.
                        networkRead.merge(e.startTime, e.getLong("readRate"), Long::plus)
                        networkWrite.merge(e.startTime, e.getLong("writeRate"), Long::plus)
                    }

                    "jdk.ThreadAllocationStatistics" -> {
                        val thread = e.getThread("thread") ?: continue
                        allocation += AllocationSample(thread.javaThreadId, e.startTime, e.getLong("allocated"))
                    }
                }
            }
        }

        val out = linkedMapOf<String, Double>()
        if (cpuUser.isNotEmpty()) {
            out["cpuJvmUserAvgPct"] = cpuUser.average()
            out["cpuJvmUserMaxPct"] = cpuUser.max()
            out["cpuMachineTotalAvgPct"] = cpuMachine.average()
        }
        if (gcPause.isNotEmpty()) out["gcPauseTotalMs"] = gcPause.sum()
        if (g1.isNotEmpty()) out["g1GcTotalMs"] = g1.sum()
        if (allocation.isNotEmpty()) out["allocatedMiB"] = allocatedInWindow(allocation) / MIB
        if (heap.isNotEmpty()) {
            out["heapUsedMinMiB"] = heap.min()
            out["heapUsedMaxMiB"] = heap.max()
        }
        if (peakThreads.isNotEmpty()) out["peakThreads"] = peakThreads.max()
        if (rss.isNotEmpty()) out["rssMaxMiB"] = rss.max()
        if (metaspace.isNotEmpty()) out["metaspaceUsedMaxMiB"] = metaspace.max()
        // The rates are in bits per second.
        if (networkRead.isNotEmpty()) out["networkReadKiBps"] = networkRead.values.average() / 8 / 1024
        if (networkWrite.isNotEmpty()) out["networkWriteKiBps"] = networkWrite.values.average() / 8 / 1024
        return out
    }
}
