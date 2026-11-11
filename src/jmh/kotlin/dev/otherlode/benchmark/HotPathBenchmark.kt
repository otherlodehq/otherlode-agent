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

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
open class HotPathBenchmark {
    // Not final, so the JIT cannot fold the call away.
    @JvmField
    var input: Int = 7

    // Not final, so the JIT cannot fold the key that endpointDispatch builds from pattern and verb.
    @JvmField
    var pattern: String = HotPathEndpointSeam.PATTERN

    @JvmField
    var verb: String = HotPathEndpointSeam.VERB

    private lateinit var entryOnlyUnwovenShape: HotPathShape
    private lateinit var entryOnlyWovenShape: HotPathShape
    private lateinit var branchyUnwovenShape: HotPathShape
    private lateinit var branchyWovenShape: HotPathShape
    private lateinit var defaultArgumentUnwovenShape: HotPathShape
    private lateinit var defaultArgumentWovenShape: HotPathShape
    private lateinit var prebuiltKey: Any

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

    @Benchmark
    @Threads(1)
    fun entryOnlyUnwoven1T(): Int = entryOnlyUnwovenShape.call(input)

    @Benchmark
    @Threads(Threads.MAX)
    fun entryOnlyUnwovenPerCore(): Int = entryOnlyUnwovenShape.call(input)

    @Benchmark
    @Threads(1)
    fun entryOnlyWoven1T(): Int = entryOnlyWovenShape.call(input)

    @Benchmark
    @Threads(Threads.MAX)
    fun entryOnlyWovenPerCore(): Int = entryOnlyWovenShape.call(input)

    @Benchmark
    @Threads(1)
    fun branchyUnwoven1T(): Int = branchyUnwovenShape.call(input)

    @Benchmark
    @Threads(Threads.MAX)
    fun branchyUnwovenPerCore(): Int = branchyUnwovenShape.call(input)

    @Benchmark
    @Threads(1)
    fun branchyWoven1T(): Int = branchyWovenShape.call(input)

    @Benchmark
    @Threads(Threads.MAX)
    fun branchyWovenPerCore(): Int = branchyWovenShape.call(input)

    @Benchmark
    @Threads(1)
    fun defaultArgumentUnwoven1T(): Int = defaultArgumentUnwovenShape.call(input)

    @Benchmark
    @Threads(Threads.MAX)
    fun defaultArgumentUnwovenPerCore(): Int = defaultArgumentUnwovenShape.call(input)

    @Benchmark
    @Threads(1)
    fun defaultArgumentWoven1T(): Int = defaultArgumentWovenShape.call(input)

    @Benchmark
    @Threads(Threads.MAX)
    fun defaultArgumentWovenPerCore(): Int = defaultArgumentWovenShape.call(input)

    @Benchmark
    @Threads(1)
    fun endpointDispatch1T(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, java.util.List.of(pattern, verb))
        OtherlodeEndpoints.hit(entry)
        return entry
    }

    @Benchmark
    @Threads(Threads.MAX)
    fun endpointDispatchPerCore(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, java.util.List.of(pattern, verb))
        OtherlodeEndpoints.hit(entry)
        return entry
    }

    @Benchmark
    @Threads(1)
    fun endpointDispatchPrebuiltKey1T(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, prebuiltKey)
        OtherlodeEndpoints.hit(entry)
        return entry
    }

    @Benchmark
    @Threads(Threads.MAX)
    fun endpointDispatchPrebuiltKeyPerCore(): Any? {
        val entry = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, prebuiltKey)
        OtherlodeEndpoints.hit(entry)
        return entry
    }
}
