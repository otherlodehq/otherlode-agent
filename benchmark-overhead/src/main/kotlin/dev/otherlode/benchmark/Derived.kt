package dev.otherlode.benchmark

/**
 * How far the last third of the measured window may drift from the first, as a fraction of the
 * first's request count, in either direction, before the window is judged unsteady: rising means the
 * JIT was still compiling when measurement began, falling means something degraded through it.
 */
const val MAX_WINDOW_DRIFT = 0.10

/** PetClinic must average at least this share of its pinned cores, or its throughput measured the client or Postgres. */
const val MIN_SATURATION = 0.9

/**
 * Why PetClinic was not saturated, or null when it was: its container averaged under [MIN_SATURATION]
 * of [pinnedCores], so the closed loop was limited by something other than PetClinic.
 */
fun saturationProblem(
    cpuCoresAvg: Double,
    pinnedCores: Int,
): String? {
    val floor = MIN_SATURATION * pinnedCores
    if (cpuCoresAvg >= floor) return null
    return "PetClinic averaged %.2f of its %d pinned core(s), under %.0f%%, so its throughput measured the client or Postgres"
        .format(java.util.Locale.ROOT, cpuCoresAvg, pinnedCores, MIN_SATURATION * 100)
}

/**
 * CPU figures from two readings of the container's cgroup `cpu.stat` taken [elapsedSeconds] apart.
 * When the closed loop saturates the container's pinned cores, `cpuCoresAvg` sits at their count for
 * every variant, and CPU per request is the same measurement as throughput seen from the other side;
 * [saturationProblem] fails a run below [MIN_SATURATION]. `cpuThrottledMs` is zero without a quota
 * and is kept so a harness change that restores one shows.
 */
fun cpuMetrics(
    before: CgroupCpu,
    after: CgroupCpu,
    elapsedSeconds: Double,
): Map<String, Double> {
    val cpuSeconds = (after.usageMicros - before.usageMicros) / 1e6
    return linkedMapOf(
        "cpuSeconds" to cpuSeconds,
        "cpuCoresAvg" to cpuSeconds / elapsedSeconds,
        "cpuThrottledPeriods" to (after.throttledPeriods - before.throttledPeriods).toDouble(),
        "cpuThrottledMs" to (after.throttledMicros - before.throttledMicros) / 1e3,
    )
}

/**
 * Totals divided by the requests k6 completed in the window. A closed loop serves as many requests
 * as the service can, so a variant that serves more also allocates and collects more in total; only
 * the per-request figures compare variants.
 */
fun perRequest(metrics: Map<String, Double>): Map<String, Double> {
    val requests = metrics["k6Requests"]?.takeIf { it > 0 } ?: return emptyMap()
    val out = linkedMapOf<String, Double>()
    metrics["cpuSeconds"]?.let { out["cpuMillisPerRequest"] = it * 1000.0 / requests }
    metrics["allocatedMiB"]?.let { out["allocatedKiBPerRequest"] = it * 1024.0 / requests }
    metrics["gcPauseTotalMs"]?.let { out["gcPauseMsPer1kRequests"] = it * 1000.0 / requests }
    return out
}

/**
 * Why the run's window does not look steady, or null when it does: the last third of the window
 * served more than [MAX_WINDOW_DRIFT] more or fewer requests than the first. Records the ratio of
 * last to first in [metrics] as `warmupDrift`. The advice for a rising window depends on `warmupSteady`: a
 * warmup that hit its cap needs a longer cap, one judged steady needs a stricter rule.
 */
fun warmthProblem(metrics: MutableMap<String, Double>): String? {
    val first = metrics["k6FirstThirdRequests"] ?: return "k6 reported no request count for the window's first third"
    val last = metrics["k6LastThirdRequests"] ?: return "k6 reported no request count for the window's last third"
    if (first <= 0) return "the window's first third served no request"
    val drift = last / first
    metrics["warmupDrift"] = drift
    return when {
        drift > 1 + MAX_WINDOW_DRIFT -> {
            val more = "the window's last third served %.0f%% more requests than its first".format(java.util.Locale.ROOT, (drift - 1) * 100)
            if (metrics["warmupSteady"] == 1.0) {
                "$more although the warmup was judged steady; lower -PwarmupSteadyDrift or lengthen -PwarmupSliceSeconds"
            } else {
                "$more, so the JVM was still warming up when the warmup reached its cap; raise -PwarmupSeconds"
            }
        }

        drift < 1 - MAX_WINDOW_DRIFT -> {
            "the window's last third served %.0f%% fewer requests than its first, so the service degraded through the window"
                .format(java.util.Locale.ROOT, (1 - drift) * 100)
        }

        else -> {
            null
        }
    }
}
