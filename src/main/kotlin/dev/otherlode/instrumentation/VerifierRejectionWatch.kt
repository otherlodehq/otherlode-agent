package dev.otherlode.instrumentation

import dev.otherlode.bootstrap.OtherlodeVerifierMessages
import dev.otherlode.registry.ProbeRegistry

/**
 * What the run can see of the JVM's verifier rejections. [watched] is true once [VerifyErrorHook]
 * installed, and every manifest the run sends says so. [drainInto] moves the messages the hook
 * collected into the registry, and runs on the export thread.
 *
 * A name in [ownFailures] is dropped: the agent's own retransformation of that class failed, so the
 * `VerifyError` is not a refusal to link it. A message in no read format, and each message the ring
 * lost to overflow, adds to the run's unplaced total. [carried] and [carriedOverflow] are what the
 * hook's own check at install drained besides its own message; the first [drainInto] reads them.
 */
class VerifierRejectionWatch(
    val watched: Boolean,
    private val ownFailures: OwnRetransformationFailures = OwnRetransformationFailures(),
    private val drainMessages: () -> Array<String> = { OtherlodeVerifierMessages.drain() },
    private val takeOverflow: () -> Long = { OtherlodeVerifierMessages.takeOverflow() },
    private var carried: Array<String> = emptyArray(),
    private var carriedOverflow: Long = 0,
) {
    /** Parses what the hook collected since the last call and records it in [registry]. Does nothing when not [watched]. */
    fun drainInto(
        registry: ProbeRegistry,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!watched) return
        val messages = carried + drainMessages()
        carried = emptyArray()
        var unplaced = carriedOverflow + takeOverflow()
        carriedOverflow = 0
        for (message in messages) {
            when (val parsed = VerifierMessageParser.parse(message)) {
                is ParsedVerifierMessage.Placed -> {
                    if (!ownFailures.contains(parsed.className)) registry.recordVerifierRejected(parsed.className, now)
                }

                ParsedVerifierMessage.Ignored -> {}

                ParsedVerifierMessage.Unplaced -> {
                    unplaced++
                }
            }
        }
        registry.recordUnplacedVerificationFailures(unplaced)
    }

    companion object {
        /** A run without the hook: it reads nothing and says so. */
        val NOT_WATCHED = VerifierRejectionWatch(watched = false)
    }
}
