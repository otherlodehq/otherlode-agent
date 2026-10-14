package dev.otherlode.classlisting

import dev.otherlode.export.ChunkSender
import dev.otherlode.export.ClassListing
import dev.otherlode.export.Exporter

/**
 * The [ChunkSender] for the class listing. [ClassListingPublisher] offers the chunks and makes the
 * first attempt through [exporter]; a refusal of the content drops the listing for the process.
 */
class ClassListingSender(
    exporter: Exporter,
    retryExporter: Exporter = exporter,
) : ChunkSender<ClassListing>(
        exporter,
        retryExporter,
        noun = "class listing",
        whole = "listing",
        send = Exporter::exportClassListing,
        chunkIndex = ClassListing::chunkIndex,
        chunkCount = ClassListing::chunkCount,
    )
