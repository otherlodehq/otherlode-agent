package dev.otherlode.export

/**
 * The limits a payload chunk is built under: an element limit per payload kind, the share of it a
 * chunk may use, and a cap on encoded bytes.
 */
object ChunkLimits {
    // The element limits must equal ingest.Max*Elements in otherlode-collector, which refuses a payload
    // over one with 413. MAX_ENCODED_BYTES is half the collector's body limit.

    /** The most encoded bytes a chunk holds. */
    const val MAX_ENCODED_BYTES: Long = 8L * 1024 * 1024

    /** The element limit for a delta batch. */
    const val DELTA_BATCH_ELEMENT_LIMIT: Long = 500_000

    /** The element limit for a probe manifest. */
    const val MANIFEST_ELEMENT_LIMIT: Long = 100_000

    /** The element limit for a static baseline. */
    const val STATIC_BASELINE_ELEMENT_LIMIT: Long = 200_000

    /** The element limit for a class listing. */
    const val CLASS_LISTING_ELEMENT_LIMIT: Long = 100_000

    /** The share of an element limit a chunk may use. */
    const val MAX_ELEMENT_PERCENT: Long = 90

    /**
     * The weight a chunk of manifest entries reserves for the manifest around it: a resource whose
     * values are each at most 1024 bytes, and the manifest's own scalar fields.
     */
    val MANIFEST_FRAME_ALLOWANCE: PayloadWeight = PayloadWeight(64L * 1024, 64)
}

/**
 * Splits [items], in order, into chunks of at most [maxPerChunk] items. [kind] admits each chunk's
 * weight plus [allowance], except a chunk of one item that breaks a bound once [allowance] is added.
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

/** A payload kind, with its element limit. */
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
 * An encoded size in [bytes] and an element count, used as an upper bound for part of a payload. An
 * element is an embedded message or one entry of a repeated field, at any depth.
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
