package dev.otherlode.classlisting

import dev.otherlode.export.ClassListing
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.Exporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassListingPublisherTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")
    private val rules = IncludeRules(listOf("com.acme"), listOf("com.acme.internal"))

    private class RecordingExporter : Exporter {
        val listings = mutableListOf<ClassListing>()

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {
            listings += listing
        }
    }

    @Test
    fun `sends every chunk of the listing in order, stamped with the rules and the clock`() {
        val exporter = RecordingExporter()
        val publisher =
            ClassListingPublisher(
                { listOf("com.acme.A", "com.acme.B", "com.acme.C") },
                rules,
                ClassListingSender(exporter),
                maxNamesPerChunk = 2,
                clock = { 77L },
            )

        publisher.run(resource)

        assertEquals(listOf(listOf("com.acme.A", "com.acme.B"), listOf("com.acme.C")), exporter.listings.map { it.classNames })
        assertEquals(setOf(rules), exporter.listings.map { it.includeRules }.toSet())
        assertEquals(setOf(77L), exporter.listings.map { it.listedAt }.toSet())
        assertEquals(setOf(resource), exporter.listings.map { it.resource }.toSet())
    }

    @Test
    fun `a listing that could not be read whole sends nothing, not even an empty listing`() {
        val exporter = RecordingExporter()
        val sender = ClassListingSender(exporter)

        ClassListingPublisher({ null }, rules, sender).run(resource)
        sender.retryPending()

        assertEquals(emptyList(), exporter.listings)
    }

    @Test
    fun `an empty listing is sent as one empty chunk`() {
        val exporter = RecordingExporter()

        ClassListingPublisher({ emptyList() }, rules, ClassListingSender(exporter)).run(resource)

        assertEquals(1, exporter.listings.size)
        assertEquals(emptyList(), exporter.listings.single().classNames)
    }

    @Test
    fun `a listing that throws is logged and sends nothing, and the thread ends normally`() {
        val exporter = RecordingExporter()
        val publisher = ClassListingPublisher({ throw IllegalStateException("boom") }, rules, ClassListingSender(exporter))

        publisher.run(resource)

        assertEquals(emptyList(), exporter.listings)
    }
}
