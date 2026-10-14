package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.ChunkSender
import dev.otherlode.export.Exporter
import dev.otherlode.export.StaticBaseline

/**
 * The [ChunkSender] for the static baseline. [StaticBaselinePublisher] offers the chunks and makes
 * the first attempt through [exporter]; a refusal of the content drops the scan for the process.
 */
class StaticBaselineSender(
    exporter: Exporter,
    retryExporter: Exporter = exporter,
) : ChunkSender<StaticBaseline>(
        exporter,
        retryExporter,
        noun = "static baseline",
        whole = "scan",
        send = Exporter::exportStaticBaseline,
        chunkIndex = StaticBaseline::chunkIndex,
        chunkCount = StaticBaseline::chunkCount,
    )
