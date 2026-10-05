package dev.otherlode.benchmark

import dev.otherlode.config.AgentConfig
import dev.otherlode.dependencies.LoadedDependencyCounter
import dev.otherlode.export.ExportScheduler
import dev.otherlode.instrumentation.LoadedClassSweep
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * Times what one flush costs the adopter's JVM, in milliseconds per flush, at two registry sizes:
 * `demo` (the demo corpus) and `all` (the five analyser corpora together). The registry is filled
 * from the real transformer's registrations; see [FlushFixture].
 *
 * One operation is one call of [ExportScheduler.flush], the method the schedule runs. That covers
 * the delta batch (`ProbeRegistry.computeDeltaBatches`, which copies and compares every class's
 * count array), the manifest delta (`computeManifestDeltas`, which walks every class), the endpoint
 * and dependency registries (empty here), the send pool's hand-off of the two sends to its own
 * threads, and the encoding of every payload with `ProtoPayloadCodec` in [EncodingExporter],
 * which drops the bytes instead of posting them. Left out: HTTP and the network, the retry
 * backoff, and the loaded-class sweep, which needs an `Instrumentation` and a classpath of a real
 * application; it is timed on its own by the two `sweep` benchmarks, against the loaded classes of
 * the benchmark's own JVM.
 *
 * - `steadyFlush`: the manifest was delivered, and about 10% of probes changed since the last
 *   delivered delta batch. Each invocation's setup adds a hit to the same tenth, so the share
 *   stays the same however long the run is.
 * - `firstFlush`: a flush that finds nothing delivered: the whole manifest (every probe location,
 *   class locations, call edges, references) and a delta batch with the same tenth of probes
 *   changed. Each invocation gets a fresh registry and scheduler, so none carries over.
 * - `emptyHeartbeat`: everything delivered and nothing changed, so the delta batch is empty.
 * - `sweepCounting` and `sweepForwardPass`: [LoadedClassSweep.run] over the JVM's loaded classes,
 *   with the dependency count on, as it runs every flush once the startup listing is complete,
 *   without and with the forward pass that otherwise runs on every tenth flush.
 *
 * Invocation-level setup keeps the registry rebuild and the hit bump out of the scores. The
 * smallest operations take tens of microseconds, near JMH's per-invocation timing floor, so the
 * `demo` figures are upper bounds.
 *
 * Run only this suite with `./gradlew jmh -Potherlode.benchmark.include=FlushBenchmark`. The first
 * trial of each size weaves its corpora once per JVM. Fork 1, three warmup and five measurement
 * iterations of five seconds each.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
open class FlushBenchmark {
    /** One flush with the manifest delivered and a tenth of the probes changed. */
    @Benchmark
    fun steadyFlush(state: SteadyState): Long {
        state.harness.scheduler.flush()
        return state.harness.exporter.encodedBytes
    }

    /** The first flush after weaving: the full manifest and the first delta batch. */
    @Benchmark
    fun firstFlush(state: FirstState): Long {
        state.harness.scheduler.flush()
        return state.harness.exporter.encodedBytes
    }

    /** A flush with nothing changed, which sends an empty delta batch as a heartbeat. */
    @Benchmark
    fun emptyHeartbeat(state: HeartbeatState): Long {
        state.harness.scheduler.flush()
        return state.harness.exporter.encodedBytes
    }

    /** The sweep's confirmation and dependency count over the loaded classes, without the forward pass. */
    @Benchmark
    fun sweepCounting(state: SweepState) {
        state.sweep.run(runForwardPass = false)
    }

    /** The sweep with the forward pass, which runs on every tenth flush and on the last one. */
    @Benchmark
    fun sweepForwardPass(state: SweepState) {
        state.sweep.run(runForwardPass = true)
    }

