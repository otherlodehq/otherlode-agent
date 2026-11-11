package dev.otherlode.export

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

class IngestNoticesTest {
    private var server: HttpServer? = null
    private var clock = 0L
    private val notices = IngestNotices { clock }
    private val batch = DeltaBatch(ResourceAttributes("checkout", "1.0.0", "i-1", "test", "run-1"), emptyList())

    private val serviceMessage =
        "otherlode: Otherlode stores this service's payloads but does not track the service, " +
            "because the tenant's plan tracks no more services"
    private val environmentMessage =
        "otherlode: Otherlode stores this environment's payloads but does not track the environment, " +
            "because the service already watches as many environments as its cap allows"

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(
        status: Int,
        vararg noticeLines: String,
    ): String {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            noticeLines.forEach { exchange.responseHeaders.add("Otherlode-Notice", it) }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        return "http://localhost:${httpServer.address.port}"
    }

    private fun exporterFor(
        endpoint: String,
        limiter: IngestNotices = notices,
    ) = HttpExporter(endpoint = endpoint, maxAttempts = 1, initialBackoff = Duration.ofMillis(1), notices = limiter)

    private val retained = mutableListOf<JulLogger>()

    private fun captured(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(IngestNotices::class.java.name)
        retained += julLogger
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
        }
        return records
    }

    private fun warnings(block: () -> Unit): List<String> = captured(block).filter { it.level == JulLevel.WARNING }.map { it.message }

    @Test
    fun `a 202 with a service-over-cap notice logs the service message once an hour`() {
        val exporter = exporterFor(startServer(202, "service-over-cap"))

        assertEquals(listOf(serviceMessage), warnings { exporter.exportDeltaBatch(batch) })
        assertEquals(emptyList(), warnings { exporter.exportDeltaBatch(batch) })

        clock += TimeUnit.MINUTES.toNanos(59)
        assertEquals(emptyList(), warnings { exporter.exportDeltaBatch(batch) })

        clock += TimeUnit.MINUTES.toNanos(1)
        assertEquals(listOf(serviceMessage), warnings { exporter.exportDeltaBatch(batch) })
    }

    @Test
    fun `a header line that joins two notices logs both`() {
        val exporter = exporterFor(startServer(202, "service-over-cap, environment-not-tracked"))

        assertEquals(listOf(serviceMessage, environmentMessage), warnings { exporter.exportDeltaBatch(batch) })
    }

    @Test
    fun `two header lines each log their notice, and empty parts are dropped`() {
        val exporter = exporterFor(startServer(202, "service-over-cap,,", " environment-not-tracked "))

        assertEquals(listOf(serviceMessage, environmentMessage), warnings { exporter.exportDeltaBatch(batch) })
    }

    @Test
    fun `an unknown well formed notice logs itself`() {
        val exporter = exporterFor(startServer(202, "plan-is-odd"))

        assertEquals(
            listOf("otherlode: Otherlode sent a notice this agent does not know: plan-is-odd"),
            warnings { exporter.exportDeltaBatch(batch) },
        )
    }

    @Test
    fun `a malformed notice logs as unknown`() {
        val unknown = "otherlode: Otherlode sent a notice this agent does not know: unknown"
        for (value in listOf("Bad_Value", "a".repeat(65))) {
            clock += TimeUnit.HOURS.toNanos(2)
            val exporter = exporterFor(startServer(202, value))

            assertEquals(listOf(unknown), warnings { exporter.exportDeltaBatch(batch) }, value)
            server?.stop(0)
        }
    }

    @Test
    fun `a notice of 64 bytes is well formed`() {
        val value = "a".repeat(64)
        val exporter = exporterFor(startServer(202, value))

        assertEquals(
            listOf("otherlode: Otherlode sent a notice this agent does not know: $value"),
            warnings { exporter.exportDeltaBatch(batch) },
        )
    }

    @Test
    fun `a 200 that carries a notice logs no notice`() {
        val exporter = exporterFor(startServer(200, "service-over-cap"))

        val logged = warnings { runCatching { exporter.exportDeltaBatch(batch) } }

        assertEquals(emptyList(), logged)
    }

    @Test
    fun `exporters built with the same limiter share the hourly limit`() {
        val endpoint = startServer(202, "service-over-cap")
        val first = exporterFor(endpoint)
        val second = exporterFor(endpoint)

        assertEquals(listOf(serviceMessage), warnings { first.exportDeltaBatch(batch) })
        assertEquals(emptyList(), warnings { second.exportDeltaBatch(batch) })
    }

    @Test
    fun `exporters built with their own limiters each log`() {
        val endpoint = startServer(202, "service-over-cap")
        val first = exporterFor(endpoint, IngestNotices { clock })
        val second = exporterFor(endpoint, IngestNotices { clock })

        assertEquals(listOf(serviceMessage), warnings { first.exportDeltaBatch(batch) })
        assertEquals(listOf(serviceMessage), warnings { second.exportDeltaBatch(batch) })
    }

    @Test
    fun `past 32 different notices a new one logs as unknown, and a known one still logs`() {
        val values = (1..40).map { n -> "notice-" + "x".repeat(n) }
        val exporter = exporterFor(startServer(202, values.joinToString(","), "service-over-cap"))

        val logged = warnings { exporter.exportDeltaBatch(batch) }

        val shown = values.take(32).map { "otherlode: Otherlode sent a notice this agent does not know: $it" }
        assertEquals(
            shown + "otherlode: Otherlode sent a notice this agent does not know: unknown" + serviceMessage,
            logged,
        )
    }

    @Test
    fun `past the cap a notice already kept still logs itself an hour later`() {
        val values = (1..32).map { n -> "notice-" + "x".repeat(n) }
        warnings { exporterFor(startServer(202, values.joinToString(","))).exportDeltaBatch(batch) }
        server?.stop(0)
        clock += TimeUnit.HOURS.toNanos(2)
        val exporter = exporterFor(startServer(202, values.first(), "notice-new"))

        assertEquals(
            listOf(
                "otherlode: Otherlode sent a notice this agent does not know: ${values.first()}",
                "otherlode: Otherlode sent a notice this agent does not know: unknown",
            ),
            warnings { exporter.exportDeltaBatch(batch) },
        )
    }
}
