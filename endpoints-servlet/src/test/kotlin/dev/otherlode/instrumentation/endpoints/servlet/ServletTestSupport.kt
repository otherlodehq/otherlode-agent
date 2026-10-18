package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.export.EndpointLocation
import dev.otherlode.export.EndpointTemplateKind
import dev.otherlode.registry.EndpointRegistry

/** The registry reads the servlet tests share. */
abstract class ServletTestSupport {
    protected val container: ServletContainer = containerUnderTest()

    protected val registry: EndpointRegistry get() = ServletTestAgent.endpointRegistry

    protected fun servletEndpoints(): List<EndpointLocation> = registry.endpoints().filter { it.framework == "servlet" }

    /** The `VERB template` of every servlet endpoint whose template starts with [prefix]. */
    protected fun declared(prefix: String): Set<String> =
        servletEndpoints().filter { it.routeTemplate.startsWith(prefix) }.map { "${it.verb} ${it.routeTemplate}" }.toSet()

    protected fun endpoint(
        verb: String,
        template: String,
        kind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ): EndpointLocation? = servletEndpoints().singleOrNull { it.verb == verb && it.routeTemplate == template && it.templateKind == kind }

    /** The hits counted for the endpoint, zero for one that is declared and never called. */
    protected fun hits(
        verb: String,
        template: String,
        kind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ): Long {
        val id =
            checkNotNull(endpoint(verb, template, kind)) {
                "no endpoint $verb $template ($kind) is declared: ${servletEndpoints()}"
            }.endpointId
        return registry
            .computeDeltas(maxPerBatch = 10_000)
            .flatMap { it.deltas }
            .firstOrNull { it.endpointId == id }
            ?.hitsTotal ?: 0L
    }

    protected fun totalHits(): Long = registry.computeDeltas(maxPerBatch = 10_000).flatMap { it.deltas }.sumOf { it.hitsTotal }
}
