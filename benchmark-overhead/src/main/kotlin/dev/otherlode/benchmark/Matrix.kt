package dev.otherlode.benchmark

/**
 * One of the ways PetClinic is run. [otherlode] marks the variants that run the Otherlode agent, the
 * only ones the agent's error scan, the collector's delivery checks and the manifest counts apply to.
 */
enum class Variant(
    val id: String,
    val otherlode: Boolean,
) {
    NONE("none", false),
    AGENT("agent", true),
    AGENT_BASELINE("agent-baseline", true),
    OTEL("otel", false),
}

/** The variants a run covers unless `-Pvariants` says otherwise. */
val DEFAULT_VARIANTS = listOf(Variant.NONE, Variant.AGENT, Variant.AGENT_BASELINE)

/**
 * The variants named in [text], a comma-separated list of ids, in the order given. Refuses an
 * unknown id, a repeated one, and a list without `none`, which every change is measured against.
 */
fun parseVariants(text: String): List<Variant> {
    val variants =
        text.split(',').map { raw ->
            val id = raw.trim()
            Variant.entries.firstOrNull { it.id == id }
                ?: error("Unknown variant '$id' in -Pvariants, expected a list of ${Variant.entries.joinToString(", ") { it.id }}")
        }
    val repeated =
        variants
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    check(repeated.isEmpty()) { "-Pvariants names ${repeated.joinToString(", ") { it.id }} more than once" }
    check(Variant.NONE in variants) { "-Pvariants must include none: every change is measured against it" }
    return variants
}

/** Which classes the agent is told to instrument. One config per Gradle invocation. */
enum class Config(
    val id: String,
    val includePackages: String,
) {
    HEADLINE("headline", "org.springframework.samples.petclinic"),
    CEILING("ceiling", "org.springframework"),
    ;

    companion object {
        fun parse(id: String): Config = entries.firstOrNull { it.id == id } ?: error("Unknown -Pconfig '$id', expected headline or ceiling")
    }
}

/** [variants] in the order round [round] runs them, so no variant always goes first. */
fun rotatedVariants(
    round: Int,
    variants: List<Variant> = DEFAULT_VARIANTS,
): List<Variant> = variants.indices.map { variants[(round + it) % variants.size] }

/** The `-javaagent` option string for a run, or null for a variant that does not run the Otherlode agent. */
fun agentOptions(
    config: Config,
    variant: Variant,
): String? {
    if (!variant.otherlode) return null
    val base = "exportUrl=http://collector:4319,serviceName=petclinic,includePackages=${config.includePackages}"
    return if (variant == Variant.AGENT_BASELINE) "$base,staticBaselineEnabled=true" else base
}

/** Where the OpenTelemetry Java agent jar sits in every variant's container. */
const val OTEL_AGENT_PATH = "/app/opentelemetry-javaagent.jar"

/** The network alias of the no-op OTLP sink. */
const val OTLP_SINK_HOST = "otlp-sink"

/** The port the sink listens on, OTLP's `http/protobuf` default. */
const val OTLP_SINK_PORT = 4318

/**
 * The JVM options that run the OpenTelemetry agent, before `-jar`: the agent, the sink as its OTLP
 * endpoint, and the service name. Everything else stays at the agent's defaults. Empty for any other variant.
 */
fun otelJvmOptions(variant: Variant): List<String> {
    if (variant != Variant.OTEL) return emptyList()
    return listOf(
        "-javaagent:$OTEL_AGENT_PATH",
        "-Dotel.exporter.otlp.endpoint=http://$OTLP_SINK_HOST:$OTLP_SINK_PORT",
        "-Dotel.service.name=petclinic",
    )
}
