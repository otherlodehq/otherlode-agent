package dev.otherlode.classlisting

import dev.otherlode.export.ChunkLimits
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassListingChunkerTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1", flushIntervalSeconds = 30)
    private val rules = IncludeRules(listOf("com.acme"), listOf("com.acme.internal"))

    private fun names(count: Int) = List(count) { "com.acme.C%05d".format(it) }

    @Test
    fun `no chunk holds more than the cap, and the sorted order is kept across chunks`() {
        val all = names(7)

        val chunks = ClassListingChunker.chunk(all, resource, rules, listedAt = 42L, maxNamesPerChunk = 3)

        assertEquals(listOf(3, 3, 1), chunks.map { it.classNames.size })
        assertEquals(all, chunks.flatMap { it.classNames })
    }

    @Test
    fun `every chunk carries the resource, the rules and the time, and numbers itself`() {
        val chunks = ClassListingChunker.chunk(names(5), resource, rules, listedAt = 42L, maxNamesPerChunk = 2)

        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
        assertEquals(setOf(3), chunks.map { it.chunkCount }.toSet())
        assertEquals(setOf(resource), chunks.map { it.resource }.toSet())
        assertEquals(setOf(rules), chunks.map { it.includeRules }.toSet())
        assertEquals(setOf(42L), chunks.map { it.listedAt }.toSet())
    }

    @Test
    fun `a listing of exactly the cap is one chunk`() {
        val chunks = ClassListingChunker.chunk(names(3), resource, rules, listedAt = 1L, maxNamesPerChunk = 3)

        assertEquals(1, chunks.size)
        assertEquals(1, chunks.single().chunkCount)
    }

    @Test
    fun `an empty listing is still one chunk, since it is a statement`() {
        val chunks = ClassListingChunker.chunk(emptyList(), resource, rules, listedAt = 9L)

        assertEquals(1, chunks.size)
        assertEquals(emptyList(), chunks.single().classNames)
        assertEquals(0, chunks.single().chunkIndex)
        assertEquals(1, chunks.single().chunkCount)
        assertEquals(rules, chunks.single().includeRules)
    }

    @Test
    fun `the default cap is 20000 names`() {
        val chunks = ClassListingChunker.chunk(names(20_001), resource, rules, listedAt = 1L)

        assertEquals(listOf(20_000, 1), chunks.map { it.classNames.size })
    }

    @Test
    fun `a cap below one is refused`() {
        assertFailsWith<IllegalArgumentException> { ClassListingChunker.chunk(names(1), resource, rules, 1L, maxNamesPerChunk = 0) }
    }

    @Test
    fun `long names split a chunk by encoded bytes long before the name cap`() {
        val all = List(300) { "com.acme." + "N".repeat(60_000) + "%04d".format(it) }

        val chunks = ClassListingChunker.chunk(all, resource, rules, listedAt = 1L)

        assertTrue(chunks.size >= 3, "300 names of 60,000 bytes are 18 MB, so more than two chunks")
        assertEquals(all, chunks.flatMap { it.classNames })
        chunks.forEach { assertTrue(ProtoPayloadCodec.encode(it).size <= ChunkLimits.MAX_ENCODED_BYTES) }
        assertEquals(setOf(chunks.size), chunks.map { it.chunkCount }.toSet())
    }

    @Test
    fun `a name of the longest length is never split and always fits`() {
        val longest = "n.".padEnd(65_535, 'N')

        val chunks = ClassListingChunker.chunk(listOf(longest), resource, rules, listedAt = 1L)

        assertEquals(listOf(listOf(longest)), chunks.map { it.classNames })
    }

    @Test
    fun `many short names split a chunk by element count when the name cap is above it`() {
        val all = names(100_000)

        val chunks = ClassListingChunker.chunk(all, resource, rules, listedAt = 1L, maxNamesPerChunk = 1_000_000)

        assertTrue(chunks.size >= 2)
        assertEquals(all, chunks.flatMap { it.classNames })
        chunks.forEach {
            val weight = ProtoPayloadCodec.weightOf(it)
            assertTrue(weight.elements <= PayloadKind.CLASS_LISTING.maxElements, "a chunk holds ${weight.elements} elements")
            assertTrue(ProtoPayloadCodec.encode(it).size <= ChunkLimits.MAX_ENCODED_BYTES)
        }
        assertEquals(
            PayloadKind.CLASS_LISTING.maxElements,
            ProtoPayloadCodec.weightOf(chunks.first()).elements,
            "the first chunk is filled to the bound",
        )
    }
}
