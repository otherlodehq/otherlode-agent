package dev.otherlode.export

/**
 * The limits a chunk of any payload kind is built under. The element limits are the collector's:
 * `ingest.Max*Elements` in otherlode-collector. A payload over one is refused with 413. A chunk
 * closes at [MAX_ELEMENT_PERCENT] percent of the limit for its kind, and at [MAX_ENCODED_BYTES] of
 * encoded bytes, which is half the collector's body limit.
 */
object ChunkLimits {
    /** The most encoded bytes a chunk holds. */
    const val MAX_ENCODED_BYTES: Long = 8L * 1024 * 1024

    /** The collector's element limit for a delta batch. */
    const val DELTA_BATCH_ELEMENT_LIMIT: Long = 500_000

    /** The collector's element limit for a probe manifest. */
    const val MANIFEST_ELEMENT_LIMIT: Long = 100_000

    /** The collector's element limit for a static baseline. */
    const val STATIC_BASELINE_ELEMENT_LIMIT: Long = 200_000

    /** The collector's element limit for a class listing. */
    const val CLASS_LISTING_ELEMENT_LIMIT: Long = 100_000

    /** The share of an element limit a chunk may use. */
    const val MAX_ELEMENT_PERCENT: Long = 90

    /**
     * What a chunk of riders leaves for the manifest around it: the resource, whose values are each
     * at most 1024 bytes, and the manifest's own scalar fields.
     */
    val MANIFEST_FRAME_ALLOWANCE: PayloadWeight = PayloadWeight(64L * 1024, 64)
}

/**
 * Splits [items] into chunks of at most [maxPerChunk] items. Each chunk, with [allowance] for the
 * payload around it, weighs no more than a chunk of [kind] admits. An item that alone breaks a
 * bound gets a chunk of its own.
 */
fun <T> chunkedByWeight(
    items: List<T>,
    maxPerChunk: Int,
    kind: PayloadKind,
    allowance: PayloadWeight,
    weightOf: (T) -> PayloadWeight,
): List<List<T>> {
    val chunks = mutableListOf<List<T>>()
    var chunk = mutableListOf<T>()
    var weight = allowance
    for (item in items) {
        val itemWeight = weightOf(item)
        if (chunk.isNotEmpty() && (chunk.size == maxPerChunk || !kind.admits(weight + itemWeight))) {
            chunks += chunk
            chunk = mutableListOf()
            weight = allowance
        }
        chunk += item
        weight += itemWeight
    }
    if (chunk.isNotEmpty()) chunks += chunk
    return chunks
}

/** A bound a chunk can break, with the words a log line uses for it. */
enum class ChunkBound(
    val description: String,
) {
    ENCODED_BYTES("the limit of ${ChunkLimits.MAX_ENCODED_BYTES / (1024 * 1024)} MiB on encoded bytes"),
    ELEMENTS("the limit on elements"),
}

/** The four payload kinds, each with the collector's element limit for it. */
enum class PayloadKind(
    val collectorElementLimit: Long,
) {
    DELTA_BATCH(ChunkLimits.DELTA_BATCH_ELEMENT_LIMIT),
    MANIFEST(ChunkLimits.MANIFEST_ELEMENT_LIMIT),
    STATIC_BASELINE(ChunkLimits.STATIC_BASELINE_ELEMENT_LIMIT),
    CLASS_LISTING(ChunkLimits.CLASS_LISTING_ELEMENT_LIMIT),
    ;

    /** The most elements a chunk of this kind holds. */
    val maxElements: Long = collectorElementLimit * ChunkLimits.MAX_ELEMENT_PERCENT / 100

    /** The bound [weight] breaks, or null when a chunk of this kind may weigh that much. */
    fun exceededBound(weight: PayloadWeight): ChunkBound? =
        when {
            weight.bytes > ChunkLimits.MAX_ENCODED_BYTES -> ChunkBound.ENCODED_BYTES
            weight.elements > maxElements -> ChunkBound.ELEMENTS
            else -> null
        }

    /** Whether a chunk of this kind may weigh [weight]. */
    fun admits(weight: PayloadWeight): Boolean = exceededBound(weight) == null
}

/**
 * An upper bound on what a part of a payload adds to its encoded size and its element count.
 * [ProtoPayloadCodec] computes it from the encoding the exporter sends, so the sum over the parts
 * of a payload is at least the payload's own size and element count.
 *
 * An element is every embedded message and every element of a repeated field, at every depth. The
 * resource counts as one. A packed repeated scalar counts one per value.
 */
data class PayloadWeight(
    val bytes: Long,
    val elements: Long,
) {
    /** The weight of both parts together. */
    operator fun plus(other: PayloadWeight): PayloadWeight = PayloadWeight(bytes + other.bytes, elements + other.elements)

    companion object {
        val ZERO: PayloadWeight = PayloadWeight(0, 0)
    }
}
