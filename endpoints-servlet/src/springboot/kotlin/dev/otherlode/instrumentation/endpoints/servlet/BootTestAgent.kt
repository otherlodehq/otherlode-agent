package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointModules
import dev.otherlode.registry.EndpointRegistry
import java.lang.instrument.Instrumentation

object BootTestAgent {
    lateinit var endpointRegistry: EndpointRegistry
        private set

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        endpointRegistry = EndpointRegistry()
        EndpointInstrumentation(endpointRegistry, EndpointModules.discover()).install(inst)
    }
}
