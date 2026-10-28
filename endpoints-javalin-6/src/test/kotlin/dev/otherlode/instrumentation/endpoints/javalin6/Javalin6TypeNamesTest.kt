package dev.otherlode.instrumentation.endpoints.javalin6

import kotlin.test.Test
import kotlin.test.assertEquals

class Javalin6TypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.javalin.router.ParsedEndpoint",
                "io.javalin.apibuilder.ApiBuilder",
            ),
            Javalin6Module().typeNames,
        )
    }
}
