package dev.otherlode.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Facts read out of PetClinic's log. */
object LogScan {
    private val STARTED = Regex("""Started PetClinicApplication in [\d.]+ seconds \(process running for ([\d.]+)\)""")
    private val SKIPPED = Regex("""otherlode: instrumentation failed for (\S+?),""")

    /** The "process running for" seconds from Spring Boot's started line, or null if it never logged one. */
    fun bootProcessSeconds(log: String): Double? =
        STARTED
            .find(log)
            ?.groupValues
            ?.get(1)
            ?.toDouble()

    fun hasVerifyError(log: String): Boolean = "VerifyError" in log

    /** The classes the agent logged an instrumentation failure for, once each, in log order. */
    fun skippedClasses(log: String): List<String> =
        SKIPPED
            .findAll(log)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
}

/** `VmRSS` and `VmHWM` from `/proc/<pid>/status`, in bytes. */
data class ProcStatus(
    val rssBytes: Long,
    val hwmBytes: Long,
) {
    companion object {
        fun parse(status: String): ProcStatus {
            fun kb(key: String) =
                Regex("""^$key:\s+(\d+)\s+kB""", RegexOption.MULTILINE)
                    .find(status)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?.times(1024)
                    ?: error("No $key in /proc status")
            return ProcStatus(kb("VmRSS"), kb("VmHWM"))
        }
    }
}

/**
 * The CPU time a cgroup v2 container has used, from its own `/sys/fs/cgroup/cpu.stat`. Unlike JFR's
 * `jdk.CPULoad`, whose share is taken against a machine-wide base, two readings of this give the
 * exact CPU seconds the container spent between them.
 */
data class CgroupCpu(
    val usageMicros: Long,
    val throttledPeriods: Long,
    val throttledMicros: Long,
) {
    companion object {
        fun parse(cpuStat: String): CgroupCpu {
            fun field(name: String) =
                Regex("""^$name\s+(\d+)""", RegexOption.MULTILINE)
                    .find(cpuStat)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            return CgroupCpu(
                field("usage_usec") ?: error("No usage_usec in cpu.stat"),
                field("nr_throttled") ?: 0,
                field("throttled_usec") ?: 0,
            )
        }
    }
}

/**
 * What the agent's manifests said during one run, summed over the collector's log lines for it:
 * the collector's log sink writes one JSON line per manifest with the size of each list.
 */
data class ManifestCounts(
    val manifests: Int,
    val probes: Long,
    val endpoints: Long,
    val skippedClasses: Long,
    val disabledEndpointModules: Long,
) {
    companion object {
        private val mapper = ObjectMapper()

        fun parse(collectorLog: String): ManifestCounts {
            val lines =
                collectorLog
                    .lineSequence()
                    .filter { "received probe manifest" in it }
                    .mapNotNull { runCatching { mapper.readTree(it) }.getOrNull() }
                    .toList()

            fun sum(field: String) = lines.sumOf { it.path(field).asLong(0) }
            return ManifestCounts(lines.size, sum("probes"), sum("endpoints"), sum("skipped_classes"), sum("disabled_endpoint_modules"))
        }
    }
}

private val AGENT_ERROR = Regex("""^(?:SEVERE:|.*\bERROR\b.*?)\s*otherlode:""")

/**
 * The agent's own ERROR lines in PetClinic's log. Before Spring Boot sets up logging,
 * `java.util.logging` prints one as `SEVERE: otherlode: ...`; after, Spring routes it through its
 * own logging, which prints the level and the logger first (`ERROR ExportScheduler - otherlode: ...`).
 */
fun agentErrors(log: String): List<String> = log.lineSequence().filter { AGENT_ERROR.containsMatchIn(it) }.toList()

/** Class space committed and used, in bytes, from `jcmd VM.metaspace scale=1`. */
data class ClassSpace(
    val committedBytes: Long,
    val usedBytes: Long,
) {
    companion object {
        private val CLASS_LINE =
            Regex(
                """^\s+Class:\s+\d+ chunks,\s+\d+ bytes capacity,\s*(\d+) bytes \(.*?\) committed,\s*(\d+) bytes \(.*?\) used""",
                RegexOption.MULTILINE,
            )

        fun parse(report: String): ClassSpace? =
            CLASS_LINE.find(report)?.let { ClassSpace(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
    }
}

/** k6's `--summary-export` JSON, reduced to the metrics the harness records. */
class K6Summary(
    val metrics: Map<String, Double>,
    val checksPassRate: Double,
) {
    companion object {
        private val mapper = ObjectMapper()

        fun parse(json: String): K6Summary {
            val m = mapper.readTree(json).path("metrics")

            fun value(
                metric: String,
                key: String,
            ): Double? =
                m
                    .path(metric)
                    .path(key)
                    .takeIf(JsonNode::isNumber)
                    ?.asDouble()
            val out = linkedMapOf<String, Double>()

            fun put(
                name: String,
                v: Double?,
            ) {
                if (v != null) out[name] = v
            }
            put("k6ThroughputPerSec", value("http_reqs", "rate"))
            put("k6Requests", value("http_reqs", "count"))
            put("k6FirstThirdRequests", value("http_reqs{third:first}", "count"))
            put("k6LastThirdRequests", value("http_reqs{third:last}", "count"))
            put("httpReqAvgMs", value("http_req_duration", "avg"))
            put("httpReqP95Ms", value("http_req_duration", "p(95)"))
            put("httpReqP99Ms", value("http_req_duration", "p(99)"))
            put("iterationAvgMs", value("iteration_duration", "avg"))
            put("iterationP95Ms", value("iteration_duration", "p(95)"))
            val checks = value("checks", "value") ?: error("k6 summary has no checks metric: the script must check every response")
            out["checksPassRate"] = checks
            return K6Summary(out, checks)
        }
    }
}
