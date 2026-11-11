package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.ExternalClass
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.PayloadWeight
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import dev.otherlode.export.UnprobedClass
import dev.otherlode.export.UnreadableClass

/**
 * Splits one [StaticScanResult] into [StaticBaseline] chunks of bounded size. A chunk closes when the
 * next entry would take it past [maxEntriesPerChunk], or past the encoded bytes or element count that
 * [PayloadKind.STATIC_BASELINE] allows, measured by [ProtoPayloadCodec]. A declared class weighs one, plus
 * its methods, their calls, its branch sites by [dev.otherlode.export.BranchSite.chunkWeight], its referenced
 * classes and the mappings it carries. An unreadable or unprobed class weighs one.
 *
 * Classes are never split across chunks. A declared class heavier than [maxEntriesPerChunk] gets a chunk
 * of its own. A class that breaks the byte or element bound alone makes [chunk] throw
 * [ClassTooLargeException] and produce no chunk. A mapping in [StaticScanResult.externalClasses] travels
 * in the chunk of the first declared class that references its name. Every chunk carries the same
 * resource, [includeRules] and [scannedAt], and its own `chunkIndex` out of `chunkCount`.
 *
 * [chunk] produces at least one chunk, so an empty scan is still a statement.
 */
object StaticBaselineChunker {
    fun chunk(
        result: StaticScanResult,
        resource: ResourceAttributes,
        scannedAt: Long,
        maxEntriesPerChunk: Int,
        includeRules: IncludeRules? = null,
    ): List<StaticBaseline> {
        require(maxEntriesPerChunk > 0) { "maxEntriesPerChunk must be positive" }
        val kind = PayloadKind.STATIC_BASELINE
        val frame = ProtoPayloadCodec.weightOf(StaticBaseline(resource, emptyList(), scannedAt = scannedAt, includeRules = includeRules))
        val mappings = result.externalClasses.associateBy { it.className }
        val placed = HashSet<String>()
        val chunks = mutableListOf<Chunk>()
        var current = Chunk(frame)

        fun place(
            className: String,
            weight: Int,
            encoded: PayloadWeight,
            add: Chunk.() -> Unit,
        ) {
            kind.exceededBound(frame + encoded)?.let { throw ClassTooLargeException(className, it) }
            if (current.size > 0 && (current.size + weight > maxEntriesPerChunk || !kind.admits(current.encoded + encoded))) {
                chunks += current
                current = Chunk(frame)
            }
            current.add()
            current.size += weight
            current.encoded += encoded
        }

        result.declaredClasses.forEach { c ->
            val references = c.methods.sumOf { it.referencedClasses.size } + c.referencedClasses.size
            val sites = c.methods.sumOf { method -> method.branchSites.sumOf { it.chunkWeight } }
            val carried =
                (c.methods.asSequence().flatMap { it.referencedClasses } + c.referencedClasses)
                    .filter { it in mappings && placed.add(it) }
                    .map { mappings.getValue(it) }
                    .toList()
            val weight = (c.methods.size + c.methods.sumOf { it.calls.size } + sites + 1 + references + carried.size).coerceAtLeast(1)
            val encoded = carried.fold(ProtoPayloadCodec.entryWeight(c)) { total, mapping -> total + ProtoPayloadCodec.baselineEntryWeight(mapping) }
            place(c.className, weight, encoded) {
                declared += c
                external += carried
            }
        }
        result.unreadableClasses.forEach { c -> place(c.className, 1, ProtoPayloadCodec.entryWeight(c)) { unreadable += c } }
        result.unprobedClasses.forEach { c -> place(c.className, 1, ProtoPayloadCodec.entryWeight(c)) { unprobed += c } }
        if (current.size > 0 || chunks.isEmpty()) chunks += current

        return chunks.mapIndexed { index, chunk ->
            StaticBaseline(
                resource = resource,
                declaredClasses = chunk.declared,
                unreadableClasses = chunk.unreadable,
                unprobedClasses = chunk.unprobed,
                externalClasses = chunk.external,
                scannedAt = scannedAt,
                chunkIndex = index,
                chunkCount = chunks.size,
                includeRules = includeRules,
            )
        }
    }

    private class Chunk(
        var encoded: PayloadWeight,
    ) {
        val declared = mutableListOf<DeclaredClass>()
        val unreadable = mutableListOf<UnreadableClass>()
        val unprobed = mutableListOf<UnprobedClass>()
        val external = mutableListOf<ExternalClass>()
        var size = 0
    }
}
