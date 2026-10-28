package dev.otherlode.instrumentation.endpoints.micronaut

import kotlin.test.Test
import kotlin.test.assertEquals

class MicronautTypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.micronaut.web.router.DefaultRouter",
                "io.micronaut.http.server.RouteExecutor",
            ),
            MicronautModule().typeNames,
        )
    }
}
