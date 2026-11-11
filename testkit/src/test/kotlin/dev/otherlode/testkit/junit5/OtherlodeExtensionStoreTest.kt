package dev.otherlode.testkit.junit5

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import java.lang.reflect.Proxy
import java.util.function.Function
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OtherlodeExtensionStoreTest {
    private val stored = mutableMapOf<Any, Any?>()
    private var previousPort: String? = null
    private var previousTimeout: String? = null

    @BeforeTest
    fun useAFreePortAndAShortTimeout() {
        previousPort = System.setProperty("otherlode.testkit.port", "0")
        previousTimeout = System.setProperty("otherlode.testkit.startup.timeout.seconds", "1")
    }

    @AfterTest
    fun restoreProperties() {
        restore("otherlode.testkit.port", previousPort)
        restore("otherlode.testkit.startup.timeout.seconds", previousTimeout)
        runCatching { OtherlodeExtension.collector().close() }
    }

    private fun restore(
        key: String,
        value: String?,
    ) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    @Test
    fun `the value kept in the root store is not AutoCloseable, so a store that closes such values cannot stop the collector`() {
        val context = fakeContext()

        assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(context) }

        assertTrue(stored.isNotEmpty(), "beforeAll must have put the collector in the store")
        assertTrue(stored.values.none { it is AutoCloseable }, "stored ${stored.values.map { it?.javaClass?.name }}")
    }

    @Test
    fun `a startup timeout names includePackages, the javaagent flag and the flush interval`() {
        val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }
        val message = failure.message.orEmpty()

        assertTrue("includePackages=<your package>" in message, message)
        assertTrue("refuses to start" in message, message)
        assertTrue("-javaagent:" in message, message)
        assertTrue("flushIntervalSeconds=1" in message, message)
        assertTrue("default flush interval is 60 seconds" in message, message)
    }

    @Test
    fun `a parameter resolved before beforeAll starts the collector in the root store`() {
        val parameterContext =
            Proxy.newProxyInstance(ParameterContext::class.java.classLoader, arrayOf(ParameterContext::class.java)) { _, _, _ -> null }
                as ParameterContext

        val resolved = OtherlodeExtension().resolveParameter(parameterContext, fakeContext())

        assertTrue(stored.isNotEmpty(), "resolveParameter must start the collector in the root store")
        assertSame(OtherlodeExtension.collector(), resolved)
    }

    @Test
    fun `a port already in use fails naming the property and the one-fork rule`() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("localhost")).use { taken ->
            System.setProperty("otherlode.testkit.port", taken.localPort.toString())

            val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }

            val message = failure.message.orEmpty()
            assertTrue("otherlode.testkit.port" in message, message)
            assertTrue("maxParallelForks = 1" in message, message)
        }
    }

    @Test
    fun `a port property that is not a number fails naming the property`() {
        System.setProperty("otherlode.testkit.port", "not-a-port")

        val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }

        assertTrue("otherlode.testkit.port" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a startup timeout property that is not a positive number fails naming the property`() {
        System.setProperty("otherlode.testkit.startup.timeout.seconds", "0")

        val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }

        assertTrue("otherlode.testkit.startup.timeout.seconds" in failure.message.orEmpty(), failure.message)
    }

    private fun fakeContext(): ExtensionContext {
        val loader = ExtensionContext::class.java.classLoader
        val store =
            Proxy.newProxyInstance(loader, arrayOf(ExtensionContext.Store::class.java)) { _, method, args ->
                when (method.name) {
                    "getOrComputeIfAbsent" -> {
                        @Suppress("UNCHECKED_CAST")
                        val compute = args[1] as Function<Any, Any?>
                        stored.getOrPut(args[0]) { compute.apply(args[0]) }
                    }

                    "get" -> {
                        stored[args[0]]
                    }

                    "put" -> {
                        stored[args[0]] = args[1]
                        null
                    }

                    else -> {
                        null
                    }
                }
            } as ExtensionContext.Store
        lateinit var context: ExtensionContext
        context =
            Proxy.newProxyInstance(loader, arrayOf(ExtensionContext::class.java)) { _, method, _ ->
                when (method.name) {
                    "getRoot" -> context
                    "getStore" -> store
                    else -> null
                }
            } as ExtensionContext
        return context
    }
}
