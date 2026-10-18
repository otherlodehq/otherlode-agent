package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.AllVerbsServlet
import com.example.servlet.fixture.BareServlet
import com.example.servlet.fixture.BaseGetServlet
import com.example.servlet.fixture.FailingServlet
import com.example.servlet.fixture.ForwardingServlet
import com.example.servlet.fixture.GenericOnlyServlet
import com.example.servlet.fixture.GetAndHeadServlet
import com.example.servlet.fixture.GetPostServlet
import com.example.servlet.fixture.IncludingServlet
import com.example.servlet.fixture.InheritingServlet
import com.example.servlet.fixture.OuterServiceServlet
import com.example.servlet.fixture.ProtectedServiceServlet
import com.example.servlet.fixture.ProtectedServiceSuperServlet
import com.example.servlet.fixture.PublicServiceSuperServlet
import com.example.servlet.fixture.SubOfOwnedServlet
import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointTemplateKind
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HTTP = "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V"
private const val FIXTURE = "com.example.servlet.fixture"

/**
 * Proves the servlet module end to end against a real embedded container and a real `-javaagent`
 * (see [ServletTestAgent]). Every test uses templates of its own, since the registry is one per JVM.
 */
class ServletModuleTest : ServletTestSupport() {
    @AfterEach
    fun moduleStillOn() {
        assertTrue(registry.disabledModules().isEmpty(), "no module switched itself off: ${registry.disabledModules()}")
    }

    @Test
    fun `a context is declared at its first request, with a mapping never called`() {
        container
            .start("") {
                servlet("a", GetPostServlet(), "/declared/a")
                servlet("b", GetPostServlet(), "/declared/b", "/declared/b2/*")
            }.use { app ->
                assertEquals(emptySet(), declared("/declared"), "nothing is declared before a request")

                assertEquals(200, app.send("/declared/a"))

                assertEquals(
                    setOf(
                        "GET /declared/a",
                        "POST /declared/a",
                        "GET /declared/b",
                        "POST /declared/b",
                        "GET /declared/b2/*",
                        "POST /declared/b2/*",
                    ),
                    declared("/declared"),
                )
                assertEquals(1, hits("GET", "/declared/a"))
                assertEquals(0, hits("GET", "/declared/b"))
                assertEquals(0, hits("POST", "/declared/a"))
                for (endpoint in servletEndpoints().filter { it.routeTemplate.startsWith("/declared") }) {
                    assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                    assertEquals(EndpointTemplateKind.PATH, endpoint.templateKind)
                }
            }
    }

    @Test
    fun `the verbs are those of the do methods, and each request counts against its verb`() {
        container
            .start("") {
                servlet("all", AllVerbsServlet(), "/verbs/all")
            }.use { app ->
                assertEquals(200, app.send("/verbs/all"))
                assertEquals(
                    setOf(
                        "GET /verbs/all",
                        "POST /verbs/all",
                        "PUT /verbs/all",
                        "DELETE /verbs/all",
                        "OPTIONS /verbs/all",
                        "TRACE /verbs/all",
                    ),
                    declared("/verbs"),
                    "doPatch declares nothing before Servlet 6.1, and HEAD is no verb of its own",
                )
                app.send("/verbs/all", "POST")
                app.send("/verbs/all", "POST")
                app.send("/verbs/all", "PUT")
                app.send("/verbs/all", "DELETE")
                app.send("/verbs/all", "OPTIONS")
                assertEquals(1, hits("GET", "/verbs/all"))
                assertEquals(2, hits("POST", "/verbs/all"))
                assertEquals(1, hits("PUT", "/verbs/all"))
                assertEquals(1, hits("DELETE", "/verbs/all"))
                assertEquals(1, hits("OPTIONS", "/verbs/all"))
                assertEquals(0, hits("TRACE", "/verbs/all"))
            }
    }

    @Test
    fun `a HEAD request counts against GET`() {
        container
            .start("") {
                servlet("plain", GetPostServlet(), "/head/plain")
            }.use { app ->
                assertEquals(200, app.send("/head/plain", "HEAD"))
                assertNull(endpoint("HEAD", "/head/plain"))
                assertEquals(1, hits("GET", "/head/plain"))
            }
    }

