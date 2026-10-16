package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeVerifierMessages
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring's behaviour. Each check lives in [RingChecks], which loads only after the bootstrap
 * holder is installed, since this class is loaded for discovery before any test runs.
 */
class OtherlodeVerifierMessagesTest {
    @BeforeEach
    fun installBootstrapHolder() {
        BootstrapHolder.install(ByteBuddyAgent.install())
    }

    @Test
    fun `drain returns each message once and in the order recorded by one thread`() = RingChecks.drainReturnsEachOnce()

    @Test
    fun `a message recorded after a drain is returned by the next one only`() = RingChecks.laterMessageComesNext()

    @Test
    fun `a write that finds the ring full counts one overflow and keeps what was stored`() = RingChecks.overflowCounts()

    @Test
    fun `the shared ring holds 8192 messages between drains`() = RingChecks.sharedRingCapacity()

    @Test
    fun `concurrent writers lose nothing below capacity and every message is drained once`() = RingChecks.concurrentWriters()

    @Test
    fun `concurrent writers racing a drain lose nothing between them`() = RingChecks.writersRacingDrain()

    @Test
    fun `a writer that claims after its array was let go counts an overflow`() = RingChecks.stragglerAfterSeal()

    @Test
    fun `a slot claimed but not yet stored holds back the slots after it until it is stored`() = RingChecks.gapIsNotSkipped()

    @Test
    fun `a message stored two drains after its claim is still returned`() = RingChecks.lateStoreArrives()

    @Test
    fun `a slot claimed and never stored counts as overflow once its array ages out`() = RingChecks.neverStoredAgesOut()

    @Test
    fun `a message equal to one already stored is kept once, however often it repeats`() = RingChecks.repeatsKeptOnce()

    @Test
    fun `two different messages with the same hash code are both kept`() = RingChecks.hashCollisionKeepsBoth()

    @Test
    fun `a message lost to overflow is kept when it comes again`() = RingChecks.overflowedMessageComesAgain()

    @Test
    fun `a slot is cleared once it is read, so a drained message is not held`() = RingChecks.readSlotIsCleared()

    private object RingChecks {
        fun readSlotIsCleared() {
            val ring = OtherlodeVerifierMessages.Ring(4)
            val target = ring.writeTarget()
            ring.record("held")
            ring.drain()

            val messages =
                target.javaClass
                    .getDeclaredField("messages")
                    .apply { isAccessible = true }
                    .get(target) as Array<*>
            assertEquals(null, messages[0])
        }

        fun repeatsKeptOnce() {
            val ring = OtherlodeVerifierMessages.Ring(8)
            repeat(10_000) { ring.record("rejected") }
            ring.record("other")

            assertEquals(listOf("rejected", "other"), ring.drain().toList())
            assertEquals(0L, ring.takeOverflow())
            ring.record("rejected")
            assertEquals(emptyList(), ring.drain().toList())
        }

        fun hashCollisionKeepsBoth() {
            assertEquals("Aa".hashCode(), "BB".hashCode())
            val ring = OtherlodeVerifierMessages.Ring(8)
            ring.record("Aa")
            ring.record("BB")
            ring.record("Aa")

            assertEquals(listOf("Aa", "BB", "Aa"), ring.drain().toList())
        }

        fun overflowedMessageComesAgain() {
            val ring = OtherlodeVerifierMessages.Ring(1)
            ring.record("first")
            ring.record("lost")

            assertEquals(listOf("first"), ring.drain().toList())
            assertEquals(1L, ring.takeOverflow())
            ring.record("lost")
            assertEquals(listOf("lost"), ring.drain().toList())
        }

        fun stragglerAfterSeal() {
            val ring = OtherlodeVerifierMessages.Ring(4)
            ring.record("first")
            val paused = ring.writeTarget()
            val drained = ring.drain().size + ring.drain().size + ring.drain().size

            val index = ring.claim(paused)
            if (index >= 0) ring.store(paused, index, "straggler")
            val later = (1..4).sumOf { ring.drain().size }

            assertEquals(1, drained + later)
            assertEquals(1L, ring.takeOverflow())
        }

