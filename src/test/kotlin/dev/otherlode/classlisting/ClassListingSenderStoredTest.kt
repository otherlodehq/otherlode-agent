package dev.otherlode.classlisting

import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.ClassListing
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ResourceAttributes
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassListingSenderStoredTest {
    private var server: HttpServer? = null
    private val requestCount = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `a chunk answered 200 stays pending and is sent again at the next flush`() {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(if (requestCount.incrementAndGet() == 1) 200 else 202, -1)
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        val exporter =
            HttpExporter(
                endpoint = "http://localhost:${httpServer.address.port}",
                maxAttempts = 1,
                initialBackoff = Duration.ofMillis(1),
            )
        val sender = ClassListingSender(exporter)
        sender.offer(
            listOf(
                ClassListing(
                    resource = ResourceAttributes("checkout", "1.0.0", "i-1", "test", "run-1"),
                    classNames = listOf("com.acme.A"),
                    includeRules = IncludeRules(listOf("com.acme")),
                    listedAt = 1000L,
                ),
            ),
        )

        assertFalse(sender.sendPending())
        assertEquals(1, requestCount.get())

        assertTrue(sender.sendPending())
        assertEquals(2, requestCount.get())
    }
}
