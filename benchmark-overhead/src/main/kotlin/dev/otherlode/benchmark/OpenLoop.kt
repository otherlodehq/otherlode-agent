package dev.otherlode.benchmark

import java.util.Locale

/** How k6 drives PetClinic in the measured window. */
enum class Load(
    val id: String,
) {
    /** A fixed number of virtual users, each starting an iteration as the last finishes. */
    CLOSED("closed"),

    /** Iterations start at a fixed rate whatever PetClinic does, so latency is not queueing. */
    OPEN("open"),
    ;

    companion object {
        /** The load shape named [id], `closed` or `open`. */
        fun parse(id: String): Load = entries.firstOrNull { it.id == id } ?: error("Unknown -Pload '$id', expected closed or open")
    }
}

/** The most virtual users k6 may start for an open-loop window, unless twice the pre-allocated virtual users is more. */
const val OPEN_MAX_VUS = 200

/** How long, in seconds, an iteration may take before the pre-allocated virtual users run out: one is held per iteration started in it. */
const val OPEN_STALL_SECONDS = 1.0

/** The open loop pre-allocates at least this many times the closed loop's virtual users, so its first iterations do not wait for a VU to start. */
const val OPEN_PREALLOCATION_FACTOR = 4

/**
 * The open-loop window: the `constant-arrival-rate` scenario `k6/overhead.js` builds from [env].
 * The rate is whole iterations a minute, the unit that expresses a fractional rate per second exactly.
 */
data class OpenLoopLoad(
    val iterationsPerMinute: Int,
    val durationSeconds: Int,
    val preAllocatedVus: Int,
    val maxVus: Int,
) {
    /** The environment variables `k6/overhead.js` reads the scenario from. */
    fun env(): Map<String, String> =
        linkedMapOf(
            "OPEN_RATE_PER_MIN" to iterationsPerMinute.toString(),
            "OPEN_DURATION_S" to durationSeconds.toString(),
            "OPEN_PRE_ALLOCATED_VUS" to preAllocatedVus.toString(),
            "OPEN_MAX_VUS" to maxVus.toString(),
        )

    companion object {
        /**
         * The scenario for [iterationsPerSecond] over [durationSeconds]. It pre-allocates the larger of
         * [CpuSplit.USERS_PER_CORE] a core of [petclinicCores] times [OPEN_PREALLOCATION_FACTOR] and the
         * iterations that start in [OPEN_STALL_SECONDS] at the rate, and allows the larger of [OPEN_MAX_VUS]
         * and twice that.
         */
        fun of(
            iterationsPerSecond: Double,
            durationSeconds: Int,
            petclinicCores: Int,
        ): OpenLoopLoad {
            val rate = perMinute(iterationsPerSecond)
            require(rate >= 1) { "An open-loop rate of $iterationsPerSecond iterations/s rounds to no iteration a minute" }
            val preAllocated =
                maxOf(
                    CpuSplit.USERS_PER_CORE * petclinicCores * OPEN_PREALLOCATION_FACTOR,
                    Math.ceil(rate / 60.0 * OPEN_STALL_SECONDS).toInt(),
                )
            return OpenLoopLoad(rate, durationSeconds, preAllocated, maxOf(OPEN_MAX_VUS, 2 * preAllocated))
        }
    }
}

/** [iterationsPerSecond] as the whole iterations a minute k6 runs. */
fun perMinute(iterationsPerSecond: Double): Int = Math.round(iterationsPerSecond * 60.0).toInt()

// k6's default system tags without `url`, which would start a metric series per id in a path.
private const val K6_SYSTEM_TAGS =
    "proto,subproto,status,method,name,group,check,error,error_code,tls_version,scenario,service,expected_response"

/**
 * The arguments of `k6`. A closed run passes [virtualUsers] and [seconds]; an open run ([open] set)
 * passes neither, since `-u` and `--duration` on the command line replace the scenarios the script declares.
 */
fun k6Arguments(
    seconds: Int,
    virtualUsers: Int,
    open: OpenLoopLoad?,
): List<String> =
    buildList {
        add("run")
        if (open == null) {
            add("-u")
            add(virtualUsers.toString())
        }
        add("--system-tags")
        add(K6_SYSTEM_TAGS)
        if (open == null) {
            add("--duration")
            add("${seconds}s")
        }
        add("--summary-export")
        add("/tmp/k6.json")
        add("--summary-trend-stats")
        add("avg,med,p(95),p(99),max")
        add("/app/overhead.js")
    }

