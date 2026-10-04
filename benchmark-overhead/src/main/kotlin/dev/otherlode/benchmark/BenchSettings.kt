package dev.otherlode.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** What one Gradle invocation was asked to measure, read from the system properties the build sets. */
data class BenchSettings(
    val config: Config,
    val repeats: Int,
    val warmupSeconds: Int,
    val windowSeconds: Int,
    val agentJar: Path,
    val collectorDir: Path,
    val resultsDir: Path,
    val projectDir: Path,
    val agentRepoDir: Path,
) {
    /** Where this config's results land. */
    val configDir: Path get() = resultsDir.resolve(config.id)

    companion object {
        fun fromSystemProperties(): BenchSettings {
            fun prop(name: String) =
                System.getProperty(name) ?: error("System property $name is not set; run through the benchmark-overhead Gradle build")
            return BenchSettings(
                config = Config.parse(prop("bench.config")),
                repeats = prop("bench.repeats").toInt().also { require(it >= 1) { "-Prepeats must be at least 1" } },
                warmupSeconds = prop("bench.warmupSeconds").toInt(),
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
            )
        }
    }
}
