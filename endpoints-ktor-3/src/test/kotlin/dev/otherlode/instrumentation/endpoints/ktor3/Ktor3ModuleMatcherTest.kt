package dev.otherlode.instrumentation.endpoints.ktor3

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Ktor3ModuleMatcherTest {
    @Test
    fun `the type matcher names this module's own Ktor 3 types and nothing else`() {
        val matcher = Ktor3Module().typeMatcher()

        assertTrue(matcher.matches(named("io.ktor.server.routing.RoutingNode")), "io.ktor.server.routing.RoutingNode")
        assertTrue(matcher.matches(named("io.ktor.server.routing.RoutingRoot")), "io.ktor.server.routing.RoutingRoot")
        assertFalse(matcher.matches(named("io.ktor.server.routing.Route")), "Ktor 2's own name for the node type belongs to Ktor2Module")
        assertFalse(matcher.matches(named("java.lang.String")), "an unrelated type must never match")
    }

    @Test
    fun `transform hands back the builder unchanged for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)

        val returned =
            Ktor3Module().transform(
                builder,
                named("io.ktor.server.routing.Route"),
                AdviceBinder(javaClass.classLoader, javaClass.classLoader),
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unvisited")
    }

    private class UnrelatedType

    private fun named(name: String): TypeDescription = TypeDescription.Latent(name, 0, null)
}
