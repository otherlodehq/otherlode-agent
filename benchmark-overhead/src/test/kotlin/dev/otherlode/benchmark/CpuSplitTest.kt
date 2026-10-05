package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CpuSplitTest {
    @Test
    fun `4 CPUs give PetClinic one core, k6 two, and Postgres with the collector one`() {
        assertEquals(CpuSplit("0", "1-2", "3", 1), CpuSplit.of(4))
    }

    @Test
    fun `8 CPUs give PetClinic two cores, k6 four, and Postgres with the collector two`() {
        assertEquals(CpuSplit("0-1", "2-5", "6-7", 2), CpuSplit.of(8))
    }

    @Test
    fun `16 CPUs keep PetClinic at two cores, give Postgres with the collector four, and k6 the rest`() {
        assertEquals(CpuSplit("0-1", "2-11", "12-15", 2), CpuSplit.of(16))
    }

    @Test
    fun `the virtual users scale with PetClinic's cores`() {
        assertEquals(4, CpuSplit.of(4).virtualUsers)
        assertEquals(8, CpuSplit.of(12).virtualUsers)
    }

    @Test
    fun `an odd count gives the remainder to k6`() {
        assertEquals(CpuSplit("0", "1-3", "4", 1), CpuSplit.of(5))
        assertEquals(CpuSplit("0", "1", "2", 1), CpuSplit.of(3))
    }

    @Test
    fun `2 CPUs leave PetClinic core 0 and share core 1 among the rest`() {
        assertEquals(CpuSplit("0", "1", "1", 1), CpuSplit.of(2))
    }

    @Test
    fun `a single CPU cannot be split`() {
        assertThrows(IllegalArgumentException::class.java) { CpuSplit.of(1) }
    }

    @Test
    fun `PetClinic's cores never appear in another container's set`() {
        fun cores(set: String): Set<Int> =
            set.split('-').map { it.toInt() }.let { if (it.size == 1) setOf(it[0]) else (it[0]..it[1]).toSet() }
        for (n in 2..64) {
            val split = CpuSplit.of(n)
            val own = cores(split.petclinic)
            assertEquals(split.petclinicCores, own.size, "n=$n")
            assertEquals(emptySet<Int>(), own intersect cores(split.k6), "n=$n")
            assertEquals(emptySet<Int>(), own intersect cores(split.support), "n=$n")
            assertEquals((0 until n).toSet(), own + cores(split.k6) + cores(split.support), "n=$n")
        }
    }
}
