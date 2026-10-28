package dev.otherlode.instrumentation.endpoints.javalin7

import kotlin.test.Test
import kotlin.test.assertEquals

class Javalin7TypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.javalin.router.ParsedEndpoint",
                "io.javalin.apibuilder.ApiBuilder",
            ),
            Javalin7Module().typeNames,
        )
    }
}