        fun gapIsNotSkipped() {
            val ring = OtherlodeVerifierMessages.Ring(4)
            val target = ring.writeTarget()
            val first = ring.claim(target)
            ring.record("second")

            assertEquals(emptyList(), ring.drain().toList())
            ring.store(target, first, "first")
            assertEquals(listOf("first", "second"), ring.drain().toList())
            assertEquals(0L, ring.takeOverflow())
        }

        fun lateStoreArrives() {
            val ring = OtherlodeVerifierMessages.Ring(4)
            val target = ring.writeTarget()
            val index = ring.claim(target)
            ring.drain()
            ring.drain()

            ring.store(target, index, "late")

            assertEquals(listOf("late"), ring.drain().toList())
            assertEquals(0L, ring.takeOverflow())
        }

        fun neverStoredAgesOut() {
            val ring = OtherlodeVerifierMessages.Ring(4)
            ring.claim(ring.writeTarget())
            repeat(3) { assertEquals(emptyList(), ring.drain().toList()) }

            assertEquals(1L, ring.takeOverflow())
        }

        fun drainReturnsEachOnce() {
            val ring = OtherlodeVerifierMessages.Ring(8)
            ring.record("a")
            ring.record("b")
            ring.record(null)
            ring.record("c")

            assertEquals(listOf("a", "b", "c"), ring.drain().toList())
            assertTrue(ring.drain().isEmpty())
            assertEquals(0L, ring.takeOverflow())
        }

        fun laterMessageComesNext() {
            val ring = OtherlodeVerifierMessages.Ring(4)
            ring.record("a")
            ring.drain()
            ring.record("b")

            assertEquals(listOf("b"), ring.drain().toList())
            assertTrue(ring.drain().isEmpty())
        }

        fun overflowCounts() {
            val ring = OtherlodeVerifierMessages.Ring(3)
            repeat(5) { ring.record("m$it") }

            assertEquals(listOf("m0", "m1", "m2"), ring.drain().toList())
            assertEquals(2L, ring.takeOverflow())
            assertEquals(0L, ring.takeOverflow())
            ring.record("again")
            assertEquals(listOf("again"), ring.drain().toList())
        }

        fun sharedRingCapacity() {
            OtherlodeVerifierMessages.drain()
            OtherlodeVerifierMessages.takeOverflow()
            repeat(OtherlodeVerifierMessages.CAPACITY + 3) { OtherlodeVerifierMessages.record("m$it") }

            assertEquals(8192, OtherlodeVerifierMessages.drain().size)
            assertEquals(3L, OtherlodeVerifierMessages.takeOverflow())
        }

        fun concurrentWriters() {
            val writers = 8
            val perWriter = 500
            val ring = OtherlodeVerifierMessages.Ring(writers * perWriter)
            val pool = Executors.newFixedThreadPool(writers)
            val start = CountDownLatch(1)
            try {
                repeat(writers) { writer ->
                    pool.submit {
                        start.await()
                        repeat(perWriter) { ring.record("w$writer-$it") }
                    }
                }
                start.countDown()
                pool.shutdown()
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }

            val drained = ring.drain().toList()
            assertEquals(writers * perWriter, drained.size)
            assertEquals(writers * perWriter, drained.toSet().size)
            assertEquals(0L, ring.takeOverflow())
        }

        fun writersRacingDrain() {
            val writers = 4
            val perWriter = 20_000
            val ring = OtherlodeVerifierMessages.Ring(1 shl 16)
            val pool = Executors.newFixedThreadPool(writers)
            val seen = ArrayList<String>()
            try {
                repeat(writers) { writer -> pool.submit { repeat(perWriter) { ring.record("w$writer-$it") } } }
                pool.shutdown()
                while (!pool.isTerminated) {
                    seen += ring.drain()
                    pool.awaitTermination(1, TimeUnit.MILLISECONDS)
                }
            } finally {
                pool.shutdownNow()
            }
            repeat(4) { seen += ring.drain() }

            assertEquals(writers * perWriter, seen.size + ring.takeOverflow().toInt())
            assertEquals(seen.size, seen.toSet().size)
        }
    }
}
