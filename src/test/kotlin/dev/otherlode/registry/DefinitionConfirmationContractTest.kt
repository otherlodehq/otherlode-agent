package dev.otherlode.registry

import dev.otherlode.export.ProbeKind
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the same definition-confirmation scenarios against [ProbeRegistry] and [EndpointRegistry],
 * so the two state machines cannot drift apart. Each registry is driven through its own seams.
 */
class DefinitionConfirmationContractTest {
    private val name = "com.example.Foo"

    private fun newAttempt() = DefinitionAttempt(Thread.currentThread(), listOf("x.Definer#defineClass1"))

    private interface Subject {
        fun define(attempt: DefinitionAttempt)

        fun endAttempt(attempt: DefinitionAttempt)

        fun sweepMissing(times: Int = 1)

        fun sweepLoaded()

        fun hit()

        fun withheld(): Int

        fun unconfirmed(): Int
    }

    private class ProbeSubject : Subject {
        private val ended: MutableSet<DefinitionAttempt> = Collections.newSetFromMap(IdentityHashMap())
        private var next: DefinitionAttempt? = null
        private var counts: LongArray? = null
        private val registry =
            object : ProbeRegistry(confirmsDefinitions = true) {
                override fun captureAttempt(): DefinitionAttempt? = next

                override fun attemptEnded(attempt: DefinitionAttempt): Boolean = attempt in ended
            }

        override fun define(attempt: DefinitionAttempt) {
            next = attempt
            counts =
                registry.register(
                    "com.example.Foo",
                    layoutHash = 1L,
                    probes = listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", line = 1)),
                    classLoader = null,
                )
        }

        override fun endAttempt(attempt: DefinitionAttempt) {
            ended += attempt
        }

        override fun sweepMissing(times: Int) {
            repeat(times) { registry.confirmFrom(emptySet()) }
        }

        override fun sweepLoaded() {
            registry.confirmFrom(setOf("com.example.Foo"))
        }

        override fun hit() {
            counts!![0]++
        }

        override fun withheld() = registry.withheldForGoodClassCount()

        override fun unconfirmed() = registry.unconfirmedClassCount()
    }

    private class EndpointSubject : Subject {
        private val ended: MutableSet<DefinitionAttempt> = Collections.newSetFromMap(IdentityHashMap())
        private val registry = EndpointRegistry().also { registry -> registry.attemptEnded = { it in ended } }
        private var entry: EndpointRegistry.EndpointEntry? = null

        override fun define(attempt: DefinitionAttempt) {
            entry =
                registry.register(
                    key = "GET /a",
                    framework = "jaxrs",
                    verb = "GET",
                    verbatimTemplate = "/a",
                    declaringClass = DeclaringClass.of("com.example.Foo", null, attempt),
                )
        }

        override fun endAttempt(attempt: DefinitionAttempt) {
            ended += attempt
        }

        override fun sweepMissing(times: Int) {
            repeat(times) { registry.confirmFrom(emptySet(), registry.registrationSequence()) }
        }

        override fun sweepLoaded() {
            registry.confirmFrom(setOf("com.example.Foo"), registry.registrationSequence())
        }

        override fun hit() {
            entry!!.hit()
        }

        override fun withheld() = registry.withheldForGoodClassCount()

        override fun unconfirmed() = registry.unconfirmedClassCount()
    }

    private fun eachSubject(scenario: (String, Subject) -> Unit) {
        scenario("probe", ProbeSubject())
        scenario("endpoint", EndpointSubject())
    }

    @Test
    fun `a class found in the loaded set is confirmed`() =
        eachSubject { label, subject ->
            subject.define(newAttempt())
            assertEquals(1, subject.unconfirmed(), label)

            subject.sweepLoaded()

            assertEquals(0, subject.unconfirmed(), label)
            assertEquals(0, subject.withheld(), label)
        }

    @Test
    fun `a class whose attempt is still running takes no miss`() =
        eachSubject { label, subject ->
            subject.define(newAttempt())

            subject.sweepMissing(times = 4)

            assertEquals(1, subject.unconfirmed(), label)
            assertEquals(0, subject.withheld(), label)
        }

    @Test
    fun `two misses after every attempt ended withhold the class`() =
        eachSubject { label, subject ->
            val attempt = newAttempt()
            subject.define(attempt)
            subject.endAttempt(attempt)

            subject.sweepMissing()
            assertEquals(1, subject.unconfirmed(), "$label: one miss is not final")
            subject.sweepMissing()

            assertEquals(1, subject.withheld(), label)
            assertEquals(0, subject.unconfirmed(), label)
        }

    @Test
    fun `a new attempt lifts a withhold`() =
        eachSubject { label, subject ->
            val first = newAttempt()
            subject.define(first)
            subject.endAttempt(first)
            subject.sweepMissing(times = 2)
            assertEquals(1, subject.withheld(), label)

            subject.define(newAttempt())

            assertEquals(0, subject.withheld(), label)
            assertEquals(1, subject.unconfirmed(), label)
        }

    @Test
    fun `evidence of a hit lifts a withhold and confirms the class`() =
        eachSubject { label, subject ->
            val attempt = newAttempt()
            subject.define(attempt)
            subject.endAttempt(attempt)
            subject.sweepMissing(times = 2)
            assertEquals(1, subject.withheld(), label)

            subject.hit()

            assertEquals(0, subject.withheld(), label)
            assertEquals(0, subject.unconfirmed(), label)
        }
}
