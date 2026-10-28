package dev.otherlode.instrumentation.endpoints.vertxweb

import kotlin.test.Test
import kotlin.test.assertEquals

class VertxWebTypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.vertx.ext.web.impl.RouterImpl",
                "io.vertx.ext.web.impl.RouteImpl",
                "io.vertx.ext.web.impl.RouteState",
            ),
            VertxWebModule().typeNames,
        )
    }
}
