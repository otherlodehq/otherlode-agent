package dev.otherlode.testkit

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ExportScheduler
import dev.otherlode.export.HttpExporter
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves [OtherlodeTestCollector.awaitSettled] against a real [ExportScheduler] whose flushes split
 * into several delta batches. Each cycle exercises fresh methods after the scheduler has started
 * and expects every one to be hit once the wait returns.
 */
class SettledWaitEndToEndTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null
    private var scheduler: ExportScheduler? = null
    private var collector: OtherlodeTestCollector? = null

    @AfterTest
    fun tearDown() {
        scheduler?.stop()
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedOtherlode = null
        collector?.close()
    }

    @Test
    fun `awaitSettled returns only after every hit made before the call has arrived, with flushes split into single-delta batches`() {
        val target = OtherlodeTestCollector.start()
        collector = target
        val registry = ProbeRegistry()
        val config =
            AgentConfig.parse(
                "includePackages=com.example.testkittarget," +
                    "exportUrl=${target.exportUrl}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-e2e," +
                    "serviceInstanceId=e2e-settled",
            )
        val otherlode = OtherlodeInstrumentation(config, registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val fixtures =
            CLASS_NAMES.map { name ->
                val type = Class.forName("$PACKAGE.$name", true, loader)
                type to type.getDeclaredConstructor().newInstance()
            }

        val exportScheduler =
            ExportScheduler(
                config,
                TestResources.forConfig(config),
                registry,
                EndpointRegistry(),
                HttpExporter(target.exportUrl),
                verifierWatch = watchedVerifier(),
                maxDeltasPerBatch = 1,
            )
        scheduler = exportScheduler
        exportScheduler.start()
        for (name in CLASS_NAMES) target.awaitProbe("$PACKAGE.$name", methodName(CYCLES - 1), Duration.ofSeconds(10))

        val missed = mutableListOf<String>()
        for (cycle in 0 until CYCLES) {
            for ((type, instance) in fixtures) type.getMethod(methodName(cycle)).invoke(instance)
            target.awaitSettled(Duration.ofSeconds(15))
            for (name in CLASS_NAMES) {
                if (!target.wasHit("$PACKAGE.$name", methodName(cycle))) missed += "cycle $cycle: $name.${methodName(cycle)}"
            }
        }

        assertTrue(missed.isEmpty(), "methods exercised before awaitSettled but not hit when it returned: $missed")
    }

    private fun methodName(cycle: Int) = "m%02d".format(cycle)

    private companion object {
        const val PACKAGE = "com.example.testkittarget"
        const val CYCLES = 20
        val CLASS_NAMES = "ABCDEFGH".map { "Settled$it" }
    }
}
