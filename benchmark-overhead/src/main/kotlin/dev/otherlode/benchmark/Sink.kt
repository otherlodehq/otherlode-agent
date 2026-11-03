package dev.otherlode.benchmark

/** What the no-op OTLP sink received on one path. */
data class SinkCount(
    val requests: Long,
    val bytes: Long,
)

/** The sink's `GET /counts` reply: one `<path> <requests> <bytes>` line per path it has seen. */
class SinkCounts(
    val byPath: Map<String, SinkCount>,
) {
    companion object {
        /** The counts in [text], a `GET /counts` reply. */
        fun parse(text: String): SinkCounts =
            SinkCounts(
                text
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .associate { line ->
                        val fields = line.trim().split(' ')
                        require(fields.size == 3) { "Not a '<path> <requests> <bytes>' line from the sink: '$line'" }
                        val requests = requireNotNull(fields[1].toLongOrNull()) { "Not a request count in the sink's line: '$line'" }
                        val bytes = requireNotNull(fields[2].toLongOrNull()) { "Not a byte count in the sink's line: '$line'" }
                        fields[0] to SinkCount(requests, bytes)
                    },
            )
    }
}

private const val TRACES_PATH = "/v1/traces"
private const val METRICS_PATH = "/v1/metrics"
private const val LOGS_PATH = "/v1/logs"

/**
 * What the sink received between [before] and [after]: requests per signal, and bytes over every
 * path. A path the sink never saw counts zero.
 */
fun sinkMetrics(
    before: SinkCounts,
    after: SinkCounts,
): Map<String, Double> {
    fun requests(path: String) = ((after.byPath[path]?.requests ?: 0) - (before.byPath[path]?.requests ?: 0)).toDouble()
    val bytes = after.byPath.values.sumOf { it.bytes } - before.byPath.values.sumOf { it.bytes }
    return linkedMapOf(
        "sinkTraceExports" to requests(TRACES_PATH),
        "sinkMetricExports" to requests(METRICS_PATH),
        "sinkLogExports" to requests(LOGS_PATH),
        "sinkBytes" to bytes.toDouble(),
    )
}

/** The problem when the sink saw no trace export in the run, or no reading of it was taken. */
fun sinkTraceProblem(metrics: Map<String, Double>): String? {
    val traces = metrics["sinkTraceExports"] ?: return "the OpenTelemetry agent exported no trace: the sink was not read"
    return if (traces > 0) null else "the OpenTelemetry agent exported no trace"
}
