package dev.otherlode.benchmark

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
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
open class BranchSiteAnalyzerBenchmark {
    @Param("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")
    lateinit var corpus: String

    private lateinit var loaded: BenchmarkCorpus

    @Setup(Level.Trial)
    fun load() {
        loaded = BenchmarkCorpus.load(corpus)
        println()
        println("corpus $corpus:${loaded.classCount} classes, include packages ${loaded.includePackages}")
    }

    @Benchmark
    fun analyzeCorpus(blackhole: Blackhole) {
        loaded.analyzeAll { blackhole.consume(it) }
    }
}
