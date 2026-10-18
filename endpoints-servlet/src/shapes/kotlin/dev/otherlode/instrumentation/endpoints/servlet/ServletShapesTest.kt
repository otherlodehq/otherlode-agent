package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.AllVerbsServlet
import com.example.servlet.fixture.GenericOnlyServlet
import com.example.servlet.fixture.GetAndHeadServlet
import com.example.servlet.fixture.GetPostServlet
import com.example.servlet.fixture.InheritingServlet
import com.example.servlet.fixture.OuterServiceServlet
import com.example.servlet.fixture.ProtectedServiceServlet
import com.example.servlet.fixture.PublicServiceSuperServlet
import com.example.servlet.fixture.SubOfOwnedServlet
import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.registry.RouteTemplateNormalizer
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.implementation.StubMethod
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val HTTP_SERVLET = "jakarta.servlet.http.HttpServlet"

/** Reads of servlet class files that no embedded container can show. */
class ServletShapesTest {
    private val shapes = ServletShapes()
    private val loader: ClassLoader = ServletShapesTest::class.java.classLoader

    private fun counted(name: String): ServletShape.Counted = assertIs<ServletShape.Counted>(shapes.of(name, loader))

    private fun verbs(name: String): List<String> = counted(name).verbs.map { it.verb }

