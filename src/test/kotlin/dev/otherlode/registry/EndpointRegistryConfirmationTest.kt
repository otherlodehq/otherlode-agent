package dev.otherlode.registry

import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the rules that keep an endpoint a class declared from its transform out of the manifest
 * until the JVM is known to have defined that class. The attempts are scripted: an attempt reads
 * as over once it is in [ended], so no test needs a second thread.
 */
class EndpointRegistryConfirmationTest {
    private val declaring = "com.example.Resource"
    private val ended: MutableSet<DefinitionAttempt> = Collections.newSetFromMap(IdentityHashMap())

    private fun registry() = EndpointRegistry().also { registry -> registry.attemptEnded = { it in ended } }

    private fun attempt(): DefinitionAttempt = DefinitionAttempt(Thread.currentThread(), listOf("x.Definer#defineClass1"))

    private fun declaredBy(
        name: String = declaring,
        loader: ClassLoader? = null,
        attempt: DefinitionAttempt? = attempt(),
    ) = DeclaringClass.of(name, loader, attempt)

    private fun EndpointRegistry.declare(
        template: String,
        by: DeclaringClass,
        verb: String = "GET",
    ) = register(key = "$verb $template", framework = "jaxrs", verb = verb, verbatimTemplate = template, declaringClass = by)

    private fun EndpointRegistry.published(): List<String> =
        computeManifestEntries(100)
            .flatMap {
                it.endpoints
            }.map { it.routeTemplate }
            .sorted()

    private fun EndpointRegistry.sweepWithoutTheClass(times: Int = 1): List<WithheldEndpointClass> =
        (1..times).flatMap { confirmFrom(emptySet(), registrationSequence()) }

    @Test
    fun `an endpoint of an unconfirmed declaring class is bound but not published`() {
        val registry = registry()

        registry.declare("/orders", declaredBy())

        assertNotNull(registry.lookup("GET /orders"), "the key is bound at once, so a hit before confirmation counts")
        assertEquals(1, registry.endpoints().size)
        assertEquals(emptyList(), registry.published())
        assertEquals(1, registry.unconfirmedClassCount())
    }

    @Test
    fun `a declaring class named in the loaded set publishes every endpoint it declared`() {
        val registry = registry()
        val by = declaredBy()
        registry.declare("/a", by)
        registry.declare("/b", by)

        val withheld = registry.confirmFrom(setOf(declaring), registry.registrationSequence())

        assertEquals(emptyList(), withheld)
        assertEquals(listOf("/a", "/b"), registry.published())
        assertEquals(0, registry.unconfirmedClassCount())
    }

    @Test
    fun `a published endpoint is not published again`() {
        val registry = registry()
        registry.declare("/a", declaredBy())
        registry.confirmFrom(setOf(declaring), registry.registrationSequence())

        registry.computeManifestEntries(100).forEach(registry::advanceManifest)

        assertEquals(emptyList(), registry.published())
    }

    @Test
    fun `a hit on one endpoint publishes it before any sweep and confirms the class for its siblings`() {
        val registry = registry()
        val by = declaredBy()
        val hit = registry.declare("/hit", by)
        registry.declare("/sibling", by)
        registry.declare("/other-class", declaredBy("com.example.Other"))

        hit.hit()

        assertEquals(listOf("/hit", "/sibling"), registry.published(), "a hit on any endpoint of the class confirms the class")
        assertEquals(1, registry.unconfirmedClassCount(), "the other class is still unconfirmed")
    }

    @Test
    fun `a registration outside any transform publishes the endpoint a class that never defined declared`() {
        val registry = registry()
        registry.declare("/shared", declaredBy())
        registry.declare("/own", declaredBy())
        registry.sweepWithoutTheClass(times = 3)

        registry.register(key = "runtime", framework = "jaxrs", verb = "GET", verbatimTemplate = "/shared")

        assertEquals(listOf("/shared"), registry.published())
    }

    @Test
    fun `discovery by dispatch publishes the endpoint a class that never defined declared`() {
        val registry = registry()
        registry.declare("/shared", declaredBy())
        registry.declare("/own", declaredBy())

        registry.recordDispatch(key = "dispatch", framework = "jaxrs", verb = "GET", verbatimTemplate = "/shared")

        assertEquals(listOf("/shared"), registry.published())
    }

    @Test
    fun `an endpoint dispatch discovered first is published although a class that never defined declares it after`() {
        val registry = registry()
        registry.recordDispatch(key = "dispatch", framework = "jaxrs", verb = "GET", verbatimTemplate = "/shared")
        registry.declare("/shared", declaredBy())

        assertEquals(listOf("/shared"), registry.published())
    }

