package dev.otherlode.benchmark

import com.github.dockerjava.api.command.CreateContainerCmd
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/** A container type Kotlin can chain on. */
class Box(
    image: String,
) : GenericContainer<Box>(image)

/** A run broke one of the validity rules. The build fails with this. */
class InvalidRunException(
    message: String,
) : RuntimeException(message)

/**
 * Does not wait at all. The harness polls readiness itself, every 100 ms, because Testcontainers'
 * stock HTTP wait shares a rate limiter that allows one request a second, which would put a one
 * second grain on the startup time.
 */
private class NoWait : AbstractWaitStrategy() {
    override fun waitUntilReady() = Unit
}

/**
 * The macro benchmark: PetClinic against Postgres, driven by k6, with and without the agent.
 *
 * Derived in shape from OpenTelemetry's benchmark-overhead (Copyright The OpenTelemetry Authors,
 * Apache-2.0, modified): the container layout, the JFR settings, the warmup with a throwaway
 * recording and the closed-loop k6 run come from there.
 */
class Harness(
    private val settings: BenchSettings,
) {
    private val network: Network = Network.newNetwork()
    private val dockerClient get() = DockerClientFactory.lazyClient()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    private val petclinicImage: String by lazy {
        println("Building the PetClinic image (cached after the first build)...")
        ImageFromDockerfile("otherlode-bench-petclinic", false)
            .withFileFromPath("Dockerfile", settings.projectDir.resolve("Dockerfile.petclinic"))
            .get()
    }
    private val collectorImage: String by lazy {
        println("Building the collector image from ${settings.collectorDir}...")
        ImageFromDockerfile("otherlode-bench-collector", false)
            .withFileFromPath(".", settings.collectorDir)
            .withDockerfilePath("Dockerfile")
            .get()
    }

    private var javaVersion: String = "unknown"
    private val failures = mutableListOf<String>()

    /** Runs the whole matrix, writes the results, and throws if any run was invalid. */
    fun runAll() {
        // Each invocation starts from an empty results directory, so no file from an earlier one is read as this one's.
        settings.configDir.toFile().deleteRecursively()
        Files.createDirectories(settings.configDir)
        val runs = mutableListOf<RunRecord>()
        val collector = startCollector()
        try {
            outer@ for (repeat in 1..settings.repeats) {
                for (variant in rotatedVariants(repeat - 1)) {
                    println("=== ${settings.config.id} ${variant.id} repeat $repeat of ${settings.repeats} ===")
                    try {
                        runs += runOne(collector, variant, repeat)
                    } catch (e: Exception) {
                        failures += "${variant.id} repeat $repeat: ${e.message ?: e::class.simpleName}"
                        break@outer
                    }
                }
            }
        } finally {
            writeResults(runs, collector)
            collector.stop()
            network.close()
        }
        if (failures.isNotEmpty()) {
            throw InvalidRunException("The overhead run is invalid:\n" + failures.joinToString("\n") { " - $it" })
        }
    }

    private fun startCollector(): Box =
        Box(collectorImage)
            .withNetwork(network)
            .withNetworkAliases("collector")
            .withExposedPorts(COLLECTOR_PORT)
            .withEnv("OTHERLODE_COLLECTOR_INSECURE_NO_AUTH", "1")
            .withEnv("OTHERLODE_COLLECTOR_RATE_LIMIT_RPS", "0")
            .waitingFor(Wait.forHttp("/healthz").forPort(COLLECTOR_PORT))
            .also { it.start() }

    private fun collectorMetrics(collector: Box): CollectorMetrics {
        val url = "http://${collector.host}:${collector.getMappedPort(COLLECTOR_PORT)}/metrics"
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET $url answered ${res.statusCode()}" }
        return CollectorMetrics.parse(res.body())
    }

    private fun runOne(
        collector: Box,
        variant: Variant,
        repeat: Int,
    ): RunRecord {
        val dir = settings.configDir.resolve("runs").resolve("${variant.id}-r$repeat")
        Files.createDirectories(dir)
        val metricsBefore = collectorMetrics(collector)
        val collectorLogStart = collector.logs.length

        val postgres =
            PostgreSQLContainer("postgres:16.3")
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withUsername(DB_USER)
                .withPassword(DB_PASS)
                .withDatabaseName("petclinic")
        val petclinic = petclinicContainer(variant)
        val metrics = linkedMapOf<String, Double>()
        var log = ""
        try {
            postgres.start()

            val t0 = System.nanoTime()
            petclinic.start()
            awaitHealthy(petclinic, t0)
            metrics["startupMs"] = (System.nanoTime() - t0) / 1_000_000.0
            if (javaVersion == "unknown") javaVersion = exec(petclinic, "java", "-version").trim()

            warmup(petclinic)

            run(petclinic, "JFR.start", "settings=/app/overhead.jfc", "name=measure")
            val cpuBefore = CgroupCpu.parse(exec(petclinic, "cat", CPU_STAT))
            val windowStart = System.nanoTime()
            metrics += measuredWindow(dir)
            val elapsedSeconds = (System.nanoTime() - windowStart) / 1e9
            val cpuAfter = CgroupCpu.parse(exec(petclinic, "cat", CPU_STAT))
            // Stopped here, so the recording covers the window and not the shutdown, which only the agent variants pay for in full.
            run(petclinic, "JFR.stop", "name=measure", "filename=$JFR_PATH")
            requireRunning(petclinic, "after the measured window")
            metrics += cpuMetrics(cpuBefore, cpuAfter, elapsedSeconds)

            val metaspace = jcmd(petclinic, "VM.metaspace", "scale=1")
            val classSpace = ClassSpace.parse(metaspace) ?: throw InvalidRunException("VM.metaspace output had no class space line")
            metrics["classSpaceCommittedMiB"] = classSpace.committedBytes / MIB
            metrics["classSpaceUsedMiB"] = classSpace.usedBytes / MIB
            val proc = ProcStatus.parse(exec(petclinic, "cat", "/proc/1/status"))
            metrics["vmRssMiB"] = proc.rssBytes / MIB
            metrics["vmHwmMiB"] = proc.hwmBytes / MIB
            petclinic.copyFileFromContainer(JFR_PATH, dir.resolve("measure.jfr").toString())

            // A graceful exit, so the agent's shutdown flush delivers its last manifest before the collector's log is read.
            exec(petclinic, "sh", "-c", "kill 1")
            awaitExit(petclinic)
            log = petclinic.logs
        } catch (e: Exception) {
            runCatching { log = petclinic.logs }
            Files.writeString(dir.resolve("petclinic.log"), log)
            throw if (e is InvalidRunException) e else InvalidRunException("${e::class.simpleName}: ${e.message}")
        } finally {
            runCatching { petclinic.stop() }
            runCatching { postgres.stop() }
        }
        Files.writeString(dir.resolve("petclinic.log"), log)
        val collectorLog = collector.logs.let { if (it.length >= collectorLogStart) it.substring(collectorLogStart) else it }
        Files.writeString(dir.resolve("collector.log"), collectorLog)

        val problems = mutableListOf<String>()
        if (LogScan.hasVerifyError(log)) problems += "PetClinic's log contains VerifyError"
        agentErrors(log).takeIf { it.isNotEmpty() }?.let { problems += "the agent logged errors: ${it.joinToString(" | ")}" }
        LogScan.bootProcessSeconds(log)?.let { metrics["bootProcessSeconds"] = it }
            ?: problems.add("PetClinic's log has no Started PetClinicApplication line")
        val metricsAfter = collectorMetrics(collector)
        if (variant != Variant.NONE) {
            problems += collectorProblems(metricsBefore, metricsAfter, expectBaseline = variant == Variant.AGENT_BASELINE)
            val manifests = ManifestCounts.parse(collectorLog)
            metrics["manifestProbes"] = manifests.probes.toDouble()
            metrics["manifestEndpoints"] = manifests.endpoints.toDouble()
            metrics["manifestSkippedClasses"] = manifests.skippedClasses.toDouble()
            if (manifests.probes <= 0) problems += "the agent's manifests carried no probe: nothing was instrumented"
            if (manifests.endpoints <= 0) problems += "the agent's manifests carried no endpoint"
            if (manifests.disabledEndpointModules >
                0
            ) {
                problems += "the agent disabled ${manifests.disabledEndpointModules} endpoint module(s)"
            }
        }
        metrics["collectorDeltasAccepted"] = (metricsAfter.accepted("deltas") - metricsBefore.accepted("deltas")).toDouble()
        metrics["collectorManifestAccepted"] = (metricsAfter.accepted("manifest") - metricsBefore.accepted("manifest")).toDouble()
        metrics["collectorBaselineAccepted"] =
            (metricsAfter.accepted("static_baseline") - metricsBefore.accepted("static_baseline")).toDouble()
        metrics += JfrMetrics.read(dir.resolve("measure.jfr"))
        metrics += perRequest(metrics)

        val checks = metrics["checksPassRate"] ?: 0.0
        if (checks < 1.0) problems += "k6 checks pass rate was $checks, below 1.0"
        warmthProblem(metrics)?.let { problems += it }
        if (problems.isNotEmpty()) throw InvalidRunException(problems.joinToString("; "))

        val skipped = if (variant == Variant.NONE) emptyList() else LogScan.skippedClasses(log)
        return RunRecord(settings.config, variant, repeat, metrics, skipped)
    }

    private fun petclinicContainer(variant: Variant): Box {
        val options = agentOptions(settings.config, variant)
        val command =
            buildList {
                add("java")
                add("-Xms1g")
                add("-Xmx1g")
                add("-XX:+UseG1GC")
                if (options != null) add("-javaagent:/app/otherlode-agent.jar=$options")
                add("-jar")
                add("/app/petclinic.jar")
            }
        return Box(petclinicImage)
            .withNetwork(network)
            .withNetworkAliases("petclinic")
            .withExposedPorts(PETCLINIC_PORT)
            .withEnv("SPRING_PROFILES_ACTIVE", "postgres,spring-data-jpa")
            .withEnv("POSTGRES_URL", "jdbc:postgresql://postgres:5432/petclinic")
            .withEnv("POSTGRES_USER", DB_USER)
            .withEnv("POSTGRES_PASS", DB_PASS)
            .withCopyFileToContainer(MountableFile.forClasspathResource("overhead.jfc"), "/app/overhead.jfc")
            .also {
                // Every variant gets the jar, so copying it into the container costs the startup time of none as much as the others.
                it.withCopyFileToContainer(MountableFile.forHostPath(settings.agentJar), "/app/otherlode-agent.jar")
            }.withCreateContainerCmdModifier { cmd: CreateContainerCmd ->
                cmd.hostConfig!!
                    .withNanoCPUs(2_000_000_000L)
                    .withMemory(2L * 1024 * 1024 * 1024)
                    .withMemorySwap(2L * 1024 * 1024 * 1024)
            }.withCommand(*command.toTypedArray())
            .waitingFor(NoWait())
    }

    private fun awaitHealthy(
        petclinic: Box,
        t0: Long,
    ) {
        val url = URI("http://${petclinic.host}:${petclinic.getMappedPort(PETCLINIC_PORT)}/petclinic/actuator/health")
        val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(2)).build()
        val deadline = t0 + Duration.ofMinutes(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (!petclinic.isRunning) throw InvalidRunException("PetClinic exited during startup")
            val code = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull()
            if (code == 200) return
            Thread.sleep(100)
        }
        throw InvalidRunException("PetClinic did not report healthy within 5 minutes")
    }

    /**
     * Warms the JIT with the same load as the measured window. A throwaway recording runs through
     * it: leaving the recording out of the warmup makes the measured one pay for JFR's own first
     * use (class loading and JIT of its event code) inside the window, and the variants then
     * disagree for reasons unrelated to the agent.
     */
    private fun warmup(petclinic: Box) {
        if (settings.warmupSeconds <= 0) return
        println("Warming up for ${settings.warmupSeconds} s...")
        run(petclinic, "JFR.start", "settings=/app/overhead.jfc", "name=warmup", "filename=/tmp/warmup.jfr")
        runK6(settings.warmupSeconds, null, windowMillis = 0)
        run(petclinic, "JFR.stop", "name=warmup")
    }

    private fun measuredWindow(dir: Path): Map<String, Double> {
        println("Measuring for ${settings.windowSeconds} s...")
        val summary = dir.resolve("k6.json")
        runK6(settings.windowSeconds, summary, windowMillis = settings.windowSeconds * 1000L)
        return K6Summary.parse(Files.readString(summary)).metrics
    }

    private fun runK6(
        seconds: Int,
        summaryOut: Path?,
        windowMillis: Long,
    ) {
        val k6 =
            Box(K6_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("k6")
                .withCopyFileToContainer(MountableFile.forHostPath(settings.projectDir.resolve("k6/overhead.js")), "/app/overhead.js")
                .withCreateContainerCmdModifier { it.withUser("root") }
                .withEnv("WINDOW_MS", windowMillis.toString())
                .withCommand(
                    "run",
                    "-u",
                    "5",
                    "--system-tags",
                    K6_SYSTEM_TAGS,
                    "--duration",
                    "${seconds}s",
                    "--summary-export",
                    "/tmp/k6.json",
                    "--summary-trend-stats",
                    "avg,med,p(95),p(99),max",
                    "/app/overhead.js",
                ).withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(seconds + 300L)))
        try {
            k6.start()
            if (summaryOut != null) {
                k6.copyFileFromContainer("/tmp/k6.json", summaryOut.toString())
                Files.writeString(summaryOut.resolveSibling("k6.log"), k6.logs)
            }
        } catch (e: Exception) {
            if (summaryOut != null) runCatching { Files.writeString(summaryOut.resolveSibling("k6.log"), k6.logs) }
            throw InvalidRunException("k6 failed: ${e.message}")
        } finally {
            runCatching { k6.stop() }
        }
    }

    private fun exec(
        container: Box,
        vararg command: String,
    ): String {
        val r = container.execInContainer(*command)
        if (r.exitCode != 0) throw InvalidRunException("`${command.joinToString(" ")}` exited ${r.exitCode}: ${r.stderr} ${r.stdout}")
        return r.stdout + r.stderr
    }

    private fun jcmd(
        container: Box,
        vararg args: String,
    ): String = exec(container, "jcmd", "1", *args)

    private fun run(
        container: Box,
        vararg args: String,
    ) {
        jcmd(container, *args)
    }

    private fun requireRunning(
        container: Box,
        when_: String,
    ) {
        if (!container.isRunning) throw InvalidRunException("PetClinic had exited $when_")
    }

    private fun awaitExit(container: Box) {
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (container.isRunning) {
            if (System.nanoTime() > deadline) throw InvalidRunException("PetClinic did not exit within 120 s of kill 1")
            Thread.sleep(200)
        }
    }

    private fun writeResults(
        runs: List<RunRecord>,
        collector: Box,
    ) {
        val dir = settings.configDir
        Files.writeString(dir.resolve("runs.csv"), runsCsv(runs))
        val summary = StringBuilder(summaryMarkdown(settings.config, runs))
        if (failures.isNotEmpty()) {
            summary.append("\n### Invalid run\n\n").append(failures.joinToString("\n") { "- $it" }).append('\n')
        }
        Files.writeString(dir.resolve("summary.md"), summary.toString())
        runCatching { Files.writeString(dir.resolve("metadata.properties"), metadata(collector)) }
            .onFailure { println("Could not write metadata: $it") }
    }

    private fun metadata(collector: Box): String {
        val info = dockerClient.infoCmd().exec()

        fun digest(image: String): String =
            runCatching {
                val inspected = dockerClient.inspectImageCmd(image).exec()
                inspected.repoDigests
                    ?.joinToString(" ")
                    .orEmpty()
                    .ifEmpty { inspected.id ?: "unknown" }
            }.getOrDefault("unknown")

        fun git(
            dir: Path,
            vararg args: String,
        ): String =
            runCatching {
                val p = ProcessBuilder("git", "-C", dir.toString(), *args).redirectErrorStream(true).start()
                p.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
                    .also { p.waitFor() }
            }.getOrDefault("unknown")

        val sha =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(settings.agentJar)).joinToString("") { "%02x".format(it) }
        val props =
            linkedMapOf(
                "runner.os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
                "runner.cpus" to Runtime.getRuntime().availableProcessors().toString(),
                "docker.cpus" to info.ncpu.toString(),
                "docker.memoryBytes" to info.memTotal.toString(),
                "petclinic.java.version" to javaVersion.lines().joinToString(" | "),
                "agent.jar" to settings.agentJar.fileName.toString(),
                "agent.jar.sha256" to sha,
                "agent.repo.commit" to git(settings.agentRepoDir, "rev-parse", "HEAD"),
                // The jar may predate the commit: CI builds it fresh, a local run may not.
                "agent.repo.dirty" to git(settings.agentRepoDir, "status", "--porcelain").isNotEmpty().toString(),
                "agent.jar.modified" to Files.getLastModifiedTime(settings.agentJar).toString(),
                "petclinic.commit" to PETCLINIC_SHA,
                "collector.dir" to settings.collectorDir.toString(),
                "collector.commit" to git(settings.collectorDir, "rev-parse", "HEAD"),
                "image.petclinic.base" to digest("eclipse-temurin:21-jdk"),
                "image.petclinic" to digest(petclinicImage),
                "image.collector" to digest(collectorImage),
                "image.postgres" to digest("postgres:16.3"),
                "image.k6" to digest(K6_IMAGE),
                "config" to settings.config.id,
                "includePackages" to settings.config.includePackages,
                "repeats" to settings.repeats.toString(),
                "warmupSeconds" to settings.warmupSeconds.toString(),
                "windowSeconds" to settings.windowSeconds.toString(),
            )
        return props.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
    }

    private companion object {
        const val PETCLINIC_PORT = 9966
        const val COLLECTOR_PORT = 4319
        const val DB_USER = "petclinic"
        const val DB_PASS = "petclinic"
        const val K6_IMAGE = "grafana/k6:2.3.0"
        const val JFR_PATH = "/tmp/measure.jfr"
        const val CPU_STAT = "/sys/fs/cgroup/cpu.stat"

        // k6's default system tags without `url`, which would start a metric series per id in a path.
        const val K6_SYSTEM_TAGS =
            "proto,subproto,status,method,name,group,check,error,error_code,tls_version,scenario,service,expected_response"
        const val PETCLINIC_SHA = "afc8fc1d3b8ec8de69414482b3bd70a1da78cb95"
        const val MIB = 1024.0 * 1024.0
    }
}
