package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportSchedulerClassListingBlindTest {
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

    private fun scheduler(
        registry: ProbeRegistry,
        exporter: Exporter,
        blind: AtomicBoolean,
    ): ExportScheduler {
        val dependencies = DependencyRegistry().apply { markListingComplete() }
        return ExportScheduler(
            config,
            resource,
            registry,
            EndpointRegistry(),
            exporter,
            dependencyRegistry = dependencies,
            externalClassRegistry = ExternalClassRegistry(dependencies::isListingComplete, { 0 }, dependencies::isSendable),
            classListingBlind = blind::get,
        )
    }

    @Test
    fun `a manifest says false until the listing is blind, and a class chunk after that says true`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val blind = AtomicBoolean(false)
        val scheduler = scheduler(registry, exporter, blind)
        registry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        scheduler.flush()
        assertTrue(exporter.manifests.isNotEmpty())
        assertTrue(exporter.manifests.none { it.classListingBlind })

        blind.set(true)
        registry.register("com.example.B", 2L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        scheduler.flush()

        val chunk = exporter.manifests.last { it.probes.isNotEmpty() }
        assertTrue(chunk.classListingBlind)
    }

    @Test
    fun `a flag that rises with nothing else to send goes out on a manifest of its own, once`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val blind = AtomicBoolean(false)
        val scheduler = scheduler(registry, exporter, blind)
        scheduler.flush()
        val before = exporter.manifests.size

        blind.set(true)
        scheduler.flush()

        assertEquals(before + 1, exporter.manifests.size)
        val sent = exporter.manifests.last()
        assertTrue(sent.classListingBlind)
        assertTrue(sent.probes.isEmpty())

        scheduler.flush()
        assertEquals(before + 1, exporter.manifests.size, "the delivered flag is not sent again")
    }

    @Test
    fun `an unconfirmed manifest does not count as delivering the flag`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val blind = AtomicBoolean(false)
        val scheduler = scheduler(registry, exporter, blind)
        scheduler.flush()
        blind.set(true)
        exporter.failManifests = true
        scheduler.flush()
        val before = exporter.manifests.size

        exporter.failManifests = false
        scheduler.flush()

        assertEquals(before + 1, exporter.manifests.size)
        assertTrue(exporter.manifests.last().classListingBlind)
    }

    @Test
    fun `a run that never goes blind never says so`() {
        val registry = ProbeRegistry()
        val exporter = Exporter()
        val scheduler = scheduler(registry, exporter, AtomicBoolean(false))
        registry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))

        repeat(3) { scheduler.flush() }

        assertFalse(exporter.manifests.any { it.classListingBlind })
    }
}