/** The rate an open-loop matrix runs at, and how the harness came by it. */
data class OpenRate(
    val perSecond: Double,
    val source: Source,
    val measuredPerSecond: Double?,
    val fraction: Double?,
) {
    /** The whole iterations a minute the window runs, as k6 is given them. */
    val iterationsPerMinute: Int get() = perMinute(perSecond)

    /** How the rate was obtained. */
    enum class Source(
        val id: String,
    ) {
        GIVEN("given"),
        CALIBRATED("calibrated"),
    }

    /** The keys the run's `metadata.properties` records the rate under. */
    fun properties(): Map<String, String> =
        linkedMapOf<String, String>().also {
            it["openIterationRate"] = perSecond.toString()
            it["openIterationsPerMinute"] = iterationsPerMinute.toString()
            it["openIterationRateSource"] = source.id
            measuredPerSecond?.let { m -> it["openIterationRateMeasured"] = m.toString() }
            fraction?.let { f -> it["openRateFraction"] = f.toString() }
        }

    companion object {
        /** A rate given with `-PiterationRate`, in iterations per second. */
        fun given(perSecond: Double): OpenRate {
            require(perSecond > 0) { "The open-loop rate must be above zero" }
            return OpenRate(perSecond, Source.GIVEN, null, null)
        }

        /** [fraction] of the [measuredPerSecond] the closed loop reached at the end of a steady warmup. */
        fun calibrated(
            measuredPerSecond: Double,
            fraction: Double,
        ): OpenRate {
            require(measuredPerSecond > 0) { "The calibration measured no iterations: the closed loop completed none in its last slice" }
            require(fraction > 0 && fraction <= 1) { "The open-loop rate fraction must be above 0 and at most 1" }
            return OpenRate(measuredPerSecond * fraction, Source.CALIBRATED, measuredPerSecond, fraction)
        }
    }
}

/** A Markdown section giving [rate] and how it was obtained, or an empty string when the run was not open-loop. */
fun openRateSection(rate: OpenRate?): String {
    if (rate == null) return ""
    val how =
        when (rate.source) {
            OpenRate.Source.GIVEN -> {
                "given with -PiterationRate"
            }

            OpenRate.Source.CALIBRATED -> {
                "calibrated: ${num(rate.fraction!!)} of the ${num(rate.measuredPerSecond!!)} iterations/s the closed loop reached on `none`"
            }
        }
    return "\n### Open loop rate\n\nArrival rate ${num(rate.perSecond)} iterations/s, run as ${rate.iterationsPerMinute} a minute, $how.\n"
}

/** PetClinic must average under this share of its pinned cores in an open loop, or its requests queued. */
const val OPEN_MAX_UTILISATION = 0.8

/** Why the window's load was not a valid test of PetClinic under [load], or null when it was. */
fun loadProblem(
    load: Load,
    metrics: Map<String, Double>,
    pinnedCores: Int,
): String? =
    when (load) {
        Load.CLOSED -> {
            metrics["cpuCoresAvg"]?.let { saturationProblem(it, pinnedCores) }
        }

        Load.OPEN -> {
            droppedIterationsProblem(metrics["k6DroppedIterations"] ?: 0.0)
                ?: metrics["cpuCoresAvg"]?.let { headroomProblem(it, pinnedCores) }
        }
    }

/**
 * Why PetClinic had no headroom in an open loop, or null when it had: its container averaged
 * [OPEN_MAX_UTILISATION] or more of [pinnedCores], so its requests queued and latency measured the queue.
 */
fun headroomProblem(
    cpuCoresAvg: Double,
    pinnedCores: Int,
): String? {
    if (cpuCoresAvg < OPEN_MAX_UTILISATION * pinnedCores) return null
    val share =
        "%.2f of its %d pinned core(s), at or above %.0f%%".format(
            Locale.ROOT,
            cpuCoresAvg,
            pinnedCores,
            OPEN_MAX_UTILISATION * 100,
        )
    return "PetClinic averaged $share, so its requests queued at the open loop's rate; lower -PopenRateFraction or -PiterationRate"
}

/** The problem when k6 dropped an iteration because it had no free virtual user at the arrival rate, or null when it dropped none. */
fun droppedIterationsProblem(dropped: Double): String? =
    if (dropped > 0) "k6 dropped ${dropped.toLong()} iteration(s): no virtual user was free to start them at the arrival rate" else null
