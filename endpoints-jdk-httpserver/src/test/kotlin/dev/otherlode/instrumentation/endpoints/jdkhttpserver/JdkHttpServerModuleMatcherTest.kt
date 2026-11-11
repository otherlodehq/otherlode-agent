package dev.otherlode.instrumentation.endpoints.jdkhttpserver

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdkHttpServerModuleMatcherTest {
    @Test
    fun `the type matcher names this module's own JDK HttpServer types and nothing else`() {
        val matcher = JdkHttpServerModule().typeMatcher()

        assertTrue(matcher.matches(named("sun.net.httpserver.ServerImpl")), "sun.net.httpserver.ServerImpl")
        assertTrue(matcher.matches(named("sun.net.httpserver.HttpContextImpl")), "sun.net.httpserver.HttpContextImpl")
        assertFalse(matcher.matches(named("com.sun.net.httpserver.HttpServer")), "the public facade is not what carries the implementation")
        assertFalse(matcher.matches(named("java.lang.String")), "an unrelated type must never match")
    }

    @Test
    fun `transform hands back the builder unchanged for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)

        val returned =
            JdkHttpServerModule().transform(
                builder,
                named("com.sun.net.httpserver.HttpServer"),
                AdviceBinder(javaClass.classLoader, javaClass.classLoader),
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unvisited")
    }

    @Test
    fun `the module asks for HttpHandler lambdas to be recorded, and for no other interface`() {
        assertEquals(setOf("com.sun.net.httpserver.HttpHandler"), JdkHttpServerModule().handlerInterfaces)
    }

    private class UnrelatedType

    private fun named(name: String): TypeDescription = TypeDescription.Latent(name, 0, null)
}
