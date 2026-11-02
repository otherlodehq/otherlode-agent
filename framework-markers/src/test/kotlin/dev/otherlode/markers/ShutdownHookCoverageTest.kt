package dev.otherlode.markers

import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every payload one forked JVM's agent posted, in arrival order. */
private class OrderedReceiver : AutoCloseable {
    sealed interface Event {
        data class Delta(
            val batch: DeltaBatch,
        ) : Event

        data class Manifest(
            val manifest: ProbeManifest,
        ) : Event
    }

    val events = CopyOnWriteArrayList<Event>()
    val failures = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            try {
                when (exchange.requestURI.path) {
                    "/v1/otherlode/manifest" -> events += Event.Manifest(ProtoPayloadCodec.decodeProbeManifest(body))
                    "/v1/otherlode/deltas" -> events += Event.Delta(ProtoPayloadCodec.decodeDeltaBatch(body))
                }
            } catch (e: Exception) {
                failures += "${exchange.requestURI.path}: $e"
            }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

/**
 * Code that an application's own shutdown hook runs after the agent's hook has flushed. JVM
 * shutdown hooks start concurrently and in no defined order, so a framework's context close that
 * takes a moment runs its code after the agent's final flush. The right behaviour is that the
 * collector learns of that code; each test fails while it does not.
 */
class ShutdownHookCoverageTest {
    private val agentJar = ForkedJvm.property("otherlode.agent.shadedJar")

    private fun run(mode: String): OrderedReceiver {
        val receiver = OrderedReceiver()
        val agent = "$agentJar=serviceName=hookapp,includePackages=hookapp,flushIntervalSeconds=60,exportUrl=${receiver.endpoint}"
        val forked = ForkedJvm.run(ForkedJvm.property("markers.mains"), "hookapp.HookMain", mode, javaagent = agent)
        assertTrue("app hook ran $mode" in forked.output, "the application's hook ran before the JVM exited: ${forked.output}")
        assertTrue(receiver.failures.isEmpty(), "payloads decode: ${receiver.failures}")
        return receiver
    }

    private fun describe(receiver: OrderedReceiver): String =
        receiver.events.joinToString("\n") {
            when (it) {
                is OrderedReceiver.Event.Delta ->
                    "delta final=${it.batch.finalFlush} hits=${it.batch.deltas.size} total=${it.batch.deltas.sumOf { d -> d.hitsTotal }}"
                is OrderedReceiver.Event.Manifest -> "manifest classes=${it.manifest.probes.map { p -> p.className }.toSet()}"
            }
        }

    @Test
    fun `a method an application's shutdown hook calls after the agent flushed is counted`() {
        val receiver = run("method")
        receiver.use {
            val ids =
                receiver.events
                    .filterIsInstance<OrderedReceiver.Event.Manifest>()
                    .flatMap { it.manifest.probes }
                    .filter { it.className == "hookapp.Service" && it.methodName == "late" && it.kind.name == "METHOD" }
                    .map { it.classId to it.probeIndex }
                    .toSet()
            assertTrue(ids.isNotEmpty(), "the manifest declares Service.late\n${describe(receiver)}")
            val hit =
                receiver.events
                    .filterIsInstance<OrderedReceiver.Event.Delta>()
                    .flatMap { it.batch.deltas }
                    .any { (it.classId to it.probeIndex) in ids && it.hitsTotal > 0 }
            val final = receiver.events.filterIsInstance<OrderedReceiver.Event.Delta>().any { it.batch.finalFlush }
            assertTrue(final, "the run ended cleanly: a final_flush batch arrived")
            assertTrue(hit, "a hit for Service.late, run by the application's hook, reached the collector\n${describe(receiver)}")
        }
    }

    @Test
    fun `a class first loaded by an application's shutdown hook reaches a manifest and counts its hit`() {
        val receiver = run("class")
        receiver.use {
            val manifestClasses =
                receiver.events
                    .filterIsInstance<OrderedReceiver.Event.Manifest>()
                    .flatMap { it.manifest.probes }
                    .filter { it.className == "hookapp.LateLoaded" }
            assertEquals(
                true,
                manifestClasses.isNotEmpty(),
                "hookapp.LateLoaded, first loaded in the application's hook, is in a manifest\n${describe(receiver)}",
            )
            val ids = manifestClasses.filter { it.methodName == "run" }.map { it.classId to it.probeIndex }.toSet()
            val hit =
                receiver.events
                    .filterIsInstance<OrderedReceiver.Event.Delta>()
                    .flatMap { it.batch.deltas }
                    .any { (it.classId to it.probeIndex) in ids && it.hitsTotal > 0 }
            assertTrue(hit, "and its method's hit reached the collector\n${describe(receiver)}")
        }
    }
}
