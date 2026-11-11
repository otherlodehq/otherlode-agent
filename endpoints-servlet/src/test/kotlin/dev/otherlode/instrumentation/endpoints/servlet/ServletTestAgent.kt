package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

// The container loads HttpServlet before any test method runs, so a self-attach inside a test is too late.
object ServletTestAgent {
    lateinit var endpointRegistry: EndpointRegistry
        private set

    private lateinit var endpointInstrumentation: EndpointInstrumentation
    private lateinit var endpointTransformer: ResettableClassFileTransformer

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        endpointRegistry = EndpointRegistry()
        endpointInstrumentation = EndpointInstrumentation(endpointRegistry, listOf(ServletModule()))
        endpointTransformer = endpointInstrumentation.install(inst)
    }

    fun flush(): String {
        var threadName = ""
        val thread =
            Thread({
                threadName = Thread.currentThread().name
                endpointInstrumentation.beforeManifest(graceMillis = 0, finalFlush = false)
            }, "servlet-test-flush")
        thread.start()
        thread.join()
        return threadName
    }
}
