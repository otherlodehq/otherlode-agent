package dev.otherlode.export

import com.sun.net.httpserver.HttpServer
import dev.otherlode.config.AgentConfig
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.otherlode.proto.DeltaBatch as ProtoDeltaBatch
import dev.otherlode.proto.ProbeManifest as ProtoProbeManifest

class ExportSchedulerUnstoredAnswerTest {
    private val config =
        AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test,flushIntervalSeconds=60")

    @Volatile private var status = 200
    private val manifests: MutableList<ProtoProbeManifest> = Collections.synchronizedList(mutableListOf())
    private val deltas: MutableList<ProtoDeltaBatch> = Collections.synchronizedList(mutableListOf())
    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes()
                when (exchange.requestURI.path) {
                    "/v1/otherlode/manifest" -> manifests += ProtoProbeManifest.parseFrom(body)
                    "/v1/otherlode/deltas" -> deltas += ProtoDeltaBatch.parseFrom(body)
                }
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }

    private val registry = ProbeRegistry()
    private val scheduler =
        ExportScheduler(
            config,
            TestResources.forConfig(config),
            registry,
            EndpointRegistry(),
            HttpExporter(
                endpoint = "http://localhost:${server.address.port}",
                maxAttempts = 1,
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofMillis(10),
            ),
            clock = { 1_000_000L },
        )

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `a receiver that answers 200 keeps the manifest and the counts, and only a 202 confirms`() {
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] = 3

        scheduler.flush()
        scheduler.flush()
        val unstored = manifests.toList()
        manifests.clear()
        deltas.clear()
        status = 202
        scheduler.flush()
        val stored = manifests.toList()
        val storedDeltas = deltas.toList()
        manifests.clear()
        deltas.clear()
        scheduler.flush()

        assertEquals(2, unstored.size, "the manifest is sent again after a 200")
        assertTrue(unstored.all { !it.resource.manifestConfirmed && it.probesCount == 1 }, "$unstored")
        assertEquals(1, stored.size)
        assertTrue(
            storedDeltas.any { batch -> batch.deltasList.any { it.hitsTotal == 3L } },
            "the flush that gets a 202 sends the counts the 200s did not store: " +
                "${storedDeltas.map { b -> b.deltasList.map { it.hitsTotal } }}",
        )
        assertTrue(manifests.isEmpty(), "a manifest a 202 stored is not sent again")
        assertTrue(
            deltas.isNotEmpty() && deltas.all { it.resource.manifestConfirmed },
            "the flush after a 202 says confirmed: ${deltas.map { it.resource.manifestConfirmed }}",
        )
    }
}
