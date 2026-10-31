package dev.otherlode.instrumentation

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Starts a JVM whose `main` sets `java.util.logging.manager`. The agent must not have fixed the
 * LogManager to the default class before `main` runs, whatever the agent was told to do.
 */
class JulManagerTest {
    private companion object {
        const val PACKAGE = "jultest"
        const val PROCESS_TIMEOUT_SECONDS = 120L

        val MAIN_SOURCE =
            """
            package jultest;

            import java.util.logging.Handler;
            import java.util.logging.Level;
            import java.util.logging.LogManager;
            import java.util.logging.LogRecord;
            import java.util.logging.Logger;

            public class JulMain {
                public static class TestManager extends LogManager {}

                public static void main(String[] args) throws Exception {
                    System.setProperty("java.util.logging.manager", TestManager.class.getName());
                    System.out.println("manager=" + LogManager.getLogManager().getClass().getName());
                    try {
                        Class<?> loggers = Class.forName("dev.otherlode.bootstrap.OtherlodeLoggers", false, null);
                        System.out.println("loggers=" + loggers.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        System.out.println("loggers=absent");
                    }
                    Logger logger = Logger.getLogger("dev.otherlode");
                    logger.setLevel(Level.ALL);
                    logger.addHandler(new Handler() {
                        public void publish(LogRecord record) {
                            System.out.println("jul=" + record.getLoggerName() + " source=" + record.getSourceClassName() + ": " + record.getMessage());
                        }
                        public void flush() {}
                        public void close() {}
                    });
                    Thread.sleep(Long.parseLong(args[0]));
                }
            }
            """.trimIndent()
    }

    private val classes = Files.createTempDirectory("otherlode-jul").toFile()

    @AfterTest
    fun cleanUp() {
        classes.deleteRecursively()
    }

    private fun compileMain() {
        val source = File(classes, "jultest/JulMain.java")
        source.parentFile.mkdirs()
        source.writeText(MAIN_SOURCE)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "no system Java compiler" }
        check(compiler.run(null, null, null, "-d", classes.absolutePath, source.absolutePath) == 0) { "could not compile the fixture" }
    }

    private fun run(
        agentOptions: String,
        sleepMillis: Long = 0,
        jvmOptions: List<String> = emptyList(),
    ): String {
        compileMain()
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val output = Files.createTempFile("otherlode-jul", ".log").toFile()
        try {
            val command =
                listOf(
                    File(System.getProperty("java.home"), "bin/java").absolutePath,
                    *jvmOptions.toTypedArray(),
                    "-javaagent:$agentJar=$agentOptions",
                    "-cp",
                    classes.absolutePath,
                    "$PACKAGE.JulMain",
                    sleepMillis.toString(),
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
        } finally {
            output.delete()
        }
    }

    private fun assertBootstrapNotAppended(output: String) {
        assertTrue("loggers=absent" in output, "the bootstrap jar was not appended. Output:\n$output")
        assertTrue("bootstrap classpath has been appended" !in output, "no CDS warning. Output:\n$output")
    }

    private fun assertMainChoseTheManager(output: String) {
        assertTrue(
            "manager=$PACKAGE.JulMain\$TestManager" in output,
            "the manager main named is the one in force. Output:\n$output",
        )
    }

    @Test
    fun `a normal start leaves the log manager to main and keeps the startup line`() {
        val output =
            run(
                "serviceName=jul,includePackages=$PACKAGE,flushIntervalSeconds=1,exportUrl=http://127.0.0.1:9",
                sleepMillis = 6000,
            )

        assertMainChoseTheManager(output)
        assertTrue("loggers=null" in output, "OtherlodeLoggers is defined by the boot loader. Output:\n$output")
        assertTrue("otherlode: installing endpoint modules" in output, "the startup line shows. Output:\n$output")
        assertTrue(
            Regex("""jul=dev\.otherlode\.[\w.]+ source=([\w.$]+): otherlode: .*failed""").containsMatchIn(output),
            "a line logged after main set up logging reaches the handler. Output:\n$output",
        )
        val sources =
            Regex(
                """jul=dev\.otherlode\.[\w.]+ source=([\w.$]+): otherlode:""",
            ).findAll(output).map { it.groupValues[1] }.toList()
        assertTrue(
            sources.isNotEmpty() && sources.all { it.startsWith("dev.otherlode.") && "AgentLogger" !in it },
            "each record names the class that logged, not the logger. Sources: $sources",
        )
    }

    @Test
    fun `a disabled start leaves the log manager to main and keeps the disabled line`() {
        val output = run("enabled=false")

        assertMainChoseTheManager(output)
        assertTrue("otherlode: disabled by configuration" in output, "the disabled line shows. Output:\n$output")
        assertTrue(
            Regex("""(?m)^INFO: otherlode: disabled by configuration""").containsMatchIn(output),
            "in the default console format. Output:\n$output",
        )
        assertBootstrapNotAppended(output)
    }

    @Test
    fun `a line written before install follows the console format property`() {
        val output = run("enabled=false", jvmOptions = listOf("-Djava.util.logging.SimpleFormatter.format=FMT %4\$s %5\$s%n"))

        assertTrue(
            Regex("""(?m)^FMT INFO otherlode: disabled by configuration""").containsMatchIn(output),
            "in the format the property names. Output:\n$output",
        )
    }

    @Test
    fun `a refused start leaves the log manager to main and keeps the refusal`() {
        val output = run("serviceName=jul,includePackages=,exportUrl=http://127.0.0.1:9")

        assertMainChoseTheManager(output)
        assertTrue("otherlode:" in output.replace("manager=", ""), "the refusal shows. Output:\n$output")
        assertTrue(
            Regex("""(?m)^SEVERE: otherlode: includePackages is not set""").containsMatchIn(output),
            "in the default console format. Output:\n$output",
        )
        assertBootstrapNotAppended(output)
    }
}
