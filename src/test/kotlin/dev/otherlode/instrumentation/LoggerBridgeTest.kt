package dev.otherlode.instrumentation

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Starts JVMs with a `System.LoggerFinder` bridge on the class path. The application's `main`
 * names its logging configuration and only then logs, so the agent must not have started the
 * bridge's backend before `main` ran.
 *
 * One case uses `log4j-jpl` and checks the application's Log4j configuration is the one in force.
 * The other uses a bridge that only records each request for a logger, and checks none is made
 * before `main` has run. The jars are the `loggerBridgeTestJars` configuration; Gradle passes their
 * paths in `otherlode.test.log4jJars`.
 */
class LoggerBridgeTest {
    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val MARKER = "BRIDGEMARK"
        const val FORKS = 3
        const val MAIN_DELAY_MILLIS = 3000L
        const val PAST_GATE_MILLIS = 20_000L

        val MAIN_SOURCE =
            """
            package bridgetest;

            public class Main {
                public static void main(String[] args) throws Exception {
                    Thread.sleep(Long.parseLong(args[2]));
                    System.out.println("MAINSET");
                    System.setProperty("log4j2.configurationFile", args[0]);
                    System.getLogger("bridgetest.Main").log(System.Logger.Level.INFO, "first app line");
                    Thread.sleep(Long.parseLong(args[1]));
                    System.getLogger("bridgetest.Main").log(System.Logger.Level.INFO, "second app line");
                }
            }
            """.trimIndent()

        val FINDER_SOURCE =
            """
            package probe;

            public class Finder extends System.LoggerFinder {
                public System.Logger getLogger(String name, Module module) {
                    System.out.println("PROBE " + name + " module=" + module);
                    return new System.Logger() {
                        public String getName() { return name; }
                        public boolean isLoggable(Level level) { return false; }
                        public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {}
                        public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {}
                    };
                }
            }
            """.trimIndent()

        val GATE_SOURCE =
            """
            package bridgetest;

            import dev.otherlode.bootstrap.OtherlodeLoggers;

            public class GateMain {
                public static void main(String[] args) throws Exception {
                    OtherlodeLoggers.setFallback(name -> new System.Logger() {
                        public String getName() { return name; }
                        public boolean isLoggable(Level level) { return true; }
                        public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
                            System.out.println("FALLBACK " + msg);
                        }
                        public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
                            System.out.println("FALLBACK " + format);
                        }
                    });
                    System.Logger logger = OtherlodeLoggers.get("gate.test");
                    logger.log(System.Logger.Level.ERROR, "early");
                    if (args[0].equals("open")) {
                        OtherlodeLoggers.openGate();
                    } else {
                        Thread.sleep(Long.parseLong(args[1]));
                    }
                    logger.log(System.Logger.Level.ERROR, "late");
                }
            }
            """.trimIndent()

