package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportSchedulerStartingTest {
    private val config =
        AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test,flushIntervalSeconds=60")
    private val resource = TestResources.forConfig(config)
    private val intervalMillis = 60_000L
    private val agentStart = 1_000_000L

    private class Recorder : Exporter {
        val deltas: MutableList<DeltaBatch> = Collections.synchronizedList(mutableListOf())
        val manifests: MutableList<ProbeManifest> = Collections.synchronizedList(mutableListOf())
        @Volatile var afterStamp: () -> Unit = {}

        override fun exportDeltaBatch(batch: DeltaBatch) {
            deltas += batch
            afterStamp()
        }

        override fun exportManifest(manifest: ProbeManifest) {
            manifests += manifest
            afterStamp()
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}

        fun stamps(): List<Long> = (deltas.map { it.payloadSequence to it.countsPendingSince } + manifests.map { it.payloadSequence to it.countsPendingSince }).sortedBy { it.first }.map { it.second }

        fun clear() {
            deltas.clear()
            manifests.clear()
        }
    }

    private val now = AtomicLong(agentStart)
    private val monotonic = AtomicLong(agentStart)
    private val registry = ProbeRegistry()
    private val exporter = Recorder()
    private val scheduler = scheduler(registry)

    private fun scheduler(registry: ProbeRegistry) =
        ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = now::get, startsPending = true, monotonicMillis = monotonic::get)
    private var classes = 0

    private fun loadClass() {
        registry.register("com.example.C${classes++}", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
    }

    private fun flushAt(age: Long): List<Long> {
        exporter.clear()
        now.set(agentStart + age)
        monotonic.set(agentStart + age)
        scheduler.flush()
        return exporter.stamps()
    }

    @Test
    fun `a first flush within one interval of the start says pending though every send is confirmed`() {
        loadClass()

        val stamps = flushAt(600)

        assertTrue(stamps.isNotEmpty() && exporter.manifests.isNotEmpty())
        assertTrue(stamps.all { it == agentStart }, "every payload says pending since the agent's start: $stamps")
    }

    @Test
    fun `a flush past one interval that finds new in-scope classes stays pending`() {
        loadClass()
        flushAt(600)
        loadClass()

        val stamps = flushAt(intervalMillis + 600)

        assertTrue(stamps.isNotEmpty() && stamps.all { it == agentStart }, "$stamps")
    }

    @Test
    fun `the first flush past one interval that finds no new class says nothing is pending and later flushes stay so`() {
        loadClass()
        val first = flushAt(600)

        val settled = flushAt(intervalMillis + 600)
        assertTrue(first.isNotEmpty() && first.all { it == agentStart }, "$first")
        loadClass()
        val later = flushAt(2 * intervalMillis + 600)

        assertTrue(settled.isNotEmpty() && settled.all { it == 0L }, "$settled")
        assertTrue(later.isNotEmpty() && later.all { it == 0L }, "a class loading after the run settled does not bring starting back: $later")
    }

    @Test
    fun `a skipped, an unprobed and an unreported class each count as an in-scope class`() {
        loadClass()
        flushAt(600)
        registry.recordSkipped("com.example.Skipped", "too large")
        assertTrue(flushAt(intervalMillis + 600).all { it == agentStart })
        registry.recordNothingToProbe("com.example.Empty")
        assertTrue(flushAt(2 * intervalMillis + 600).all { it == agentStart })
        registry.recordUnreported("com.example.Unreported")
        assertTrue(flushAt(3 * intervalMillis + 600).all { it == agentStart })
        assertTrue(flushAt(4 * intervalMillis + 600).all { it == 0L })
    }

    @Test
    fun `starting ends after ten intervals though classes are still loading`() {
        for (k in 0 until 10) {
            loadClass()
            val stamps = flushAt(k * intervalMillis + 600)
            assertTrue(stamps.all { it == agentStart }, "flush $k is still starting: $stamps")
        }
        loadClass()

        val stamps = flushAt(10 * intervalMillis + 600)

        assertTrue(stamps.isNotEmpty() && stamps.all { it == 0L }, "$stamps")
    }

    @Test
    fun `a late heartbeat says the run is no longer starting when the cap passes after the flush's stamps`() {
        loadClass()
        val stamped = CountDownLatch(2)
        exporter.afterStamp = {
            stamped.countDown()
            stamped.await(5, TimeUnit.SECONDS)
            now.set(agentStart + 10 * intervalMillis + 500)
            monotonic.set(agentStart + 10 * intervalMillis + 500)
        }
        now.set(agentStart + 10 * intervalMillis - 1_000)
        monotonic.set(agentStart + 10 * intervalMillis - 1_000)

        scheduler.flush()

        val late = exporter.deltas.maxBy { it.payloadSequence }
        assertEquals(0L, late.countsPendingSince)
        assertTrue(late.deltas.isEmpty(), "the newest payload is a heartbeat")
        val earlier = exporter.deltas.filter { it !== late }.map { it.countsPendingSince } + exporter.manifests.map { it.countsPendingSince }
        assertTrue(earlier.isNotEmpty() && earlier.all { it == agentStart }, "the flush's own stamps said pending: $earlier")
    }

    @Test
    fun `a final flush after loading stopped during shutdown ends starting, as for a finished batch job`() {
        loadClass()
        flushAt(600)
        now.set(agentStart + 1_000)
        monotonic.set(agentStart + 1_000)
        scheduler.flushAtShutdownStart(Duration.ofSeconds(5))
        exporter.clear()

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val closing = exporter.deltas.single { it.finalFlush }
        assertEquals(0L, closing.countsPendingSince)
    }

    @Test
    fun `a final flush after a class loaded during shutdown stays pending, as for a JVM stopped while starting`() {
        loadClass()
        flushAt(600)
        now.set(agentStart + 1_000)
        monotonic.set(agentStart + 1_000)
        scheduler.flushAtShutdownStart(Duration.ofSeconds(5))
        loadClass()
        exporter.clear()

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val closing = exporter.deltas.single { it.finalFlush }
        assertEquals(agentStart, closing.countsPendingSince)
    }

    @Test
    fun `a final flush with no early shutdown flush stays pending`() {
        loadClass()
        flushAt(600)
        now.set(agentStart + 1_000)
        monotonic.set(agentStart + 1_000)
        exporter.clear()

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val closing = exporter.deltas.single { it.finalFlush }
        assertEquals(agentStart, closing.countsPendingSince)
    }

    @Test
    fun `flushes run back to back after a slow one do not shorten the quiet interval`() {
        loadClass()
        flushAt(600)
        loadClass()
        flushAt(intervalMillis + 600)

        val catchUp = flushAt(intervalMillis + 601)

        assertTrue(catchUp.isNotEmpty() && catchUp.all { it == agentStart }, "one millisecond of quiet is not an interval: $catchUp")
    }

    @Test
    fun `a run that has loaded no in-scope class stays starting until the cap`() {
        val early = flushAt(intervalMillis + 100)
        val later = flushAt(3 * intervalMillis)

        assertTrue(early.isNotEmpty() && early.all { it == agentStart }, "$early")
        assertTrue(later.isNotEmpty() && later.all { it == agentStart }, "$later")
    }

    @Test
    fun `a run with a class awaiting the sweep's confirmation stays starting`() {
        val confirming = ProbeRegistry(confirmsDefinitions = true)
        val scheduler = scheduler(confirming)
        confirming.register("com.example.Held", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        check(confirming.unconfirmedClassCount() == 1)
        now.set(agentStart + 600)
        monotonic.set(agentStart + 600)
        scheduler.flush()
        exporter.clear()
        now.set(agentStart + 2 * intervalMillis)
        monotonic.set(agentStart + 2 * intervalMillis)

        scheduler.flush()

        val stamps = exporter.stamps()
        assertTrue(stamps.isNotEmpty() && stamps.all { it == agentStart }, "$stamps")
    }

    @Test
    fun `a wall clock that jumps forward does not end starting`() {
        loadClass()
        flushAt(600)
        exporter.clear()
        now.set(agentStart + 20 * intervalMillis)
        monotonic.set(agentStart + 1_000)

        scheduler.flush()

        val stamps = exporter.stamps()
        assertTrue(stamps.isNotEmpty() && stamps.all { it == agentStart }, "$stamps")
    }
}
