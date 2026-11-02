package dev.otherlode.shutdown

import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.ProtoPayloadCodec
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.fail

/** What one forked JVM printed, when its `main` ended and when the process exited. */
internal class ForkedRun(
    val output: String,
    val exitCode: Int,
    val mainDoneAtMillis: Long?,
    val exitedAtMillis: Long,
) {
    /** Milliseconds from the end of `main`, which is when the JVM starts to shut down, to the process exit. */
    val shutdownMillis: Long get() = exitedAtMillis - checkNotNull(mainDoneAtMillis) { "main printed no end time. Output:\n$output" }

    /** The epoch milliseconds in the output line that starts with [label], or null. */
    fun stamp(label: String): Long? = Regex("""(?m)^$label (\d+)$""").find(output)?.groupValues?.get(1)?.toLong()
}

/** A collector that accepts every connection and never answers. */
internal class BlackholeCollector : AutoCloseable {
    private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
    private val held = CopyOnWriteArrayList<java.net.Socket>()
    val endpoint: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        Thread({
            try {
                while (true) held += server.accept()
            } catch (_: java.io.IOException) {
            }
        }, "blackhole-collector").apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
        held.forEach { runCatching { it.close() } }
    }
}

/** A collector that answers [status] to everything, and records when each delta batch arrived. */
internal class AnsweringCollector(
    private val status: Int = 200,
) : AutoCloseable {
    class Arrival(
        val atMillis: Long,
        val batch: DeltaBatch,
    )

    val deltas = CopyOnWriteArrayList<Arrival>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/v1/otherlode/deltas") {
                runCatching { deltas += Arrival(System.currentTimeMillis(), ProtoPayloadCodec.decodeDeltaBatch(body)) }
            }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

/** Compiles small Java programs and runs them in a JVM with the shaded agent attached. */
internal class ForkedShutdownApp : AutoCloseable {
    private val classes = Files.createTempDirectory("otherlode-shutdown").toFile()

    val agentJar: String = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }

    fun compile(vararg sources: Pair<String, String>) {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "no system Java compiler" }
        val files =
            sources.map { (path, text) ->
                File(classes, "src/$path").also {
                    it.parentFile.mkdirs()
                    it.writeText(text)
                }
            }
        val args = listOf("-d", File(classes, "out").absolutePath) + files.map { it.absolutePath }
        check(compiler.run(null, null, null, *args.toTypedArray()) == 0) { "could not compile the fixture" }
    }

    /**
     * Runs [mainClass] with [agentOptions] and [programArgs]. Its `main` prints `main done <millis>`
     * as its last act; the run records when the process exited.
     */
    fun run(
        mainClass: String,
        agentOptions: String?,
        vararg programArgs: String,
        jvmOptions: List<String> = emptyList(),
        timeoutSeconds: Long = 120,
    ): ForkedRun {
        val output = File(classes, "output.log")
        val command =
            listOfNotNull(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                *jvmOptions.toTypedArray(),
                agentOptions?.let { "-javaagent:$agentJar=$it" },
                "-cp",
                File(classes, "out").absolutePath,
                mainClass,
                *programArgs,
            )
        val builder = ProcessBuilder(command)
        builder.environment().keys.removeIf { it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") || it == "JAVA_TOOL_OPTIONS" }
        val process = builder.redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("$mainClass did not exit within $timeoutSeconds seconds. Output:\n${output.readText()}")
        }
        val exitedAt = System.currentTimeMillis()
        val text = output.readText()
        val mainDone = Regex("""(?m)^main done (\d+)$""").find(text)?.groupValues?.get(1)?.toLong()
        return ForkedRun(text, process.exitValue(), mainDone, exitedAt)
    }

    override fun close() {
        classes.deleteRecursively()
    }
}
