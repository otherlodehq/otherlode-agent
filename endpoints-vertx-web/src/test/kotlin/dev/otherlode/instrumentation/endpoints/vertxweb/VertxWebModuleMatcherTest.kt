package dev.otherlode.instrumentation.endpoints.vertxweb

import net.bytebuddy.description.type.TypeDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins which classes [VertxWebModule] accepts and what it asks of the agent. */
class VertxWebModuleMatcherTest {
    private fun type(name: String): TypeDescription = TypeDescription.Latent(name, 0, null)

    @Test
    fun `the router, the route and the route state are accepted`() {
        val matcher = VertxWebModule().typeMatcher()

        assertTrue(matcher.matches(type("io.vertx.ext.web.impl.RouterImpl")))
        assertTrue(matcher.matches(type("io.vertx.ext.web.impl.RouteImpl")))
        assertTrue(matcher.matches(type("io.vertx.ext.web.impl.RouteState")))
    }

    @Test
    fun `other classes are not`() {
        val matcher = VertxWebModule().typeMatcher()

        assertFalse(matcher.matches(type("io.vertx.ext.web.Router")))
        assertFalse(matcher.matches(type("io.vertx.ext.web.impl.RoutingContextImpl")))
        assertFalse(matcher.matches(type("io.vertx.ext.web.impl.RouterState")))
        assertFalse(matcher.matches(type("com.example.RouterImpl")))
    }

    @Test
    fun `the module names itself, its handler interface and the named module it instruments`() {
        val module = VertxWebModule()

        assertEquals("vertx-web", module.name)
        assertEquals(setOf("io.vertx.core.Handler"), module.handlerInterfaces)
        assertEquals(setOf("io.vertx.web"), module.bootModulesNeedingSeamRead)
    }
}
