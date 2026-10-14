package dev.otherlode.classlisting

import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
