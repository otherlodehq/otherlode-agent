package dev.otherlode.export

import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportSchedulerManifestConfirmedTest {
    private val config =
        AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test,flushIntervalSeconds=60")

    private class Recorder : Exporter {
        val sent: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
        val manifests: MutableList<ProbeManifest> = Collections.synchronizedList(mutableListOf())
        @Volatile var failManifests = false

        override fun exportDeltaBatch(batch: DeltaBatch) {
            sent += "delta" to batch.resource.manifestConfirmed
        }

        override fun exportManifest(manifest: ProbeManifest) {
            sent += "manifest" to manifest.resource.manifestConfirmed
            manifests += manifest
            if (failManifests) throw ExportFailedException("unavailable", 503, refused = false)
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {}

        fun take(kind: String): List<Boolean> = sent.filter { it.first == kind }.map { it.second }.also { sent.clear() }
    }

    private val registry = ProbeRegistry()
    private val exporter = Recorder()
    private val scheduler =
        ExportScheduler(config, TestResources.forConfig(config), registry, EndpointRegistry(), exporter, clock = { 1_000_000L })
    private var classes = 0

    private fun loadClass() {
        registry.register("com.example.C${classes++}", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3
    }

    @Test
    fun `the manifest that is confirmed carries false and every payload of the next flush carries true`() {
        loadClass()

        scheduler.flush()
        val first = exporter.sent.toList()
        exporter.sent.clear()
        scheduler.flush()

        assertEquals(listOf(false), first.filter { it.first == "manifest" }.map { it.second })
        assertTrue(first.any { it.first == "delta" })
        assertTrue(exporter.sent.isNotEmpty() && exporter.sent.all { it.second }, "every later payload says confirmed: ${exporter.sent}")
    }

    @Test
    fun `a confirmed manifest whose only class is too large to send sets the field`() {
        val literal = "x".repeat(65_535)
        val site =
            BranchSite(
                siteIndex = 0,
                siteKey = null,
                line = 1,
                condition = List(140) { ConditionPart(ConditionPartKind.STRING_LITERAL, literal) },
                outcomes = listOf(BranchOutcome(0, BranchRole.TAKEN), BranchOutcome(1, BranchRole.FALL_THROUGH)),
            )
        registry.register(
            "com.example.Huge",
            1L,
            listOf(ProbeMeta(ProbeKind.BRANCH, "m", "()V", line = 1, branchIndex = 0, branchSites = listOf(site))),
        )

        scheduler.flush()
        val first = exporter.manifests.toList()
        exporter.sent.clear()
        scheduler.flush()

        assertEquals(listOf("com.example.Huge"), first.flatMap { it.skippedClasses }.map { it.className })
        assertTrue(first.all { it.probes.isEmpty() }, "the class is too large to send")
        assertTrue(exporter.sent.isNotEmpty() && exporter.sent.all { it.second }, "${exporter.sent}")
    }

    @Test
    fun `a later manifest for a new class carries true`() {
        loadClass()
        scheduler.flush()
        exporter.sent.clear()
        loadClass()

        scheduler.flush()

        assertEquals(listOf(true), exporter.take("manifest"))
    }

    @Test
    fun `a manifest that failed to send leaves the field false on the retry`() {
        loadClass()
        exporter.failManifests = true

        scheduler.flush()
        val failed = exporter.take("manifest")
        scheduler.flush()
        val retried = exporter.take("manifest")
        exporter.failManifests = false
        scheduler.flush()
        val delivered = exporter.take("manifest")
        scheduler.flush()
        val after = exporter.sent.toList()

        assertEquals(listOf(false), failed)
        assertEquals(listOf(false), retried)
        assertEquals(listOf(false), delivered)
        assertTrue(after.isNotEmpty() && after.all { it.second }, "after the confirmation every payload says confirmed: $after")
    }

    @Test
    fun `a confirmed manifest with no class leaves the field false until one with a class is confirmed`() {
        registry.recordSkipped("com.example.Skipped", "too large")

        scheduler.flush()
        val skippedOnly = exporter.take("manifest")
        scheduler.flush()
        val heartbeat = exporter.sent.toList()
        exporter.sent.clear()
        loadClass()
        scheduler.flush()
        val withClass = exporter.sent.toList()
        exporter.sent.clear()
        scheduler.flush()

        assertEquals(listOf(false), skippedOnly)
        assertTrue(heartbeat.isNotEmpty() && heartbeat.none { it.second }, "a manifest with no class confirmed nothing: $heartbeat")
        assertTrue(withClass.filter { it.first == "manifest" }.none { it.second }, "the manifest that carries the class is built before: $withClass")
        assertTrue(exporter.sent.isNotEmpty() && exporter.sent.all { it.second }, "now confirmed: ${exporter.sent}")
        assertFalse(skippedOnly.single())
    }
}
