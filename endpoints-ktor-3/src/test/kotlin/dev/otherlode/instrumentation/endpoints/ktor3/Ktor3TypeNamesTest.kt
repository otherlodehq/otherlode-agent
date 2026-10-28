package dev.otherlode.instrumentation.endpoints.ktor3

import kotlin.test.Test
import kotlin.test.assertEquals

class Ktor3TypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.ktor.server.routing.RoutingNode",
                "io.ktor.server.routing.RoutingRoot",
            ),
            Ktor3Module().typeNames,
        )
    }
}
