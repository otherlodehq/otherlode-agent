package dev.otherlode.benchmark

import com.example.hotpath.BranchyFixture
import com.example.hotpath.DefaultArgumentFixture
import com.example.hotpath.EntryOnlyFixture
import dev.otherlode.bootstrap.OtherlodeEndpoints
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * Measures what the woven code costs on an adopter's hot path, in nanoseconds per call. Four shapes:
 * a method that gets only an entry probe, a method with a loop, a `when` and an `if`, a call into a
 * Kotlin function with three optional parameters, and one request's dispatch through
 * `OtherlodeEndpoints`.
 *
 * The first three each run as two copies of one class file in one JVM: `Unwoven` as the compiler
 * wrote it, `Woven` after the agent's real transformer has rewritten the same bytes; see
 * [HotPathWeaver]. The difference is the cost of the probes. Each runs at one thread (`1T`) and at
 * one thread per core (`PerCore`). The per-core run of `entryOnlyWoven` is the contention
 * measurement: every thread increments the same array element.
 *
 * The endpoint shape has no unwoven twin, so its score is absolute. `endpointDispatch` builds the
 * key, looks the entry up and counts the hit, the sequence `HandleMatchAdvice.onEnter` runs after
 * Spring's own accessors. `endpointDispatchPrebuiltKey` leaves the key allocation out, which
 * separates it from the seam's own cost.
 *
 * Fork 1, three warmup and five measurement iterations of five seconds each, as
 * [BranchSiteAnalyzerBenchmark] has. The fork needs `-Djdk.attach.allowAttachSelf=true`, which the
 * build passes.
 *
 * Run only this suite with `./gradlew jmh -Potherlode.benchmark.include=HotPathBenchmark`. The
 * property is a regular expression over benchmark names. A plain `./gradlew jmh` runs this suite
 * and the analyser benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
open class HotPathBenchmark {
    /** The argument every call receives. Not final, so the JIT cannot fold the call away. */
    @JvmField
    var input: Int = 7

    /** The route template of the endpoint shapes, not final for the same reason as [input]. */
    @JvmField
    var pattern: String = HotPathEndpointSeam.PATTERN

    /** The verb of the endpoint shapes. */
    @JvmField
    var verb: String = HotPathEndpointSeam.VERB

    private lateinit var entryOnlyUnwovenShape: HotPathShape
    private lateinit var entryOnlyWovenShape: HotPathShape
    private lateinit var branchyUnwovenShape: HotPathShape
    private lateinit var branchyWovenShape: HotPathShape
    private lateinit var defaultArgumentUnwovenShape: HotPathShape
    private lateinit var defaultArgumentWovenShape: HotPathShape
    private lateinit var prebuiltKey: Any

    /** Weaves each fixture and installs the endpoint seam once per trial, so none of it is in a score. */
    @Setup
    fun prepare() {
        val weaver = HotPathWeaver()
        entryOnlyUnwovenShape = weaver.unwoven(EntryOnlyFixture::class.java)
        entryOnlyWovenShape = weaver.woven(EntryOnlyFixture::class.java)
        branchyUnwovenShape = weaver.unwoven(BranchyFixture::class.java)
        branchyWovenShape = weaver.woven(BranchyFixture::class.java)
        defaultArgumentUnwovenShape = weaver.unwoven(DefaultArgumentFixture::class.java)
        defaultArgumentWovenShape = weaver.woven(DefaultArgumentFixture::class.java)
        prebuiltKey = HotPathEndpointSeam().key
    }

    /** The entryOnlyUnwoven shape at one thread. */
    @Benchmark
    @Threads(1)
    fun entryOnlyUnwoven1T(): Int = entryOnlyUnwovenShape.call(input)

    /** The entryOnlyUnwoven shape at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun entryOnlyUnwovenPerCore(): Int = entryOnlyUnwovenShape.call(input)

    /** The entryOnlyWoven shape at one thread. */
    @Benchmark
    @Threads(1)
    fun entryOnlyWoven1T(): Int = entryOnlyWovenShape.call(input)

    /** The entryOnlyWoven shape at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun entryOnlyWovenPerCore(): Int = entryOnlyWovenShape.call(input)

    /** The branchyUnwoven shape at one thread. */
    @Benchmark
    @Threads(1)
    fun branchyUnwoven1T(): Int = branchyUnwovenShape.call(input)

    /** The branchyUnwoven shape at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun branchyUnwovenPerCore(): Int = branchyUnwovenShape.call(input)

    /** The branchyWoven shape at one thread. */
    @Benchmark
    @Threads(1)
    fun branchyWoven1T(): Int = branchyWovenShape.call(input)

    /** The branchyWoven shape at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun branchyWovenPerCore(): Int = branchyWovenShape.call(input)

    /** The defaultArgumentUnwoven shape at one thread. */
    @Benchmark
    @Threads(1)
    fun defaultArgumentUnwoven1T(): Int = defaultArgumentUnwovenShape.call(input)

    /** The defaultArgumentUnwoven shape at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun defaultArgumentUnwovenPerCore(): Int = defaultArgumentUnwovenShape.call(input)

    /** The defaultArgumentWoven shape at one thread. */
    @Benchmark
    @Threads(1)
    fun defaultArgumentWoven1T(): Int = defaultArgumentWovenShape.call(input)

    /** The defaultArgumentWoven shape at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun defaultArgumentWovenPerCore(): Int = defaultArgumentWovenShape.call(input)

    /** Builds the key, looks the entry up and counts one hit, at one thread. */
    @Benchmark
    @Threads(1)
    fun endpointDispatch1T(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, java.util.List.of(pattern, verb))
        OtherlodeEndpoints.hit(entry)
        return entry
    }

    /** Builds the key, looks the entry up and counts one hit, at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun endpointDispatchPerCore(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, java.util.List.of(pattern, verb))
        OtherlodeEndpoints.hit(entry)
        return entry
    }

    /** Looks the entry up and counts one hit, at one thread. */
    @Benchmark
    @Threads(1)
    fun endpointDispatchPrebuiltKey1T(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, prebuiltKey)
        OtherlodeEndpoints.hit(entry)
        return entry
    }

    /** Looks the entry up and counts one hit, at one thread per core. */
    @Benchmark
    @Threads(Threads.MAX)
    fun endpointDispatchPrebuiltKeyPerCore(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, prebuiltKey)
        OtherlodeEndpoints.hit(entry)
        return entry
    }
}
