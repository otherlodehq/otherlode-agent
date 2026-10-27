package dev.otherlode.benchmark

import java.util.Locale

/** The metrics a startup-only run records, in column order. */
val STARTUP_METRICS = listOf("startupMs", "bootProcessSeconds", "startupCpuMs", "startupStealMs")

/** The CPU the container used from its start to now, in milliseconds, from one reading of its new cgroup. */
fun startupCpuMs(cpu: CgroupCpu): Double = cpu.usageMicros / 1000.0

/** The CPU model and logical CPU count out of `/proc/cpuinfo`. */
data class CpuInfo(
    val model: String,
    val count: Int,
) {
    companion object {
        /** The first `model name` and the number of `processor` entries, or null when the text has no model name. */
        fun parse(cpuinfo: String): CpuInfo? {
            val model =
                Regex("""^model name\s*:\s*(.+?)\s*$""", RegexOption.MULTILINE)
                    .find(cpuinfo)
                    ?.groupValues
                    ?.get(1)
                    ?: return null
            val count = Regex("""^processor\s*:""", RegexOption.MULTILINE).findAll(cpuinfo).count()
            return CpuInfo(model, count)
        }
    }
}

/** Per-core counters out of `/proc/stat`, in USER_HZ ticks. */
object ProcStat {
    /** The cores in a Docker cpuset string such as `0`, `0-1` or `0,2-3`. */
    fun cores(cpuset: String): List<Int> =
        cpuset.split(',').flatMap { part ->
            val bounds = part.trim().split('-').map { it.toInt() }
            if (bounds.size == 1) listOf(bounds[0]) else (bounds[0]..bounds[1]).toList()
        }

    /** The steal ticks (field 8 of a `cpuN` line) summed over [cores]. */
    fun stealTicks(
        stat: String,
        cores: List<Int>,
    ): Long {
        val lines = stat.lineSequence().map { it.trim().split(Regex("""\s+""")) }.associateBy { it[0] }
        return cores.sumOf { core ->
            val fields = lines["cpu$core"] ?: error("No cpu$core line in /proc/stat")
            fields.getOrNull(8)?.toLong() ?: error("The cpu$core line in /proc/stat has no steal field")
        }
    }

    /** The steal time between two readings of `/proc/stat`, in milliseconds, for [cores], with [clockTick] ticks a second. */
    fun stealMs(
        before: String,
        after: String,
        cores: List<Int>,
        clockTick: Int = DEFAULT_CLOCK_TICK,
    ): Double = (stealTicks(after, cores) - stealTicks(before, cores)) * 1000.0 / clockTick

    /** The clock tick Linux reports `/proc/stat` in unless `getconf CLK_TCK` says otherwise. */
    const val DEFAULT_CLOCK_TICK = 100
}

/** The 25th, 50th and 75th percentiles of one metric over the repeats of one variant, by linear interpolation. */
data class Quartiles(
    val q1: Double,
    val median: Double,
    val q3: Double,
)

/** The quartiles of [values], or null when there are none. */
fun quartiles(values: List<Double>): Quartiles? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()

    fun at(p: Double): Double {
        val position = p * (sorted.size - 1)
        val low = Math.floor(position).toInt()
        val high = Math.ceil(position).toInt()
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
    return Quartiles(at(0.25), at(0.5), at(0.75))
}

private fun cell(q: Quartiles) = "${num(q.median)} (${num(q.q1)} to ${num(q.q3)})"

/**
 * The summary of a startup-only run: per startup metric, each variant's median with its
 * interquartile range, and each agent variant's median against `none`'s as a change and a ratio.
 */
fun startupSummaryMarkdown(
    config: Config,
    runs: List<RunRecord>,
): String {
    val variants = Variant.entries.filter { v -> runs.any { it.variant == v } }
    val byVariant = variants.associateWith { v -> runs.filter { it.variant == v } }
    val sb = StringBuilder()
    sb.append("## Startup, ${config.id} (includePackages=${config.includePackages})\n\n")
    sb.append("Runs per variant: ").append(variants.joinToString(", ") { "${it.id} ${byVariant.getValue(it).size}" }).append("\n\n")
    sb.append("Each cell is the median with the interquartile range.\n\n")
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
    for (name in STARTUP_METRICS) {
        val spreads = variants.associateWith { v -> quartiles(byVariant.getValue(v).mapNotNull { it.metrics[name] }) }
        if (spreads.values.all { it == null }) continue
        val row = StringBuilder("| $name |")
        for (v in variants) {
            val q = spreads[v]
            row.append(" ${q?.let(::cell) ?: "n/a"} |")
            if (v != Variant.NONE && Variant.NONE in variants) {
                val base = spreads[Variant.NONE]
                val change = if (q != null && base != null) percentChange(base.median, q.median) else null
                row.append(
                    " ${change?.let { "${signed(it)} (${String.format(Locale.ROOT, "%.2f", q!!.median / base!!.median)}x)" } ?: "n/a"} |",
                )
            }
        }
        sb.append(row).append('\n')
    }
    return sb.toString()
}
