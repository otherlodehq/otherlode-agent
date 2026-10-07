package dev.otherlode.export

/**
 * A destination for the agent's three payloads: delta batches, manifests and
 * static baselines. An implementation should throw on failure. [ExportScheduler]
 * treats a thrown exception as a transient failure, and leaves the registry
 * baseline where it is.
 */
interface Exporter {
    fun exportDeltaBatch(batch: DeltaBatch)

    fun exportManifest(manifest: ProbeManifest)

    fun exportStaticBaseline(baseline: StaticBaseline)
}
