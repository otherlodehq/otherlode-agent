package dev.otherlode.benchmark

import com.example.hotpath.BranchyFixture
import com.example.hotpath.DefaultArgumentFixture
import com.example.hotpath.EntryOnlyFixture
import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Keeps the hot-path suite's setup honest: the woven copy is woven, the unwoven one is not, and both compute the same. */
class HotPathWeavingTest {
    private val weaver = HotPathWeaver()

    private fun hits(
        className: String,
        kind: ProbeKind,
        methodName: String? = null,
    ): Long {
        val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
        val manifest = weaver.registry.manifest(resource)
        val indexes =
            manifest.probes
                .filter {
                    it.className == className && it.kind == kind &&
                        (methodName == null || it.methodName == methodName)
                }.map { it.probeIndex }
                .toSet()
        return weaver.registry
            .computeDeltaBatch(resource)
            .batch.deltas
            .filter { it.probeIndex in indexes }
            .sumOf { it.hitsTotal }
    }

    @Test
    fun `the woven entry-only fixture counts its method entry`() {
        val shape = weaver.woven(EntryOnlyFixture::class.java)
        shape.call(1)
        assertEquals(1L, hits(EntryOnlyFixture::class.java.name, ProbeKind.METHOD, "call"))
    }

    @Test
    fun `the woven branchy fixture counts a branch outcome`() {
        val shape = weaver.woven(BranchyFixture::class.java)
        shape.call(1)
        assertTrue(hits(BranchyFixture::class.java.name, ProbeKind.BRANCH) > 0L)
    }

    @Test
    fun `the woven branchy fixture probes the loop, every switch case and the if`() {
        weaver.woven(BranchyFixture::class.java)
        val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
        val branchProbes =
            weaver.registry.manifest(resource).probes.count {
                it.className == BranchyFixture::class.java.name && it.methodName == "call" && it.kind == ProbeKind.BRANCH
            }
        // Two outcomes for the loop's jump, five for the tableswitch (four cases and the default), two for the if.
        assertEquals(9, branchProbes)
    }

    @Test
    fun `the woven default-argument fixture counts omitted parameters`() {
        val shape = weaver.woven(DefaultArgumentFixture::class.java)
        shape.call(1)
        // call omits b, c and d once, then c and d once: two of the three parameters are omitted twice.
        assertEquals(5L, hits(DefaultArgumentFixture::class.java.name, ProbeKind.OPTIONAL_ARGUMENT))
    }

    @Test
    fun `the unwoven copies count nothing`() {
        val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
        for (fixture in FIXTURES) weaver.woven(fixture).call(1)

        fun totals() =
            weaver.registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        val before = totals()
        for (fixture in FIXTURES) repeat(10) { weaver.unwoven(fixture).call(it) }
        assertEquals(before, totals(), "a call to an unwoven copy reached a probe")
    }

    @Test
    fun `woven and unwoven copies are separate classes`() {
        for (fixture in FIXTURES) {
            assertTrue(weaver.woven(fixture).javaClass !== weaver.unwoven(fixture).javaClass)
        }
    }

    @Test
    fun `woven and unwoven copies return the same results`() {
        for (fixture in FIXTURES) {
            val woven = weaver.woven(fixture)
            val unwoven = weaver.unwoven(fixture)
            for (x in -50..500) assertEquals(unwoven.call(x), woven.call(x), "${fixture.simpleName}.call($x)")
        }
    }

    @Test
    fun `the endpoint seam resolves its registered entry and counts a hit`() {
        val seam = HotPathEndpointSeam()
        val key = java.util.List.of(HotPathEndpointSeam.PATTERN, HotPathEndpointSeam.VERB)
        val found = OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, key)
        assertNotNull(found)
        assertEquals(seam.entry, found)
        OtherlodeEndpoints.hit(found)
        assertEquals(1L, seam.entry.count)
        OtherlodeEndpoints.hit(OtherlodeEndpoints.lookup(HotPathEndpointSeam.MODULE, seam.key))
        assertEquals(2L, seam.entry.count)
    }

    private companion object {
        val FIXTURES = listOf(EntryOnlyFixture::class.java, BranchyFixture::class.java, DefaultArgumentFixture::class.java)
    }
}
