package dev.otherlode.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ExportScheduler
import dev.otherlode.export.HttpExporter
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.jdkhttpserver.JdkHttpServerModule
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.junit.jupiter.api.Order
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Advice weaves only at class load, so install must run before sun.net.httpserver loads and before any
// OtherlodeTestCollector starts. The Order and junit-platform.properties run this class first.
@Order(Int.MIN_VALUE)
class EndpointEndToEndTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedEndpointInstrumentation: EndpointInstrumentation? = null
    private var scheduler: ExportScheduler? = null
    private var collector: OtherlodeTestCollector? = null
    private var appServer: HttpServer? = null

    @AfterTest
    fun tearDown() {
        scheduler?.stop()
        appServer?.stop(0)
        installedTransformer?.let { installedEndpointInstrumentation?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedEndpointInstrumentation = null
        collector?.close()
    }

    @Test
    fun `an endpoint's call count and never-called status are observable over the wire`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(JdkHttpServerModule()))
        installedEndpointInstrumentation = endpointInstrumentation
        installedTransformer = endpointInstrumentation.install(instrumentation)

        val target = OtherlodeTestCollector.start()
        collector = target

        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        appServer = server
        server.createContext("/checkout", HttpHandler { exchange -> respond(exchange) })
        server.createContext("/promo", HttpHandler { exchange -> respond(exchange) })
        server.start()

        val client = HttpClient.newHttpClient()
        val port = server.address.port
        get(client, port, "/checkout")
        get(client, port, "/checkout")

        val config =
            AgentConfig.parse(
                "exportUrl=${target.exportUrl}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-endpoint-e2e," +
                    "serviceInstanceId=e2e-endpoint-1",
            )
        val exporter = HttpExporter(target.exportUrl)
        val exportScheduler =
            ExportScheduler(config, TestResources.forConfig(config), ProbeRegistry(), registry, exporter, verifierWatch = watchedVerifier())
        scheduler = exportScheduler
        exportScheduler.start()

        target.awaitEndpoint("*", "/checkout", Duration.ofSeconds(10))
        target.awaitNextFlush(Duration.ofSeconds(10))

        assertTrue(target.wasCalled("*", "/checkout"))
        assertEquals(2L, target.callCount("*", "/checkout"))
        assertTrue(target.neverCalled().any { it.routeTemplate == "/promo" })
    }

    private fun get(
        client: HttpClient,
        port: Int,
        path: String,
    ) {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun respond(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
}
