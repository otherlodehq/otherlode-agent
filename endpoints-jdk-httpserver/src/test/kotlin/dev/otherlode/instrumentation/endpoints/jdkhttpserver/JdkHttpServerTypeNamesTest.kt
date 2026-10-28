package dev.otherlode.instrumentation.endpoints.jdkhttpserver

import kotlin.test.Test
import kotlin.test.assertEquals

class JdkHttpServerTypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "sun.net.httpserver.ServerImpl",
                "sun.net.httpserver.HttpContextImpl",
                "sun.net.httpserver.ContextList",
            ),
            JdkHttpServerModule().typeNames,
        )
    }
}
