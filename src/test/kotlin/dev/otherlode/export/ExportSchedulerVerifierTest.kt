package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.instrumentation.OwnRetransformationFailures
import dev.otherlode.instrumentation.VerifierRejectionWatch
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.time.Duration
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the scheduler does with the verifier's rejections (ADR 0071): drains them before the sends,
 * stamps the watched flag and the unplaced total on every manifest, and sends a manifest for a rise
 * in the unplaced total alone.
 */
class ExportSchedulerVerifierTest {
    private val config = AgentConfig.parse("serviceName=checkout,serviceInstanceId=instance-1")
    private val resource = TestResources.forConfig(config)

    private class Exporter : dev.otherlode.export.Exporter {
        val manifests: MutableList<ProbeManifest> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var failManifests = false

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {
            if (failManifests) throw RuntimeException("collector unreachable")
            manifests += manifest
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}
    }

    private val split = { name: String -> "Bad type\nException Details:\n  Location:\n    $name.m()V @0: return\n  Reason:\n    x\n" }

    private fun watchOf(
        vararg messages: String,
        overflow: Long = 0,
        ownFailures: OwnRetransformationFailures = OwnRetransformationFailures(),
    ): VerifierRejectionWatch {
        val pending = ArrayDeque(messages.toList())
        var pendingOverflow = overflow
        return VerifierRejectionWatch(
            watched = true,
            ownFailures = ownFailures,
            drainMessages = {
                val taken = pending.toTypedArray()
                pending.clear()
                taken
            },
            takeOverflow = { pendingOverflow.also { pendingOverflow = 0 } },
        )
    }

    private fun listedDependencies() = DependencyRegistry().apply { markListingComplete() }

    private fun scheduler(
        registry: ProbeRegistry,
        exporter: Exporter,
        watch: VerifierRejectionWatch,
        endpointRegistry: EndpointRegistry = EndpointRegistry(),
        dependencyRegistry: DependencyRegistry = listedDependencies(),
    ) = ExportScheduler(
        config,
        resource,
        registry,
        endpointRegistry,
        exporter,
        dependencyRegistry = dependencyRegistry,
        externalClassRegistry = ExternalClassRegistry(dependencyRegistry::isListingComplete, { 0 }, dependencyRegistry::isSendable),
        verifierWatch = watch,
    )

    @Test
    fun `a rejected class the hook read goes out on the manifest of the same flush, once`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val scheduler = scheduler(registry, exporter, watchOf(split("com/example/Bad")))

        scheduler.flush()
        scheduler.flush()