    @Test
    fun `HEAD is a verb of its own when the class declares doHead`() {
        container
            .start("") {
                servlet("own", GetAndHeadServlet(), "/head/own")
            }.use { app ->
                assertEquals(200, app.send("/head/own", "HEAD"))
                assertEquals(setOf("GET /head/own", "HEAD /head/own"), declared("/head/own"))
                assertEquals(1, hits("HEAD", "/head/own"))
                assertEquals(0, hits("GET", "/head/own"))
                assertEquals(200, app.send("/head/own"))
                assertEquals(1, hits("GET", "/head/own"))
            }
    }

    @Test
    fun `a verb the servlet does not declare counts nothing`() {
        container
            .start("") {
                servlet("getonly", BaseGetServlet(), "/m405/get")
            }.use { app ->
                assertEquals(200, app.send("/m405/get"))
                val before = totalHits()
                assertEquals(405, app.send("/m405/get", "DELETE"))
                assertEquals(405, app.send("/m405/get", "POST"))
                assertEquals(before, totalHits())
                assertEquals(1, hits("GET", "/m405/get"))
            }
    }

    @Test
    fun `a servlet that overrides service, or is no HttpServlet, serves every verb`() {
        container
            .start("") {
                servlet("protected", ProtectedServiceServlet(), "/star/protected")
                servlet("generic", GenericOnlyServlet(), "/star/generic")
                servlet("bare", BareServlet(), "/star/bare")
            }.use { app ->
                assertEquals(200, app.send("/star/protected"))
                assertEquals(200, app.send("/star/protected", "PURGE"))
                assertEquals(200, app.send("/star/generic"))
                assertEquals(200, app.send("/star/generic", "POST"))
                assertEquals(200, app.send("/star/bare"))
                assertEquals(setOf("* /star/protected", "* /star/generic", "* /star/bare"), declared("/star"))
                assertEquals(2, hits("*", "/star/protected"))
                assertEquals(2, hits("*", "/star/generic"))
                assertEquals(1, hits("*", "/star/bare"))
            }
    }

    @Test
    fun `a request counts once when a service override calls super`() {
        container
            .start("") {
                servlet("public", PublicServiceSuperServlet(), "/once/public")
                servlet("protected", ProtectedServiceSuperServlet(), "/once/protected")
                servlet("outer", OuterServiceServlet(), "/once/outer")
                servlet("plain", GetPostServlet(), "/once/plain")
            }.use { app ->
                for (path in listOf("/once/public", "/once/protected", "/once/outer", "/once/plain")) {
                    assertEquals(200, app.send(path))
                    assertEquals(200, app.send(path))
                }
                assertEquals(2, hits("*", "/once/public"))
                assertEquals(2, hits("*", "/once/protected"))
                assertEquals(2, hits("*", "/once/outer"))
                assertEquals(2, hits("GET", "/once/plain"))
            }
    }

    @Test
    fun `a forward counts the first servlet only`() {
        container
            .start("") {
                servlet("start", ForwardingServlet("/fwd/target"), "/fwd/start")
                servlet("target", GetPostServlet(), "/fwd/target")
            }.use { app ->
                assertEquals(200, app.send("/fwd/start"))
                assertEquals(1, hits("GET", "/fwd/start"))
                assertEquals(0, hits("GET", "/fwd/target"))
            }
    }

    @Test
    fun `an include counts the including servlet only`() {
        container
            .start("") {
                servlet("start", IncludingServlet("/inc/target"), "/inc/start")
                servlet("target", GetPostServlet(), "/inc/target")
            }.use { app ->
                assertEquals(200, app.send("/inc/start"))
                assertEquals(1, hits("GET", "/inc/start"))
                assertEquals(0, hits("GET", "/inc/target"))
            }
    }

