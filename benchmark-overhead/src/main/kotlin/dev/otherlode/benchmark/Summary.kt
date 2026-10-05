package dev.otherlode.benchmark

import java.util.Locale

/** Metric names, in column order. Every run reports a subset of these. */
object MetricNames {
    val K6 =
        listOf(
            "k6ThroughputPerSec",
            "k6Requests",
            "warmupDrift",
            "httpReqAvgMs",
            "httpReqP95Ms",
            "httpReqP99Ms",
            "iterationAvgMs",
            "iterationP95Ms",
            "checksPassRate",
        )
    val JFR =
        listOf(
            "cpuJvmUserAvgPct",
            "cpuJvmUserMaxPct",
            "cpuMachineTotalAvgPct",
            "gcPauseTotalMs",
            "g1GcTotalMs",
            "allocatedMiB",
            "heapUsedMinMiB",
            "heapUsedMaxMiB",
            "peakThreads",
            "rssMaxMiB",
            "metaspaceUsedMaxMiB",
            "networkReadKiBps",
            "networkWriteKiBps",
        )
    val PROCESS =
        listOf(
            "cpuMillisPerRequest",
            "allocatedKiBPerRequest",
            "gcPauseMsPer1kRequests",
            "cpuSeconds",
            "cpuCoresAvg",
            "cpuThrottledPeriods",
            "cpuThrottledMs",
            "startupMs",
            "bootProcessSeconds",
            "vmRssMiB",
            "vmHwmMiB",
            "classSpaceCommittedMiB",
            "classSpaceUsedMiB",
        )
    val COLLECTOR =
        listOf(
            "collectorDeltasAccepted",
            "collectorManifestAccepted",
            "collectorBaselineAccepted",
            "manifestProbes",
            "manifestEndpoints",
            "manifestSkippedClasses",
        )
    val WARMUP = listOf("warmupSecondsUsed", "warmupSteady", "warmupLastSliceThroughputPerSec")
    val ALL = K6 + JFR + PROCESS + COLLECTOR + WARMUP
}

/**
 * One finished run: its metrics, and the classes the agent logged as skipped. The manifests'
 * `manifestSkippedClasses` count is complete; [skippedClasses] names only those the agent logged a
 * failure for, since a class turned away by the type matcher is recorded without a log line.
 */
data class RunRecord(
    val config: Config,
    val variant: Variant,
    val repeat: Int,
    val metrics: Map<String, Double>,
    val skippedClasses: List<String>,
)

/** Median, minimum and maximum of one metric over the repeats of one variant. */
data class Spread(
    val median: Double,
    val min: Double,
    val max: Double,
)

/** The spread of [values], or null when there are none. */
fun spread(values: List<Double>): Spread? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    return Spread(median, sorted.first(), sorted.last())
}

/** [value] as a percentage change from [baseline], or null when the baseline is zero. */
fun percentChange(
    baseline: Double,
    value: Double,
): Double? = if (baseline == 0.0) null else (value - baseline) / baseline * 100.0

private fun num(v: Double): String =
    if (v == Math.rint(v) &&
        Math.abs(v) < 1e15
    ) {
        String.format(Locale.ROOT, "%.1f", v)
    } else {
        String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
    }

private fun fmt(s: Spread) = "${num(s.median)} (${num(s.min)} to ${num(s.max)})"

private fun signed(v: Double): String {
    val text = String.format(Locale.ROOT, "%+.1f%%", v).replace("+-", "-")
    return if (text == "-0.0%" || text == "+0.0%") "0.0%" else text
}

/** The CSV of every run, one row each, empty cells for a metric the run did not produce. */
fun runsCsv(runs: List<RunRecord>): String {
    val header = listOf("config", "variant", "repeat") + MetricNames.ALL + "loggedSkippedClassCount"
    val rows =
        runs.map { r ->
            listOf(r.config.id, r.variant.id, r.repeat.toString()) +
                MetricNames.ALL.map { name -> r.metrics[name]?.let { num(it) } ?: "" } +
                r.skippedClasses.size.toString()
        }
    return (listOf(header) + rows).joinToString("\n") { it.joinToString(",") } + "\n"
}

