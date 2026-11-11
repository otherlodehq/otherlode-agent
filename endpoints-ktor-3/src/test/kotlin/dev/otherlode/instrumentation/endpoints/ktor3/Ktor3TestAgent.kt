package dev.otherlode.instrumentation.endpoints.ktor3

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

// Runs as a -javaagent: the inline routing DSL makes Gradle's test discovery load RoutingNode and RoutingRoot
// before any test method runs.
object Ktor3TestAgent {
    lateinit var registry: EndpointRegistry
        private set

    private lateinit var instrumentation: Instrumentation
    private lateinit var transformer: ResettableClassFileTransformer

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(Ktor3Module()))
        transformer = endpointInstrumentation.install(inst)
        instrumentation = inst
    }

    fun uninstall() {
        EndpointInstrumentation(registry, listOf(Ktor3Module())).uninstall(instrumentation, transformer)
    }
}
