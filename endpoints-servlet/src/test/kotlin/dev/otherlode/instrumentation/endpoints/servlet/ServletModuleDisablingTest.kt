package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import dev.otherlode.export.DisabledEndpointModuleKind
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRegistration
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletMapping
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.MappingMatch
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A switch-off is process-wide, so each test that switches the module off sits in a class of its own,
// and the build forks a JVM per test class.
class ServletModuleDisablingTest : ServletTestSupport() {
    @Test
    fun `a mapping outside the five forms disables the module and declares nothing`() {
        val context = contextWithMappings("no-leading-slash" to listOf("disabling/x"), "fine" to listOf("/disabling/fine"))
        ServletModule().declare(context)

        val disabled = registry.disabledModules().single()
        assertEquals("servlet", disabled.module)
        assertEquals(DisabledEndpointModuleKind.ROUTE_WALK_FAILED, disabled.kind)
        assertTrue("disabling/x" in disabled.reason, disabled.reason)
        assertEquals(emptySet(), declared("/disabling"), "a read that fails declares nothing of it")
    }
}

private fun contextWithMappings(
    vararg mappings: Pair<String, List<String>>,
    failingMethod: String = "",
    failure: () -> Throwable? = { null },
): ServletContext {
    val loader = ServletModuleDisablingTest::class.java.classLoader
    val registrations =
        mappings.associate { (name, patterns) ->
            name to
                Proxy.newProxyInstance(loader, arrayOf(ServletRegistration::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getName" -> name
                        "getClassName" -> GetPostServlet::class.java.name
                        "getMappings" -> patterns
                        else -> null
                    }
                } as ServletRegistration
        }
    return Proxy.newProxyInstance(loader, arrayOf(ServletContext::class.java)) { _, method, _ ->
        if (method.name == failingMethod) failure()?.let { throw it }
        if (method.name == "getServletRegistrations") registrations else null
    } as ServletContext
}

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
                assertEquals(emptySet(), declared("/nomatch"), "nothing is declared for a request that cannot be counted")
            }
    }
}

class ServletModuleDefaultMappingTest : ServletTestSupport() {
    @Test
    fun `a request that answers with the API default mapping disables the module with LINKAGE_ERROR before declaring anything`() {
        assumeFalse(
            container.javaClass.simpleName.startsWith("Undertow"),
            "Undertow refuses a request that is neither its own nor a wrapper",
        )
        val hidesMapping =
            object : Filter {
                override fun doFilter(
                    request: ServletRequest,
                    response: ServletResponse,
                    chain: FilterChain,
                ) {
                    chain.doFilter(withoutMapping(request as HttpServletRequest), response)
                }
            }
        container
            .start("") {
                servlet("plain", GetPostServlet(), "/default/a")
                servlet("other", GetPostServlet(), "/default/b")
                filter("hide", hidesMapping, "/default/a")
            }.use { app ->
                assertEquals(200, app.send("/default/a"))

                val disabled = registry.disabledModules().single()
                assertEquals("servlet", disabled.module)
                assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, disabled.kind)
                assertEquals(emptySet(), declared("/default"), "no servlet is declared to be read as never called")
                assertEquals(200, app.send("/default/b"))
                assertEquals(emptySet(), declared("/default"))
            }
    }
}

