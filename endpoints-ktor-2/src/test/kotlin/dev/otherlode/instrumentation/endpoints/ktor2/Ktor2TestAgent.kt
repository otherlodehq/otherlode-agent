package dev.otherlode.instrumentation.endpoints.ktor2

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

// Runs as a -javaagent: the inline routing DSL makes Gradle's test discovery load Route and Routing before any
// test method runs.
object Ktor2TestAgent {
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
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(Ktor2Module()))
        transformer = endpointInstrumentation.install(inst)
        instrumentation = inst
    }

    fun uninstall() {
        EndpointInstrumentation(registry, listOf(Ktor2Module())).uninstall(instrumentation, transformer)
    }
}
