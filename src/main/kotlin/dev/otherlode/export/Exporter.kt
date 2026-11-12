package dev.otherlode.export

/**
 * A destination for the agent's payloads: delta batches, manifests, static baselines and class
 * listings. An implementation throws on failure.
 */
interface Exporter {
    fun exportDeltaBatch(batch: DeltaBatch)

    fun exportManifest(manifest: ProbeManifest)

    fun exportStaticBaseline(baseline: StaticBaseline)

    fun exportClassListing(listing: ClassListing)
}
