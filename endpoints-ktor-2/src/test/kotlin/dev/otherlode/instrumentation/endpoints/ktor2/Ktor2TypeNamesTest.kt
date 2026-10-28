package dev.otherlode.instrumentation.endpoints.ktor2

import kotlin.test.Test
import kotlin.test.assertEquals

class Ktor2TypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.ktor.server.routing.Route",
                "io.ktor.server.routing.Routing",
            ),
            Ktor2Module().typeNames,
        )
    }
}
