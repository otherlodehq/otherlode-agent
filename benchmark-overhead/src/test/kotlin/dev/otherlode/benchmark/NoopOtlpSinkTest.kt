package dev.otherlode.benchmark

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/** Runs `NoopOtlpSink.java` as the harness's sink container does, on a free local port, and talks to it over HTTP. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoopOtlpSinkTest {
    private val http = HttpClient.newHttpClient()
    private lateinit var process: Process
    private lateinit var base: String
    private lateinit var dir: Path

    @BeforeAll
    fun startSink() {
        val port = ServerSocket(0).use { it.localPort }
        dir = Files.createTempDirectory("noop-otlp-sink")
        val source = dir.resolve("NoopOtlpSink.java")
        checkNotNull(
            javaClass.getResourceAsStream("/NoopOtlpSink.java"),
        ).use { Files.copy(it, source, StandardCopyOption.REPLACE_EXISTING) }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        process = ProcessBuilder(java, source.toString(), port.toString()).redirectErrorStream(true).start()
        base = "http://localhost:$port"
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (runCatching { get("/counts").statusCode() }.getOrNull() != 200) {
            check(process.isAlive) { "the sink exited: ${process.inputStream.bufferedReader().readText()}" }
            check(System.nanoTime() < deadline) { "the sink did not answer within 60 s" }
            Thread.sleep(100)
        }
    }

    @AfterAll
    fun stopSink() {
        process.destroy()
        dir.toFile().deleteRecursively()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("$base$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(
        path: String,
        body: ByteArray,
    ): HttpResponse<ByteArray> =
        http.send(
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    @Test
    fun `a post is answered 200 with an empty protobuf body and counted against its path`() {
        val before = SinkCounts.parse(get("/counts").body())
        val reply = post("/v1/traces", ByteArray(1500))
        assertEquals(200, reply.statusCode())
        assertEquals("application/x-protobuf", reply.headers().firstValue("Content-Type").orElse(""))
        assertEquals(0, reply.body().size)
        post("/v1/traces", ByteArray(500))
        post("/v1/metrics", ByteArray(7))
        val delta = sinkMetrics(before, SinkCounts.parse(get("/counts").body()))
        assertEquals(2.0, delta["sinkTraceExports"])
        assertEquals(1.0, delta["sinkMetricExports"])
        assertEquals(0.0, delta["sinkLogExports"])
        assertEquals(2007.0, delta["sinkBytes"])
    }

    @Test
    fun `the counts are one line per path`() {
        post("/v1/logs", ByteArray(3))
        val lines = get("/counts").body().lines().filter { it.isNotEmpty() }
        assertTrue(lines.any { it.matches(Regex("""/v1/logs \d+ \d+""")) }, lines.toString())
    }

    @Test
    fun `anything else is 404`() {
        assertEquals(404, get("/nothing").statusCode())
        val put =
            http.send(
                HttpRequest.newBuilder(URI("$base/v1/traces")).PUT(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertEquals(404, put.statusCode())
    }
}
