package dev.otherlode.registry

import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchRole
import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.ChunkLimits
import dev.otherlode.export.ConditionPart
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeRegistryChunkBoundsTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")
    private val skipReason = "too large to report: its manifest entries exceed the collector's limits"

    private fun small(count: Int = 2) = List(count) { ProbeMeta(ProbeKind.METHOD, "method$it", "()V", line = it) }

    private fun literalProbes(literals: Int): List<ProbeMeta> {
        val literal = "x".repeat(65_535)
        val site =
            BranchSite(
                siteIndex = 0,
                siteKey = null,
                line = 1,
                condition = List(literals) { ConditionPart(ConditionPartKind.STRING_LITERAL, literal) },
                outcomes = listOf(BranchOutcome(0, BranchRole.TAKEN), BranchOutcome(1, BranchRole.FALL_THROUGH)),
            )
        return listOf(ProbeMeta(ProbeKind.BRANCH, "m", "()V", line = 1, branchIndex = 0, branchSites = listOf(site)))
    }

    private fun edgeProbes(edges: Int): List<ProbeMeta> {
        val edge = CallEdge("com.example.Callee", "c", "()V", virtual = false)
        return listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", line = 1, calls = List(edges) { edge }))
    }

    private fun assertWithinBounds(
        registry: ProbeRegistry,
        maxEntries: Int = 1_000_000,
    ) = registry.computeManifestDeltas(resource, maxEntries).forEach {
        val weight = ProtoPayloadCodec.weightOf(it.manifest)
        assertTrue(ProtoPayloadCodec.encode(it.manifest).size <= ChunkLimits.MAX_ENCODED_BYTES)
        assertTrue(weight.elements <= PayloadKind.MANIFEST.maxElements, "a chunk holds ${weight.elements} elements")
    }

    @Test
    fun `long string constants split manifest chunks by encoded bytes before the entry cap`() {
        val registry = ProbeRegistry()
        (1..4).forEach { registry.register("com.example.Long$it", 1L, literalProbes(76)) }

        val chunks = registry.computeManifestDeltas(resource, maxEntriesPerChunk = 1_000_000).toList()

        assertEquals(listOf(1, 1, 1, 1), chunks.map { it.manifest.probes.size })
        assertWithinBounds(registry)
    }

    @Test
    fun `many small nested entries split manifest chunks by element count before the byte cap`() {
        val registry = ProbeRegistry()
        (1..3).forEach { registry.register("com.example.Wide$it", 1L, edgeProbes(40_000)) }

        val chunks = registry.computeManifestDeltas(resource, maxEntriesPerChunk = 10_000_000).toList()

        assertEquals(listOf(2, 1), chunks.map { it.manifest.probes.size })
        assertWithinBounds(registry, 10_000_000)
    }

    @Test
    fun `an unconfirmed class too large to report is not reported as skipped`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register("com.example.Huge", 1L, literalProbes(140))

        val chunks = registry.computeManifestDeltas(resource, maxEntriesPerChunk = 1_000_000).toList()

        assertTrue(chunks.flatMap { it.manifest.skippedClasses }.none { it.className == "com.example.Huge" })
    }

    @Test
    fun `a class whose entries alone exceed the byte bound goes out as a skipped class with a fixed reason`() {
        val registry = ProbeRegistry()
        registry.register("com.example.First", 1L, small())
        registry.register("com.example.Huge", 1L, literalProbes(140))
        registry.register("com.example.Last", 1L, small())

        val chunks = registry.computeManifestDeltas(resource, maxEntriesPerChunk = 1_000_000).toList()

        val manifests = chunks.map { it.manifest }
        assertEquals(setOf("com.example.First", "com.example.Last"), manifests.flatMap { m -> m.probes.map { it.className } }.toSet())
        val skipped = manifests.flatMap { it.skippedClasses }
        assertEquals(listOf("com.example.Huge"), skipped.map { it.className })
        assertEquals(skipReason, skipped.single().reason)
        assertEquals(2, manifests.sumOf { it.classLocations.size }, "the huge class sends no class location")
        chunks.forEach { registry.advanceManifestBaseline(it) }
        assertEquals(0, registry.classesHoldingMetadata(), "the huge class's metadata is released with the rest")
        assertTrue(registry.computeManifestDeltas(resource, 1_000_000).toList().isEmpty())
    }

    @Test
    fun `a class whose entries alone exceed the element bound goes out as a skipped class`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Wide", 1L, edgeProbes(95_000))
        registry.register("com.example.Normal", 1L, small())

        val manifests = registry.computeManifestDeltas(resource, 10_000_000).map { it.manifest }.toList()

        assertEquals(listOf("com.example.Wide"), manifests.flatMap { it.skippedClasses }.map { it.className })
        assertEquals(listOf("com.example.Normal"), manifests.flatMap { m -> m.probes.map { it.className } }.distinct())
    }

    @Test
    fun `the chunks behind an oversize class go out in the same flush`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Huge", 1L, literalProbes(140))
        (1..3).forEach { registry.register("com.example.Behind$it", 1L, small(1)) }

        val chunks = registry.computeManifestDeltas(resource, maxEntriesPerChunk = 2).toList()

        assertEquals(3, chunks.sumOf { it.manifest.probes.size })
        assertEquals(1, chunks.sumOf { it.manifest.skippedClasses.size })
    }

    @Test
    fun `the hits of an oversize class appear in no delta batch, before or after its manifest`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Normal", 1L, small(1))[0] = 3L
        registry.register("com.example.Huge", 1L, literalProbes(140))[0] = 9L

        val before = registry.computeDeltaBatches(resource, 100).flatMap { it.batch.deltas }
        registry.computeManifestDeltas(resource, 1_000_000).toList().forEach { registry.advanceManifestBaseline(it) }
        val after = registry.computeDeltaBatches(resource, 100).flatMap { it.batch.deltas }

        assertEquals(listOf(3L), before.map { it.hitsTotal })
        assertEquals(listOf(3L), after.map { it.hitsTotal })
    }

    @Test
    fun `a class delivered in an earlier manifest keeps its hits`() {
        val registry = ProbeRegistry()
        val counts = registry.register("com.example.Normal", 1L, small(1))
        registry.computeManifestDeltas(resource, 1_000_000).toList().forEach { registry.advanceManifestBaseline(it) }

        counts[0] = 4L

        assertEquals(listOf(4L), registry.computeDeltaBatches(resource, 100).flatMap { it.batch.deltas }.map { it.hitsTotal })
    }

    @Test
    fun `delta batches close by encoded bytes when the entry cap is far above them`() {
        val registry = ProbeRegistry()
        val probes = List(80_000) { ProbeMeta(ProbeKind.METHOD, "m", "()V", line = 1) }
        (1..5).forEach { registry.register("com.example.Busy$it", 1L, probes).fill(Long.MAX_VALUE / 2) }

        val batches = registry.computeDeltaBatches(resource, Int.MAX_VALUE)

        assertTrue(batches.size >= 2, "400,000 deltas do not fit one batch")
        assertEquals(400_000, batches.sumOf { it.batch.deltas.size })
        batches.forEach {
            val weight = ProtoPayloadCodec.weightOf(it.batch)
            assertTrue(ProtoPayloadCodec.encode(it.batch).size <= ChunkLimits.MAX_ENCODED_BYTES)
            assertTrue(weight.elements <= PayloadKind.DELTA_BATCH.maxElements)
            assertTrue(it.weight.bytes <= ChunkLimits.MAX_ENCODED_BYTES)
        }
    }
}
