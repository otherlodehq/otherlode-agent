package dev.otherlode.instrumentation

import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Collects the manifests and delta batches one fixture JVM posts, and answers every post with 200. */
internal class AgentReceiver : AutoCloseable {
    val manifests = CopyOnWriteArrayList<ProbeManifest>()
    val batches = CopyOnWriteArrayList<DeltaBatch>()
    val failures = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val endpoint: String get() = "http://${server.address.address.hostAddress.let {
        if (':' in it) "[$it]" else it
    }}:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            try {
                when (exchange.requestURI.path) {
                    "/v1/otherlode/manifest" -> manifests += ProtoPayloadCodec.decodeProbeManifest(body)
                    "/v1/otherlode/deltas" -> batches += ProtoPayloadCodec.decodeDeltaBatch(body)
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
