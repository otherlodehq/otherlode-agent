package dev.otherlode.export

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpExporterStoredTest {
    private var server: HttpServer? = null
    private val requestCount = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(handler: (requestNumber: Int) -> Int): String {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(handler(requestCount.incrementAndGet()), -1)
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        return "http://localhost:${httpServer.address.port}"
    }

    private fun exporterFor(endpoint: String) =
        HttpExporter(
            endpoint = endpoint,
            maxAttempts = 5,
            initialBackoff = Duration.ofMillis(1),
            maxBackoff = Duration.ofMillis(10),
        )

    private val batch = DeltaBatch(ResourceAttributes("checkout", "1.0.0", "i-1", "test", "run-1"), emptyList())

    @Test
    fun `a 202 counts as stored`() {
        val endpoint = startServer { 202 }

        exporterFor(endpoint).exportDeltaBatch(batch)

        assertEquals(1, requestCount.get())
    }

    @Test
    fun `a 2xx other than 202 fails the send after one request and is not a refusal`() {
        for (status in listOf(200, 201, 204)) {
            requestCount.set(0)
            val endpoint = startServer { status }

            val failure = assertFailsWith<ExportFailedException>("status $status") { exporterFor(endpoint).exportDeltaBatch(batch) }

            assertEquals(status, failure.statusCode)
            assertFalse(failure.refused)
            assertEquals(1, requestCount.get(), "status $status must not retry inside the send")
            assertTrue(failure.message.orEmpty().contains("$endpoint/v1/otherlode/deltas"), failure.message)
            assertTrue(failure.message.orEmpty().contains("answered $status, not 202"), failure.message)
            assertTrue(failure.message.orEmpty().contains("counts as not stored"), failure.message)
            server?.stop(0)
        }
    }

    @Test
    fun `a 503 still retries until a 202`() {
        val endpoint = startServer { requestNumber -> if (requestNumber < 3) 503 else 202 }

        exporterFor(endpoint).exportDeltaBatch(batch)

        assertEquals(3, requestCount.get())
    }
}
