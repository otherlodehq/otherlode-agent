package dev.otherlode.registry

internal class DefinitionWatch(
    attempt: DefinitionAttempt?,
    unwatchable: Boolean,
    sequence: Long,
) {
    private enum class State { PENDING, CONFIRMED, WITHHELD }

    enum class Verdict {
        SKIPPED,
        CONFIRMED,
        PENDING,
        MISSED,
        WITHHELD,
    }

    // Send-pool threads read isConfirmed, isWithheld and sequence without the lock, so state and
    // registrationSequence stay volatile.
    @Volatile
    private var state = State.PENDING

    @Volatile
    private var registrationSequence = sequence

    private var misses = 0
    private val attempts: MutableList<DefinitionAttempt> = if (attempt == null) mutableListOf() else mutableListOf(attempt)
    private var unwatchable = unwatchable
    private var reported = false

    val isConfirmed: Boolean get() = state == State.CONFIRMED

    val isWithheld: Boolean get() = state == State.WITHHELD

    val sequence: Long get() = registrationSequence

    fun hasAttempts(): Boolean = synchronized(this) { attempts.isNotEmpty() }

    fun startAttempt(
        attempt: DefinitionAttempt?,
        unwatchable: Boolean,
        nextSequence: () -> Long,
    ) {
        synchronized(this) {
            if (state == State.CONFIRMED) return
            if (attempt != null && attempts.any { it === attempt }) return
            if (attempt != null) attempts += attempt
            if (unwatchable) this.unwatchable = true
            misses = 0
            state = State.PENDING
            registrationSequence = nextSequence()
        }
    }

    fun confirm(): Boolean =
        synchronized(this) {
            if (state == State.CONFIRMED) return false
            state = State.CONFIRMED
            attempts.clear()
            true
        }

    fun reconcile(
        defined: Boolean,
        registeredUpTo: Long,
        attemptEnded: (DefinitionAttempt) -> Boolean,
    ): Verdict {
        if (registrationSequence > registeredUpTo || state != State.PENDING) return Verdict.SKIPPED
        if (defined) return if (confirm()) Verdict.CONFIRMED else Verdict.SKIPPED
        val watched = synchronized(this) { if (unwatchable) null else attempts.toList() } ?: return Verdict.PENDING
        if (watched.any { !attemptEnded(it) }) return Verdict.PENDING
        return synchronized(this) {
            if (attempts != watched || unwatchable || registrationSequence > registeredUpTo || state != State.PENDING) {
                return@synchronized Verdict.PENDING
            }
            misses++
            attempts.clear()
            if (misses < 2) return@synchronized Verdict.MISSED
            state = State.WITHHELD
            Verdict.WITHHELD
        }
    }

    fun markReported(): Boolean =
        synchronized(this) {
            if (reported) return false
            reported = true
            true
        }
}
