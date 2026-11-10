package dev.otherlode.export

/**
 * Sets [ResourceAttributes.manifestConfirmed] on every payload just before [delegate] sends it, to
 * the value [confirmed] gives at that moment. A payload already built keeps the value it was sent
 * with. Every sender of the process goes through one of these over the same source, so the four
 * payload kinds cannot disagree.
 */
class ManifestConfirmedExporter(
    private val delegate: Exporter,
    private val confirmed: () -> Boolean,
) : Exporter {
    override fun exportDeltaBatch(batch: DeltaBatch) = delegate.exportDeltaBatch(batch.copy(resource = stamp(batch.resource)))

    override fun exportManifest(manifest: ProbeManifest) = delegate.exportManifest(manifest.copy(resource = stamp(manifest.resource)))

    override fun exportStaticBaseline(baseline: StaticBaseline) =
        delegate.exportStaticBaseline(baseline.copy(resource = stamp(baseline.resource)))

    override fun exportClassListing(listing: ClassListing) = delegate.exportClassListing(listing.copy(resource = stamp(listing.resource)))

    private fun stamp(resource: ResourceAttributes): ResourceAttributes = resource.copy(manifestConfirmed = confirmed())
}