private fun withoutMapping(request: HttpServletRequest): HttpServletRequest =
    Proxy.newProxyInstance(HttpServletRequest::class.java.classLoader, arrayOf(HttpServletRequest::class.java)) { proxy, method, args ->
        val arguments = args.orEmpty()
        if (method.name == "getHttpServletMapping") {
            InvocationHandler.invokeDefault(proxy, method, *arguments)
        } else {
            try {
                method.invoke(request, *arguments)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as HttpServletRequest

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

class ServletModuleUnreadableContextTest : ServletTestSupport() {
    @Test
    fun `a context that throws IllegalStateException from getServletRegistrations disables the module`() {
        val context =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ServletContext::class.java),
            ) { _, method, _ ->
                if (method.name == "getServletRegistrations") throw IllegalStateException("not usable") else null
            }
        ServletModule().declare(context)

        val disabled = registry.disabledModules().single()
        assertEquals("servlet", disabled.module)
        assertEquals(DisabledEndpointModuleKind.ROUTE_WALK_FAILED, disabled.kind)
        assertEquals("java.lang.IllegalStateException", disabled.reason)
    }
}

class ServletModuleDestroyedContextTest : ServletTestSupport() {
    @Test
    fun `a context that throws IllegalStateException from getClassLoader on a refresh is dropped and the module stays on`() {
        assertDroppedAfter("getClassLoader", "/destroyed/loader")
    }

    @Test
    fun `a context that throws IllegalStateException from getServletRegistrations on a refresh is dropped and the module stays on`() {
        assertDroppedAfter("getServletRegistrations", "/destroyed/registrations")
    }

    @Test
    fun `a dropped context is read no more until a request declares it again`() {
        var destroyed = false
        var reads = 0
        val context =
            contextWithMappings("again" to listOf("/destroyed/again"), failingMethod = "getClassLoader") {
                reads++
                if (destroyed) IllegalStateException("destroyed") else null
            }
        val module = ServletModule()
        module.declare(context)
        assertEquals(1, reads)

        destroyed = true
        module.beforeManifest(0, false)
        assertEquals(2, reads)
        module.beforeManifest(0, false)
        assertEquals(2, reads, "a dropped context is not read on the next manifest")

        destroyed = false
        module.declare(context)
        assertEquals(3, reads, "a request declares a dropped context afresh")
        assertEquals(emptyList(), registry.disabledModules())
        assertEquals(setOf("GET /destroyed/again", "POST /destroyed/again"), declared("/destroyed/again"))
    }

    private fun assertDroppedAfter(
        failingMethod: String,
        mapping: String,
    ) {
        var destroyed = false
        val context =
            contextWithMappings("kept" to listOf(mapping), failingMethod = failingMethod) {
                if (destroyed) IllegalStateException("destroyed") else null
            }
        val module = ServletModule()
        module.declare(context)
        val before = setOf("GET $mapping", "POST $mapping")
        assertEquals(before, declared(mapping))

        destroyed = true
        module.beforeManifest(0, false)
        module.beforeManifest(0, false)

        assertEquals(emptyList(), registry.disabledModules())
        assertEquals(before, declared(mapping), "the endpoints it declared stay")
    }
}

class ServletModuleRefreshFailureTest : ServletTestSupport() {
    @Test
    fun `a context that throws another exception on a refresh disables the module`() {
        var broken = false
        val context =
            contextWithMappings("live" to listOf("/refresh/live"), failingMethod = "getClassLoader") {
                if (broken) UnsupportedOperationException("broken") else null
            }
        val module = ServletModule()
        module.declare(context)
        assertEquals(emptyList(), registry.disabledModules())

        broken = true
        module.beforeManifest(0, false)

        val disabled = registry.disabledModules().single()
        assertEquals("servlet", disabled.module)
        assertEquals(DisabledEndpointModuleKind.ROUTE_WALK_FAILED, disabled.kind)
        assertEquals("java.lang.UnsupportedOperationException", disabled.reason)
    }
}

class ServletModuleStoppedApplicationTest : ServletTestSupport() {
    @Test
    fun `a flush after the container stopped the application keeps its endpoints and leaves the module on`() {
        container
            .start("") {
                servlet("stopped", GetPostServlet(), "/stopped/a")
            }.use { app ->
                assertEquals(200, app.send("/stopped/a"))
                assertEquals(setOf("GET /stopped/a", "POST /stopped/a"), declared("/stopped"))
            }

        ServletTestAgent.flush()

        assertEquals(emptyList(), registry.disabledModules())
        assertEquals(setOf("GET /stopped/a", "POST /stopped/a"), declared("/stopped"))
    }
}
