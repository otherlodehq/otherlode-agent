package dev.otherlode.markers

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/** What one forked JVM printed and which classes it loaded, from `-Xlog:class+load`. */
internal class ForkedRun(
    val output: String,
    val loadedClasses: Set<String>,
)

internal object ForkedJvm {
    private const val TIMEOUT_SECONDS = 120L
    private val classLoadLine = Regex("""\] (\S+) source:""")

    fun property(name: String): String = checkNotNull(System.getProperty(name)) { "system property $name is not set" }

    /** The classpath of one pinned release: the small programs in `src/main/java` and the release's own jars. */
    fun classpath(
        framework: String,
        version: String,
    ): String = listOf(property("markers.mains"), property("markers.classpath.$framework:$version")).joinToString(File.pathSeparator)

    fun run(
        classpath: String,
        mainClass: String,
        mode: String,
        javaagent: String? = null,
    ): ForkedRun {
        val directory = Files.createTempDirectory("framework-markers").toFile()
        try {
            val classLog = File(directory, "class-load.log")
            val outputFile = File(directory, "output.log")
            val command =
                listOfNotNull(
                    File(System.getProperty("java.home"), "bin/java").absolutePath,
                    javaagent?.let { "-javaagent:$it" },
                    "-Xlog:class+load:file=${classLog.absolutePath}",
                    "-cp",
                    classpath,
                    mainClass,
                    mode,
                )
            val builder = ProcessBuilder(command)
            builder.environment().keys.removeIf {
                it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") || it == "JAVA_TOOL_OPTIONS" || it == "JDK_JAVA_OPTIONS"
            }
            val process = builder.redirectErrorStream(true).redirectOutput(outputFile).start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("$mainClass $mode did not exit within $TIMEOUT_SECONDS seconds. Output:\n${outputFile.readText()}")
            }
            val output = outputFile.readText()
            assertEquals(0, process.exitValue(), "$mainClass $mode exit code. Output:\n$output")
            val loaded =
                classLog
                    .readLines()
                    .mapNotNull { classLoadLine.find(it)?.groupValues?.get(1) }
                    .toSet()
            return ForkedRun(output, loaded)
        } finally {
            directory.deleteRecursively()
        }
    }
}
