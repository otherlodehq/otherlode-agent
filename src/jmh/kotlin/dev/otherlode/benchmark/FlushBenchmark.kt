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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
open class FlushBenchmark {
    @Benchmark
    fun steadyFlush(state: SteadyState): Long {
        state.harness.scheduler.flush()
        return state.harness.exporter.encodedBytes
    }

    @Benchmark
    fun firstFlush(state: FirstState): Long {
        state.harness.scheduler.flush()
        return state.harness.exporter.encodedBytes
    }

    @Benchmark
    fun emptyHeartbeat(state: HeartbeatState): Long {
        state.harness.scheduler.flush()
        return state.harness.exporter.encodedBytes
    }

    @Benchmark
    fun sweepCounting(state: SweepState) {
        state.sweep.run(runForwardPass = false)
    }

    @Benchmark
    fun sweepForwardPass(state: SweepState) {
        state.sweep.run(runForwardPass = true)
    }

    class Harness(
        val populated: PopulatedRegistry,
        includePackages: List<String>,
    ) {
        val exporter = EncodingExporter()

        val scheduler =
            ExportScheduler(
                AgentConfig.parse("includePackages=" + includePackages.joinToString(";")),
                FLUSH_RESOURCE,
                populated.registry,
                EndpointRegistry(),
                exporter,
            )

        fun close() = scheduler.stop()
    }

    @State(Scope.Benchmark)
    open class SizedState {
        @Param("demo", "all")
        lateinit var size: String

        val fixture: FlushFixture by lazy {
            val woven = if (size == "all") FlushFixture.of(*FlushFixture.ALL_CORPORA.toTypedArray()) else FlushFixture.of("demo")
            println()
            println("registry $size: ${woven.classCount} classes, ${woven.probeCount} probes, corpora ${woven.corpora}")
            woven
        }
    }

    @State(Scope.Benchmark)
    open class SteadyState : SizedState() {
        lateinit var harness: Harness

        @Setup(Level.Trial)
        fun deliver() {
            harness = Harness(fixture.populate(), fixture.includePackages)
            harness.scheduler.flush()
        }

        @Setup(Level.Invocation)
        fun change() {
            harness.populated.markChanged()
            harness.exporter.reset()
        }

        @TearDown(Level.Trial)
        fun stop() = harness.close()
    }

    @State(Scope.Benchmark)
    open class FirstState : SizedState() {
        lateinit var harness: Harness

        @Setup(Level.Invocation)
        fun fresh() {
            harness = Harness(fixture.populate(), fixture.includePackages).also { it.populated.markChanged() }
        }

        @TearDown(Level.Invocation)
        fun stop() = harness.close()
    }

    @State(Scope.Benchmark)
    open class HeartbeatState : SizedState() {
        lateinit var harness: Harness

        @Setup(Level.Trial)
        fun deliver() {
            harness = Harness(fixture.populate(), fixture.includePackages)
            harness.scheduler.flush()
        }

        @TearDown(Level.Trial)
        fun stop() = harness.close()
    }

    @State(Scope.Benchmark)
    open class SweepState {
        lateinit var sweep: LoadedClassSweep

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
                    EndpointRegistry(),
                    config,
                    LoadedDependencyCounter(dependencies, config.includePackages, config.excludePackages),
                )
            sweep.run(runForwardPass = true)
            println()
            println("sweep: ${instrumentation.allLoadedClasses.size} loaded classes")
        }
    }
}
