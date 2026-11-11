package dev.otherlode.export

/**
 * A destination for the agent's payloads: delta batches, manifests, static baselines and class
 * listings. An implementation throws on failure. A refused delta batch, signalled by
 * [ExportFailedException.refused], does not stop the flush's remaining delta sends. Any other
 * failure ends them.
 */
interface Exporter {
    fun exportDeltaBatch(batch: DeltaBatch)

    fun exportManifest(manifest: ProbeManifest)

    fun exportStaticBaseline(baseline: StaticBaseline)

    fun exportClassListing(listing: ClassListing)
}
