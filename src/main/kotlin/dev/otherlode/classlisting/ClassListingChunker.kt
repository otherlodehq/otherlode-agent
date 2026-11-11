package dev.otherlode.classlisting

import dev.otherlode.export.ClassListing
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes

/**
 * Splits a listing's class names into [ClassListing] chunks. A chunk closes when the next name
 * would take it past [maxNamesPerChunk] names, or past the encoded bytes or element count of
 * [PayloadKind.CLASS_LISTING]. The names keep their order, and every chunk carries the same
 * resource, rules and `listedAt`. An empty listing produces one empty chunk.
 */
object ClassListingChunker {
    const val DEFAULT_MAX_NAMES_PER_CHUNK: Int = 20_000

    /** Throws [IllegalArgumentException] when [maxNamesPerChunk] is below one. */
    fun chunk(
        classNames: List<String>,
        resource: ResourceAttributes,
        includeRules: IncludeRules,
        listedAt: Long,
        maxNamesPerChunk: Int = DEFAULT_MAX_NAMES_PER_CHUNK,
    ): List<ClassListing> {
        require(maxNamesPerChunk > 0) { "maxNamesPerChunk must be positive" }
        val kind = PayloadKind.CLASS_LISTING
        val frame = ProtoPayloadCodec.weightOf(ClassListing(resource, emptyList(), includeRules, listedAt))
        val parts = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var weight = frame
        for (name in classNames) {
            val nameWeight = ProtoPayloadCodec.classNameWeight(name)
            if (current.isNotEmpty() && (current.size >= maxNamesPerChunk || !kind.admits(weight + nameWeight))) {
                parts += current
                current = mutableListOf()
                weight = frame
            }
            current += name
            weight += nameWeight
        }
        if (current.isNotEmpty() || parts.isEmpty()) parts += current
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
