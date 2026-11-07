package dev.otherlode.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefinitionWatchTest {
    private var seq = 100L
    private val next: () -> Long = { ++seq }

    private fun attempt() = DefinitionAttempt(Thread.currentThread(), listOf("x.Definer#defineClass1"))

    private val ended: (DefinitionAttempt) -> Boolean = { true }
    private val running: (DefinitionAttempt) -> Boolean = { false }

    private fun pending(
        attempt: DefinitionAttempt? = attempt(),
        unwatchable: Boolean = false,
    ) = DefinitionWatch(attempt, unwatchable, 1L)

    private fun confirmed() = pending().also { assertTrue(it.confirm()) }

    private fun withheld() =
        pending().also {
            assertEquals(DefinitionWatch.Verdict.MISSED, it.reconcile(false, Long.MAX_VALUE, ended))
            assertEquals(DefinitionWatch.Verdict.WITHHELD, it.reconcile(false, Long.MAX_VALUE, ended))
        }

    @Test
    fun `a new watch is pending with its sequence`() {
        val w = DefinitionWatch(attempt(), false, 7L)
        assertFalse(w.isConfirmed)
        assertFalse(w.isWithheld)
        assertEquals(7L, w.sequence)
        assertTrue(w.hasAttempts())
    }

    @Test
    fun `a watch built without an attempt holds none`() {
        assertFalse(DefinitionWatch(null, false, 1L).hasAttempts())
    }

    @Test
    fun `startAttempt on a pending watch adds the attempt and takes a new sequence`() {
        val w = pending(attempt = null)
        w.startAttempt(attempt(), false, next)
        assertTrue(w.hasAttempts())
        assertEquals(101L, w.sequence)
    }

    @Test
    fun `startAttempt with null adds nothing and takes a new sequence`() {
        val w = pending(attempt = null)
        w.startAttempt(null, false, next)
        assertFalse(w.hasAttempts())
        assertEquals(101L, w.sequence)
    }

    @Test
    fun `startAttempt with the same attempt twice changes nothing the second time`() {
        val a = attempt()
        val w = pending(attempt = a)
        w.startAttempt(a, false, next)
        w.startAttempt(a, false, next)
        assertEquals(1L, w.sequence)
        var seen = 0
        w.reconcile(false, Long.MAX_VALUE) {
            seen++
            true
        }
        assertEquals(1, seen)
    }

    @Test
    fun `startAttempt unwatchable stops misses`() {
        val w = pending()
        w.startAttempt(null, true, next)
        assertEquals(DefinitionWatch.Verdict.PENDING, w.reconcile(false, Long.MAX_VALUE, ended))
        assertEquals(DefinitionWatch.Verdict.PENDING, w.reconcile(false, Long.MAX_VALUE, ended))
        assertFalse(w.isWithheld)
    }

    @Test
    fun `startAttempt clears a miss`() {
        val w = pending()
        assertEquals(DefinitionWatch.Verdict.MISSED, w.reconcile(false, Long.MAX_VALUE, ended))
        w.startAttempt(attempt(), false, next)
        assertEquals(DefinitionWatch.Verdict.MISSED, w.reconcile(false, Long.MAX_VALUE, ended))
        assertFalse(w.isWithheld)
    }

    @Test
    fun `startAttempt on a withheld watch clears the withhold`() {
        val w = withheld()
        w.startAttempt(attempt(), false, next)
        assertFalse(w.isWithheld)
        assertFalse(w.isConfirmed)
        assertEquals(101L, w.sequence)
    }

    @Test
    fun `startAttempt on a confirmed watch does nothing`() {
        val w = confirmed()
        w.startAttempt(attempt(), true, next)
        assertTrue(w.isConfirmed)
        assertEquals(1L, w.sequence)
        assertFalse(w.hasAttempts())
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(false, Long.MAX_VALUE, ended))
    }

    @Test
    fun `confirm returns true once and clears attempts`() {
        val w = pending()
        assertTrue(w.confirm())
        assertFalse(w.confirm())
        assertTrue(w.isConfirmed)
        assertFalse(w.hasAttempts())
    }

    @Test
    fun `confirm lifts a withhold`() {
        val w = withheld()
        assertTrue(w.confirm())
        assertTrue(w.isConfirmed)
        assertFalse(w.isWithheld)
    }

    @Test
    fun `reconcile confirms a defined pending watch even while its attempt runs`() {
        val w = pending()
        assertEquals(DefinitionWatch.Verdict.CONFIRMED, w.reconcile(true, Long.MAX_VALUE, running))
        assertTrue(w.isConfirmed)
        assertFalse(w.hasAttempts())
    }

    @Test
    fun `reconcile skips a confirmed watch`() {
        val w = confirmed()
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(true, Long.MAX_VALUE, ended))
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(false, Long.MAX_VALUE, ended))
    }

    @Test
    fun `reconcile skips a withheld watch even when defined`() {
        val w = withheld()
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(true, Long.MAX_VALUE, ended))
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(false, Long.MAX_VALUE, ended))
        assertTrue(w.isWithheld)
    }

    @Test
    fun `reconcile skips a watch registered after the snapshot`() {
        val w = DefinitionWatch(attempt(), false, 5L)
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(true, 4L, ended))
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(false, 4L, ended))
        assertFalse(w.isConfirmed)
        assertEquals(DefinitionWatch.Verdict.CONFIRMED, w.reconcile(true, 5L, ended))
    }

    @Test
    fun `reconcile leaves a watch pending while an attempt runs`() {
        val w = pending()
        repeat(3) { assertEquals(DefinitionWatch.Verdict.PENDING, w.reconcile(false, Long.MAX_VALUE, running)) }
        assertFalse(w.isWithheld)
    }

    @Test
    fun `reconcile leaves an unwatchable watch pending`() {
        val w = pending(attempt = null, unwatchable = true)
        repeat(3) { assertEquals(DefinitionWatch.Verdict.PENDING, w.reconcile(false, Long.MAX_VALUE, ended)) }
        assertFalse(w.isWithheld)
    }

    @Test
    fun `reconcile counts a miss against a watch with no attempt`() {
        val w = pending(attempt = null)
        assertEquals(DefinitionWatch.Verdict.MISSED, w.reconcile(false, Long.MAX_VALUE, running))
        assertEquals(DefinitionWatch.Verdict.WITHHELD, w.reconcile(false, Long.MAX_VALUE, running))
    }

    @Test
    fun `two misses withhold`() {
        val w = pending()
        assertEquals(DefinitionWatch.Verdict.MISSED, w.reconcile(false, Long.MAX_VALUE, ended))
        assertFalse(w.isWithheld)
        assertEquals(DefinitionWatch.Verdict.WITHHELD, w.reconcile(false, Long.MAX_VALUE, ended))
        assertTrue(w.isWithheld)
        assertFalse(w.isConfirmed)
    }

    @Test
    fun `a miss drops the ended attempts`() {
        val w = pending()
        w.reconcile(false, Long.MAX_VALUE, ended)
        assertFalse(w.hasAttempts())
    }

    @Test
    fun `an attempt added between the read and the miss drops the miss`() {
        val w = pending()
        val verdict =
            w.reconcile(false, Long.MAX_VALUE) {
                w.startAttempt(attempt(), false, next)
                true
            }
        assertEquals(DefinitionWatch.Verdict.PENDING, verdict)
        assertEquals(DefinitionWatch.Verdict.MISSED, w.reconcile(false, Long.MAX_VALUE, ended))
        assertEquals(DefinitionWatch.Verdict.WITHHELD, w.reconcile(false, Long.MAX_VALUE, ended))
    }

    @Test
    fun `a confirmation between the read and the miss drops the miss`() {
        val w = pending()
        val verdict =
            w.reconcile(false, Long.MAX_VALUE) {
                w.confirm()
                true
            }
        assertEquals(DefinitionWatch.Verdict.PENDING, verdict)
        assertTrue(w.isConfirmed)
        assertFalse(w.isWithheld)
    }

    @Test
    fun `a retry that moves the sequence past the snapshot drops the miss`() {
        val w = pending()
        val verdict =
            w.reconcile(false, 1L) {
                w.startAttempt(null, false, next)
                true
            }
        assertEquals(DefinitionWatch.Verdict.PENDING, verdict)
        assertEquals(DefinitionWatch.Verdict.SKIPPED, w.reconcile(false, 1L, ended))
    }

    @Test
    fun `markReported is true once`() {
        val w = pending()
        assertTrue(w.markReported())
        assertFalse(w.markReported())
    }

    @Test
    fun `markReported survives a lifted withhold`() {
        val w = withheld()
        assertTrue(w.markReported())
        w.startAttempt(attempt(), false, next)
        assertFalse(w.markReported())
    }

    @Test
    fun `a watch stays unwatchable when a later transform has an attempt`() {
        val w = pending(attempt = null, unwatchable = true)
        w.startAttempt(attempt(), false, next)
        assertEquals(DefinitionWatch.Verdict.PENDING, w.reconcile(false, Long.MAX_VALUE, ended))
    }

    @Test
    fun `a retry that makes the watch unwatchable between the read and the miss drops the miss`() {
        val w = pending()
        val verdict = w.reconcile(false, Long.MAX_VALUE) {
            w.startAttempt(null, true, next)
            true
        }
        assertEquals(DefinitionWatch.Verdict.PENDING, verdict)
    }

    @Test
    fun `reconcile reads its attempts outside the lock`() {
        val w = pending()
        var otherFinished = false
        w.reconcile(false, Long.MAX_VALUE) {
            val other = Thread { w.startAttempt(attempt(), false, next) }
            other.start()
            other.join(5_000)
            otherFinished = !other.isAlive
            true
        }
        assertTrue(otherFinished, "another thread took the watch's lock while attemptEnded ran")
    }
}
