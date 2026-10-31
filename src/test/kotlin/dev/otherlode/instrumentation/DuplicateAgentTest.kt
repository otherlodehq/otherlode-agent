package dev.otherlode.instrumentation

import dev.otherlode.export.ProbeKind
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Starts a JVM with the shaded agent jar given twice as `-javaagent`. The second start must change
 * nothing: a class compiled for Java 8 loads once woven, and each probe counts a hit once.
 */
class DuplicateAgentTest {
    private companion object {
        const val PACKAGE = "com.example.probewindow"
        const val ALREADY_RUNNING = "the agent is already running in this JVM"
        const val PROCESS_TIMEOUT_SECONDS = 120L
    }

    private fun runWithAgentTimes(
        agents: Int,
        form: String,
    ): Pair<String, Map<String, Long>> {
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val classes = checkNotNull(System.getProperty("otherlode.fixtures.probewindow.$form.dirs")) { "no $form fixtures" }
        val outputFile = Files.createTempFile("otherlode-duplicate-agent", ".log").toFile()
        try {
            AgentReceiver().use { receiver ->
                val agent =
                    "-javaagent:$agentJar=serviceName=duplicate-agent,includePackages=$PACKAGE," +
                        "flushIntervalSeconds=1,exportUrl=${receiver.endpoint}"
                val command =
                    listOf(File(System.getProperty("java.home"), "bin/java").absolutePath) +
                        List(agents) { agent } +
                        listOf("-cp", classes, "$PACKAGE.SubclassConstantMain")
                val builder = ProcessBuilder(command)
                builder.environment().keys.removeIf { it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") }
                val process = builder.redirectErrorStream(true).redirectOutput(outputFile).start()
                if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    fail("did not exit within $PROCESS_TIMEOUT_SECONDS seconds. Output:\n${outputFile.readText()}")
                }
                val output = outputFile.readText()
                assertEquals(0, process.exitValue(), "ran to completion with $agents agents. Output:\n$output")
                val hits = HashMap<Pair<Int, Int>, Long>()
                for (delta in receiver.batches.flatMap { it.deltas }) {
                    hits.merge(
                        delta.classId to delta.probeIndex,
                        delta.hitsTotal,
                        ::maxOf,
                    )
                }
                val counts =
                    receiver.manifests
                        .flatMap { it.probes }
                        .filter { it.className.startsWith("$PACKAGE.Circle") && it.kind == ProbeKind.METHOD }
                        .associate { "${it.className}.${it.methodName}" to (hits[it.classId to it.probeIndex] ?: 0L) }
                return output to counts
            }
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `a second -javaagent of the jar is ignored with one warning and a version 52 class counts each hit once`() {
        val (output, counts) = runWithAgentTimes(agents = 2, form = "legacy")

        assertTrue("area 1" in output, "the program ran. Output:\n$output")
        assertEquals(1, Regex(Regex.escape(ALREADY_RUNNING)).findAll(output).count(), "one warning. Output:\n$output")
        assertEquals(2L, counts["$PACKAGE.Circle.<init>"], "$counts")
        assertEquals(1L, counts["$PACKAGE.Circle.area"], "$counts")
    }

    @Test
    fun `a single -javaagent logs no warning about a second start`() {
        val (output, _) = runWithAgentTimes(agents = 1, form = "legacy")

        assertTrue(ALREADY_RUNNING !in output, output)
    }
}
