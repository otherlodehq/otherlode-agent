package dev.otherlode.classlisting

import dev.otherlode.export.ClassListing
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes

/**
 * Splits a listing's class names into [ClassListing] chunks of at most [maxNamesPerChunk] names, so
 * a large classpath never produces a single POST the collector might refuse.
 *
 * The names keep their order across the chunks. Every chunk carries the same resource, rules and
 * `listedAt`, and its own `chunkIndex` out of `chunkCount`, so a collector can tell when it holds
 * the whole listing.
 *
 * Always produces at least one chunk: an empty listing is still a statement ("no class under the
 * rules").
 */
object ClassListingChunker {
    /** Names per chunk. */
    const val DEFAULT_MAX_NAMES_PER_CHUNK: Int = 20_000

    /**
     * The chunks of one listing of [classNames], each stamped with [resource], [includeRules] and
     * [listedAt]. Throws [IllegalArgumentException] when [maxNamesPerChunk] is below one.
     */
    fun chunk(
        classNames: List<String>,
        resource: ResourceAttributes,
        includeRules: IncludeRules,
        listedAt: Long,
        maxNamesPerChunk: Int = DEFAULT_MAX_NAMES_PER_CHUNK,
    ): List<ClassListing> {
        require(maxNamesPerChunk > 0) { "maxNamesPerChunk must be positive" }
        val parts = classNames.chunked(maxNamesPerChunk).ifEmpty { listOf(emptyList()) }
        return parts.mapIndexed { index, names ->
            ClassListing(
                resource = resource,
                classNames = names,
                includeRules = includeRules,
                listedAt = listedAt,
                chunkIndex = index,
                chunkCount = parts.size,
            )
        }
    }
}
