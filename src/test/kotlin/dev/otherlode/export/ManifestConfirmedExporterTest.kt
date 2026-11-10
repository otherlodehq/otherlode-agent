package dev.otherlode.export

import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestConfirmedExporterTest {
    private class Recorder : Exporter {
        val seen = mutableListOf<Boolean>()

        override fun exportDeltaBatch(batch: DeltaBatch) {
            seen += batch.resource.manifestConfirmed
        }

        override fun exportManifest(manifest: ProbeManifest) {
            seen += manifest.resource.manifestConfirmed
        }

        override fun exportStaticBaseline(baseline: StaticBaseline) {
            seen += baseline.resource.manifestConfirmed
        }

        override fun exportClassListing(listing: ClassListing) {
            seen += listing.resource.manifestConfirmed
        }
    }

    private val resource = ResourceAttributes("checkout", null, "instance-1", null, "run-1")

    private fun sendAllKinds(exporter: Exporter) {
        exporter.exportDeltaBatch(DeltaBatch(resource, emptyList()))
        exporter.exportManifest(ProbeManifest(resource, emptyList()))
        exporter.exportStaticBaseline(StaticBaseline(resource, emptyList(), scannedAt = 1L))
        exporter.exportClassListing(ClassListing(resource, emptyList(), IncludeRules(emptyList(), emptyList()), 1L))
    }

    @Test
    fun `all four payload kinds carry the value the source gives when each is sent`() {
        val recorder = Recorder()
        var confirmed = false
        val exporter = ManifestConfirmedExporter(recorder) { confirmed }

        sendAllKinds(exporter)
        confirmed = true
        sendAllKinds(exporter)

        assertEquals(List(4) { false } + List(4) { true }, recorder.seen)
    }

    @Test
    fun `a payload keeps the other resource attributes`() {
        val seen = mutableListOf<ResourceAttributes>()
        val exporter =
            ManifestConfirmedExporter(
                object : Exporter by Recorder() {
                    override fun exportDeltaBatch(batch: DeltaBatch) {
                        seen += batch.resource
                    }
                },
            ) { true }

        exporter.exportDeltaBatch(DeltaBatch(resource, emptyList()))

        assertEquals(resource.copy(manifestConfirmed = true), seen.single())
    }
}
