package dev.otherlode.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** What one Gradle invocation was asked to measure, read from the system properties the build sets. */
data class BenchSettings(
    val config: Config,
    val repeats: Int,
    val warmupSeconds: Int,
    val warmupSliceSeconds: Int,
    val warmupMinSeconds: Int,
    val warmupSteadyDrift: Double,
    val windowSeconds: Int,
    val agentJar: Path,
    val collectorDir: Path,
    val resultsDir: Path,
    val projectDir: Path,
    val agentRepoDir: Path,
    val startupOnly: Boolean = false,
    val variants: List<Variant> = DEFAULT_VARIANTS,
    val otelAgentJar: Path? = null,
    val load: Load = Load.CLOSED,
    val iterationRate: Double? = null,
    val openRateFraction: Double = DEFAULT_OPEN_RATE_FRACTION,
) {
    init {
        require(Variant.OTEL !in variants || otelAgentJar != null) { "-PotelAgentJar is required when -Pvariants lists otel" }
        require(!(startupOnly && load == Load.OPEN)) { "-Pload=open has no startup-only mode: drop -PstartupOnly or -Pload" }
        require(iterationRate == null || load == Load.OPEN) { "-PiterationRate sets the open loop's rate and needs -Pload=open" }
        require(iterationRate == null || iterationRate > 0) { "-PiterationRate must be above zero" }
        require(iterationRate == null || perMinute(iterationRate) >= 1) { "-PiterationRate rounds to no iteration a minute" }
        require(openRateFraction > 0 && openRateFraction <= 1) { "-PopenRateFraction must be above 0 and at most 1" }
    }

    /** Where this config's results land: a startup-only run and an open-loop run each keep their own directory. */
    val configDir: Path
        get() =
            resultsDir.resolve(
                when {
                    startupOnly -> "${config.id}-startup"
                    load == Load.OPEN -> "${config.id}-open"
                    else -> config.id
                },
            )

    companion object {
        /** The share of `none`'s steady closed-loop iteration rate the open loop runs at unless `-PopenRateFraction` says otherwise. */
        const val DEFAULT_OPEN_RATE_FRACTION = 0.25

        fun fromSystemProperties(): BenchSettings {
            fun prop(name: String) =
                System.getProperty(name) ?: error("System property $name is not set; run through the benchmark-overhead Gradle build")
            return BenchSettings(
                config = Config.parse(prop("bench.config")),
                repeats = prop("bench.repeats").toInt().also { require(it >= 1) { "-Prepeats must be at least 1" } },
                warmupSeconds = prop("bench.warmupSeconds").toInt(),
                warmupSliceSeconds =
                    prop("bench.warmupSliceSeconds").toInt().also { require(it >= 1) { "-PwarmupSliceSeconds must be at least 1" } },
                warmupMinSeconds = prop("bench.warmupMinSeconds").toInt(),
                warmupSteadyDrift =
                    prop("bench.warmupSteadyDrift").toDouble().also { require(it >= 0) { "-PwarmupSteadyDrift must not be negative" } },
                windowSeconds = prop("bench.windowSeconds").toInt().also { require(it >= 1) { "-PwindowSeconds must be at least 1" } },
                agentJar =
                    Paths.get(prop("bench.agentJar")).also {
                        require(Files.isRegularFile(it)) { "-PagentJar names no file: $it" }
                    },
                collectorDir =
                    Paths.get(prop("bench.collectorDir")).also {
                        require(Files.isRegularFile(it.resolve("Dockerfile"))) { "-PcollectorDir has no Dockerfile: $it" }
                    },
                resultsDir = Paths.get(prop("bench.resultsDir")),
                projectDir = Paths.get(prop("bench.projectDir")),
                agentRepoDir = Paths.get(prop("bench.agentRepoDir")),
                startupOnly = prop("bench.startupOnly").toBooleanStrict(),
                variants = parseVariants(prop("bench.variants")),
                otelAgentJar =
                    System.getProperty("bench.otelAgentJar")?.let { Paths.get(it) }?.also {
                        require(Files.isRegularFile(it)) { "-PotelAgentJar names no file: $it" }
                    },
                load = Load.parse(prop("bench.load")),
                iterationRate = System.getProperty("bench.iterationRate")?.toDouble(),
                openRateFraction = System.getProperty("bench.openRateFraction")?.toDouble() ?: DEFAULT_OPEN_RATE_FRACTION,
            )
        }
    }
}
