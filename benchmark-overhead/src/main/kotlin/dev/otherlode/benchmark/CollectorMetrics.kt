package dev.otherlode.benchmark

/** The collector's ingest counters, read from its Prometheus text exposition. */
class CollectorMetrics private constructor(
    private val series: List<Series>,
) {
    private data class Series(
        val name: String,
        val labels: Map<String, String>,
        val value: Long,
    )

    /** Payloads of kind [payload] (`deltas`, `manifest`, `static_baseline`) the collector accepted. */
    fun accepted(payload: String): Long = series.filter { it.name == ACCEPTED && it.labels["payload"] == payload }.sumOf { it.value }

    /** Every payload the collector turned away, whatever the payload kind or reason. */
    fun rejectedTotal(): Long = series.filter { it.name == REJECTED }.sumOf { it.value }

    companion object {
        private const val ACCEPTED = "otherlode_collector_ingest_accepted_total"
        private const val REJECTED = "otherlode_collector_ingest_rejected_total"
        private val LINE = Regex("""^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)})?\s+(\S+)\s*$""")
        private val LABEL = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""")

        /** Parses an exposition. Comment and unparseable lines are ignored. */
        fun parse(text: String): CollectorMetrics {
            val parsed =
                text.lineSequence().filterNot { it.startsWith("#") || it.isBlank() }.mapNotNull { line ->
                    val m = LINE.matchEntire(line.trim()) ?: return@mapNotNull null
                    val labels = LABEL.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                    val value = m.groupValues[3].toDoubleOrNull()?.toLong() ?: return@mapNotNull null
                    Series(m.groupValues[1], labels, value)
                }
            return CollectorMetrics(parsed.toList())
        }
    }
}

/**
 * What is wrong with an agent run, judged from the collector's counters before and after it.
 * Empty when the collector took deltas and a manifest, took the static baseline if [expectBaseline],
 * and rejected nothing.
 */
fun collectorProblems(
    before: CollectorMetrics,
    after: CollectorMetrics,
    expectBaseline: Boolean,
): List<String> {
    val problems = mutableListOf<String>()
    val kinds = listOf("deltas", "manifest") + if (expectBaseline) listOf("static_baseline") else emptyList()
    for (kind in kinds) {
        if (after.accepted(kind) - before.accepted(kind) <= 0) problems += "the collector accepted no $kind payload during the run"
    }
    val rejected = after.rejectedTotal() - before.rejectedTotal()
    if (rejected > 0) problems += "the collector rejected $rejected payload(s) during the run"
    return problems
}
