package dev.otherlode.export

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpExporterRetryAfterTest {
    private var server: HttpServer? = null
    private val requestCount = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(handler: (Int) -> Pair<Int, String?>): String {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/") { exchange ->
            val (status, retryAfter) = handler(requestCount.incrementAndGet())
            if (retryAfter != null) exchange.responseHeaders.add("Retry-After", retryAfter)
            exchange.sendResponseHeaders(status, -1)
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
    fun `a Retry-After over the cap on a 503 makes one attempt and fails with the status`() {
        val endpoint = startServer { 503 to "5" }

        val elapsed =
            measureTimeMillis {
                val failure = assertFailsWith<ExportFailedException> { exporterFor(endpoint).exportDeltaBatch(batch) }
                assertEquals(503, failure.statusCode)
                assertFalse(failure.refused)
            }

        assertEquals(1, requestCount.get())
        assertTrue(elapsed < 1000, "waited $elapsed ms")
    }

    @Test
    fun `a Retry-After of 1 waits about a second and retries`() {
        val endpoint = startServer { n -> if (n < 2) 503 to "1" else 202 to null }

        val elapsed = measureTimeMillis { exporterFor(endpoint).exportDeltaBatch(batch) }

        assertEquals(2, requestCount.get())
        assertTrue(elapsed in 900..1900, "waited $elapsed ms")
    }

    @Test
    fun `a Retry-After in HTTP date form within the cap is honoured`() {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(2))
        val endpoint = startServer { n -> if (n < 2) 503 to date else 202 to null }

        val started = System.nanoTime()
        exporterFor(endpoint).exportDeltaBatch(batch)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(2, requestCount.get())
        assertTrue(elapsedMs >= 500, "the wait came from the date, not the 1 ms backoff: $elapsedMs ms")
    }

    @Test
    fun `a Retry-After in HTTP date form over the cap fails at once`() {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60))
        val endpoint = startServer { 503 to date }

        val failure = assertFailsWith<ExportFailedException> { exporterFor(endpoint).exportDeltaBatch(batch) }

        assertEquals(503, failure.statusCode)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun `a malformed or past Retry-After falls back to the backoff`() {
        for (value in listOf("soon", "-3", "1.5", "Mon, 01 Jan 2001 00:00:00 GMT")) {
            requestCount.set(0)
            server?.stop(0)
            val endpoint = startServer { 503 to value }

            val failure = assertFailsWith<ExportFailedException> { exporterFor(endpoint).exportDeltaBatch(batch) }

            assertEquals(503, failure.statusCode, value)
            assertEquals(5, requestCount.get(), value)
        }
    }

    @Test
    fun `a 429 with Retry-After over the cap fails at once and without it uses the backoff`() {
        val over = startServer { 429 to "30" }
        val failure = assertFailsWith<ExportFailedException> { exporterFor(over).exportDeltaBatch(batch) }
        assertEquals(429, failure.statusCode)
        assertFalse(failure.refused)
        assertEquals(1, requestCount.get())

        server?.stop(0)
        requestCount.set(0)
        val plain = startServer { 429 to null }
        val exhausted = assertFailsWith<ExportFailedException> { exporterFor(plain).exportDeltaBatch(batch) }
        assertEquals(429, exhausted.statusCode)
        assertEquals(5, requestCount.get())
    }

    @Test
    fun `a 429 with a short Retry-After retries after it`() {
        val endpoint = startServer { n -> if (n < 2) 429 to "0" else 202 to null }

        exporterFor(endpoint).exportDeltaBatch(batch)

        assertEquals(2, requestCount.get())
    }

    @Test
    fun `parseRetryAfter reads seconds and dates and ignores the rest`() {
        assertEquals(Duration.ofSeconds(7), HttpExporter.parseRetryAfter(" 7 "))
        assertNull(HttpExporter.parseRetryAfter(null))
        assertNull(HttpExporter.parseRetryAfter(""))
        assertNull(HttpExporter.parseRetryAfter("abc"))
    }
}
