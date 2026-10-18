package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.export.EndpointLocation
import dev.otherlode.export.EndpointTemplateKind
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun sendHttp(
    port: Int,
    path: String,
    method: String,
): Int {
    val request =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
}

/**
 * A Spring Boot application on embedded Tomcat, with the Spring MVC module and the servlet module
 * both installed by a real `-javaagent` (see [BootTestAgent]): Spring's `dispatcherServlet` is left
 * out of the servlet module, a request counts once, and a servlet Boot registers is counted.
 */
class SpringBootServletTest {
    private val registry get() = BootTestAgent.endpointRegistry

    private fun endpoints(framework: String): List<EndpointLocation> = registry.endpoints().filter { it.framework == framework }

    private fun hits(endpoint: EndpointLocation): Long =
        registry
            .computeDeltas(maxPerBatch = 10_000)
            .flatMap { it.deltas }
            .firstOrNull { it.endpointId == endpoint.endpointId }
            ?.hitsTotal ?: 0L

    private fun frameworkHits(framework: String): Long = endpoints(framework).sumOf { hits(it) }

    @Test
    fun `dispatcherServlet is left out, a controller counts once in the Spring MVC module, and a registered servlet counts in the servlet module`() {
        val context =
            SpringApplicationBuilder(BootApp::class.java)
                .properties("server.port=0", "spring.h2.console.enabled=true", "spring.main.banner-mode=off")
                .run()
        context.use {
            val port = (context as ServletWebServerApplicationContext).webServer.port

            assertEquals(200, sendHttp(port, "/boot/hello", "GET"))

            val servlet = endpoints("servlet")
            val spring = endpoints("spring-webmvc")
            assertEquals(emptyList(), registry.disabledModules(), "no module switched itself off")
            assertEquals(
                setOf("GET /boot/plain", "GET /h2-console/*", "POST /h2-console/*"),
                servlet.map { "${it.verb} ${it.routeTemplate}" }.toSet(),
                "dispatcherServlet declares nothing; the plain servlet and the H2 console are declared with their verbs",
            )
            assertTrue(servlet.none { it.routeTemplate == "/" }, "dispatcherServlet is no endpoint at the default mapping: $servlet")
            assertTrue(servlet.none { it.handlerClass.orEmpty().startsWith("org.springframework.") }, servlet.toString())
            assertTrue(servlet.all { it.templateKind == EndpointTemplateKind.PATH })
            val hello = spring.single { it.verb == "GET" && it.routeTemplate == "/boot/hello" }
            assertEquals(1, hits(hello), "the Spring MVC module counts the request")
            assertEquals(0, frameworkHits("servlet"), "and the servlet module counts nothing for it")

            assertEquals(404, sendHttp(port, "/boot/missing", "GET"))
            assertEquals(0, frameworkHits("servlet"), "an unmatched request through dispatcherServlet counts nothing in the servlet module")

            val springBefore = frameworkHits("spring-webmvc")
            assertEquals(200, sendHttp(port, "/boot/plain", "GET"))
            val plain = servlet.single { it.verb == "GET" && it.routeTemplate == "/boot/plain" }
            assertEquals(BootPlainServlet::class.java.name, plain.handlerClass)
            assertEquals(1, hits(plain))
            assertEquals(springBefore, frameworkHits("spring-webmvc"), "a plain servlet's request is no Spring MVC request")

            sendHttp(port, "/h2-console/", "GET")
            val console = servlet.single { it.verb == "GET" && it.routeTemplate == "/h2-console/*" }
            assertEquals("org.h2.server.web.JakartaWebServlet", console.handlerClass)
            assertEquals(1, hits(console))
            assertEquals(1, hits(hello), "the controller's request was counted once")
            assertEquals(emptyList(), registry.disabledModules())
        }
    }
}