    @Test
    fun `a hit on an endpoint two classes share publishes it but confirms neither class`() {
        val registry = registry()
        val live = declaredBy("com.example.Live")
        val failed = declaredBy("com.example.Failed")
        val shared = registry.declare("/users", live)
        registry.declare("/users", failed)
        registry.declare("/users/export", failed)
        registry.confirmFrom(setOf("com.example.Live"), registry.registrationSequence())

        shared.hit()

        assertEquals(listOf("/users"), registry.published(), "the hit does not say which class served it")
        assertEquals(1, registry.unconfirmedClassCount(), "the class that never defined is still unconfirmed")
    }

    @Test
    fun `a hit on an endpoint a class declared and another source also produced confirms no class`() {
        val registry = registry()
        val alsoRegistered = registry.declare("/registered", declaredBy("com.example.A"))
        registry.register(key = "runtime /registered", framework = "jaxrs", verb = "GET", verbatimTemplate = "/registered")
        val dispatched = registry.recordDispatch("dispatch /dispatched", "jaxrs", "GET", "/dispatched")!!
        registry.declare("/dispatched", declaredBy("com.example.B"))

        alsoRegistered.hit()
        dispatched.hit()

        assertEquals(2, registry.unconfirmedClassCount(), "a hit on an entry another source produced does not say which one served it")
    }

    @Test
    fun `a hit on an endpoint only a withheld class declared confirms it and lifts the withhold`() {
        val registry = registry()
        val by = declaredBy()
        val own = registry.declare("/own", by)
        ended += by.attempt!!
        registry.sweepWithoutTheClass(times = 2)
        assertEquals(1, registry.withheldForGoodClassCount())

        own.hit()

        assertEquals(listOf("/own"), registry.published())
        assertEquals(0, registry.withheldForGoodClassCount(), "a hit on its own endpoint proves the class was defined")
        assertEquals(0, registry.unconfirmedClassCount())
    }

    @Test
    fun `an endpoint two declaring classes share is published once either is confirmed`() {
        val registry = registry()
        registry.declare("/shared", declaredBy("com.example.A"))
        registry.declare("/shared", declaredBy("com.example.B"))
        assertEquals(emptyList(), registry.published())

        registry.confirmFrom(setOf("com.example.B"), registry.registrationSequence())

        assertEquals(listOf("/shared"), registry.published())
    }

    @Test
    fun `a collected classloader publishes the endpoints rather than withholding them`() {
        val registry = registry()
        val collected = DeclaringClass(declaring, loaderId = 7, classLoaderRef = WeakReference<ClassLoader>(null), attempt = attempt())
        registry.declare("/a", collected)

        registry.sweepWithoutTheClass(times = 3)

        assertEquals(listOf("/a"), registry.published())
        assertEquals(0, registry.withheldForGoodClassCount())
    }

    @Test
    fun `a class absent on two sweeps after its attempt ended is withheld for good and named once with its endpoint count`() {
        val registry = registry()
        val attempt = attempt()
        val by = declaredBy(attempt = attempt)
        registry.declare("/a", by)
        registry.declare("/b", by)
        ended += attempt

        assertEquals(emptyList(), registry.sweepWithoutTheClass(), "the first miss names nothing")
        assertEquals(1, registry.unconfirmedClassCount())
        assertEquals(listOf(WithheldEndpointClass(declaring, 2)), registry.sweepWithoutTheClass(), "the second miss withholds for good")
        assertEquals(emptyList(), registry.sweepWithoutTheClass(times = 3), "a name is returned once")

        assertEquals(1, registry.withheldForGoodClassCount())
        assertEquals(0, registry.unconfirmedClassCount())
        assertEquals(emptyList(), registry.published())
        assertNotNull(registry.lookup("GET /a"), "withholding does not unbind the key")
    }

    @Test
    fun `an attempt that is still running takes no miss`() {
        val registry = registry()
        registry.declare("/a", declaredBy())

        assertEquals(emptyList(), registry.sweepWithoutTheClass(times = 10))

        assertEquals(1, registry.unconfirmedClassCount())
        assertEquals(0, registry.withheldForGoodClassCount())
        registry.confirmFrom(setOf(declaring), registry.registrationSequence())
        assertEquals(listOf("/a"), registry.published())
    }

    @Test
    fun `a declaration with no readable attempt is never named`() {
        val registry = registry()
        registry.declare("/a", declaredBy(attempt = null))

        assertEquals(emptyList(), registry.sweepWithoutTheClass(times = 10))

        assertEquals(1, registry.unconfirmedClassCount())
        assertEquals(0, registry.withheldForGoodClassCount())
    }

