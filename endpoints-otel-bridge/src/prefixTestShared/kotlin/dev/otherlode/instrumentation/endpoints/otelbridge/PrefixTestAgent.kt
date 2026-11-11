package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointModules
import dev.otherlode.registry.EndpointRegistry
import java.lang.instrument.Instrumentation
import java.util.concurrent.CopyOnWriteArrayList

object PrefixTestAgent {
    lateinit var registry: EndpointRegistry
        private set

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
