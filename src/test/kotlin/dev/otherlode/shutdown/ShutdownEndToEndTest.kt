package dev.otherlode.shutdown

import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent's shutdown hook against real JVMs (ADR 0084): what it opens, how long it takes, what
 * it waits for and where its late log lines go.
 */
class ShutdownEndToEndTest {
    private companion object {
        const val PACKAGE = "shutdowntest"

        val APP_SOURCE =
            """
            package shutdowntest;

            public class App {
                public static void work() {}

                public static void main(String[] args) throws Exception {
                    work();
                    long hookMillis = Long.parseLong(args[0]);
                    if (args.length > 1 && args[1].equals("jul")) {
                        java.util.logging.Logger.getLogger("app").info("app uses JUL");
                    }
                    if (hookMillis > 0) {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try {
                                Thread.sleep(hookMillis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            System.out.println("hook ended " + System.currentTimeMillis());
                        }, "app-hook"));
                    }
                    System.out.println("main done " + System.currentTimeMillis());
                }
            }
            """.trimIndent()

        val PROBE_SOURCE =
            """
            package shutdowntest;

            public class Probe {
                public static void main(String[] args) throws Exception {
                    java.lang.reflect.Field hooks = Class.forName("java.lang.ApplicationShutdownHooks").getDeclaredField("hooks");
                    System.out.println("deep access to ApplicationShutdownHooks.hooks: " + hooks.trySetAccessible());
                    System.out.println("probe module: " + Probe.class.getModule());
                    System.out.println("main done " + System.currentTimeMillis());
                }
            }
            """.trimIndent()
    }

    private val app = ForkedShutdownApp()

    @AfterTest
    fun cleanUp() = app.close()

    private fun options(exportUrl: String) = "serviceName=shutdown,includePackages=$PACKAGE,flushIntervalSeconds=60,exportUrl=$exportUrl"

    @Test
    fun `a class on the classpath cannot open the JDK's hook table with the agent attached`() {
        app.compile("shutdowntest/Probe.java" to PROBE_SOURCE)
        val closedPort = ServerSocket(0).use { it.localPort }
        val withAgent = app.run("$PACKAGE.Probe", options("http://127.0.0.1:$closedPort"))
        val control = app.run("$PACKAGE.Probe", null, jvmOptions = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"))

        assertTrue(
            "deep access to ApplicationShutdownHooks.hooks: true" in control.output,
            "the probe sees an open package. Output:\n${control.output}",
        )
        assertTrue(
            "deep access to ApplicationShutdownHooks.hooks: false" in withAgent.output,
            "the agent opens nothing. Output:\n${withAgent.output}",
        )
        assertFalse("cannot watch the application's shutdown hooks" in withAgent.output, "the weave installed. Output:\n${withAgent.output}")
    }

    @Test
    fun `the final flush waits for the application's shutdown hook`() {
        app.compile("shutdowntest/App.java" to APP_SOURCE)
        AnsweringCollector().use { collector ->
            val run = app.run("$PACKAGE.App", options(collector.endpoint), "1500")

            val hookEnded = checkNotNull(run.stamp("hook ended")) { "the hook ended. Output:\n${run.output}" }
            val final = collector.deltas.filter { it.batch.finalFlush }
            assertEquals(1, final.size, "one closing batch. Output:\n${run.output}")
            assertTrue(final.single().atMillis >= hookEnded, "the closing batch came after the hook ended")
            assertTrue(collector.deltas.any { !it.batch.finalFlush && it.atMillis < hookEnded }, "the early flush went out before the hook ended")
        }
    }

    @Test
    fun `with no application hook and a collector that never answers, shutdown ends by the 28 second deadline`() {
        app.compile("shutdowntest/App.java" to APP_SOURCE)
        BlackholeCollector().use { collector ->
            val run = app.run("$PACKAGE.App", options(collector.endpoint), "0")

            println("no application hook: shutdown took ${run.shutdownMillis} ms")
            assertTrue(run.shutdownMillis <= 30_000, "took ${run.shutdownMillis} ms. Output:\n${run.output}")
        }
    }

    @Test
    fun `with a 25 second application hook and a collector that never answers, shutdown ends by the 28 second deadline`() {
        app.compile("shutdowntest/App.java" to APP_SOURCE)
        BlackholeCollector().use { collector ->
            val run = app.run("$PACKAGE.App", options(collector.endpoint), "25000")

            println("25 s application hook: shutdown took ${run.shutdownMillis} ms")
            assertTrue(run.shutdownMillis in 25_000..30_000, "took ${run.shutdownMillis} ms. Output:\n${run.output}")
        }
    }

    @Test
    fun `the agent's shutdown lines reach standard error although the application uses java util logging`() {
        app.compile("shutdowntest/App.java" to APP_SOURCE)
        AnsweringCollector(status = 400).use { collector ->
            val run =
                app.run(
                    "$PACKAGE.App",
                    options(collector.endpoint),
                    "1000",
                    "jul",
                    jvmOptions = listOf("-Djava.util.logging.SimpleFormatter.format=%1\$tQ LOG %5\$s%n"),
                )

            println(run.output)
            val hookEnded = checkNotNull(run.stamp("hook ended")) { "the hook ended. Output:\n${run.output}" }
            val lines =
                Regex("""(?m)^(\d+) LOG (otherlode: .*)$""")
                    .findAll(run.output)
                    .map { it.groupValues[1].toLong() to it.groupValues[2] }
                    .toList()
            assertTrue(lines.any { it.first < hookEnded }, "the early flush's log lines reached standard error. Output:\n${run.output}")
            assertTrue(lines.any { it.first >= hookEnded }, "the final flush's log lines reached standard error. Output:\n${run.output}")
        }
    }
}
