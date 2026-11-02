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

    private data class Stamp(val starting: Boolean, val pendingSince: Long)

    private class Recorder : Exporter {
        val deltas: MutableList<DeltaBatch> = Collections.synchronizedList(mutableListOf())
        val manifests: MutableList<ProbeManifest> = Collections.synchronizedList(mutableListOf())
        @Volatile var afterStamp: () -> Unit = {}
        @Volatile var refuseHitBatches = false

        override fun exportDeltaBatch(batch: DeltaBatch) {
            deltas += batch
            afterStamp()
            if (refuseHitBatches && batch.deltas.isNotEmpty()) throw ExportFailedException("refused", 413, refused = true)
        }

        override fun exportManifest(manifest: ProbeManifest) {
            manifests += manifest
            afterStamp()
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}

        fun stamps(): List<Stamp> =
            (deltas.map { Triple(it.payloadSequence, it.starting, it.countsPendingSince) } +
                manifests.map { Triple(it.payloadSequence, it.starting, it.countsPendingSince) })
                .sortedBy { it.first }
                .map { Stamp(it.second, it.third) }

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
        ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = now::get, hasStartingPhase = true, monotonicMillis = monotonic::get)
    private var classes = 0

    private fun loadClass() {
        registry.register("com.example.C${classes++}", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
    }

    private fun flushAt(age: Long): List<Stamp> {
        exporter.clear()
        now.set(agentStart + age)
        monotonic.set(agentStart + age)
        scheduler.flush()
        return exporter.stamps()
    }

    private fun assertStarting(stamps: List<Stamp>, message: String = "") {
        assertTrue(stamps.isNotEmpty(), "no payload: $message")
        assertTrue(stamps.all { it.starting && it.pendingSince == 0L }, "every payload says starting and nothing pending: $stamps $message")
    }

    private fun assertSettled(stamps: List<Stamp>, message: String = "") {
        assertTrue(stamps.isNotEmpty(), "no payload: $message")
        assertTrue(stamps.all { !it.starting && it.pendingSince == 0L }, "every payload says neither starting nor pending: $stamps $message")
    }

    @Test
    fun `a first flush within one interval of the start says starting and not pending though every send is confirmed`() {
        loadClass()

        val stamps = flushAt(600)

        assertTrue(exporter.manifests.isNotEmpty())
        assertStarting(stamps)
    }

    @Test
    fun `a flush past one interval that finds new in-scope classes stays starting`() {
        loadClass()
        flushAt(600)
        loadClass()

        val stamps = flushAt(intervalMillis + 600)

        assertStarting(stamps)
    }

    @Test
    fun `the first flush past one interval that finds no new class says starting ended and later flushes stay so`() {
        loadClass()
        val first = flushAt(600)

        val settled = flushAt(intervalMillis + 600)
        assertStarting(first)
        loadClass()
        val later = flushAt(2 * intervalMillis + 600)

        assertSettled(settled)
        assertSettled(later, "a class loading after the run settled does not bring starting back")
    }

    @Test
    fun `a skipped, an unprobed and an unreported class each count as an in-scope class`() {
        loadClass()
        flushAt(600)
        registry.recordSkipped("com.example.Skipped", "too large")
        assertStarting(flushAt(intervalMillis + 600))
        registry.recordNothingToProbe("com.example.Empty")
        assertStarting(flushAt(2 * intervalMillis + 600))
        registry.recordUnreported("com.example.Unreported")
        assertStarting(flushAt(3 * intervalMillis + 600))
        assertSettled(flushAt(4 * intervalMillis + 600))
    }

    @Test
    fun `starting ends after ten intervals though classes are still loading`() {
        for (k in 0 until 10) {
            loadClass()
            val stamps = flushAt(k * intervalMillis + 600)
            assertStarting(stamps, "flush $k is still starting")
        }
        loadClass()

        val stamps = flushAt(10 * intervalMillis + 600)

        assertSettled(stamps)
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
        assertTrue(!late.starting, "the late heartbeat says starting ended")
        assertTrue(late.deltas.isEmpty(), "the newest payload is a heartbeat")
        val earlier = exporter.deltas.filter { it !== late }.map { Stamp(it.starting, it.countsPendingSince) } +
            exporter.manifests.map { Stamp(it.starting, it.countsPendingSince) }
        assertTrue(earlier.isNotEmpty() && earlier.all { it.starting && it.pendingSince == 0L }, "the flush's own stamps said starting: $earlier")
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
        assertTrue(!closing.starting)
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
        assertEquals(0L, closing.countsPendingSince)
        assertTrue(closing.starting)
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
        assertEquals(0L, closing.countsPendingSince)
        assertTrue(closing.starting)
    }

    @Test
    fun `flushes run back to back after a slow one do not shorten the quiet interval`() {
        loadClass()
        flushAt(600)
        loadClass()
        flushAt(intervalMillis + 600)

        val catchUp = flushAt(intervalMillis + 601)

        assertStarting(catchUp, "one millisecond of quiet is not an interval")
    }

    @Test
    fun `a run that has loaded no in-scope class stays starting until the cap`() {
        val early = flushAt(intervalMillis + 100)
        val later = flushAt(3 * intervalMillis)

        assertStarting(early)
        assertStarting(later)
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

        assertStarting(exporter.stamps())
    }

    @Test
    fun `a wall clock that jumps forward does not end starting`() {
        loadClass()
        flushAt(600)
        exporter.clear()
        now.set(agentStart + 20 * intervalMillis)
        monotonic.set(agentStart + 1_000)

        scheduler.flush()

        assertStarting(exporter.stamps())
    }

    @Test
    fun `a run whose hits are refused while it is starting says both starting and pending`() {
        loadClass()
        exporter.refuseHitBatches = true

        flushAt(600)
        flushAt(1_200)

        val newest = exporter.stamps().last()
        assertTrue(newest.starting, "the newest payload says starting: ${exporter.stamps()}")
        assertEquals(agentStart + 600, newest.pendingSince, "the newest payload says the refused hits are pending: ${exporter.stamps()}")
        assertTrue(exporter.manifests.all { it.starting })
    }

    @Test
    fun `hits refused after the flush's stamps said nothing was pending are said by a late heartbeat`() {
        val counter = registry.register("com.example.Held", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        counter[0] = 1
        flushAt(600)
        exporter.refuseHitBatches = true
        counter[0] = 2

        flushAt(1_200)

        val accepted =
            (exporter.deltas.filter { it.deltas.isEmpty() }.map { it.payloadSequence to it.countsPendingSince } +
                exporter.manifests.map { it.payloadSequence to it.countsPendingSince })
                .sortedBy { it.first }
        assertTrue(accepted.isNotEmpty() && accepted.last().second != 0L, "the newest accepted payload says pending: $accepted")
    }

    @Test
    fun `two hit batches refused in one flush are said by a late heartbeat`() {
        val registry = ProbeRegistry()
        val exporter = Recorder()
        val scheduler =
            ExportScheduler(
                config, resource, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 1, clock = now::get,
                hasStartingPhase = true, monotonicMillis = monotonic::get,
            )
        val first = registry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        val second = registry.register("com.example.B", 2L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        first[0] = 1
        second[0] = 1
        now.set(agentStart + 600)
        monotonic.set(agentStart + 600)
        scheduler.flush()
        exporter.refuseHitBatches = true
        first[0] = 2
        second[0] = 2
        now.set(agentStart + 1_200)
        monotonic.set(agentStart + 1_200)

        scheduler.flush()

        val accepted =
            (exporter.deltas.filter { it.deltas.isEmpty() || !exporter.refuseHitBatches }.map { it.payloadSequence to it.countsPendingSince } +
                exporter.manifests.map { it.payloadSequence to it.countsPendingSince })
                .sortedBy { it.first }
        val refusedSequences = exporter.deltas.filter { it.deltas.isNotEmpty() && it.payloadSequence > 2 }.map { it.payloadSequence }
        val newestAccepted = accepted.filter { it.first !in refusedSequences }.maxBy { it.first }
        assertTrue(newestAccepted.second != 0L, "the newest accepted payload says pending: ${exporter.deltas.map { it.payloadSequence to it.countsPendingSince }}")
    }

    @Test
    fun `once starting ends a refused batch still says pending and not starting`() {
        loadClass()
        exporter.refuseHitBatches = true
        flushAt(600)

        flushAt(intervalMillis + 600)

        val hitBatch = exporter.deltas.filter { it.deltas.isNotEmpty() }.maxBy { it.payloadSequence }
        assertTrue(!hitBatch.starting, "starting ended")
        assertEquals(agentStart + 600, hitBatch.countsPendingSince, "the refused batch says pending")
        val newest = exporter.stamps().last()
        assertTrue(!newest.starting && newest.pendingSince != 0L, "${exporter.stamps()}")
    }
}
