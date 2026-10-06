package dev.otherlode.registry

import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeRegistryDefinitionAttemptTest {
    private val resource = ResourceAttributes("checkout", null, "instance-1", null, "run-1")
    private val name = "com.example.Foo"

    /**
     * A registry whose attempt capture and attempt check are scripted, so a test sets the rules
     * without a thread. Each [register] takes the next attempt from [next], or none when it is
     * null, and an attempt reads as ended once it is in [ended].
     */
    private class ScriptedRegistry : ProbeRegistry(confirmsDefinitions = true) {
        var next: DefinitionAttempt? = null
        val ended: MutableSet<DefinitionAttempt> = Collections.newSetFromMap(IdentityHashMap())

        override fun captureAttempt(): DefinitionAttempt? = next

        override fun attemptEnded(attempt: DefinitionAttempt): Boolean = attempt in ended

        fun attempt(): DefinitionAttempt = DefinitionAttempt(Thread.currentThread(), listOf("x.Definer#defineClass1"))
    }

    private fun probes() = listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", line = 1))

    private fun ScriptedRegistry.registerFoo(loader: ClassLoader? = null) =
        register(name, layoutHash = 1L, probes = probes(), classLoader = loader)

    private fun ScriptedRegistry.failed() = manifest(resource).failedClasses.map { it.className }

    @Test
    fun `an absent class whose attempt is ongoing takes no miss and is confirmed when the class shows up`() {
        val registry = ScriptedRegistry()
        registry.next = registry.attempt()
        registry.registerFoo()

        repeat(10) { assertTrue(registry.confirmFrom(emptySet()).isEmpty()) }

        assertEquals(1, registry.unconfirmedClassCount())
        assertEquals(0, registry.withheldForGoodClassCount())
        assertTrue(registry.failed().isEmpty())

        registry.confirmFrom(setOf(name))

        assertEquals(0, registry.unconfirmedClassCount())
        assertEquals(
            listOf(name),
            registry
                .computeManifestDelta(resource)
                .manifest.probes
                .map { it.className },
        )
    }

    @Test
    fun `once the attempt has ended two misses withhold the class and name it failed once`() {
        val registry = ScriptedRegistry()
        val attempt = registry.attempt()
        registry.next = attempt
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        assertEquals(0, registry.withheldForGoodClassCount())

        registry.ended += attempt
        assertTrue(registry.confirmFrom(emptySet()).isEmpty(), "the first miss after the end is not yet final")
        assertEquals(listOf(name), registry.confirmFrom(emptySet()))
        assertTrue(registry.confirmFrom(emptySet()).isEmpty())

        assertEquals(listOf(name), registry.failed())
        assertEquals(1, registry.withheldForGoodClassCount())
    }

    @Test
    fun `an entry registered after the sweep's sequence is not judged by that sweep`() {
        val registry = ScriptedRegistry()
        val attempt = registry.attempt()
        registry.next = attempt
        registry.ended += attempt
        val before = registry.registrationSequence()
        registry.registerFoo()

        repeat(5) { assertTrue(registry.confirmFrom(emptySet(), registeredUpTo = before).isEmpty()) }
        assertEquals(1, registry.unconfirmedClassCount())
        assertEquals(0, registry.withheldForGoodClassCount())

        registry.confirmFrom(emptySet(), registeredUpTo = registry.registrationSequence())
        assertEquals(listOf(name), registry.confirmFrom(emptySet(), registeredUpTo = registry.registrationSequence()))
    }

    @Test
    fun `a late entry is not confirmed by a loaded name the sweep read before it registered`() {
        val registry = ScriptedRegistry()
        val before = registry.registrationSequence()
        registry.registerFoo()

        registry.confirmFrom(setOf(name), registeredUpTo = before)

        assertEquals(1, registry.unconfirmedClassCount())
    }

    @Test
    fun `a retry of a withheld class clears its unsent failure and starts a new attempt`() {
        val registry = ScriptedRegistry()
        val first = registry.attempt()
        registry.next = first
        registry.ended += first
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        assertEquals(listOf(name), registry.failed())

        val second = registry.attempt()
        registry.next = second
        registry.registerFoo()

        assertTrue(registry.failed().isEmpty(), "an unsent failure is dropped")
        assertEquals(0, registry.withheldForGoodClassCount())
        assertEquals(1, registry.unconfirmedClassCount())
        repeat(5) { assertTrue(registry.confirmFrom(emptySet()).isEmpty(), "the new attempt is ongoing") }

        registry.confirmFrom(setOf(name))

        assertTrue(registry.failed().isEmpty(), "a later confirmation keeps the name off the wire")
        assertEquals(
            listOf(name),
            registry
                .computeManifestDelta(resource)
                .manifest.probes
                .map { it.className },
        )
    }

    @Test
    fun `a retry of a class whose failure was already sent leaves the sent failure alone`() {
        val registry = ScriptedRegistry()
        val first = registry.attempt()
        registry.next = first
        registry.ended += first
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        registry.advanceManifestBaseline(registry.computeManifestDelta(resource))

        registry.next = registry.attempt()
        registry.registerFoo()
        registry.confirmFrom(setOf(name))

        assertEquals(listOf(name), registry.failed(), "the server lets any evidence of loading win")
        assertTrue(
            registry
                .computeManifestDelta(resource)
                .manifest.failedClasses
                .isEmpty(),
            "nothing is sent twice",
        )
    }

    @Test
    fun `a retry of an unconfirmed class that has a miss starts again from no misses`() {
        val registry = ScriptedRegistry()
        val first = registry.attempt()
        registry.next = first
        registry.ended += first
        registry.registerFoo()
        registry.confirmFrom(emptySet())

        registry.next = registry.attempt()
        registry.registerFoo()

        repeat(5) { assertTrue(registry.confirmFrom(emptySet()).isEmpty()) }
        assertEquals(0, registry.withheldForGoodClassCount())
    }

    @Test
    fun `a namesake confirmed in another loader removes an unsent failure`() {
        val registry = ScriptedRegistry()
        val loaderA = ClassLoader.getSystemClassLoader()
        val loaderB = object : ClassLoader() {}
        val failedAttempt = registry.attempt()
        registry.next = failedAttempt
        registry.ended += failedAttempt
        registry.registerFoo(loaderA)
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        assertEquals(listOf(name), registry.failed())

        registry.next = null
        registry.registerFoo(loaderB)
        registry.confirmFrom(setOf(name))

        assertTrue(registry.failed().isEmpty())
        assertEquals(1, registry.withheldForGoodClassCount(), "the first loader's entry stays withheld")
    }

    @Test
    fun `a namesake that registers keeps an unsent failure off the wire while its attempt is pending`() {
        val registry = ScriptedRegistry()
        val loaderB = object : ClassLoader() {}
        val failedAttempt = registry.attempt()
        registry.next = failedAttempt
        registry.ended += failedAttempt
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        assertEquals(listOf(name), registry.failed())

        registry.next = registry.attempt()
        registry.registerFoo(loaderB)

        assertTrue(registry.failed().isEmpty())
    }

    @Test
    fun `a namesake confirmed by a probe hit removes an unsent failure on the next sweep`() {
        val registry = ScriptedRegistry()
        val loaderB = object : ClassLoader() {}
        val failedAttempt = registry.attempt()
        registry.next = failedAttempt
        registry.ended += failedAttempt
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        registry.next = null
        registry.registerFoo(loaderB)[0]++

        registry.confirmFrom(emptySet())

        assertTrue(registry.failed().isEmpty())
    }

    @Test
    fun `an entry with no recorded attempt counts its misses from the start`() {
        val registry = ScriptedRegistry()
        registry.next = null
        registry.registerFoo()

        assertTrue(registry.confirmFrom(emptySet()).isEmpty())
        assertEquals(listOf(name), registry.confirmFrom(emptySet()))
    }

    @Test
    fun `the attempt check is read only for entries absent from the loaded set and not yet final`() {
        var reads = 0
        val registry =
            object : ProbeRegistry(confirmsDefinitions = true) {
                override fun captureAttempt() = DefinitionAttempt(Thread.currentThread(), listOf("x.Definer#defineClass1"))

                override fun attemptEnded(attempt: DefinitionAttempt): Boolean {
                    reads++
                    return false
                }
            }
        registry.register("com.example.Loaded", layoutHash = 1L, probes = probes())
        registry.register("com.example.Hit", layoutHash = 1L, probes = probes())[0]++

        registry.confirmFrom(setOf("com.example.Loaded"))

        assertEquals(0, reads)
    }

    @Test
    fun `a class registered in a transform whose attempt cannot be read is never named failed`() {
        val registry = ScriptedRegistry()
        registry.next = null
        registry.inTransform { registry.register(name, layoutHash = 1L, probes = probes()) }

        repeat(10) { assertTrue(registry.confirmFrom(emptySet()).isEmpty()) }

        assertTrue(registry.failed().isEmpty())
        assertEquals(1, registry.unconfirmedClassCount())
    }

    @Test
    fun `a class takes a miss only once every one of its attempts has ended`() {
        val registry = ScriptedRegistry()
        val first = registry.attempt()
        registry.next = first
        registry.registerFoo()
        val second = registry.attempt()
        registry.next = second
        registry.registerFoo()

        registry.ended += second
        repeat(5) { assertTrue(registry.confirmFrom(emptySet()).isEmpty(), "the first attempt still runs") }
        assertEquals(0, registry.withheldForGoodClassCount())

        registry.ended += first
        registry.confirmFrom(emptySet())
        assertEquals(listOf(name), registry.confirmFrom(emptySet()))
    }

    @Test
    fun `a failure staged for a manifest stays when a namesake registers before the manifest is confirmed`() {
        val registry = ScriptedRegistry()
        val first = registry.attempt()
        registry.next = first
        registry.ended += first
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        registry.confirmFrom(emptySet())
        val staged = registry.computeManifestDeltas(resource, 1000).toList()
        assertEquals(listOf(name), staged.flatMap { it.manifest.failedClasses }.map { it.className })

        registry.next = registry.attempt()
        registry.registerFoo(loader = ClassLoader.getSystemClassLoader())

        assertEquals(listOf(name), registry.failed(), "a staged failure may already be on its way, so it stays")
    }

    @Test
    fun `a class withheld again after a retry is reported only once`() {
        val registry = ScriptedRegistry()
        val first = registry.attempt()
        registry.next = first
        registry.ended += first
        registry.registerFoo()
        registry.confirmFrom(emptySet())
        assertEquals(listOf(name), registry.confirmFrom(emptySet()))

        val second = registry.attempt()
        registry.next = second
        registry.ended += second
        registry.registerFoo()
        registry.confirmFrom(emptySet())

        assertTrue(registry.confirmFrom(emptySet()).isEmpty(), "the name was returned once already")
        assertEquals(listOf(name), registry.failed())
    }
}
