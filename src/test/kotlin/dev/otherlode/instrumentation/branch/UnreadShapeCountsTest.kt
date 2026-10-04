package dev.otherlode.instrumentation.branch

import dev.otherlode.export.UnreadShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnreadShapeCountsTest {
    @Test
    fun `counts add up by family once per class name, and a class is warned about once, when a release names it`() {
        val counts = UnreadShapeCounts()

        assertTrue(counts.record("a.A", "3.10.0", UnreadCause.UNREAD_RELEASE, mapOf(UnreadShape.CASE_CLASS to 2)))
        assertEquals(
            false,
            counts.record("a.A", "3.10.0", UnreadCause.UNREAD_RELEASE, mapOf(UnreadShape.CASE_CLASS to 2)),
            "a second loader's copy",
        )
        assertEquals(
            false,
            counts.record("a.B", null, UnreadCause.VERSION_BLIND, mapOf(UnreadShape.SCALA_ENUM to 4)),
            "version-blind: no warning",
        )
        assertEquals(false, counts.record("a.C", null, UnreadCause.VERSION_BLIND, emptyMap()))

        assertEquals(6L, counts.total(), "a.A counts once although two loaders defined it")
        assertEquals(2L, counts.countOf(UnreadShape.CASE_CLASS))
        assertEquals(4L, counts.countOf(UnreadShape.SCALA_ENUM))
        assertEquals(4L, counts.countOf(UnreadCause.VERSION_BLIND))
        assertEquals(listOf("3.10.0"), counts.unreadReleases())
        assertEquals(2, counts.classes())
    }

    @Test
    fun `a class first recorded with no release is still warned about when a later copy names one`() {
        val counts = UnreadShapeCounts()

        assertEquals(false, counts.record("a.A", null, UnreadCause.VERSION_BLIND, mapOf(UnreadShape.CASE_CLASS to 2)))
        assertTrue(counts.record("a.A", "3.10.0", UnreadCause.UNREAD_RELEASE, mapOf(UnreadShape.CASE_CLASS to 2)))
        assertEquals(listOf("3.10.0"), counts.unreadReleases())
    }

    @Test
    fun `outcomes add up by family once per class name and stay apart from the method totals`() {
        val counts = UnreadShapeCounts()

        counts.recordOutcomes("a.A", mapOf(UnreadShape.COROUTINE_MACHINERY to 4, UnreadShape.SWITCH_LOWERING to 2))
        counts.recordOutcomes("a.A", mapOf(UnreadShape.COROUTINE_MACHINERY to 4))
        counts.recordOutcomes("a.B", mapOf(UnreadShape.SWITCH_LOWERING to 1))
        counts.recordOutcomes("a.C", emptyMap())

        assertEquals(7L, counts.outcomeTotal())
        assertEquals(4L, counts.outcomeCountOf(UnreadShape.COROUTINE_MACHINERY))
        assertEquals(3L, counts.outcomeCountOf(UnreadShape.SWITCH_LOWERING))
        assertEquals(2, counts.outcomeClasses())
        assertEquals(0L, counts.total())
        assertEquals(0, counts.classes())
    }

    @Test
    fun `a class analysed from received bytes is counted once however many loaders define it`() {
        val counts = UnreadShapeCounts()

        counts.recordReceivedBytesClass("a.A")
        counts.recordReceivedBytesClass("a.A")
        counts.recordReceivedBytesClass("a.B")

        assertEquals(2, counts.receivedBytesClasses())
    }
}