        val sent = exporter.manifests.flatMap { it.classesFailedToInitialize }
        assertEquals(listOf("com.example.Bad" to InitializationFailure.VERIFIER_REJECTED), sent.map { it.className to it.reason })
    }

    @Test
    fun `a class the verifier rejects again on every flush is sent once`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val again =
            VerifierRejectionWatch(
                watched = true,
                drainMessages = { arrayOf(split("com/example/Bad")) },
                takeOverflow = { 0 },
            )
        val scheduler = scheduler(registry, exporter, again)

        repeat(3) { scheduler.flush() }

        assertEquals(1, exporter.manifests.sumOf { it.classesFailedToInitialize.size })
    }

    @Test
    fun `overflow the drain itself counts is taken in the same flush`() {
        val registry = ProbeRegistry()
        var overflow = 0L
        val watch =
            VerifierRejectionWatch(
                watched = true,
                drainMessages = {
                    overflow += 1
                    emptyArray()
                },
                takeOverflow = { overflow.also { overflow = 0 } },
            )

        watch.drainInto(registry)

        assertEquals(1L, registry.unplacedVerificationFailures())
    }

    @Test
    fun `a failure while reading the rejections counts one unplaced failure`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val failing =
            VerifierRejectionWatch(
                watched = true,
                drainMessages = { error("the ring could not be read") },
                takeOverflow = { 0 },
            )

        scheduler(registry, exporter, failing).flush()

        assertEquals(1L, registry.unplacedVerificationFailures())
        assertEquals(1L, exporter.manifests.last().unplacedVerificationFailures)
    }

    @Test
    fun `a run that is not watched drains nothing`() {
        val registry = ProbeRegistry()
        val unwatched =
            VerifierRejectionWatch(
                watched = false,
                drainMessages = { error("an unwatched run drained the ring") },
                takeOverflow = { error("an unwatched run took the overflow") },
            )

        unwatched.drainInto(registry)

        assertEquals(0L, registry.unplacedVerificationFailures())
    }

    @Test
    fun `what the hook's install check drained besides its own message is read on the first flush, once`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val carried =
            VerifierRejectionWatch(
                watched = true,
                drainMessages = { emptyArray() },
                takeOverflow = { 0 },
                carried = arrayOf(split("com/example/Early")),
                carriedOverflow = 2,
            )
        val scheduler = scheduler(registry, exporter, carried)

        scheduler.flush()
        scheduler.flush()

        assertEquals(listOf("com.example.Early"), exporter.manifests.flatMap { it.classesFailedToInitialize }.map { it.className })
        assertEquals(2L, registry.unplacedVerificationFailures())
    }

    @Test
    fun `a name the agent's own retransformation failed on is not sent, though other names are`() {
        val own = OwnRetransformationFailures().apply { record("com.example.Mine") }
        val exporter = Exporter()
        val scheduler =
            scheduler(ProbeRegistry(), exporter, watchOf(split("com/example/Mine"), split("com/example/Other"), ownFailures = own))

        scheduler.flush()

        assertEquals(listOf("com.example.Other"), exporter.manifests.flatMap { it.classesFailedToInitialize }.map { it.className })
    }

    @Test
    fun `the final flush drains too`() {
        val exporter = Exporter()
        val scheduler = scheduler(ProbeRegistry(), exporter, watchOf(split("com/example/Late")))

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(listOf("com.example.Late"), exporter.manifests.flatMap { it.classesFailedToInitialize }.map { it.className })
    }

    @Test
    fun `a message in no read format and each message lost to overflow add to the unplaced total`() {
        val exporter = Exporter()
        val scheduler = scheduler(ProbeRegistry(), exporter, watchOf("Could not link verifier", "payment failed", overflow = 3))

        scheduler.flush()

        assertEquals(5L, exporter.manifests.last().unplacedVerificationFailures)
    }

    @Test
    fun `a rise in the unplaced total alone sends a manifest, and a flush that changes nothing sends none`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val scheduler = scheduler(registry, exporter, watchOf())
        scheduler.flush()
        val before = exporter.manifests.size

        scheduler.flush()
        assertEquals(before, exporter.manifests.size, "nothing changed")

        registry.recordUnplacedVerificationFailures(2)
        scheduler.flush()
        assertEquals(before + 1, exporter.manifests.size)
        val sent = exporter.manifests.last()
        assertTrue(sent.probes.isEmpty() && sent.classesFailedToInitialize.isEmpty())
        assertEquals(2L, sent.unplacedVerificationFailures)

        scheduler.flush()
        assertEquals(before + 1, exporter.manifests.size, "the delivered total is not sent again")

        registry.recordUnplacedVerificationFailures(1)
        scheduler.flush()
        assertEquals(3L, exporter.manifests.last().unplacedVerificationFailures)
        assertEquals(before + 2, exporter.manifests.size)
    }

    @Test
    fun `an unconfirmed manifest does not count as delivering the unplaced total`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val scheduler = scheduler(registry, exporter, watchOf())
        scheduler.flush()
        registry.recordUnplacedVerificationFailures(1)
        exporter.failManifests = true
        scheduler.flush()
        val before = exporter.manifests.size

        exporter.failManifests = false
        scheduler.flush()

        assertEquals(before + 1, exporter.manifests.size)
        assertEquals(1L, exporter.manifests.last().unplacedVerificationFailures)
    }

    @Test
    fun `a class chunk carries the unplaced total beside it, so no extra manifest follows`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val scheduler = scheduler(registry, exporter, watchOf())
        scheduler.flush()
        val before = exporter.manifests.size
        registry.recordUnplacedVerificationFailures(4)
        registry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))

        scheduler.flush()

        assertEquals(before + 1, exporter.manifests.size)
        val chunk = exporter.manifests.last()
        assertEquals("com.example.A", chunk.probes.single().className)
        assertEquals(4L, chunk.unplacedVerificationFailures)
    }

    @Test
    fun `a class chunk, a riders-only chunk and the dependencies-listed empty manifest each say whether the run is watched`() {
        for (watched in listOf(true, false)) {
            val watch = if (watched) watchOf() else VerifierRejectionWatch.NOT_WATCHED

            val classChunk = Exporter()
            val classes = ProbeRegistry().apply { register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1))) }
            scheduler(classes, classChunk, watch).flush()
            assertTrue(classChunk.manifests.any { it.probes.isNotEmpty() }, "a class chunk was sent")

            val riders = Exporter()
            val endpoints = EndpointRegistry()
            endpoints.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
            scheduler(ProbeRegistry(), riders, watch, endpointRegistry = endpoints).flush()
            assertTrue(riders.manifests.any { it.probes.isEmpty() && it.endpoints.isNotEmpty() }, "a riders-only chunk was sent")

            val empty = Exporter()
            scheduler(ProbeRegistry(), empty, watch).flush()
            assertTrue(empty.manifests.any { it.probes.isEmpty() && it.dependenciesListed }, "the empty listed manifest was sent")

            for (manifest in classChunk.manifests + riders.manifests + empty.manifests) {
                assertEquals(watched, manifest.verifierRejectionsWatched, "watched=$watched on $manifest")
            }
        }
    }
}
