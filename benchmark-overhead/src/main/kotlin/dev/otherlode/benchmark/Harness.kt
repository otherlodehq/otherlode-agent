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
open class InvalidRunException(
    message: String,
) : RuntimeException(message)

/**
 * What the warmup measured: the metrics every run records, the last slice's iterations per second
 * (null when k6 gave no count or there was no warmup), and whether the rule ended it steady.
 */
private class WarmupOutcome(
    val metrics: Map<String, Double>,
    val lastSliceIterationsPerSec: Double?,
    val steady: Boolean,
)

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

    /**
     * Built with the `docker` command line, not Testcontainers. The collector's Dockerfile uses
     * `FROM --platform=$BUILDPLATFORM`, and only BuildKit sets that variable. Testcontainers
     * builds through the classic builder, which leaves it empty and rejects the build.
     */
    private val collectorImageBuild =
        lazy {
            println("Building the collector image from ${settings.collectorDir}...")
            val image = "otherlode-bench-collector:latest"
            val build = ProcessBuilder("docker", "build", "-t", image, settings.collectorDir.toString()).inheritIO()
            build.environment()["DOCKER_BUILDKIT"] = "1"
            val exit = build.start().waitFor()
            check(exit == 0) { "docker build of the collector exited with $exit" }
            image
        }
    private val collectorImage: String by collectorImageBuild

    private val cpuSplit: CpuSplit by lazy {
        CpuSplit.of(
            checkNotNull(dockerClient.infoCmd().exec().ncpu) { "Docker reported no CPU count" },
        )
    }
    private var javaVersion: String = "unknown"
    private var listingCost: ClassListingCost? = null
    private var openRate: OpenRate? = null
    private var calibrationSteady: Boolean? = null
    private var sink: Box? = null
    private val failures = mutableListOf<String>()
    private val discarded = mutableListOf<DiscardedAttempt>()

    /** Runs the whole matrix, writes the results, and throws if any run was invalid. */
    fun runAll() {
        // Each invocation starts from an empty results directory, so no file from an earlier one is read as this one's.
        settings.configDir.toFile().deleteRecursively()
        Files.createDirectories(settings.configDir)
        val runs = mutableListOf<RunRecord>()

        fun <T> beforeTheMatrix(
            what: String,
            step: () -> T,
        ): T =
            try {
                step()
            } catch (e: Exception) {
                failures += "$what: ${e.message ?: e::class.simpleName}"
                writeResults(runs)
                throw InvalidRunException("The overhead run is invalid:\n" + failures.joinToString("\n") { " - $it" })
            }
        if (!settings.startupOnly) listingCost = beforeTheMatrix("class listing") { measureClassListing() }
        if (settings.load == Load.OPEN) {
            openRate = beforeTheMatrix("open-loop rate") { settings.iterationRate?.let(OpenRate::given) ?: calibrateOpenRate() }
        }
        val collector = startCollector()
        try {
            if (Variant.OTEL in settings.variants) {
                try {
                    startSink()
                } catch (e: Exception) {
                    failures += "OTLP sink: ${e.message ?: e::class.simpleName}"
                }
            }
            if (settings.startupOnly) {
                // The job's first start is the coldest: nothing has read the image's files or started its JDK yet.
                println("=== ${settings.config.id} warm-up start, not recorded ===")
                try {
                    startupOne(collector, Variant.NONE, 0)
                } catch (e: Exception) {
                    failures += "warm-up start: ${e.message ?: e::class.simpleName}"
                }
            }
            outer@ for (repeat in 1..(if (failures.isEmpty()) settings.repeats else 0)) {
                for (variant in rotatedVariants(repeat - 1, settings.variants)) {
                    println("=== ${settings.config.id} ${variant.id} repeat $repeat of ${settings.repeats} ===")
                    try {
                        runs +=
                            if (settings.startupOnly) {
                                startupOne(collector, variant, repeat)
                            } else {
                                attemptRepeat(variant.id, repeat, discarded) { runOneKeeping(collector, variant, repeat, it) }
                            }
                    } catch (e: Exception) {
                        val detail = e.message ?: e::class.simpleName.orEmpty()
                        val retried = discarded.any { it.variantId == variant.id && it.repeat == repeat }
                        val named = if (retried && !detail.startsWith("attempt ")) "attempt $MAX_ATTEMPTS: $detail" else detail
                        failures += "${variant.id} repeat $repeat: $named"
                        break@outer
                    }
                }
            }
            if (failures.isEmpty() && !settings.startupOnly) failures += variantWarmthProblems(runs, settings.load)
        } finally {
            writeResults(runs)
            collector.stop()
            sink?.let { runCatching { it.stop() } }
            network.close()
        }
        if (failures.isNotEmpty()) {
            throw InvalidRunException("The overhead run is invalid:\n" + failures.joinToString("\n") { " - $it" })
        }
    }

    /**
     * Runs the agent's class listing five times in one JVM in a container from the PetClinic image,
     * on PetClinic's cores, and reads its cost from the program's output.
     */
    private fun measureClassListing(): ClassListingCost {
        println("Measuring the class listing...")
        val container =
            Box(petclinicImage)
                .withCopyFileToContainer(MountableFile.forClasspathResource("ListingCost.java"), "/app/ListingCost.java")
                .withCopyFileToContainer(MountableFile.forHostPath(settings.agentJar), "/app/otherlode-agent.jar")
                .withCreateContainerCmdModifier { it.hostConfig!!.withCpusetCpus(cpuSplit.petclinic) }
                .withCommand(*listingCostCommand(settings.config).toTypedArray())
                .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(5)))
                .waitingFor(NoWait())
        try {
            try {
                container.start()
            } catch (e: Exception) {
                throw InvalidRunException("the class listing program failed: ${container.logs.trim().ifEmpty { e.message }}")
            }
            return ClassListingCost.parse(container.logs)
        } finally {
            runCatching { container.stop() }
        }
    }

    private fun startCollector(): Box =
        Box(collectorImage)
            .withNetwork(network)
            .withNetworkAliases("collector")
            .withExposedPorts(COLLECTOR_PORT)
            .withEnv("OTHERLODE_COLLECTOR_INSECURE_NO_AUTH", "1")
            .withEnv("OTHERLODE_COLLECTOR_RATE_LIMIT_RPS", "0")
            .withCreateContainerCmdModifier { it.hostConfig!!.withCpusetCpus(cpuSplit.support) }
            .waitingFor(Wait.forHttp("/healthz").forPort(COLLECTOR_PORT))
            .also { it.start() }

    private fun postgresContainer() =
        PostgreSQLContainer("postgres:16.3")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withUsername(DB_USER)
            .withPassword(DB_PASS)
            .withDatabaseName("petclinic")
            .withCreateContainerCmdModifier { it.hostConfig!!.withCpusetCpus(cpuSplit.support) }

    /**
     * Starts the no-op OTLP sink once for the matrix: a container from the PetClinic image, which has
     * a JDK, running `NoopOtlpSink.java` on the cores Postgres and the collector share. Returns when
     * its `/counts` answers.
     */
    private fun startSink() {
        println("Starting the OTLP sink...")
        val container =
            Box(petclinicImage)
                .withNetwork(network)
                .withNetworkAliases(OTLP_SINK_HOST)
                .withExposedPorts(OTLP_SINK_PORT)
                .withCopyFileToContainer(MountableFile.forClasspathResource("NoopOtlpSink.java"), "/app/NoopOtlpSink.java")
                .withCreateContainerCmdModifier { it.hostConfig!!.withCpusetCpus(cpuSplit.support) }
                .withCommand("java", "/app/NoopOtlpSink.java", OTLP_SINK_PORT.toString())
                .waitingFor(NoWait())
        sink = container
        container.start()
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (runCatching { sinkCounts() }.isFailure) {
            if (!container.isRunning) throw InvalidRunException("the OTLP sink exited at startup: ${container.logs.trim()}")
            val late = System.nanoTime() > deadline
            if (late) throw InvalidRunException("the OTLP sink did not answer /counts within 60 s: ${container.logs.trim()}")
            Thread.sleep(200)
        }
    }

    private fun sinkCounts(): SinkCounts {
        val running = checkNotNull(sink) { "The OTLP sink is not running" }
        val url = "http://${running.host}:${running.getMappedPort(OTLP_SINK_PORT)}/counts"
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET $url answered ${res.statusCode()}" }
        return SinkCounts.parse(res.body())
    }

    /**
     * The problems with the OpenTelemetry agent's run of [variant], and the sink's deltas since
     * [sinkBefore] added to [metrics]. Empty for any other variant.
     */
    private fun otelProblems(
        variant: Variant,
        sinkBefore: SinkCounts?,
        log: String,
        metrics: MutableMap<String, Double>,
    ): List<String> {
        if (variant != Variant.OTEL) return emptyList()
        metrics += sinkMetrics(checkNotNull(sinkBefore) { "No reading of the sink before the run" }, sinkCounts())
        return otelLogProblems(log) + listOfNotNull(sinkTraceProblem(metrics))
    }

    private fun collectorMetrics(collector: Box): CollectorMetrics {
        val url = "http://${collector.host}:${collector.getMappedPort(COLLECTOR_PORT)}/metrics"
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET $url answered ${res.statusCode()}" }
        return CollectorMetrics.parse(res.body())
    }

    /** [runOne]. When it fails on environmental problems only and another attempt can follow, its run directory moves to `-attemptN`. */
    private fun runOneKeeping(
        collector: Box,
        variant: Variant,
        repeat: Int,
        attempt: Int,
    ): RunRecord =
        try {
            runOne(collector, variant, repeat)
        } catch (e: EnvironmentalRunException) {
            val runs = settings.configDir.resolve("runs")
            val dir = runs.resolve("${variant.id}-r$repeat")
            if (attempt < MAX_ATTEMPTS && Files.exists(dir)) {
                Files.move(dir, runs.resolve("${variant.id}-r$repeat-attempt$attempt"))
            }
            throw e
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
        val sinkBefore = if (variant == Variant.OTEL) sinkCounts() else null

        val postgres = postgresContainer()
        val petclinic = petclinicContainer(variant)
        val metrics = linkedMapOf<String, Double>()
        var log = ""
        try {
            postgres.start()

            val stealBefore = readHostStat(postgres)
            val t0 = System.nanoTime()
            petclinic.start()
            awaitHealthy(petclinic, t0)
            metrics["startupMs"] = (System.nanoTime() - t0) / 1_000_000.0
            metrics += startupCpuAndSteal(petclinic, postgres, stealBefore)
            if (javaVersion == "unknown") javaVersion = exec(petclinic, "java", "-version").trim()

            metrics += warmup(petclinic, dir).metrics

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
            throw failed(e, metrics)
        } finally {
            runCatching { petclinic.stop() }
            runCatching { postgres.stop() }
        }
        return try {
            Files.writeString(dir.resolve("petclinic.log"), log)
            val collectorLog = collector.logs.let { if (it.length >= collectorLogStart) it.substring(collectorLogStart) else it }
            Files.writeString(dir.resolve("collector.log"), collectorLog)

            val problems = mutableListOf<String>()
            if (LogScan.hasVerifyError(log)) problems += "PetClinic's log contains VerifyError"
            if (variant.otherlode) {
                agentErrors(log).takeIf { it.isNotEmpty() }?.let { problems += "the agent logged errors: ${it.joinToString(" | ")}" }
            }
            LogScan.bootProcessSeconds(log)?.let { metrics["bootProcessSeconds"] = it }
                ?: problems.add("PetClinic's log has no Started PetClinicApplication line")
            val metricsAfter = collectorMetrics(collector)
            problems += deliveryProblems(variant, metricsBefore, metricsAfter, collectorLog, metrics)
            problems += otelProblems(variant, sinkBefore, log, metrics)
            metrics["collectorDeltasAccepted"] = (metricsAfter.accepted("deltas") - metricsBefore.accepted("deltas")).toDouble()
            metrics["collectorManifestAccepted"] = (metricsAfter.accepted("manifest") - metricsBefore.accepted("manifest")).toDouble()
            metrics["collectorBaselineAccepted"] =
                (metricsAfter.accepted("static_baseline") - metricsBefore.accepted("static_baseline")).toDouble()
            metrics += JfrMetrics.read(dir.resolve("measure.jfr"))
            metrics += perRequest(metrics)

            val checks = metrics["checksPassRate"] ?: 0.0
            if (checks < 1.0) problems += "k6 checks pass rate was $checks, below 1.0"
            warmthProblem(metrics, settings.load)?.let { problems += it }
            loadProblem(settings.load, metrics, cpuSplit.petclinicCores)?.let { problems += it }
            if (problems.isNotEmpty()) {
                val message = problems.joinToString("; ")
                throw if (isEnvironmental(problems)) EnvironmentalRunException(message) else InvalidRunException(message)
            }

            val skipped = if (variant.otherlode) LogScan.skippedClasses(log) else emptyList()
            RunRecord(settings.config, variant, repeat, metrics, skipped)
        } catch (e: Exception) {
            throw failed(e, metrics)
        }
    }

    /**
     * What the collector saw of [variant]'s run, between [before] and [after], as problems that make
     * the run invalid; the manifest counts go into [metrics]. A variant that does not run the Otherlode agent has none.
     */
    private fun deliveryProblems(
        variant: Variant,
        before: CollectorMetrics,
        after: CollectorMetrics,
        collectorLog: String,
        metrics: MutableMap<String, Double>,
    ): List<String> {
        if (!variant.otherlode) return emptyList()
        val problems = collectorProblems(before, after, expectBaseline = variant == Variant.AGENT_BASELINE).toMutableList()
        val manifests = ManifestCounts.parse(collectorLog)
        metrics["manifestProbes"] = manifests.probes.toDouble()
        metrics["manifestEndpoints"] = manifests.endpoints.toDouble()
        metrics["manifestSkippedClasses"] = manifests.skippedClasses.toDouble()
        if (manifests.probes <= 0) problems += "the agent's manifests carried no probe: nothing was instrumented"
        if (manifests.endpoints <= 0) problems += "the agent's manifests carried no endpoint"
        if (manifests.disabledEndpointModules > 0) problems += "the agent disabled ${manifests.disabledEndpointModules} endpoint module(s)"
        return problems
    }

    /**
     * A startup-only repeat: starts Postgres and PetClinic as [runOne] does, records the startup
     * metrics when PetClinic first answers 200, then stops PetClinic gracefully. There is no warmup
     * and no window.
     */
    private fun startupOne(
        collector: Box,
        variant: Variant,
        repeat: Int,
    ): RunRecord {
        val dir = settings.configDir.resolve("runs").resolve("${variant.id}-r$repeat")
        Files.createDirectories(dir)
        val metricsBefore = collectorMetrics(collector)
        val collectorLogStart = collector.logs.length
        val sinkBefore = if (variant == Variant.OTEL) sinkCounts() else null
        val postgres = postgresContainer()
        val petclinic = petclinicContainer(variant)
        val metrics = linkedMapOf<String, Double>()
        var log = ""
        try {
            postgres.start()
            val stealBefore = readHostStat(postgres)
            val t0 = System.nanoTime()
            petclinic.start()
            awaitHealthy(petclinic, t0)
            metrics["startupMs"] = (System.nanoTime() - t0) / 1_000_000.0
            metrics += startupCpuAndSteal(petclinic, postgres, stealBefore)
            if (javaVersion == "unknown") javaVersion = exec(petclinic, "java", "-version").trim()
            exec(petclinic, "sh", "-c", "kill 1")
            awaitExit(petclinic)
            log = petclinic.logs
        } catch (e: Exception) {
            runCatching { log = petclinic.logs }
            Files.writeString(dir.resolve("petclinic.log"), log)
            throw failed(e, metrics)
        } finally {
            runCatching { petclinic.stop() }
            runCatching { postgres.stop() }
        }
        Files.writeString(dir.resolve("petclinic.log"), log)
        val collectorLog = collector.logs.let { if (it.length >= collectorLogStart) it.substring(collectorLogStart) else it }
        Files.writeString(dir.resolve("collector.log"), collectorLog)
        val problems = mutableListOf<String>()
        if (LogScan.hasVerifyError(log)) problems += "PetClinic's log contains VerifyError"
        if (variant.otherlode) {
            agentErrors(log).takeIf { it.isNotEmpty() }?.let { problems += "the agent logged errors: ${it.joinToString(" | ")}" }
        }
        LogScan.bootProcessSeconds(log)?.let { metrics["bootProcessSeconds"] = it }
            ?: problems.add("PetClinic's log has no Started PetClinicApplication line")
        problems += deliveryProblems(variant, metricsBefore, collectorMetrics(collector), collectorLog, metrics)
        problems += otelProblems(variant, sinkBefore, log, metrics)
        if (problems.isNotEmpty()) throw InvalidRunException(problems.joinToString("; "))
        val skipped = if (variant.otherlode) LogScan.skippedClasses(log) else emptyList()
        return RunRecord(settings.config, variant, repeat, metrics, skipped)
    }

    /**
     * `/proc/stat` as the Docker host's kernel reports it, read through [via], a running container,
     * so a Docker Desktop VM is read as a Linux runner is. Null when it cannot be read.
     */
    private fun readHostStat(via: GenericContainer<*>): String? =
        runCatching { via.execInContainer("cat", "/proc/stat").stdout }.getOrNull()

    /** USER_HZ ticks a second, from `getconf CLK_TCK` on the host, or [ProcStat.DEFAULT_CLOCK_TICK] when that cannot be read. */
    private val clockTick: Int by lazy {
        runCatching {
            val p = ProcessBuilder("getconf", "CLK_TCK").redirectErrorStream(true).start()
            p.inputStream
                .bufferedReader()
                .readText()
                .trim()
                .toInt()
                .also { p.waitFor() }
        }.getOrDefault(ProcStat.DEFAULT_CLOCK_TICK)
    }

    /**
     * The CPU the PetClinic container used since it started, from its cgroup, and the steal time of
     * its pinned cores over the same interval, from two readings of `/proc/stat`. Steal is left out
     * when either reading failed.
     */
    private fun startupCpuAndSteal(
        petclinic: Box,
        via: GenericContainer<*>,
        stealBefore: String?,
    ): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        out["startupCpuMs"] = startupCpuMs(CgroupCpu.parse(exec(petclinic, "cat", CPU_STAT)))
        val stealAfter = readHostStat(via)
        if (stealBefore != null && stealAfter != null) {
            runCatching { ProcStat.stealMs(stealBefore, stealAfter, ProcStat.cores(cpuSplit.petclinic), clockTick) }
                .onSuccess { out["startupStealMs"] = it }
                .onFailure { println("No startup steal time: ${it.message}") }
        }
        return out
    }

    /**
     * [e] as the run's failure, with the window's drift added when the window ran and its drift lay
     * past [MAX_WINDOW_DRIFT].
     */
    private fun failed(
        e: Exception,
        metrics: MutableMap<String, Double>,
    ): InvalidRunException {
        val message = if (e is InvalidRunException) e.message else "${e::class.simpleName}: ${e.message}"
        val full = listOfNotNull(message, driftNote(metrics)).joinToString("; ")
        return if (e is EnvironmentalRunException) EnvironmentalRunException(full) else InvalidRunException(full)
    }

    /** What a failed run's window drift adds to its failure, when the window ran and its drift lay past [MAX_WINDOW_DRIFT]. */
    private fun driftNote(metrics: MutableMap<String, Double>): String? {
        if (warmthProblem(metrics, settings.load) != null) return null
        return metrics["warmupDrift"]?.let(::driftBeyondBound)?.let { "its window's last third also drifted $it from its first" }
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
                addAll(otelJvmOptions(variant))
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
                // Every variant gets each jar, so copying it into the container costs the startup time of none as much as the others.
                it.withCopyFileToContainer(MountableFile.forHostPath(settings.agentJar), "/app/otherlode-agent.jar")
                settings.otelAgentJar?.let { jar -> it.withCopyFileToContainer(MountableFile.forHostPath(jar), OTEL_AGENT_PATH) }
            }.withCreateContainerCmdModifier { cmd: CreateContainerCmd ->
                cmd.hostConfig!!
                    .withCpusetCpus(cpuSplit.petclinic)
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
     * Warms the JIT with the same load as the measured window, in slices of `warmupSliceSeconds`,
     * until throughput is steady (see [warmupVerdict]) or `warmupSeconds` is spent. A throwaway
     * recording runs through it: leaving the recording out of the warmup makes the measured one pay
     * for JFR's own first use (class loading and JIT of its event code) inside the window, and the
     * variants then disagree for reasons unrelated to the agent. Reaching the cap is not a failure
     * here; the variant's warmth rule judges its windows once the matrix has run.
     */
    private fun warmup(
        petclinic: Box,
        dir: Path,
    ): WarmupOutcome {
        if (settings.warmupSeconds <= 0) return WarmupOutcome(mapOf("warmupSecondsUsed" to 0.0, "warmupSteady" to 0.0), null, false)
        val slice = settings.warmupSliceSeconds
        println("Warming up in slices of $slice s, at least ${settings.warmupMinSeconds} s and at most ${settings.warmupSeconds} s...")
        run(petclinic, "JFR.start", "settings=/app/overhead.jfc", "name=warmup", "filename=/tmp/warmup.jfr")
        val throughputs = mutableListOf<Double>()
        var lastIterationsPerSec: Double? = null
        var verdict: WarmupVerdict
        do {
            val summary = dir.resolve("warmup-slice-${throughputs.size + 1}.json")
            runK6(slice, summary, windowMillis = 0, open = null)
            val sliceMetrics = K6Summary.parse(Files.readString(summary)).metrics
            val requests = sliceMetrics["k6Requests"] ?: throw InvalidRunException("k6 reported no request count for a warmup slice")
            throughputs += requests / slice
            lastIterationsPerSec = sliceMetrics["k6Iterations"]?.let { it / slice }
            println("  warmup slice ${throughputs.size}: %.1f requests/s".format(java.util.Locale.ROOT, throughputs.last()))
            verdict =
                warmupVerdict(throughputs, slice, settings.warmupMinSeconds, settings.warmupSeconds, settings.warmupSteadyDrift)
        } while (verdict == WarmupVerdict.CONTINUE)
        run(petclinic, "JFR.stop", "name=warmup")
        println("Warmup ended after ${throughputs.size * slice} s: ${if (verdict == WarmupVerdict.STEADY) "steady" else "cap reached"}")
        val metrics =
            linkedMapOf(
                "warmupSecondsUsed" to (throughputs.size * slice).toDouble(),
                "warmupSteady" to if (verdict == WarmupVerdict.STEADY) 1.0 else 0.0,
                "warmupLastSliceThroughputPerSec" to throughputs.last(),
            )
        return WarmupOutcome(metrics, lastIterationsPerSec, verdict == WarmupVerdict.STEADY)
    }

    /**
     * Measures the closed loop's steady iteration rate on a `none` PetClinic, started as a full run
     * starts it and warmed as a full run warms it, and returns [BenchSettings.openRateFraction] of
     * the last warmup slice's rate. A warmup that reached its cap is recorded in the metadata and
     * not refused: the rate only sets a load with headroom.
     */
    private fun calibrateOpenRate(): OpenRate {
        println("Calibrating the open-loop rate on ${Variant.NONE.id}...")
        val dir = settings.configDir.resolve("calibration")
        Files.createDirectories(dir)
        val postgres = postgresContainer()
        val petclinic = petclinicContainer(Variant.NONE)
        try {
            postgres.start()
            val t0 = System.nanoTime()
            petclinic.start()
            awaitHealthy(petclinic, t0)
            val outcome = warmup(petclinic, dir)
            val measured =
                outcome.lastSliceIterationsPerSec
                    ?: throw InvalidRunException("the calibration read no iteration count; it needs -PwarmupSeconds above 0")
            calibrationSteady = outcome.steady
            return OpenRate.calibrated(measured, settings.openRateFraction)
        } finally {
            runCatching { petclinic.stop() }
            runCatching { postgres.stop() }
        }
    }

    private fun measuredWindow(dir: Path): Map<String, Double> {
        val open = openRate?.let { OpenLoopLoad.of(it.perSecond, settings.windowSeconds, cpuSplit.petclinicCores) }
        val shape = open?.let { ", open loop at ${it.iterationsPerMinute} iterations/min" }.orEmpty()
        println("Measuring for ${settings.windowSeconds} s$shape...")
        val summary = dir.resolve("k6.json")
        runK6(settings.windowSeconds, summary, windowMillis = settings.windowSeconds * 1000L, open = open)
        return K6Summary.parse(Files.readString(summary)).metrics
    }

    private fun runK6(
        seconds: Int,
        summaryOut: Path?,
        windowMillis: Long,
        open: OpenLoopLoad?,
    ) {
        val k6 =
            Box(K6_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("k6")
                .withCopyFileToContainer(MountableFile.forHostPath(settings.projectDir.resolve("k6/overhead.js")), "/app/overhead.js")
                .withCreateContainerCmdModifier { it.withUser("root").hostConfig!!.withCpusetCpus(cpuSplit.k6) }
                .withEnv("WINDOW_MS", windowMillis.toString())
                .withCommand(*k6Arguments(seconds, cpuSplit.virtualUsers, open).toTypedArray())
                .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(seconds + 300L)))
        open?.env()?.forEach { (name, value) -> k6.withEnv(name, value) }
        try {
            k6.start()
            if (summaryOut != null) {
                k6.copyFileFromContainer("/tmp/k6.json", summaryOut.toString())
                Files.writeString(summaryOut.resolveSibling(logName(summaryOut)), k6.logs)
            }
        } catch (e: Exception) {
            if (summaryOut != null) runCatching { Files.writeString(summaryOut.resolveSibling(logName(summaryOut)), k6.logs) }
            throw InvalidRunException("k6 failed: ${e.message}")
        } finally {
            runCatching { k6.stop() }
        }
    }

    private fun logName(summary: Path): String = summary.fileName.toString().removeSuffix(".json") + ".log"

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

    private fun writeResults(runs: List<RunRecord>) {
        val dir = settings.configDir
        Files.writeString(dir.resolve("runs.csv"), runsCsv(runs))
        val summary =
            StringBuilder(
                if (settings.startupOnly) {
                    startupSummaryMarkdown(settings.config, runs)
                } else {
                    summaryMarkdown(settings.config, runs, settings.load)
                },
            )
        summary.append(openRateSection(openRate))
        summary.append(retriesSection(discarded))
        summary.append(classListingSection(listingCost) { cpuSplit.petclinic })
        if (failures.isNotEmpty()) {
            summary.append("\n### Why the run is invalid\n\n").append(failures.joinToString("\n") { "- $it" }).append('\n')
        }
        Files.writeString(dir.resolve("summary.md"), summary.toString())
        runCatching { Files.writeString(dir.resolve("metadata.properties"), metadata()) }
            .onFailure { println("Could not write metadata: $it") }
    }

    private fun metadata(): String {
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

        val hostCpuInfo = runCatching { CpuInfo.parse(Files.readString(Path.of("/proc/cpuinfo"))) }.getOrNull()

        fun sha256(file: Path) =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        val props =
            linkedMapOf(
                "runner.os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
                "runner.cpus" to Runtime.getRuntime().availableProcessors().toString(),
                "runner.cpu.model" to (hostCpuInfo?.model ?: "unknown"),
                "runner.cpu.count" to (hostCpuInfo?.count?.toString() ?: "unknown"),
                "docker.cpus" to info.ncpu.toString(),
                "cpuset.petclinic" to cpuSplit.petclinic,
                "cpuset.k6" to cpuSplit.k6,
                "cpuset.postgresAndCollector" to cpuSplit.support,
                "docker.memoryBytes" to info.memTotal.toString(),
                "petclinic.java.version" to javaVersion.lines().joinToString(" | "),
                "agent.jar" to settings.agentJar.fileName.toString(),
                "agent.jar.sha256" to sha256(settings.agentJar),
                "agent.repo.commit" to git(settings.agentRepoDir, "rev-parse", "HEAD"),
                // The jar may predate the commit: CI builds it fresh, a local run may not.
                "agent.repo.dirty" to
                    git(
                        settings.agentRepoDir,
                        *dirtyCheck(settings.agentRepoDir.toRealPath(), settings.collectorDir.toRealPath()).toTypedArray(),
                    ).isNotEmpty()
                        .toString(),
                "agent.jar.modified" to Files.getLastModifiedTime(settings.agentJar).toString(),
                "petclinic.commit" to PETCLINIC_SHA,
                "collector.dir" to settings.collectorDir.toString(),
                "collector.commit" to git(settings.collectorDir, "rev-parse", "HEAD"),
                "image.petclinic.base" to digest("eclipse-temurin:21-jdk"),
                "image.petclinic" to digest(petclinicImage),
                "image.collector" to if (collectorImageBuild.isInitialized()) digest(collectorImage) else "not built",
                "image.postgres" to digest("postgres:16.3"),
                "image.k6" to digest(K6_IMAGE),
                "config" to settings.config.id,
                "variants" to settings.variants.joinToString(",") { it.id },
                "load" to settings.load.id,
                "includePackages" to settings.config.includePackages,
                "repeats" to settings.repeats.toString(),
                "startupOnly" to settings.startupOnly.toString(),
                "warmupSeconds" to settings.warmupSeconds.toString(),
                "warmupSliceSeconds" to settings.warmupSliceSeconds.toString(),
                "warmupMinSeconds" to settings.warmupMinSeconds.toString(),
                "warmupSteadyDrift" to settings.warmupSteadyDrift.toString(),
                "windowSeconds" to settings.windowSeconds.toString(),
            )
        settings.otelAgentJar?.let {
            props["otel.jar"] = it.fileName.toString()
            props["otel.jar.sha256"] = sha256(it)
        }
        openRate?.let { props += it.properties() }
        calibrationSteady?.let { props["openIterationRateCalibrationSteady"] = it.toString() }
        listingCost?.let { props += it.properties() }
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

        const val PETCLINIC_SHA = "afc8fc1d3b8ec8de69414482b3bd70a1da78cb95"
        const val MIB = 1024.0 * 1024.0
    }
}
