package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointModules
import dev.otherlode.registry.EndpointRegistry
import java.lang.instrument.Instrumentation

/**
 * A real `-javaagent` entry point that installs every endpoint module on the classpath, as the
 * agent does: the servlet module and the Spring MVC module together.
 */
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
