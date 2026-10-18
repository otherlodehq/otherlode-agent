package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

/**
 * A real `-javaagent` entry point for the servlet tests, installed at JVM startup. The container
 * loads `HttpServlet` before any test method runs, so a self-attach inside a test would be too late.
 *
 * [endpointRegistry] is the one registry of the test JVM. [flush] does what the agent does before it
 * builds a manifest, on a thread of its own.
 */
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

    /** Runs the agent's pre-manifest step on a new thread and returns that thread's name. */
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
