package dev.otherlode.classlisting

import dev.otherlode.export.ChunkSender
import dev.otherlode.export.ClassListing
import dev.otherlode.export.Exporter

/** The [ChunkSender] for the class listing. */
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
