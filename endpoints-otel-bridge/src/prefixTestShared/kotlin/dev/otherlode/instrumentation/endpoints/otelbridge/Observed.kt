package dev.otherlode.instrumentation.endpoints.otelbridge

data class Observed(
    val framework: String,
    val verb: String,
    val template: String,
    val hits: Long,
) {
    override fun toString() = "$framework $verb $template x$hits"
}

fun observedEndpoints(): List<Observed> {
    val registry = PrefixTestAgent.registry
    val hits = registry.computeDeltas(maxPerBatch = MAX_PER_BATCH).flatMap { it.deltas }.associate { it.endpointId to it.hitsTotal }
    return registry.endpoints().map { Observed(it.framework, it.verb, it.routeTemplate, hits[it.endpointId] ?: 0L) }
}

private const val MAX_PER_BATCH = 10_000
