package dev.otherlode.markers

import java.io.File
import java.nio.file.Files
import java.util.Properties
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

    private val paths: Properties by lazy {
        Properties().also { properties -> File(checkNotNull(System.getProperty("markers.paths"))).inputStream().use(properties::load) }
    }

    /** A system property, or for the long classpaths the build writes to a file, the entry of that name there. */
    fun property(name: String): String = checkNotNull(System.getProperty(name) ?: paths.getProperty(name)) { "property $name is not set" }

    /** The classpath of one pinned release: the small programs in `src/main/java` and the release's own jars. */
    fun classpath(
        framework: String,
        version: String,
    ): String = listOf(property("markers.mains"), property("markers.classpath.$framework:$version")).joinToString(File.pathSeparator)

    private const val BIND_ATTEMPTS = 3
    private val portTaken = Regex("""(Address|Port) already in use""")

    /**
     * Runs [mainClass] in [mode] in a fresh JVM. The mains pick a free port and then bind it, so a
     * run that fails only because another process took that port in between is run again.
     */
    fun run(
        classpath: String,
        mainClass: String,
        mode: String,
        javaagent: String? = null,
    ): ForkedRun {
        repeat(BIND_ATTEMPTS - 1) {
            val attempt = runOnce(classpath, mainClass, mode, javaagent)
            if (attempt.exitCode == 0 || !portTaken.containsMatchIn(attempt.run.output)) return attempt.checked(mainClass, mode)
        }
        return runOnce(classpath, mainClass, mode, javaagent).checked(mainClass, mode)
    }

    private class Attempt(
        val run: ForkedRun,
        val exitCode: Int,
    ) {
        fun checked(
            mainClass: String,
            mode: String,
        ): ForkedRun {
            assertEquals(0, exitCode, "$mainClass $mode exit code. Output:\n${run.output}")
            return run
        }
    }

    private fun runOnce(
        classpath: String,
        mainClass: String,
        mode: String,
        javaagent: String?,
    ): Attempt {
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
            val loaded =
                classLog
                    .readLines()
                    .mapNotNull { classLoadLine.find(it)?.groupValues?.get(1) }
                    .toSet()
            return Attempt(ForkedRun(output, loaded), process.exitValue())
        } finally {
            directory.deleteRecursively()
        }
    }
}