    @Test
    fun `an error page dispatch counts nothing`() {
        container
            .start("") {
                servlet("boom", FailingServlet(), "/err/boom")
                servlet("page", GetPostServlet(), "/err/page")
                errorPage(500, "/err/page")
            }.use { app ->
                assertEquals(500, app.send("/err/boom"))
                assertEquals(1, hits("GET", "/err/boom"))
                assertEquals(0, hits("GET", "/err/page"))
            }
    }

    @Test
    fun `a filter that answers first leaves the request uncounted`() {
        val answersFirst =
            object : Filter {
                override fun doFilter(
                    request: ServletRequest,
                    response: ServletResponse,
                    chain: FilterChain,
                ) {
                    (response as HttpServletResponse).status = 202
                }
            }
        container
            .start("") {
                servlet("filtered", GetPostServlet(), "/filter/blocked")
                servlet("open", GetPostServlet(), "/filter/open")
                filter("blocker", answersFirst, "/filter/blocked")
            }.use { app ->
                assertEquals(200, app.send("/filter/open"))
                assertEquals(202, app.send("/filter/blocked"))
                assertEquals(0, hits("GET", "/filter/blocked"))
                assertEquals(1, hits("GET", "/filter/open"))
            }
    }

    @Test
    fun `the container's default and JSP servlets are left out, and a subclass of one is counted`() {
        container
            .start("") {
                containerDefaults()
                servlet("app", GetPostServlet(), "/defaults/app")
                servlet("mine", MyDefaultServlet(), "/defaults/mine/*")
            }.use { app ->
                assertEquals(200, app.send("/defaults/app"))
                assertEquals(404, app.send("/defaults/missing.txt"))
                assertEquals(404, app.send("/defaults/missing.jsp"))
                val containerHandlers = servletEndpoints().filter { it.handlerClass?.startsWith("org.apache.") == true }
                assertEquals(emptyList(), containerHandlers.filter { it.handlerClass != MyDefaultServlet::class.java.superclass.name })
                assertNull(endpoint("*", "*.jsp", EndpointTemplateKind.SERVLET_PATTERN))
                assertNull(servletEndpoints().firstOrNull { it.routeTemplate == "*.jsp" || it.routeTemplate == "*.jspx" })
                assertTrue(declared("/defaults/mine").isNotEmpty(), "a subclass of the default servlet is adopter code")
                assertEquals(404, app.send("/defaults/mine/x"))
                assertEquals(1, hits("*", "/defaults/mine/*"))
            }
    }

    @Test
    fun `a servlet another module counts behind is left out, and so is a subclass of it`() {
        container
            .start("") {
                servlet(
                    "own",
                    io.javalin.http.servlet
                        .JavalinServlet(),
                    "/owned/direct",
                )
                servlet("sub", SubOfOwnedServlet(), "/owned/sub")
                servlet("plain", GetPostServlet(), "/owned/plain")
            }.use { app ->
                assertEquals(200, app.send("/owned/plain"))
                assertEquals(200, app.send("/owned/direct"))
                assertEquals(200, app.send("/owned/sub"))
                assertEquals(setOf("GET /owned/plain", "POST /owned/plain"), declared("/owned"))
            }
    }

    @Test
    fun `an extension mapping and the default mapping are servlet pattern templates`() {
        container
            .start("") {
                servlet("ext", GetPostServlet(), "*.pattern")
                servlet("fallback", BaseGetServlet(), "/")
            }.use { app ->
                assertEquals(200, app.send("/anything/at/all.pattern"))
                assertEquals(200, app.send("/no/such/path"))
                val extension = assertNotNull(endpoint("GET", "*.pattern", EndpointTemplateKind.SERVLET_PATTERN))
                assertEquals("*.pattern", extension.verbatimTemplate)
                assertNotNull(endpoint("POST", "*.pattern", EndpointTemplateKind.SERVLET_PATTERN))
                assertNotNull(endpoint("GET", "/", EndpointTemplateKind.SERVLET_PATTERN))
                assertEquals(1, hits("GET", "*.pattern", EndpointTemplateKind.SERVLET_PATTERN))
                assertEquals(1, hits("GET", "/", EndpointTemplateKind.SERVLET_PATTERN))
                val fallback = servletEndpoints().filter { it.handlerClass == "$FIXTURE.BaseGetServlet" && it.routeTemplate == "/" }
                assertEquals(
                    listOf(EndpointTemplateKind.SERVLET_PATTERN),
                    fallback.map { it.templateKind },
                    "the default mapping is no path template",
                )
            }
    }

