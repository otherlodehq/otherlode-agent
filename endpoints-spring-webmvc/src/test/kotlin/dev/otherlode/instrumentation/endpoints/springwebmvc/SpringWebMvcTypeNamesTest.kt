package dev.otherlode.instrumentation.endpoints.springwebmvc

import kotlin.test.Test
import kotlin.test.assertEquals

class SpringWebMvcTypeNamesTest {
    @Test
    fun `the module names the classes it hooks`() {
        assertEquals(
            setOf(
                "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping",
                "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping",
                "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping",
                "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping\$PathExposingHandlerInterceptor",
                "org.springframework.web.servlet.function.support.RouterFunctionMapping",
                "org.springframework.web.servlet.function.PathResourceLookupFunction",
            ),
            SpringWebMvcModule().typeNames,
        )
    }
}