    /** A registry, a scheduler over it and the exporter the scheduler writes to. */
    class Harness(
        val populated: PopulatedRegistry,
        includePackages: List<String>,
    ) {
        /** Encodes and drops what the scheduler sends. */
        val exporter = EncodingExporter()

        /** The real scheduler, never started: a benchmark calls [ExportScheduler.flush] itself. */
        val scheduler =
            ExportScheduler(
                AgentConfig.parse("includePackages=" + includePackages.joinToString(";")),
                FLUSH_RESOURCE,
                populated.registry,
                EndpointRegistry(),
                exporter,
            )

        /** Stops the scheduler's send pool. */
        fun close() = scheduler.stop()
    }

    /** The registry size the state is built at: `demo` or `all`. */
    @State(Scope.Benchmark)
    open class SizedState {
        /** `demo` weaves the demo corpus; `all` weaves every corpus. */
        @Param("demo", "all")
        lateinit var size: String

        /**
         * The classes and registrations of [size]. Woven on first use, in the state's own trial
         * setup, and printed once so a run's output names the registry it timed.
         */
        val fixture: FlushFixture by lazy {
            val woven = if (size == "all") FlushFixture.of(*FlushFixture.ALL_CORPORA.toTypedArray()) else FlushFixture.of("demo")
            println()
            println("registry $size: ${woven.classCount} classes, ${woven.probeCount} probes, corpora ${woven.corpora}")
            woven
        }
    }

    /** Manifest delivered; a tenth of the probes change before every flush. */
    @State(Scope.Benchmark)
    open class SteadyState : SizedState() {
        lateinit var harness: Harness

        /** Builds the registry and delivers its manifest, so the measured flushes carry deltas only. */
        @Setup(Level.Trial)
        fun deliver() {
            harness = Harness(fixture.populate(), fixture.includePackages)
            harness.scheduler.flush()
        }

        /** Adds a hit to the changed tenth. Not part of any score. */
        @Setup(Level.Invocation)
        fun change() {
            harness.populated.markChanged()
            harness.exporter.reset()
        }

        /** Stops the send pool. */
        @TearDown(Level.Trial)
        fun stop() = harness.close()
    }

    /** Nothing delivered; every flush starts from a fresh registry holding the same tenth of changed probes. */
    @State(Scope.Benchmark)
    open class FirstState : SizedState() {
        lateinit var harness: Harness

        /** Builds a fresh registry and scheduler. Not part of any score. */
        @Setup(Level.Invocation)
        fun fresh() {
            harness = Harness(fixture.populate(), fixture.includePackages).also { it.populated.markChanged() }
        }

        /** Stops the send pool of the invocation's scheduler. */
        @TearDown(Level.Invocation)
        fun stop() = harness.close()
    }

    /** Everything delivered and nothing changing. */
    @State(Scope.Benchmark)
    open class HeartbeatState : SizedState() {
        lateinit var harness: Harness

        /** Builds the registry and delivers its manifest and first batch. */
        @Setup(Level.Trial)
        fun deliver() {
            harness = Harness(fixture.populate(), fixture.includePackages)
            harness.scheduler.flush()
        }

        /** Stops the send pool. */
        @TearDown(Level.Trial)
        fun stop() = harness.close()
    }

    /** A [LoadedClassSweep] over the benchmark JVM's own loaded classes, with the dependency count on. */
    @State(Scope.Benchmark)
    open class SweepState {
        lateinit var sweep: LoadedClassSweep

        /** Builds the sweep over a `demo` registry, with a completed listing so the counter runs, and prints how many classes it walks. */
        @Setup(Level.Trial)
        fun build() {
            val fixture = FlushFixture.of("demo")
            val registry = fixture.populate().registry
            val config = AgentConfig.parse("includePackages=" + fixture.includePackages.joinToString(";"))
            val dependencies = DependencyRegistry().also { it.markListingComplete() }
            val instrumentation = ByteBuddyAgent.install()
            sweep =
                LoadedClassSweep(
                    instrumentation,
                    registry,
                    config,
                    LoadedDependencyCounter(dependencies, config.includePackages, config.excludePackages),
                )
            sweep.run(runForwardPass = true)
            println()
            println("sweep: ${instrumentation.allLoadedClasses.size} loaded classes")
        }
    }
}
