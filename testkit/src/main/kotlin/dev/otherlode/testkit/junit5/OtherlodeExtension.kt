package dev.otherlode.testkit.junit5

import dev.otherlode.testkit.OtherlodeTestCollector
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * A JUnit 5 extension that starts one [OtherlodeTestCollector] for the life of the test JVM and
 * hands it to any test that asks for it.
 *
 * The extension does not attach the agent. Attach it with the `-javaagent` flag on the test task.
 *
 * ```kotlin
 * tasks.test {
 *     jvmArgs("-javaagent:/path/to/otherlode-agent.jar=exportUrl=http://localhost:4319,flushIntervalSeconds=1,includePackages=com.acme")
 * }
 * ```
 *
 * Register the extension on a test class and ask for the collector as a parameter:
 *
 * ```kotlin
 * @ExtendWith(OtherlodeExtension::class)
 * class CheckoutTest {
 *     @Test
 *     fun `checkout is called`(collector: OtherlodeTestCollector) {
 *         // exercise the app under test
 *         collector.awaitSettled(Duration.ofSeconds(10))
 *         assertTrue(collector.wasCalled("POST", "/checkout"))
 *     }
 * }
 * ```
 *
 * The collector binds to the port named by the `otherlode.testkit.port` system property, defaulting
 * to 4319, the port of the agent's default `exportUrl`. The agent always needs `includePackages`:
 * without it the agent refuses to start and no heartbeat arrives. [beforeAll] waits once per test
 * JVM for the agent's first liveness heartbeat, for the number of seconds in the
 * `otherlode.testkit.startup.timeout.seconds` system property, defaulting to 15.
 */
public class OtherlodeExtension :
    BeforeAllCallback,
    ParameterResolver {
    override fun beforeAll(context: ExtensionContext) {
        val store = context.root.getStore(NAMESPACE)
        val collector = collectorIn(context)

        val heartbeatSeen = store.get(HEARTBEAT_SEEN_KEY, Boolean::class.javaObjectType) ?: false
        if (heartbeatSeen) return
        val timeoutSeconds = resolveStartupTimeoutSeconds()
        try {
            collector.awaitNextFlush(Duration.ofSeconds(timeoutSeconds))
        } catch (e: TimeoutException) {
            throw IllegalStateException(startupTimeoutMessage(collector, timeoutSeconds), e)
        }
        store.put(HEARTBEAT_SEEN_KEY, true)
    }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean = parameterContext.parameter.type == OtherlodeTestCollector::class.java

    /**
     * Starts the collector if [beforeAll] has not run yet. Under `@TestInstance(PER_CLASS)`, JUnit
     * resolves a constructor parameter before it calls [beforeAll].
     */
    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any = collectorIn(extensionContext)

    private fun collectorIn(context: ExtensionContext): OtherlodeTestCollector {
        val collector =
            context.root
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(
                    COLLECTOR_KEY,
                    { CollectorHolder(startCollector()) },
                    CollectorHolder::class.java,
                ).collector
        sharedCollector = collector
        return collector
    }

    // Must implement neither CloseableResource nor AutoCloseable: the root store closes such values at
    // the end of the run, and the collector has to keep listening for the agent's shutdown-hook flush.
    private class CollectorHolder(
        val collector: OtherlodeTestCollector,
    )

    public companion object {
        private val NAMESPACE = ExtensionContext.Namespace.create(OtherlodeExtension::class.java)
        private const val COLLECTOR_KEY = "collector"
        private const val HEARTBEAT_SEEN_KEY = "heartbeat-seen"
        private const val PORT_PROPERTY = "otherlode.testkit.port"
        private const val STARTUP_TIMEOUT_PROPERTY = "otherlode.testkit.startup.timeout.seconds"
        private const val DEFAULT_PORT = 4319
        private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 15L

        @Volatile
        private var sharedCollector: OtherlodeTestCollector? = null

        /**
         * The collector [beforeAll] started for this test JVM.
         *
         * Throws [IllegalStateException] if no test class has run with [OtherlodeExtension]
         * registered yet.
         */
        @JvmStatic
        public fun collector(): OtherlodeTestCollector =
            sharedCollector
                ?: throw IllegalStateException(
                    "OtherlodeExtension has not run yet: add @ExtendWith(OtherlodeExtension::class) to a test class first",
                )

        private fun startCollector(): OtherlodeTestCollector {
            val port = resolvePort()
            return try {
                OtherlodeTestCollector.startForOneJvm(port)
            } catch (e: IOException) {
                throw IllegalStateException(
                    "otherlode-testkit: could not bind the collector to port $port. Another process may already be " +
                        "using it, or a previous test run's collector is still listening. Override the port with " +
                        "the '$PORT_PROPERTY' system property, and point the agent's exportUrl at the same port. " +
                        "If another fork of this test task holds it, run the tests that use the testkit with " +
                        "maxParallelForks = 1: every fork gets the same -javaagent arguments, so forks cannot " +
                        "each have a port.",
                    e,
                )
            }
        }

        private fun resolvePort(): Int {
            val raw = System.getProperty(PORT_PROPERTY)?.trim()?.ifBlank { null } ?: return DEFAULT_PORT
            return raw.toIntOrNull()
                ?: throw IllegalStateException("system property '$PORT_PROPERTY' must be an integer port, got '$raw'")
        }

        private fun resolveStartupTimeoutSeconds(): Long {
            val raw = System.getProperty(STARTUP_TIMEOUT_PROPERTY)?.trim()?.ifBlank { null } ?: return DEFAULT_STARTUP_TIMEOUT_SECONDS
            return raw.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException(
                    "system property '$STARTUP_TIMEOUT_PROPERTY' must be a positive integer, got '$raw'",
                )
        }

        private fun startupTimeoutMessage(
            collector: OtherlodeTestCollector,
            timeoutSeconds: Long,
        ): String =
            "otherlode-testkit: no delta batch arrived from the agent within ${timeoutSeconds}s. Add " +
                "\"-javaagent:<path to otherlode-agent.jar>=exportUrl=${collector.exportUrl},flushIntervalSeconds=1," +
                "includePackages=<your package>\" to the test task's JVM arguments. Without includePackages " +
                "the agent refuses to start and sends nothing; its ERROR line on the test JVM's standard " +
                "error names a package to use when it can find one. The agent's default flush interval is " +
                "60 seconds, longer than this timeout, which is the most likely cause if the flag is already " +
                "present with includePackages set."
    }
}
