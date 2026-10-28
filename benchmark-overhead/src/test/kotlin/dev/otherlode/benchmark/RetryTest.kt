package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryTest {
    private val saturation = saturationProblem(0.89, 1)!!
    private val noCount = warmthProblem(mutableMapOf())!!
    private val verify = "PetClinic's log contains VerifyError"

    @Test
    fun `a saturation problem alone is environmental`() {
        assertTrue(isEnvironmental(listOf(saturation)))
    }

    @Test
    fun `a window that served no request is not environmental, alone or with saturation`() {
        assertFalse(isEnvironmental(listOf(noCount)))
        assertFalse(isEnvironmental(listOf(warmthProblem(mutableMapOf("k6FirstThirdRequests" to 0.0, "k6LastThirdRequests" to 1.0))!!)))
        assertFalse(isEnvironmental(listOf(noCount, saturation)))
    }

    @Test
    fun `any other problem is not environmental, alone or with an environmental one`() {
        assertFalse(isEnvironmental(listOf(verify)))
        assertFalse(isEnvironmental(listOf(verify, saturation)))
        assertFalse(isEnvironmental(listOf(saturation, "k6 checks pass rate was 0.5, below 1.0")))
        assertFalse(isEnvironmental(emptyList()))
    }

    @Test
    fun `an environmental failure then a pass keeps the pass and records the retry`() {
        val discarded = mutableListOf<DiscardedAttempt>()
        val result =
            attemptRepeat("agent", 2, discarded) { attempt ->
                if (attempt == 1) throw EnvironmentalRunException(saturation)
                "ok"
            }
        assertEquals("ok", result)
        assertEquals(listOf(DiscardedAttempt("agent", 2, 1, saturation)), discarded)
    }

    @Test
    fun `two environmental failures fail the run naming both`() {
        val discarded = mutableListOf<DiscardedAttempt>()
        val e =
            assertThrows(InvalidRunException::class.java) {
                attemptRepeat("agent", 2, discarded) { attempt -> throw EnvironmentalRunException("problem $attempt") }
            }
        assertEquals(InvalidRunException::class.java, e::class.java)
        assertTrue(e.message!!.contains("attempt 1: problem 1"), e.message)
        assertTrue(e.message!!.contains("attempt 2: problem 2"), e.message)
        assertEquals(1, discarded.size)
    }

    @Test
    fun `a second repeat that fails on saturation in the same run is not retried`() {
        val discarded = mutableListOf(DiscardedAttempt("agent", 1, 1, saturation))
        var calls = 0
        val e =
            assertThrows(InvalidRunException::class.java) {
                attemptRepeat("agent", 3, discarded) {
                    calls++
                    throw EnvironmentalRunException(saturation)
                }
            }
        assertEquals(InvalidRunException::class.java, e::class.java)
        assertEquals(1, calls)
        assertTrue(e.message!!.contains("attempt 1: $saturation"), e.message)
        assertEquals(1, discarded.size)
    }

    @Test
    fun `a non-environmental failure is not retried`() {
        var calls = 0
        val discarded = mutableListOf<DiscardedAttempt>()
        assertThrows(InvalidRunException::class.java) {
            attemptRepeat("agent", 1, discarded) {
                calls++
                throw InvalidRunException(verify)
            }
        }
        assertEquals(1, calls)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun `the summary lists each retry and is absent without one`() {
        assertEquals("", retriesSection(emptyList()))
        val section = retriesSection(listOf(DiscardedAttempt("agent", 2, 1, saturation)))
        assertTrue(section.contains("agent repeat 2, attempt 1: $saturation"), section)
        assertTrue(section.startsWith("\n### Repeats that ran again"), section)
    }
}