/**
 * The Markdown summary: one row per metric, each variant's median with its minimum and maximum,
 * and the change of that median against the `none` variant's.
 */
fun summaryMarkdown(
    config: Config,
    runs: List<RunRecord>,
): String {
    val variants = Variant.entries.filter { v -> runs.any { it.variant == v } }
    val byVariant = variants.associateWith { v -> runs.filter { it.variant == v } }
    val sb = StringBuilder()
    sb.append("## Overhead, ${config.id} (includePackages=${config.includePackages})\n\n")
    sb.append("Runs per variant: ").append(variants.joinToString(", ") { "${it.id} ${byVariant.getValue(it).size}" }).append("\n\n")
    val header = StringBuilder("| metric |")
    val rule = StringBuilder("|---|")
    for (v in variants) {
        header.append(" ${v.id} |")
        rule.append("---|")
        if (v != Variant.NONE && Variant.NONE in variants) {
            header.append(" ${v.id} vs none |")
            rule.append("---|")
        }
    }
    sb
        .append(header)
        .append('\n')
        .append(rule)
        .append('\n')
    for (name in MetricNames.ALL - MetricNames.WARMUP.toSet()) {
        val spreads = variants.associateWith { v -> spread(byVariant.getValue(v).mapNotNull { it.metrics[name] }) }
        if (spreads.values.all { it == null }) continue
        val row = StringBuilder("| $name |")
        for (v in variants) {
            val s = spreads[v]
            row.append(" ${s?.let { fmt(it) } ?: "n/a"} |")
            if (v != Variant.NONE && Variant.NONE in variants) {
                val base = spreads[Variant.NONE]
                val change = if (s != null && base != null) percentChange(base.median, s.median) else null
                val inNoise = s != null && base != null && byVariant.getValue(Variant.NONE).size > 1 && s.median in base.min..base.max
                row.append(" ${change?.let { signed(it) + if (inNoise) " *" else "" } ?: "n/a"} |")
            }
        }
        sb.append(row).append('\n')
    }
    if (Variant.NONE in variants && byVariant.getValue(Variant.NONE).size > 1) {
        sb.append("\n\\* The median lies inside `none`'s own minimum to maximum: not told apart from run-to-run noise.\n")
    }
    sb.append(warmupSection(variants, byVariant))
    sb.append("\n### Classes the agent skipped\n\n")
    var any = false
    for (v in variants.filter { it != Variant.NONE }) {
        val counts = byVariant.getValue(v).map { it.metrics["manifestSkippedClasses"]?.toLong() ?: 0L }
        val union = byVariant.getValue(v).flatMap { it.skippedClasses }.distinct()
        sb.append("- ${v.id}: ${counts.joinToString(", ")} per run, from the manifests\n")
        if (counts.any { it > 0 }) any = true
        if (union.isNotEmpty()) {
            sb.append("\n  <details><summary>${v.id}: the ${union.size} the agent logged a failure for</summary>\n\n")
            union.forEach { sb.append("  - `$it`\n") }
            sb.append("\n  </details>\n\n")
        }
    }
    if (!any) sb.append("\nNo class was skipped.\n")
    return sb.toString()
}

/** Warmup length per variant as mean and range, with how many runs ended steady, or an empty string when no run recorded one. */
private fun warmupSection(
    variants: List<Variant>,
    byVariant: Map<Variant, List<RunRecord>>,
): String {
    val sb = StringBuilder()
    for (v in variants) {
        val runs = byVariant.getValue(v)
        val used = runs.mapNotNull { it.metrics["warmupSecondsUsed"] }
        if (used.isEmpty()) continue
        val steady = runs.count { it.metrics["warmupSteady"] == 1.0 }
        sb.append(
            "- ${v.id}: mean ${num(used.average())} s (${num(used.min())} to ${num(used.max())}), steady in $steady of ${runs.size}\n",
        )
    }
    return if (sb.isEmpty()) "" else "\n### Warmup used\n\n$sb"
}
