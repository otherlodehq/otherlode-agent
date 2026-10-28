package dev.otherlode.instrumentation.endpoints.springwebflux

import kotlin.test.Test
import kotlin.test.assertEquals

class SpringWebFluxTypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "org.springframework.web.reactive.result.method.AbstractHandlerMethodMapping",
                "org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping",
                "org.springframework.web.reactive.handler.AbstractUrlHandlerMapping",
                "org.springframework.web.reactive.function.server.support.RouterFunctionMapping",
                "org.springframework.web.reactive.function.server.PathResourceLookupFunction",
            ),
            SpringWebFluxModule().typeNames,
        )
    }
}
