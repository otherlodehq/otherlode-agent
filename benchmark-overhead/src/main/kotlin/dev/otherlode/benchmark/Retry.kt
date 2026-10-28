package dev.otherlode.benchmark

/** The attempts a repeat gets when its only problems are environmental. */
const val MAX_ATTEMPTS = 2

/** The repeats of one run that may run again. Past it, an environmental failure fails the run. */
const val MAX_RETRIES_PER_RUN = 1

/** A repeat that failed only on [isEnvironmental] problems. [attemptRepeat] runs it again. */
class EnvironmentalRunException(
    message: String,
) : InvalidRunException(message)

/** An attempt of a repeat that was thrown away and run again, with the problems that cost it. */
data class DiscardedAttempt(
    val variantId: String,
    val repeat: Int,
    val attempt: Int,
    val problem: String,
)

/** Whether [problems] came from the machine and not from PetClinic or the agent: the saturation problem and nothing else. */
fun isEnvironmental(problems: List<String>): Boolean = problems.isNotEmpty() && problems.all { SATURATION.matches(it) }

private val SATURATION =
    Regex("PetClinic averaged [0-9.]+ of its \\d+ pinned core\\(s\\), under \\d+%, so its throughput measured the client or Postgres")

/**
 * Runs [attempt] with its number, 1 up to [MAX_ATTEMPTS]. An [EnvironmentalRunException] on a
 * non-final attempt goes into [discarded] and the next attempt runs, while [discarded] holds fewer
 * than [MAX_RETRIES_PER_RUN] attempts. Any other failure passes through. When an environmental
 * failure cannot run again, the failure names every attempt of the repeat.
 */
fun <T> attemptRepeat(
    variantId: String,
    repeat: Int,
    discarded: MutableList<DiscardedAttempt>,
    attempt: (Int) -> T,
): T {
    val problems = mutableListOf<String>()
    for (n in 1..MAX_ATTEMPTS) {
        try {
            return attempt(n)
        } catch (e: EnvironmentalRunException) {
            val problem = e.message ?: "environmental problem"
            problems += "attempt $n: $problem"
            if (n == MAX_ATTEMPTS || discarded.size >= MAX_RETRIES_PER_RUN) throw InvalidRunException(problems.joinToString("; "))
            discarded += DiscardedAttempt(variantId, repeat, n, problem)
        }
    }
    error("unreachable")
}

/** The repeats that ran again, with each discarded attempt's problem, or an empty string when none did. */
fun retriesSection(discarded: List<DiscardedAttempt>): String {
    if (discarded.isEmpty()) return ""
    val lines = discarded.joinToString("") { "- ${it.variantId} repeat ${it.repeat}, attempt ${it.attempt}: ${it.problem}\n" }
    return "\n### Repeats that ran again\n\nThe attempts below were discarded and are in no median.\n\n$lines"
}
