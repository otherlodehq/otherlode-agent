package dev.otherlode.instrumentation.endpoints.jaxrs

import dev.otherlode.config.AgentConfig
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

// Runs as a -javaagent: Gradle's test discovery loads the fixture classes before any test method runs.
// The method tier installs before the endpoint tier, so the endpoint transformer sees the class the method tier
// rewrote.
object JaxRsTestAgent {
    private const val FIXTURE_PACKAGE = "com.example.jaxrs.fixture"

    lateinit var endpointRegistry: EndpointRegistry
        private set

    lateinit var probeRegistry: ProbeRegistry
        private set

    private lateinit var instrumentation: Instrumentation
    private lateinit var methodInstrumentation: OtherlodeInstrumentation
    private lateinit var methodTransformer: ResettableClassFileTransformer
    private lateinit var endpointInstrumentation: EndpointInstrumentation
    private lateinit var endpointTransformer: ResettableClassFileTransformer

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        instrumentation = inst

        probeRegistry = ProbeRegistry()
        methodInstrumentation = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$FIXTURE_PACKAGE"), probeRegistry)
        methodTransformer = methodInstrumentation.install(inst)

        endpointRegistry = EndpointRegistry()
        endpointInstrumentation = EndpointInstrumentation(endpointRegistry, listOf(JaxRsModule()))
        endpointTransformer = endpointInstrumentation.install(inst)
    }

    fun uninstall() {
        endpointInstrumentation.uninstall(instrumentation, endpointTransformer)
        methodInstrumentation.uninstall(instrumentation, methodTransformer)
    }
}