        val CONFIG =
            """
            <Configuration status="WARN">
              <Appenders><Console name="out" target="SYSTEM_OUT"><PatternLayout pattern="$MARKER %p %c - %m%n"/></Console></Appenders>
              <Loggers><Root level="info"><AppenderRef ref="out"/></Root></Loggers>
            </Configuration>
            """.trimIndent()
    }

    private val work = Files.createTempDirectory("otherlode-bridge").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private fun jars(): List<File> {
        val paths =
            checkNotNull(System.getProperty("otherlode.test.log4jJars")?.takeIf { it.isNotBlank() }) {
                "otherlode.test.log4jJars is not set"
            }
        return paths.split(File.pathSeparator).map { File(it) }.also { found ->
            listOf("log4j-api", "log4j-core", "log4j-jpl").forEach { name ->
                check(found.any { it.name.startsWith("$name-") }) { "no $name jar in $paths" }
            }
        }
    }

    private fun compile(
        into: File,
        relativePath: String,
        text: String,
        classPath: List<File> = emptyList(),
    ) {
        val source = File(work, "src/$relativePath")
        source.parentFile.mkdirs()
        source.writeText(text)
        into.mkdirs()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "no system Java compiler" }
        check(
            compiler.run(
                null,
                null,
                null,
                "-d",
                into.absolutePath,
                "-cp",
                classPath.joinToString(File.pathSeparator),
                source.absolutePath,
            ) ==
                0,
        ) { "could not compile $relativePath" }
    }

    private fun bootstrapJar(): File {
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val target = File(work, "bootstrap.jar")
        java.util.zip.ZipFile(agentJar).use { zip ->
            zip.getInputStream(zip.getEntry(BootstrapHolder.DEFAULT_RESOURCE)).use { target.outputStream().use(it::copyTo) }
        }
        return target
    }

    private fun gateOutput(
        mode: String,
        sleepMillis: Long,
    ): String {
        val bootstrap = bootstrapJar()
        val classes = File(work, "gate").also { compile(it, "bridgetest/GateMain.java", GATE_SOURCE, listOf(bootstrap)) }
        val output = File.createTempFile("gate", ".log", work)
        val classPath = listOf(classes, bootstrap, probeClasses()).joinToString(File.pathSeparator) { it.absolutePath }
        val command =
            listOf(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp",
                classPath,
                "bridgetest.GateMain",
                mode,
                sleepMillis.toString(),
            )
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
        assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS), "exited. Output:\n${output.readText()}")
        return output.readText()
    }

    private fun appClasses(): File = File(work, "classes").also { compile(it, "bridgetest/Main.java", MAIN_SOURCE) }

    private fun probeClasses(): File {
        val dir = File(work, "probe")
        compile(dir, "probe/Finder.java", FINDER_SOURCE)
        val services = File(dir, "META-INF/services").apply { mkdirs() }
        File(services, "java.lang.System\$LoggerFinder").writeText("probe.Finder\n")
        return dir
    }

    private fun run(
        classPath: List<File>,
        sleepMillis: Long,
        mainDelayMillis: Long,
        exportUrl: String = closedPortUrl(),
    ): String {
        val config = File(work, "log4j2.xml").apply { writeText(CONFIG) }
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val output = File.createTempFile("run", ".log", work)
        val command =
            listOf(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-javaagent:$agentJar=serviceName=bridge,includePackages=bridgetest,flushIntervalSeconds=1,exportUrl=$exportUrl",
                "-cp",
                classPath.joinToString(File.pathSeparator) { it.absolutePath },
                "bridgetest.Main",
                config.absolutePath,
                sleepMillis.toString(),
                mainDelayMillis.toString(),
            )
        val builder = ProcessBuilder(command)
        builder.environment().keys.removeIf { it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") }
        val process = builder.redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("did not exit within $PROCESS_TIMEOUT_SECONDS seconds. Output:\n${output.readText()}")
        }
        val text = output.readText()
        assertEquals(0, process.exitValue(), "ran to completion. Output:\n$text")
        return text
    }

    private fun closedPortUrl(): String = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}"

    private fun assertStartupLineOnStandardError(output: String) {
        assertTrue(
            Regex("""(?m)^INFO: otherlode: """).containsMatchIn(output),
            "the startup line is on standard error in the console format. Output:\n$output",
        )
    }

    @Test
    fun `nothing asks the bridge for a logger before main has run`() {
        AgentReceiver().use { receiver ->
            repeat(FORKS) {
                checkNoRequestBeforeMain(run(listOf(appClasses(), probeClasses()), 500, MAIN_DELAY_MILLIS, receiver.endpoint))
            }
        }
    }

    private fun checkNoRequestBeforeMain(output: String) {
        val mainSet = output.indexOf("MAINSET")
        assertTrue(mainSet >= 0, "main ran. Output:\n$output")
        assertTrue("PROBE" !in output.substring(0, mainSet), "no logger was requested before main. Output:\n$output")
        assertTrue("PROBE bridgetest.Main" in output, "the probe sees the application's own request. Output:\n$output")
        assertStartupLineOnStandardError(output)
    }

    @Test
    fun `a logger bridge keeps the application's logging configuration`() {
        AgentReceiver().use { receiver ->
            repeat(FORKS) {
                checkFirstLine(run(listOf(appClasses()) + jars(), 500, 0, receiver.endpoint))
            }
        }
    }

    private fun checkFirstLine(output: String) {
        assertTrue(
            "$MARKER INFO bridgetest.Main - first app line" in output,
            "the first app line carries the app's pattern. Output:\n$output",
        )
        assertStartupLineOnStandardError(output)
    }

    @Test
    fun `agent lines follow the application's configuration once the gate opens`() {
        val output = run(listOf(appClasses()) + jars(), sleepMillis = PAST_GATE_MILLIS, mainDelayMillis = 0)

        assertTrue(
            "$MARKER INFO bridgetest.Main - second app line" in output,
            "the second app line carries the app's pattern. Output:\n$output",
        )
        assertStartupLineOnStandardError(output)
        assertTrue(
            Regex("""$MARKER \w+ dev\.otherlode\.[\w.]+ - otherlode: .*failed""").containsMatchIn(output),
            "a line the agent logs after the gate follows the app's pattern. Output:\n$output",
        )
    }

    @Test
    fun `a JVM that exits at once does not wait for the gate and sends its final flush`() {
        AgentReceiver().use { receiver ->
            val started = System.nanoTime()
            run(listOf(appClasses()) + jars(), sleepMillis = 0, mainDelayMillis = 0, exportUrl = receiver.endpoint)
            val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)

            assertTrue(seconds < 8, "the JVM exited in $seconds s, well before the 10 s gate")
            assertTrue(receiver.batches.any { it.finalFlush }, "the collector received the final flush. Failures: ${receiver.failures}")
        }
    }

    @Test
    fun `the gate opens by time on its own and early only when told to`() {
        val byTime = gateOutput("wait", sleepMillis = 11_000)
        assertTrue(
            byTime.indexOf("FALLBACK early") in 0 until byTime.indexOf("PROBE gate.test"),
            "early line is gated, late is not. Output:\n$byTime",
        )
        assertTrue("FALLBACK late" !in byTime, "the late line left the fallback. Output:\n$byTime")

        val early = gateOutput("open", sleepMillis = 0)
        assertTrue("FALLBACK early" in early && "PROBE gate.test" in early, "openGate opens at once. Output:\n$early")
        assertTrue("FALLBACK late" !in early, "the late line left the fallback. Output:\n$early")
    }
}
