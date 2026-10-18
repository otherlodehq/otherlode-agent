package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointModules
import dev.otherlode.registry.EndpointRegistry
import java.lang.instrument.Instrumentation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real `-javaagent` entry point for the suites that run a servlet container beside OpenTelemetry.
 * It installs every endpoint module the build ships, the bridge among them, as the agent does with
 * `otelBridgeEnabled` set, and keeps the one [EndpointRegistry] the tests read.
 */
object PrefixTestAgent {
    lateinit var registry: EndpointRegistry
        private set

    /** Every warning or worse the agent logged through `System.Logger`, from the first one on; see [CapturingLoggerFinder]. */
    val warnings = CopyOnWriteArrayList<String>()

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        registry = EndpointRegistry()
        EndpointInstrumentation(registry, EndpointModules.discover()).install(inst)
    }
}
