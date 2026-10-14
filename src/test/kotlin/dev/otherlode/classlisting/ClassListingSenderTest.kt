package dev.otherlode.classlisting

import dev.otherlode.export.ClassListing
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.ExportFailedException
import dev.otherlode.export.Exporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassListingSenderTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")

    private fun chunks(count: Int) =
        List(
            count,
        ) { ClassListing(resource, listOf("com.acme.C$it"), IncludeRules(listOf("com.acme")), 1L, chunkIndex = it, chunkCount = count) }

    private class CountingExporter(
        var failure: (ClassListing) -> Exception? = { null },
    ) : Exporter {
        val attempts = mutableListOf<Int>()

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {}

        override fun exportClassListing(listing: ClassListing) {
            attempts += listing.chunkIndex
            failure(listing)?.let { throw it }
        }
    }

    @Test
    fun `a chunk the collector refuses for its content is dropped with the rest, and never sent again`() {
        for (status in listOf(400, 422)) {
            val exporter = CountingExporter { ExportFailedException("refused", status, refused = true) }
            val sender = ClassListingSender(exporter)
            sender.offer(chunks(2))

            assertTrue(sender.sendPending(), "nothing is left to send once the collector refuses the listing, status $status")
            repeat(4) { sender.sendPending() }

            assertEquals(listOf(0), exporter.attempts, "status $status")
        }
    }

    @Test
    fun `a refusal about the route, the credentials or the proxy, not the bytes, keeps the chunks`() {
        for (status in listOf(401, 403, 404, 413, 503)) {
            var refusals = 1
            val exporter = CountingExporter { if (refusals-- > 0) ExportFailedException("refused", status) else null }
            val sender = ClassListingSender(exporter)
            sender.offer(chunks(1))

            assertFalse(sender.sendPending(), "status $status")
            assertTrue(sender.sendPending(), "status $status")
            assertEquals(listOf(0, 0), exporter.attempts, "status $status")
        }
    }

    @Test
    fun `the first send goes through the first exporter and a retry through the retry exporter, one chunk at a time`() {
        val first = CountingExporter { if (it.chunkIndex == 1) ExportFailedException("timed out", null) else null }
        val retry = CountingExporter()
        val sender = ClassListingSender(first, retryExporter = retry)
        sender.offer(chunks(3))

        assertFalse(sender.sendPending())
        assertFalse(sender.retryPending())
        assertTrue(sender.retryPending())

        assertEquals(listOf(0, 1), first.attempts)
        assertEquals(listOf(1, 2), retry.attempts)
    }

    @Test
    fun `with nothing offered a send has nothing to do and succeeds`() {
        val exporter = CountingExporter()

        assertTrue(ClassListingSender(exporter).sendPending())
        assertEquals(emptyList(), exporter.attempts)
    }
}
