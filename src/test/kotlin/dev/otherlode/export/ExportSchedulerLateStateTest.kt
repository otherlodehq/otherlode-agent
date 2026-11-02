package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.instrumentation.staticscan.StaticBaselineSender
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportSchedulerLateStateTest {
    private val config = AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test")
    private val resource = TestResources.forConfig(config)

    private class Probe : Exporter {
        val deltas: MutableList<Pair<DeltaBatch, Boolean>> = Collections.synchronizedList(mutableListOf())
        val manifests: MutableList<Pair<ProbeManifest, Boolean>> = Collections.synchronizedList(mutableListOf())
        val arrivals: MutableList<ProbeManifest> = Collections.synchronizedList(mutableListOf())
        val baselines: MutableList<StaticBaseline> = Collections.synchronizedList(mutableListOf())
        @Volatile var deltaOutcome: (DeltaBatch) -> Exception? = { null }
        @Volatile var manifestOutcome: (ProbeManifest) -> Exception? = { null }
        @Volatile var beforeManifest: (ProbeManifest) -> Unit = {}
        @Volatile var afterDelta: (DeltaBatch) -> Unit = {}
        @Volatile var beforeDelta: (DeltaBatch) -> Unit = {}

        override fun exportDeltaBatch(batch: DeltaBatch) {
            beforeDelta(batch)
            val e = deltaOutcome(batch)
            deltas += batch to (e == null)
            afterDelta(batch)
            e?.let { throw it }
        }

        override fun exportManifest(manifest: ProbeManifest) {
            arrivals += manifest
            beforeManifest(manifest)
            val e = manifestOutcome(manifest)
            manifests += manifest to (e == null)
            e?.let { throw it }
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {
            baselines += baseline
        }

        override fun exportClassListing(listing: ClassListing) {}

        fun newestAccepted(): Pair<Long, Long> =
            (deltas.filter { it.second }.map { it.first.payloadSequence to it.first.countsPendingSince } +
                manifests.filter { it.second }.map { it.first.payloadSequence to it.first.countsPendingSince })
                .maxBy { it.first }
    }

    private fun refusal() = ExportFailedException("refused", 413, refused = true)

    @Test
    fun `a manifest refused after the flush's delta batch went out still leaves the run reading pending`() {
        val registry = ProbeRegistry()
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
        val exporter = Probe()
        val deltaSent = CountDownLatch(1)
        exporter.afterDelta = { deltaSent.countDown() }
        exporter.beforeManifest = { deltaSent.await(5, TimeUnit.SECONDS) }
        exporter.manifestOutcome = { refusal() }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        val newest = exporter.newestAccepted()
        assertEquals(5_000L, newest.second, "the run's newest accepted payload should say pending once its manifest was refused")
    }

    @Test
    fun `a slow manifest send leaves the shutdown flush time for its final_flush batch, marked pending`() {
        val registry = ProbeRegistry()
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
        val exporter = Probe()
        exporter.beforeManifest = { Thread.sleep(3_000) }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flushOnShutdown(Duration.ofSeconds(1))
        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(closing.countsPendingSince != 0L, "the closing batch says the unconfirmed manifest is pending")
    }

    @Test
    fun `a late heartbeat that fails with a 503 stops the flush's static baseline retry`() {
        val registry = ProbeRegistry()
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
        val exporter = Probe()
        val sender = StaticBaselineSender(exporter)
        sender.offer(listOf(StaticBaseline(resource, emptyList(), scannedAt = 1L)))
        val baselinesAfterOffer = exporter.baselines.size
        val now = java.util.concurrent.atomic.AtomicLong(1_000)
        val scheduler =
            ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, staticBaselineSender = sender, clock = now::get)
        exporter.manifestOutcome = { refusal() }
        scheduler.flush()
        val afterFirst = exporter.baselines.size

        exporter.manifestOutcome = { null }
        registry.register("com.example.D", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        val deltaLoopDone = CountDownLatch(2)
        exporter.afterDelta = { deltaLoopDone.countDown() }
        exporter.beforeManifest = { deltaLoopDone.await(5, TimeUnit.SECONDS) }
        val n = AtomicInteger(0)
        exporter.deltaOutcome = { if (n.incrementAndGet() == 3) ExportFailedException("unavailable", 503, refused = false) else null }
        now.set(2_000)
        scheduler.flush()
        assertEquals(afterFirst, exporter.baselines.size, "no static baseline retry into what looks like an outage")
    }

    private fun statusFailure(status: Int = 503) = ExportFailedException("unavailable", status, refused = false)

    private fun transportFailure(): Exception = java.net.ConnectException("Connection refused")

    private fun registryWithHit(): ProbeRegistry {
        val registry = ProbeRegistry()
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
        return registry
    }

    private fun Probe.manifestWaitsForDelta() {
        val deltaSent = CountDownLatch(1)
        afterDelta = { deltaSent.countDown() }
        beforeManifest = { deltaSent.await(5, TimeUnit.SECONDS) }
    }

    private fun external(dependencies: DependencyRegistry) =
        ExternalClassRegistry(dependencies::isListingComplete, { 0 }, dependencies::isSendable)

    private fun dependencyAwaitingDelivery(): DependencyRegistry =
        DependencyRegistry().apply {
            register(
                listOf(DependencyIdentity("g", "a", "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/a.jar",
                DependencyDiscoverySource.STARTUP_CLASSPATH,
                classCount = 1,
            )
            markListingComplete()
            markCounted()
        }

    @Test
    fun `a manifest that fails with a status after the delta batch was accepted is followed by a late heartbeat saying pending`() {
        val exporter = Probe()
        exporter.manifestWaitsForDelta()
        exporter.manifestOutcome = { statusFailure(503) }
        val scheduler = ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        assertEquals(0L, exporter.deltas.first().first.countsPendingSince, "the flush's delta batch was stamped before the manifest failed")
        assertEquals(5_000L, exporter.newestAccepted().second, "a late heartbeat says the unconfirmed manifest is pending")
    }

    @Test
    fun `a manifest computation that throws after the delta batch was accepted is followed by a late heartbeat saying pending`() {
        val deltaSent = CountDownLatch(1)
        val registry =
            object : ProbeRegistry() {
                override fun computeManifestDeltas(
                    resource: ResourceAttributes,
                    maxEntriesPerChunk: Int,
                ): Sequence<ManifestSnapshot> {
                    deltaSent.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("broken")
                }
            }
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
        val exporter = Probe()
        exporter.afterDelta = { deltaSent.countDown() }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        assertEquals(0L, exporter.deltas.first().first.countsPendingSince)
        assertEquals(5_000L, exporter.newestAccepted().second, "a late heartbeat says the run is pending")
    }

    @Test
    fun `a manifest send with no response after the delta batch was accepted is followed by a late heartbeat`() {
        val exporter = Probe()
        exporter.manifestWaitsForDelta()
        exporter.manifestOutcome = { transportFailure() }
        val scheduler = ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        assertEquals(2, exporter.deltas.size, "the delta side was accepted, so this is no outage")
        assertEquals(5_000L, exporter.deltas.last().first.countsPendingSince)
    }

    @Test
    fun `a delta batch that fails with a status sends no late heartbeat`() {
        val exporter = Probe()
        exporter.manifestWaitsForDelta()
        exporter.deltaOutcome = { statusFailure(503) }
        exporter.manifestOutcome = { statusFailure(503) }
        val scheduler = ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        assertEquals(1, exporter.deltas.size, "a collector answering 503 to everything costs no extra send")
    }

    @Test
    fun `an unplaced-count manifest that fails with a status is followed by a late heartbeat saying pending`() {
        val registry = ProbeRegistry()
        registry.recordUnplacedVerificationFailures(1)
        val exporter = Probe()
        exporter.manifestOutcome = { statusFailure(503) }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        assertEquals(5_000L, exporter.newestAccepted().second)
    }

    @Test
    fun `an unplaced-count manifest that gets no response is followed by a late heartbeat saying pending`() {
        val registry = ProbeRegistry()
        registry.recordUnplacedVerificationFailures(1)
        val exporter = Probe()
        exporter.manifestOutcome = { transportFailure() }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = { 5_000L })

        scheduler.flush()

        assertEquals(5_000L, exporter.deltas.last().first.countsPendingSince)
    }

    @Test
    fun `a refused unplaced-count manifest marks the run pending and a later confirmed one clears it`() {
        val registry = ProbeRegistry()
        registry.recordUnplacedVerificationFailures(1)
        val exporter = Probe()
        val now = java.util.concurrent.atomic.AtomicLong(5_000L)
        exporter.manifestOutcome = { refusal() }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, clock = now::get)

        scheduler.flush()

        assertEquals(5_000L, exporter.newestAccepted().second, "the refused count leaves the run reading pending")

        exporter.manifestOutcome = { null }
        now.set(9_000L)
        scheduler.flush()

        val confirmed = exporter.manifests.last { it.second }.first
        assertEquals(1L, confirmed.unplacedVerificationFailures)
        assertEquals(0L, exporter.newestAccepted().second, "the confirmed count clears it")
    }

    @Test
    fun `a refused dependenciesListed manifest marks nothing pending and is sent again`() {
        val dependencies = DependencyRegistry().apply { markListingComplete() }
        val exporter = Probe()
        exporter.manifestOutcome = { refusal() }
        val scheduler =
            ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), exporter, dependencyRegistry = dependencies, externalClassRegistry = external(dependencies), clock = { 5_000L })

        scheduler.flush()

        assertTrue(exporter.manifests.single().first.dependenciesListed, "the only manifest is the dependenciesListed one")
        assertEquals(1, exporter.deltas.size, "no heartbeat follows")
        assertTrue(exporter.deltas.all { it.first.countsPendingSince == 0L }, "no payload says pending")

        exporter.manifestOutcome = { null }
        scheduler.flush()

        assertTrue(exporter.manifests.last().second, "the next flush sends it again and it is accepted")
        assertTrue(exporter.deltas.all { it.first.countsPendingSince == 0L })
    }

    @Test
    fun `a dependenciesListed manifest that gets no response marks nothing pending and sends no late heartbeat`() {
        val dependencies = DependencyRegistry().apply { markListingComplete() }
        val exporter = Probe()
        exporter.manifestOutcome = { transportFailure() }
        val scheduler =
            ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), exporter, dependencyRegistry = dependencies, externalClassRegistry = external(dependencies), clock = { 5_000L })

        scheduler.flush()

        assertTrue(exporter.manifests.single().first.dependenciesListed)
        assertEquals(1, exporter.deltas.size)
        assertTrue(exporter.deltas.all { it.first.countsPendingSince == 0L })
    }

    @Test
    fun `a delta send that times out in the shutdown flush leaves its closing batch unconfirmed and pending`() {
        val exporter = Probe()
        val release = CountDownLatch(1)
        exporter.beforeDelta = { if (!it.finalFlush) release.await(10, TimeUnit.SECONDS) }
        val scheduler = ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter)

        try {
            scheduler.flushOnShutdown(Duration.ofMillis(2_500))
        } finally {
            release.countDown()
        }

        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertFalse(closing.flushComplete, "the flush is not complete when a delta send was abandoned")
        assertTrue(closing.countsPendingSince != 0L, "the closing batch says the abandoned hits are pending")
    }

    @Test
    fun `the shutdown flush sends no release manifest after the first manifest pass timed out`() {
        val exporter = Probe()
        val release = CountDownLatch(1)
        val firstManifestArrived = CountDownLatch(1)
        exporter.beforeManifest = {
            firstManifestArrived.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        exporter.beforeDelta = { if (!it.finalFlush) firstManifestArrived.await(5, TimeUnit.SECONDS) }
        val scheduler =
            ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter, dependencyRegistry = dependencyAwaitingDelivery())

        try {
            scheduler.flushOnShutdown(Duration.ofMillis(2_500))
            Thread.sleep(500)
        } finally {
            release.countDown()
        }

        assertEquals(1, exporter.arrivals.size, "only the first manifest pass reached the exporter")
        assertTrue(exporter.arrivals.none { it.dependencies.isNotEmpty() }, "no release manifest was started")
        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(closing.countsPendingSince != 0L)
    }

    @Test
    fun `the shutdown flush sends the release manifest before its closing batch`() {
        val exporter = Probe()
        val firstManifestArrived = CountDownLatch(1)
        exporter.beforeManifest = { firstManifestArrived.countDown() }
        exporter.beforeDelta = { if (!it.finalFlush) firstManifestArrived.await(5, TimeUnit.SECONDS) }
        val scheduler =
            ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter, dependencyRegistry = dependencyAwaitingDelivery())

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val release = exporter.manifests.single { it.first.dependencies.isNotEmpty() }
        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(release.second)
        assertTrue(release.first.payloadSequence < closing.payloadSequence)
        assertEquals(0L, closing.countsPendingSince)
    }

    @Test
    fun `a release manifest that outlasts the shutdown budget leaves the closing batch pending`() {
        val exporter = Probe()
        val release = CountDownLatch(1)
        val firstManifestArrived = CountDownLatch(1)
        exporter.beforeManifest = {
            firstManifestArrived.countDown()
            if (it.dependencies.isNotEmpty()) release.await(10, TimeUnit.SECONDS)
        }
        exporter.beforeDelta = { if (!it.finalFlush) firstManifestArrived.await(5, TimeUnit.SECONDS) }
        val scheduler =
            ExportScheduler(config, resource, registryWithHit(), EndpointRegistry(), exporter, dependencyRegistry = dependencyAwaitingDelivery())

        try {
            scheduler.flushOnShutdown(Duration.ofMillis(5_000))
        } finally {
            release.countDown()
        }

        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(closing.countsPendingSince != 0L, "the unconfirmed release manifest is pending")
    }

    @Test
    fun `the shutdown flush sends the unplaced-count manifest before its closing batch`() {
        val registry = ProbeRegistry()
        registry.recordUnplacedVerificationFailures(1)
        val exporter = Probe()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val unplaced = exporter.manifests.single { it.first.unplacedVerificationFailures == 1L }
        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(unplaced.second)
        assertTrue(unplaced.first.payloadSequence < closing.payloadSequence, "the count goes out before the closing batch")
        assertEquals(0L, closing.countsPendingSince)
    }

    @Test
    fun `the shutdown flush sends the dependenciesListed manifest before its closing batch`() {
        val dependencies = DependencyRegistry().apply { markListingComplete() }
        val exporter = Probe()
        val scheduler = ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), exporter, dependencyRegistry = dependencies, externalClassRegistry = external(dependencies))

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val listed = exporter.manifests.single { it.first.dependenciesListed }
        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(listed.first.payloadSequence < closing.payloadSequence, "the listing flag goes out before the closing batch")
    }

    @Test
    fun `an unplaced-count manifest that outlasts the shutdown budget leaves the closing batch pending`() {
        val registry = ProbeRegistry()
        registry.recordUnplacedVerificationFailures(1)
        val exporter = Probe()
        val release = CountDownLatch(1)
        exporter.beforeManifest = { release.await(10, TimeUnit.SECONDS) }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        try {
            scheduler.flushOnShutdown(Duration.ofMillis(2_500))
        } finally {
            release.countDown()
        }

        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertTrue(closing.countsPendingSince != 0L, "the unconfirmed count is pending")
    }

    @Test
    fun `a dependenciesListed manifest that outlasts the shutdown budget is bounded and marks nothing pending`() {
        val dependencies = DependencyRegistry().apply { markListingComplete() }
        val exporter = Probe()
        val release = CountDownLatch(1)
        exporter.beforeManifest = { release.await(10, TimeUnit.SECONDS) }
        val scheduler = ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), exporter, dependencyRegistry = dependencies, externalClassRegistry = external(dependencies))

        try {
            scheduler.flushOnShutdown(Duration.ofMillis(2_500))
        } finally {
            release.countDown()
        }

        val closing = synchronized(exporter.deltas) { exporter.deltas.toList() }.map { it.first }.single { it.finalFlush }
        assertEquals(0L, closing.countsPendingSince, "the flag carries no evidence, so its absence marks nothing pending")
    }
}
