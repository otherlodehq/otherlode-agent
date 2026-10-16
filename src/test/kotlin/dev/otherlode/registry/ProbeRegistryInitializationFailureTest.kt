package dev.otherlode.registry

import dev.otherlode.export.InitializationFailure
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeRegistryInitializationFailureTest {
    private val resource = ResourceAttributes("checkout", null, "instance-1", null, "run-1")

    private fun initializerProbes(): List<ProbeMeta> =
        listOf(
            ProbeMeta(ProbeKind.METHOD, "pick", "()V", line = 1),
            ProbeMeta(ProbeKind.METHOD, "<clinit>", "()V", line = 2),
            ProbeMeta(ProbeKind.INITIALIZER_RETURNED, "<clinit>", "()V", line = 2),
            ProbeMeta(ProbeKind.INITIALIZER_THREW, "<clinit>", "()V", line = 2),
        )

    private fun ProbeRegistry.names(delta: ProbeRegistry.ManifestSnapshot) = delta.manifest.classesFailedToInitialize.map { it.className }

    @Test
    fun `a threw slot above zero yields one entry on the next manifest delta`() {
        val registry = ProbeRegistry()
        val counts = registry.register("com.example.Foo", layoutHash = 1L, probes = initializerProbes())
        counts[3] = 1

        val delta = registry.computeManifestDelta(resource)

        val failure = delta.manifest.classesFailedToInitialize.single()
        assertEquals("com.example.Foo", failure.className)
        assertEquals(InitializationFailure.STATIC_INITIALIZER_THREW, failure.reason)
        assertTrue(failure.observedAt > 0)
        assertEquals(1, registry.manifest(resource).classesFailedToInitialize.size)
    }

    @Test
    fun `a delivered entry is not repeated, even when the slot counts again`() {
        val registry = ProbeRegistry()
        val counts = registry.register("com.example.Foo", layoutHash = 1L, probes = initializerProbes())
        counts[3] = 1
        registry.advanceManifestBaseline(registry.computeManifestDelta(resource))
        counts[3] = 2

        assertTrue(
            registry
                .computeManifestDelta(resource)
                .manifest.classesFailedToInitialize
                .isEmpty(),
        )
    }

    @Test
    fun `an entry that failed to send is staged again`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = initializerProbes())[3] = 1

        registry.computeManifestDelta(resource)

        assertEquals(listOf("com.example.Foo"), registry.names(registry.computeManifestDelta(resource)))
    }

    @Test
    fun `a class whose manifest was delivered still yields the entry when the slot later counts`() {
        val registry = ProbeRegistry()
        val counts = registry.register("com.example.Foo", layoutHash = 1L, probes = initializerProbes())
        val first = registry.computeManifestDelta(resource)
        assertTrue(first.manifest.classesFailedToInitialize.isEmpty())
        registry.advanceManifestBaseline(first)
        counts[3] = 1

        val second = registry.computeManifestDelta(resource)

        assertEquals(listOf("com.example.Foo"), registry.names(second))
        assertTrue(second.manifest.probes.isEmpty(), "the class's locations are not sent again")
        registry.advanceManifestBaseline(second)
        assertTrue(
            registry
                .computeManifestDelta(resource)
                .manifest.classesFailedToInitialize
                .isEmpty(),
        )
    }

    @Test
    fun `a class whose initialiser returned yields none`() {
        val registry = ProbeRegistry()
        val counts = registry.register("com.example.Foo", layoutHash = 1L, probes = initializerProbes())
        counts[1] = 1
        counts[2] = 1

        assertTrue(
            registry
                .computeManifestDelta(resource)
                .manifest.classesFailedToInitialize
                .isEmpty(),
        )
    }

    @Test
    fun `entries share the chunk cap, one weight each`() {
        val registry = ProbeRegistry()
        val counts = (1..5).map { registry.register("com.example.Foo$it", layoutHash = 1L, probes = initializerProbes()) }
        registry.advanceManifestBaseline(registry.computeManifestDelta(resource))
        counts.forEach { it[3] = 1 }

        val chunks = registry.computeManifestDeltas(resource, maxEntriesPerChunk = 2).toList()

        assertEquals(listOf(2, 2, 1), chunks.map { it.manifest.classesFailedToInitialize.size })
    }
}
