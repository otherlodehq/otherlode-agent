package dev.otherlode.benchmark

import java.nio.file.Path
import java.util.Locale

/**
 * How far the median over a variant's repeats of `warmupDrift`, the window's last-third request
 * count over its first-third count, may lie from 1 in either direction before the variant's windows
 * are judged unsteady: rising means the JIT was still compiling when measurement began, falling means
 * something degraded through it. A single run beyond it is named in the summary, and with three or
 * more repeats it does not fail its variant.
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
        .format(Locale.ROOT, cpuCoresAvg, pinnedCores, MIN_SATURATION * 100)
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
 * Why the run's window counts cannot be judged, or null when they can: k6 gave no request count for
 * a third, or the first third served nothing. Records the ratio of last to first in [metrics] as
 * `warmupDrift`; whether that drift is acceptable is judged over a variant's repeats by
 * [variantWarmthProblem].
 */
fun warmthProblem(metrics: MutableMap<String, Double>): String? {
    val first = metrics["k6FirstThirdRequests"] ?: return "k6 reported no request count for the window's first third"
    val last = metrics["k6LastThirdRequests"] ?: return "k6 reported no request count for the window's last third"
    if (first <= 0) return "the window's first third served no request"
    metrics["warmupDrift"] = last / first
    return null
}

/**
 * Why a variant's windows do not look steady, or null when they do: the median `warmupDrift` of its
 * [runs] lies outside 1 plus or minus [MAX_WINDOW_DRIFT], or a run recorded no `warmupDrift`, so the
 * median would describe fewer runs than the variant had. A single run drifts with runner noise in
 * either direction; a median that drifts means the variant's windows did. The advice for a rise
 * depends on `warmupSteady`: warmups that hit their cap need a longer cap, ones judged steady need a
 * stricter rule, and the advice names both when the variant had both.
 */
fun variantWarmthProblem(
    variant: Variant,
    runs: List<RunRecord>,
): String? {
    val drifts = runs.mapNotNull { it.metrics["warmupDrift"] }
    if (drifts.size < runs.size) return "${variant.id}: ${runs.size - drifts.size} of ${runs.size} runs recorded no warmupDrift"
    val median = spread(drifts)?.median ?: return null
    return when {
        median > 1 + MAX_WINDOW_DRIFT -> {
            val more =
                "${variant.id}: the median window's last third served %.1f%% more requests than its first".format(
                    Locale.ROOT,
                    (median - 1) * 100,
                )
            val steady = runs.count { it.metrics["warmupSteady"] == 1.0 }
            val capped = runs.size - steady
            val advice =
                buildList {
                    if (capped > 0) add("$capped of ${runs.size} warmups reached their cap, so raise -PwarmupSeconds")
                    if (steady > 0) {
                        add("$steady of ${runs.size} warmups ended steady, so lower -PwarmupSteadyDrift or lengthen -PwarmupSliceSeconds")
                    }
                }
            "$more; ${advice.joinToString("; ")}"
        }

        median < 1 - MAX_WINDOW_DRIFT -> {
            "${variant.id}: the median window's last third served %.1f%% fewer requests than its first, so the service degraded through the window"
                .format(Locale.ROOT, (1 - median) * 100)
        }

        else -> {
            null
        }
    }
}

/** [variantWarmthProblem] for each variant that has runs in [runs], in [Variant] order. */
fun variantWarmthProblems(runs: List<RunRecord>): List<String> =
    Variant.entries.mapNotNull { v -> runs.filter { it.variant == v }.takeIf { it.isNotEmpty() }?.let { variantWarmthProblem(v, it) } }

/**
 * The `git` arguments that list the agent repository's changes, leaving out [collectorDir] when it
 * lies inside [agentRepoDir], as the workflow's collector checkout does.
 */
fun dirtyCheck(
    agentRepoDir: Path,
    collectorDir: Path,
): List<String> {
    val repo = agentRepoDir.toAbsolutePath().normalize()
    val collector = collectorDir.toAbsolutePath().normalize()
    val base = listOf("status", "--porcelain")
    if (collector == repo || !collector.startsWith(repo)) return base
    return base + listOf("--", ".", ":(exclude,literal)${repo.relativize(collector).joinToString("/")}")
}

/** A run's [drift] as a signed percentage when it lies outside 1 plus or minus [MAX_WINDOW_DRIFT], or null when it does not. */
fun driftBeyondBound(drift: Double): String? =
    if (drift > 1 + MAX_WINDOW_DRIFT || drift < 1 - MAX_WINDOW_DRIFT) String.format(Locale.ROOT, "%+.1f%%", (drift - 1) * 100) else null
