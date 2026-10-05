package dev.otherlode.benchmark

/** What the warmup does after a slice. */
enum class WarmupVerdict {
    /** Run another slice. */
    CONTINUE,

    /** The last two slices each matched the one before within the drift bound, and the minimum has passed. */
    STEADY,

    /** The cap was reached without the throughput settling. The window's own warmth rule judges the run. */
    CAPPED,
}

/**
 * Whether the warmup is over, given the throughput of each slice so far, in order.
 *
 * The warmup is steady once at least [minSeconds] have been spent and the last two slices each differ
 * from the slice before them by at most [steadyDrift], as a fraction of that earlier slice. It stops
 * without being steady once [capSeconds] have been spent. A slice whose predecessor served nothing
 * cannot be steady. Steadiness is checked before the cap, so a series that settles on its last
 * permitted slice counts as steady.
 */
fun warmupVerdict(
    sliceThroughputs: List<Double>,
    sliceSeconds: Int,
    minSeconds: Int,
    capSeconds: Int,
    steadyDrift: Double,
): WarmupVerdict {
    val spent = sliceThroughputs.size.toLong() * sliceSeconds
    if (spent >= minSeconds && sliceThroughputs.size >= 3) {
        val (a, b, c) = sliceThroughputs.takeLast(3)
        if (withinDrift(a, b, steadyDrift) && withinDrift(b, c, steadyDrift)) return WarmupVerdict.STEADY
    }
    return if (spent >= capSeconds) WarmupVerdict.CAPPED else WarmupVerdict.CONTINUE
}

// The bound is inclusive; the epsilon keeps a difference that is exactly the bound in decimal from failing on binary rounding.
private fun withinDrift(
    earlier: Double,
    later: Double,
    steadyDrift: Double,
): Boolean = earlier > 0 && Math.abs(later - earlier) / earlier <= steadyDrift + 1e-12
