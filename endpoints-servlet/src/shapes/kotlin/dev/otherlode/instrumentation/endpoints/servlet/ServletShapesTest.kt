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
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.implementation.StubMethod
import java.io.ByteArrayInputStream
import java.io.InputStream
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
    fun `a mapping outside the five forms is not read`() {
        for (outside in listOf("/a/{id}", "^/a/.*", "a/b", "/a/*/b", "*.", "*.a/b", "/a*", "regex|/x", "x")) {
            assertNull(readMapping(outside), outside)
        }
    }
}
