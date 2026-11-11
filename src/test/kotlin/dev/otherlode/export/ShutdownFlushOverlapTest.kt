package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShutdownFlushOverlapTest {
    private val config = AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test")
    private val resource = TestResources.forConfig(config)
    private val neverReleased = CountDownLatch(1)

    @AfterTest
    fun releaseBlockedSends() {
        neverReleased.countDown()
    }

    private class ScriptedExporter(
        val onDelta: (DeltaBatch) -> Unit = {},
        val onManifest: (ProbeManifest) -> Unit = {},
    ) : Exporter {
        val deltas: MutableList<DeltaBatch> = Collections.synchronizedList(mutableListOf())

        override fun exportDeltaBatch(batch: DeltaBatch) {
            onDelta(batch)
            deltas += batch
        }

        override fun exportManifest(manifest: ProbeManifest) {
            onManifest(manifest)
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}
    }

    @Test
    fun `an early send that ends after the final flush began does not clear hits the final flush left pending`() {
        val registry = ProbeRegistry()
        val foo = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        val bar = registry.register("com.example.Bar", 2L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        foo[0] += 1
        val releaseEarly = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val exporter =
            ScriptedExporter(onDelta = { batch ->
                when {
                    first.getAndSet(false) -> releaseEarly.await()
                    batch.deltas.any { it.hitsTotal == 2L } -> throw ExportFailedException("413", statusCode = 413, refused = true)
                    batch.deltas.any { it.hitsTotal == 10L } -> {
                        releaseEarly.countDown()
                        Thread.sleep(500)
                    }
                }
            })
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 1)

        scheduler.flushAtShutdownStart(Duration.ofMillis(300))
        foo[0] += 1
        bar[0] += 10
        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val closing = exporter.deltas.single { it.finalFlush }
        assertNotEquals(0L, closing.countsPendingSince, "a hit batch of the final flush was refused, so the closing batch says pending")
    }

    @Test
    fun `an early manifest pass that ends after the final flush began does not clear manifest content the final flush left pending`() {
        val registry = ProbeRegistry()
        val foo = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        val releaseEarly = CountDownLatch(1)
        val refused = CountDownLatch(1)
        val finalStarted = AtomicBoolean(false)
        val exporter =
            ScriptedExporter(
                onManifest = { manifest ->
                    val classes = manifest.probes.map { it.className }.toSet()
                    if ("com.example.Late" in classes) {
                        refused.countDown()
                        throw ExportFailedException("413", statusCode = 413, refused = true)
                    }
                    if ("com.example.Foo" in classes) releaseEarly.await()
                },
                onDelta = { batch ->
                    if (finalStarted.get() && batch.deltas.isNotEmpty()) {
                        refused.await()
                        releaseEarly.countDown()
                        Thread.sleep(500)
                    }
                },
            )
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flushAtShutdownStart(Duration.ofMillis(300))
        finalStarted.set(true)
        registry.register("com.example.Late", 2L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        foo[0] += 1
        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val closing = exporter.deltas.single { it.finalFlush }
        assertNotEquals(0L, closing.countsPendingSince, "the final flush's manifest chunk was refused, so the closing batch says pending")
    }

    @Test
    fun `an early send that ends after the final flush confirmed everything does not leave the run pending`() {
        val registry = ProbeRegistry()
        val foo = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        val bar = registry.register("com.example.Bar", 2L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        foo[0] += 1
        bar[0] += 10
        val releaseEarly = CountDownLatch(1)
        val earlyEnded = CountDownLatch(1)
        val finalHitBatches = CountDownLatch(2)
        val first = AtomicBoolean(true)
        val exporter =
            ScriptedExporter(
                onDelta = { batch ->
                    when {
                        first.getAndSet(false) -> {
                            releaseEarly.await()
                            earlyEnded.countDown()
                        }
                        batch.deltas.any { it.hitsTotal == 2L || it.hitsTotal == 20L } -> finalHitBatches.countDown()
                    }
                },
                onManifest = { manifest ->
                    if (manifest.probes.any { it.className == "com.example.Late" }) {
                        finalHitBatches.await()
                        Thread.sleep(100)
                        releaseEarly.countDown()
                        earlyEnded.await()
                        Thread.sleep(300)
                    }
                },
            )
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 1)

        scheduler.flushAtShutdownStart(Duration.ofMillis(300))
        foo[0] += 1
        bar[0] += 10
        registry.register("com.example.Late", 3L, listOf(ProbeMeta(ProbeKind.METHOD, "c", "()V", 1)))
        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val closing = exporter.deltas.single { it.finalFlush }
        assertEquals(0L, closing.countsPendingSince, "the final flush confirmed every hit batch and chunk, so the closing batch says nothing is pending")
    }

    @Test
    fun `the final flush sends its hit batches while the early flush's sends are still running`() {
        val registry = ProbeRegistry()
        val foo = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        foo[0] += 1
        val early = AtomicBoolean(true)
        val exporter =
            ScriptedExporter(
                onDelta = { if (early.getAndSet(false)) neverReleased.await() },
                onManifest = { if (it.probes.isNotEmpty()) neverReleased.await() },
            )
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flushAtShutdownStart(Duration.ofMillis(300))
        foo[0] += 1
        scheduler.flushOnShutdown(Duration.ofSeconds(3))

        assertTrue(
            exporter.deltas.any { !it.finalFlush && it.deltas.any { delta -> delta.hitsTotal == 2L } },
            "the final flush's hit batch went out. Sent: ${exporter.deltas}",
        )
    }
}
