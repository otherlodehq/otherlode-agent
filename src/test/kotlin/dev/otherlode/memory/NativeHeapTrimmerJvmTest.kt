package dev.otherlode.memory

import dev.otherlode.instrumentation.AgentReceiver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class NativeHeapTrimmerJvmTest {
    private companion object {
        const val PACKAGE = "com.example.probewindow"
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val MODULES = "java.base,java.instrument,java.net.http,java.logging,jdk.unsupported"
    }

    private fun runWithoutManagement(option: String) {
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val classes = checkNotNull(System.getProperty("otherlode.fixtures.probewindow.legacy.dirs")) { "no legacy fixtures" }
        val outputFile = Files.createTempFile("otherlode-trim-no-management", ".log").toFile()
        try {
            AgentReceiver().use { receiver ->
                val command =
                    listOf(
                        File(System.getProperty("java.home"), "bin/java").absolutePath,
                        "--limit-modules",
                        MODULES,
                        "-javaagent:$agentJar=serviceName=trim-no-management,includePackages=$PACKAGE," +
                            "flushIntervalSeconds=1,exportUrl=${receiver.endpoint}$option",
                        "-cp",
                        classes,
                        "$PACKAGE.SubclassConstantMain",
                    )
                val builder = ProcessBuilder(command)
                builder.environment().keys.removeIf { it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") }
                val process = builder.redirectErrorStream(true).redirectOutput(outputFile).start()
                if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    fail("did not exit within $PROCESS_TIMEOUT_SECONDS seconds. Output:\n${outputFile.readText()}")
                }
                val output = outputFile.readText()
                assertEquals(0, process.exitValue(), "ran to completion. Output:\n$output")
                assertTrue("area 1" in output, "the program ran. Output:\n$output")
                assertFalse("failed to start" in output, "the agent started. Output:\n$output")
                assertTrue(receiver.batches.isNotEmpty(), "the agent sent a batch. Output:\n$output")
            }
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `the agent starts and sends on a JVM without java management, with the default trim`() {
        runWithoutManagement("")
    }

    @Test
    fun `the agent starts and sends on a JVM without java management, with the trim off`() {
        runWithoutManagement(",trimNativeHeapIntervalMinutes=0")
    }
}
