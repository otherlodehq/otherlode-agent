package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import dev.otherlode.export.DisabledEndpointModuleKind
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletMapping
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.MappingMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two ways the module switches itself off once it is running. Each switch-off is process-wide,
 * so each test runs in its own JVM.
 */
class ServletModuleDisablingTest : ServletTestSupport() {
    @Test
    fun `a mapping outside the five forms disables the module and declares nothing`() {
        container
            .start("") {
                servlet("fine", GetPostServlet(), "/disabling/fine")
                servlet("template", GetPostServlet(), "/disabling/{id}")
            }.use { app ->
                app.send("/disabling/fine")

                val disabled = registry.disabledModules().single()
                assertEquals("servlet", disabled.module)
                assertEquals(DisabledEndpointModuleKind.ROUTE_WALK_FAILED, disabled.kind)
                assertTrue("/disabling/{id}" in disabled.reason, disabled.reason)
                assertEquals(emptySet(), declared("/disabling"), "a read that fails declares nothing of it")
            }
    }
}

/** A declared servlet that the container matched with no match type disables the module. */
class ServletModuleNullMatchTest : ServletTestSupport() {
    @Test
    fun `a dispatch to a declared servlet with no mapping match type disables the module`() {
        val stripsMatch =
            object : Filter {
                override fun doFilter(
                    request: ServletRequest,
                    response: ServletResponse,
                    chain: FilterChain,
                ) {
                    chain.doFilter(NoMatchRequest(request as HttpServletRequest), response)
                }
            }
        container
            .start("") {
                servlet("nomatch", GetPostServlet(), "/nomatch/a")
                filter("strip", stripsMatch, "/nomatch/*")
            }.use { app ->
                app.send("/nomatch/a")

                val disabled = registry.disabledModules().single()
                assertEquals("servlet", disabled.module)
                assertEquals(DisabledEndpointModuleKind.ADVICE_FAILED, disabled.kind)
                assertEquals(0, hits("GET", "/nomatch/a"))
            }
    }
}

private class NoMatchRequest(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    override fun getHttpServletMapping(): HttpServletMapping {
        val real = super.getHttpServletMapping()
        return object : HttpServletMapping {
            override fun getMatchValue(): String = real.matchValue

            override fun getPattern(): String = real.pattern

            override fun getServletName(): String = real.servletName

            override fun getMappingMatch(): MappingMatch? = null
        }
    }
}
