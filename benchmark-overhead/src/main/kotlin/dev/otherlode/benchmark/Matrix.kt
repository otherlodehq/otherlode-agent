package dev.otherlode.benchmark

/** One of the three ways PetClinic is run. */
enum class Variant(
    val id: String,
) {
    NONE("none"),
    AGENT("agent"),
    AGENT_BASELINE("agent-baseline"),
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

/** The variants in the order round [round] runs them, so no variant always goes first. */
fun rotatedVariants(round: Int): List<Variant> {
    val all = Variant.entries
    return all.indices.map { all[(round + it) % all.size] }
}

/** The `-javaagent` option string for a run, or null for the variant with no agent. */
fun agentOptions(
    config: Config,
    variant: Variant,
): String? {
    if (variant == Variant.NONE) return null
    val base = "exportUrl=http://collector:4319,serviceName=petclinic,includePackages=${config.includePackages}"
    return if (variant == Variant.AGENT_BASELINE) "$base,staticBaselineEnabled=true" else base
}