    @Test
    fun `the context root is the path slash`() {
        container
            .start("") {
                servlet("root", GetPostServlet(), "")
            }.use { app ->
                assertEquals(200, app.send(""))
                val root = assertNotNull(endpoint("GET", "/"))
                assertEquals(EndpointTemplateKind.PATH, root.templateKind)
                assertEquals(1, hits("GET", "/"))
            }
    }

    @Test
    fun `the context path is not part of the template`() {
        container
            .start("/shop") {
                servlet("items", GetPostServlet(), "/path/items", "/path/more/*")
            }.use { app ->
                assertEquals(200, app.send("/path/items"))
                assertEquals(setOf("GET /path/items", "POST /path/items", "GET /path/more/*", "POST /path/more/*"), declared("/path"))
                assertEquals(1, hits("GET", "/path/items"))
                assertEquals(0, servletEndpoints().count { it.routeTemplate.startsWith("/shop") })
            }
    }

    @Test
    fun `a mapping added after the start is declared after the next flush, read on the flush thread`() {
        container
            .start("") {
                servlet("late", GetPostServlet(), "/late/first")
            }.use { app ->
                assertEquals(200, app.send("/late/first"))
                assertEquals(setOf("GET /late/first", "POST /late/first"), declared("/late"))

                app.addMappingAfterStart("late", "/late/second")

                assertEquals(200, app.send("/late/second"), "a request between the add and the flush is served")
                assertEquals(setOf("GET /late/first", "POST /late/first"), declared("/late"), "declared at the flush, not before")

                val flushThread = ServletTestAgent.flush()
                assertNotEquals(Thread.currentThread().name, flushThread)
                assertEquals(
                    setOf("GET /late/first", "POST /late/first", "GET /late/second", "POST /late/second"),
                    declared("/late"),
                )
                assertEquals(0, hits("GET", "/late/second"), "the request before the flush counted nothing")

                assertEquals(200, app.send("/late/second"))
                assertEquals(1, hits("GET", "/late/second"))
                assertEquals(1, hits("GET", "/late/first"))
            }
    }

    @Test
    fun `the handler is the do method that serves the verb, or service`() {
        container
            .start("") {
                servlet("inheriting", InheritingServlet(), "/join/inheriting")
                servlet("service", ProtectedServiceServlet(), "/join/service")
                servlet("generic", GenericOnlyServlet(), "/join/generic")
            }.use { app ->
                app.send("/join/inheriting")
                val get = assertNotNull(endpoint("GET", "/join/inheriting"))
                assertEquals("$FIXTURE.BaseGetServlet", get.handlerClass)
                assertEquals("doGet", get.handlerMethod)
                assertEquals(HTTP, get.handlerDescriptor)
                val post = assertNotNull(endpoint("POST", "/join/inheriting"))
                assertEquals("$FIXTURE.InheritingServlet", post.handlerClass)
                assertEquals("doPost", post.handlerMethod)

                val service = assertNotNull(endpoint("*", "/join/service"))
                assertEquals("$FIXTURE.ProtectedServiceServlet", service.handlerClass)
                assertEquals("service", service.handlerMethod)
                assertEquals(HTTP, service.handlerDescriptor)

                val generic = assertNotNull(endpoint("*", "/join/generic"))
                assertEquals("$FIXTURE.GenericOnlyServlet", generic.handlerClass)
                assertEquals("service", generic.handlerMethod)
                assertEquals("(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V", generic.handlerDescriptor)
            }
    }
}

/** An adopter's subclass of the container's default servlet. */
class MyDefaultServlet : org.apache.catalina.servlets.DefaultServlet()
