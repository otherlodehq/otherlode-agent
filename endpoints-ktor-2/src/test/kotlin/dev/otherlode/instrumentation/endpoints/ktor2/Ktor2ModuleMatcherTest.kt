package dev.otherlode.instrumentation.endpoints.ktor2

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins which Ktor 2 types [Ktor2Module] instruments, and that it leaves anything else alone.
 *
 * The names are literals here on purpose. They are the module's only link to the framework, and a
 * framework renaming one is exactly the drift this project has no muzzle for: the module would
 * match nothing, instrument nothing, and report no endpoints without ever failing. Describing each
 * type through [TypeDescription.Latent] keeps that check free of the framework itself, so nothing
 * here loads a Ktor 2 class into this JVM ahead of the end-to-end test that needs to weave it.
 */
class Ktor2ModuleMatcherTest {
    @Test
    fun `the type matcher names this module's own Ktor 2 types and nothing else`() {
        val matcher = Ktor2Module().typeMatcher()

        assertTrue(matcher.matches(named("io.ktor.server.routing.Route")), "io.ktor.server.routing.Route")
        assertTrue(matcher.matches(named("io.ktor.server.routing.Routing")), "io.ktor.server.routing.Routing")
        assertFalse(
            matcher.matches(named("io.ktor.server.routing.RoutingNode")),
            "Ktor 3's own name for the node type belongs to Ktor3Module",
        )
        assertFalse(matcher.matches(named("java.lang.String")), "an unrelated type must never match")
    }

    @Test
    fun `the type matcher rejects an interface of a Ktor 2 type's name, which is Ktor 3's Route and Routing`() {
        val matcher = Ktor2Module().typeMatcher()
        val interfaceModifiers = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT

        assertFalse(matcher.matches(named("io.ktor.server.routing.Route", interfaceModifiers)), "Route as an interface")
        assertFalse(matcher.matches(named("io.ktor.server.routing.Routing", interfaceModifiers)), "Routing as an interface")
        assertTrue(matcher.matches(named("io.ktor.server.routing.Route", Opcodes.ACC_PUBLIC)), "Route as a class")
    }

    @Test
    fun `transform hands back the builder unchanged for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)

        val returned =
            Ktor2Module().transform(
                builder,
                named("io.ktor.server.routing.RoutingNode"),
                AdviceBinder(javaClass.classLoader, javaClass.classLoader),
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unvisited")
    }

    /** A type no name in this module's matcher matches. */
    private class UnrelatedType

    /** A description carrying nothing but [name], which is all the matcher and [Ktor2Module.transform] read. */
    private fun named(
        name: String,
        modifiers: Int = 0,
    ): TypeDescription = TypeDescription.Latent(name, modifiers, null)
}
