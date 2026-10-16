package dev.otherlode.benchmark

/**
 * What the agent's class listing cost on the runner: the first call's CPU and wall time, the
 * median CPU time of the four calls after it, and the number of classes listed.
 */
data class ClassListingCost(
    val coldCpuMs: Double,
    val coldWallMs: Double,
    val warmCpuMs: Double,
    val classes: Int,
) {
    /** The values under the names `metadata.properties` records them by. */
    fun properties(): Map<String, String> =
        linkedMapOf(
            "classListingColdCpuMs" to coldCpuMs.toString(),
            "classListingColdWallMs" to coldWallMs.toString(),
            "classListingWarmCpuMs" to warmCpuMs.toString(),
            "classListingClasses" to classes.toString(),
        )

    companion object {
        private val LINE =
            Regex("""^LISTING_COST coldCpuMs=([\d.]+) coldWallMs=([\d.]+) warmCpuMs=([\d.]+) classes=(\d+)$""", RegexOption.MULTILINE)

        /**
         * The cost in `ListingCost.java`'s output line.
         *
         * @throws InvalidRunException when [output] has no such line, which is what a null listing leaves.
         */
        fun parse(output: String): ClassListingCost {
            val m =
                LINE.find(output)
                    ?: throw InvalidRunException("the class listing program printed no LISTING_COST line: ${output.trim()}")
            val (cold, wall, warm, classes) = m.destructured
            return ClassListingCost(cold.toDouble(), wall.toDouble(), warm.toDouble(), classes.toInt())
        }
    }
}

/**
 * The command that runs `ListingCost.java` in the PetClinic container: `javac` compiles it against
 * the agent jar, then a separate `java` runs it with PetClinic's jar, [config]'s include rules and no
 * excludes. A JVM that compiled the program itself, as the source launcher does, has already loaded
 * and warmed the zip and collection code the listing uses, so its first call would read low.
 */
fun listingCostCommand(config: Config): List<String> =
    listOf(
        "sh",
        "-c",
        "javac -cp /app/otherlode-agent.jar -d /tmp/listing-cost /app/ListingCost.java && " +
            "exec java -cp /app/otherlode-agent.jar:/tmp/listing-cost ListingCost \"$@\"",
        "sh",
        "/app/petclinic.jar",
        config.includePackages,
        "",
    )