    @Test
    fun `a declaration after the sweep's sequence was read is not judged by that sweep`() {
        val registry = registry()
        val attempt = attempt()
        ended += attempt
        val sequence = registry.registrationSequence()
        registry.declare("/a", declaredBy(attempt = attempt))

        repeat(3) { registry.confirmFrom(emptySet(), sequence) }

        assertEquals(0, registry.withheldForGoodClassCount())
        assertEquals(1, registry.unconfirmedClassCount())
    }

    @Test
    fun `a repeat declaration starts a new attempt and clears the misses`() {
        val registry = registry()
        val first = attempt()
        registry.declare("/a", declaredBy(attempt = first))
        ended += first
        assertEquals(emptyList(), registry.sweepWithoutTheClass(), "one miss")

        val second = attempt()
        registry.declare("/a", declaredBy(attempt = second))
        assertEquals(emptyList(), registry.sweepWithoutTheClass(times = 5), "the new attempt is still running")

        ended += second
        assertEquals(emptyList(), registry.sweepWithoutTheClass(), "the earlier miss is gone, so this is the first")
        assertEquals(listOf(WithheldEndpointClass(declaring, 1)), registry.sweepWithoutTheClass())
    }

    @Test
    fun `a repeat declaration lifts a withhold, and the class is not named again when it is withheld once more`() {
        val registry = registry()
        val first = attempt()
        registry.declare("/a", declaredBy(attempt = first))
        ended += first
        assertEquals(1, registry.sweepWithoutTheClass(times = 2).size)
        assertEquals(1, registry.withheldForGoodClassCount())

        val second = attempt()
        registry.declare("/a", declaredBy(attempt = second))
        assertEquals(0, registry.withheldForGoodClassCount())
        ended += second

        assertEquals(emptyList(), registry.sweepWithoutTheClass(times = 4))
        assertEquals(1, registry.withheldForGoodClassCount())
    }

    @Test
    fun `the declarations of one commit start one attempt`() {
        val registry = registry()
        val attempt = attempt()
        val by = declaredBy(attempt = attempt)
        registry.declare("/a", by)
        registry.declare("/b", by)
        registry.declare("/c", by)
        ended += attempt

        registry.sweepWithoutTheClass()

        assertEquals(listOf(WithheldEndpointClass(declaring, 3)), registry.sweepWithoutTheClass())
    }

    @Test
    fun `a class confirmed once stays confirmed when it declares again`() {
        val registry = registry()
        registry.declare("/a", declaredBy())
        registry.confirmFrom(setOf(declaring), registry.registrationSequence())

        registry.declare("/b", declaredBy())

        assertEquals(listOf("/a", "/b"), registry.published())
        assertEquals(0, registry.unconfirmedClassCount())
    }

    @Test
    fun `the endpoints a withheld class shares with another source are not counted as withheld`() {
        val registry = registry()
        val attempt = attempt()
        val by = declaredBy(attempt = attempt)
        registry.declare("/shared", by)
        registry.declare("/own", by)
        registry.register(key = "runtime", framework = "jaxrs", verb = "GET", verbatimTemplate = "/shared")
        ended += attempt

        registry.sweepWithoutTheClass()

        assertEquals(listOf(WithheldEndpointClass(declaring, 1)), registry.sweepWithoutTheClass())
        assertEquals(listOf("/shared"), registry.published())
    }

    @Test
    fun `a class whose endpoints all have another source is withheld without being named`() {
        val registry = registry()
        val attempt = attempt()
        registry.declare("/shared", declaredBy(attempt = attempt))
        registry.register(key = "runtime", framework = "jaxrs", verb = "GET", verbatimTemplate = "/shared")
        ended += attempt

        assertEquals(emptyList(), registry.sweepWithoutTheClass(times = 3))

        assertEquals(1, registry.withheldForGoodClassCount())
    }

    @Test
    fun `two loaders that define one class name are watched apart`() {
        val registry = registry()
        val one = ClassLoader.getPlatformClassLoader()
        val two = ClassLoader.getSystemClassLoader()
        registry.declare("/one", declaredBy(loader = one))
        registry.declare("/two", declaredBy(loader = two))

        assertEquals(2, registry.unconfirmedClassCount())
    }

    @Test
    fun `endpoints registered without a declaring class are published with no sweep`() {
        val registry = registry()

        registry.register(key = "k", framework = "spring", verb = "GET", verbatimTemplate = "/live")

        assertEquals(listOf("/live"), registry.published())
        assertEquals(0, registry.unconfirmedClassCount())
        assertNull(registry.lookup("absent"))
        assertTrue(registry.confirmFrom(emptySet()).isEmpty())
    }
}
