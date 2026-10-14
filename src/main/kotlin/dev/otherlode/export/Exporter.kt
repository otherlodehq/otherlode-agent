package dev.otherlode.export

/**
 * A destination for the agent's payloads: delta batches, manifests, static
 * baselines and class listings. An implementation should throw on failure. [ExportScheduler]
 * leaves the registry baseline where it is after a thrown exception, and offers a delta
 * batch or manifest again on a later flush; the static baseline's and the class listing's own
 * rules are [ChunkSender]'s. It reads a refusal from
 * [ExportFailedException.refused]: a refused delta batch does not stop the flush's remaining
 * delta sends. It treats every other failure as one that ends them.
 */
interface Exporter {
    fun exportDeltaBatch(batch: DeltaBatch)

    fun exportManifest(manifest: ProbeManifest)

    fun exportStaticBaseline(baseline: StaticBaseline)

    fun exportClassListing(listing: ClassListing)
}