    @Test
    fun `an HttpServlet serves the verbs of the do methods its classes declare`() {
        assertEquals(listOf("GET", "POST"), verbs(GetPostServlet::class.java.name))
        assertEquals(listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "TRACE"), verbs(AllVerbsServlet::class.java.name))
        assertEquals(listOf("GET", "HEAD"), verbs(GetAndHeadServlet::class.java.name))
        assertEquals(listOf("GET", "POST"), verbs(InheritingServlet::class.java.name))
    }

    @Test
    fun `a GET counts a HEAD request unless the class declares doHead`() {
        val withoutHead = counted(GetPostServlet::class.java.name).verbs.single { it.verb == "GET" }
        assertEquals(listOf("GET", "HEAD"), withoutHead.requests)
        val withHead = counted(GetAndHeadServlet::class.java.name).verbs
        assertEquals(listOf("GET"), withHead.single { it.verb == "GET" }.requests)
        assertEquals(listOf("HEAD"), withHead.single { it.verb == "HEAD" }.requests)
    }

    @Test
    fun `a service override, or a servlet that is no HttpServlet, serves every verb`() {
        assertEquals(listOf("*"), verbs(ProtectedServiceServlet::class.java.name))
        assertEquals(listOf("*"), verbs(PublicServiceSuperServlet::class.java.name))
        assertEquals(listOf("*"), verbs(GenericOnlyServlet::class.java.name))
    }

    @Test
    fun `the entry class is the most derived class that declares the public service`() {
        assertEquals(HTTP_SERVLET, counted(GetPostServlet::class.java.name).entryClass)
        assertEquals(HTTP_SERVLET, counted(ProtectedServiceServlet::class.java.name).entryClass)
        assertEquals(PublicServiceSuperServlet::class.java.name, counted(PublicServiceSuperServlet::class.java.name).entryClass)
        assertEquals(OuterServiceServlet::class.java.name, counted(OuterServiceServlet::class.java.name).entryClass)
        assertEquals(GenericOnlyServlet::class.java.name, counted(GenericOnlyServlet::class.java.name).entryClass)
    }

    @Test
    fun `doPatch declares PATCH only where the servlet API's HttpServlet declares it`() {
        val patchServlet = "com.example.servlet.fixture.PatchServlet"
        assertEquals(listOf("GET"), verbs(patchServlet))

        val servlet61 = ServletShapes()
        val api61 =
            ByteBuddy()
                .subclass(
                    Any::class.java,
                ).name(HTTP_SERVLET)
                .defineMethod("doPatch", Void.TYPE, Visibility.PROTECTED)
                .intercept(StubMethod.INSTANCE)
                .make()
                .bytes
        val shape =
            servlet61.of(
                patchServlet,
                object : ClassLoader(loader) {
                    override fun getResourceAsStream(name: String): InputStream? =
                        if (name ==
                            "jakarta/servlet/http/HttpServlet.class"
                        ) {
                            ByteArrayInputStream(api61)
                        } else {
                            super.getResourceAsStream(name)
                        }
                },
            )
        assertEquals(listOf("GET", "PATCH"), assertIs<ServletShape.Counted>(shape).verbs.map { it.verb })
    }

    @Test
    fun `a servlet another module counts behind is left out by name and by hierarchy`() {
        assertEquals(ServletShape.Left, shapes.of("io.javalin.http.servlet.JavalinServlet", loader))
        assertEquals(ServletShape.Left, shapes.of(SubOfOwnedServlet::class.java.name, loader))
    }

    @Test
    fun `a container servlet is left out by its exact name only`() {
        assertEquals(ServletShape.Left, shapes.of("org.apache.catalina.servlets.DefaultServlet", loader))
        assertEquals(ServletShape.Left, shapes.of("org.apache.jasper.servlet.JspServlet", loader))
        assertNotNull(counted("org.apache.catalina.servlets.WebdavServlet"))
    }

    @Test
    fun `a class file that cannot be read is reported`() {
        assertFailsWith<UnreadableServlet> { shapes.of("com.example.NoSuchServlet", loader) }
    }

    @Test
    fun `a mapping is read as one of the five specification forms`() {
        assertEquals("/" to OtherlodeEndpoints.TEMPLATE_PATH, readMapping("")?.let { it.template to it.kind })
        assertEquals("/" to OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN, readMapping("/")?.let { it.template to it.kind })
        assertEquals("/a/b" to OtherlodeEndpoints.TEMPLATE_PATH, readMapping("/a/b")?.let { it.template to it.kind })
        assertEquals("/a/*" to OtherlodeEndpoints.TEMPLATE_PATH, readMapping("/a/*")?.let { it.template to it.kind })
        assertEquals("/*" to OtherlodeEndpoints.TEMPLATE_PATH, readMapping("/*")?.let { it.template to it.kind })
        assertEquals("*.do" to OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN, readMapping("*.do")?.let { it.template to it.kind })
    }

    @Test
    fun `an exact mapping the normaliser would change, or that holds a star or brace, is a servlet pattern`() {
        for (kept in listOf("/a/", "/a*", "/a{b}", "/a/{id}", "/a/*/b", "/a//b", "/a/**", "/a/<b>", "/a//*", "/a/{b}/*", "*.d*", "*.{x}")) {
            val mapping = assertNotNull(readMapping(kept), kept)
            assertEquals(kept to OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN, mapping.template to mapping.kind, kept)
        }
        for (path in listOf("/a", "/a/b", "/a/*", "/*", "/a/b-c.d", "/Orders/*")) {
            val mapping = assertNotNull(readMapping(path), path)
            assertEquals(path to OtherlodeEndpoints.TEMPLATE_PATH, mapping.template to mapping.kind, path)
            assertEquals(path, RouteTemplateNormalizer.normalize(path), "a path template is its own identity")
        }
    }

    @Test
    fun `a servlet is left out only where the module that counts behind it covers the release`() {
        val withoutJavalin6 = hiding("io/javalin/router/ParsedEndpoint.class")
        val withoutKtor2 = hiding("io/ktor/server/routing/Route.class")
        val javalin = "io.javalin.http.servlet.JavalinServlet"
        val ktor = "io.ktor.server.servlet.ServletApplicationEngine"

        assertEquals(ServletShape.Left, ServletShapes().of(javalin, loader))
        assertEquals(ServletShape.Left, ServletShapes().of(ktor, loader))
        assertIs<ServletShape.Counted>(ServletShapes().of(javalin, withoutJavalin6), "Javalin 5 ships the servlet and no module")
        assertIs<ServletShape.Counted>(ServletShapes().of(ktor, withoutKtor2), "Ktor 1 ships the servlet and no module")
        assertEquals(ServletShape.Left, ServletShapes().of(javalin, withoutKtor2), "the markers are per framework")
        assertEquals(ServletShape.Left, ServletShapes().of(ktor, withoutJavalin6))
        assertIs<ServletShape.Counted>(
            ServletShapes().of(SubOfOwnedServlet::class.java.name, withoutJavalin6),
            "a subclass of an uncovered servlet is counted as a servlet",
        )
    }

    private fun hiding(vararg resources: String): ClassLoader =
        object : ClassLoader(loader) {
            override fun getResource(name: String): URL? = if (name in resources) null else super.getResource(name)
        }

    @Test
    fun `a mapping outside the five forms is not read`() {
        for (outside in listOf("^/a/.*", "a/b", "*.", "*.a/b", "regex|/x", "x")) {
            assertNull(readMapping(outside), outside)
        }
    }
}
