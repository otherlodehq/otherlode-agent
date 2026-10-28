package dev.otherlode.instrumentation.endpoints.micronaut3

import kotlin.test.Test
import kotlin.test.assertEquals

class Micronaut3TypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "io.micronaut.web.router.DefaultRouter",
                "io.micronaut.http.server.netty.RoutingInBoundHandler",
                "io.micronaut.servlet.http.ServletHttpHandler",
                "io.micronaut.function.aws.proxy.MicronautLambdaContainerHandler",
            ),
            Micronaut3Module().typeNames,
        )
    }
}
