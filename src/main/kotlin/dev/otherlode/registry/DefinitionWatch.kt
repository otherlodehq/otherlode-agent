package dev.otherlode.registry

/**
 * The confirmation state of one watched class: whether the JVM defined it, as far as the agent
 * can tell. A registry holds one per class whose data it holds back until the class is confirmed.
 *
 * Every transition runs under the watch's own monitor. [isConfirmed], [isWithheld] and [sequence]
 * are volatile reads, so a send-pool thread reads them without the lock. A confirmation by
 * evidence ([confirm]) lifts a withhold. [reconcile] leaves a withheld class alone. A
 * confirmation is never taken back.
 */
internal class DefinitionWatch(
    attempt: DefinitionAttempt?,
    unwatchable: Boolean,
    sequence: Long,
) {
    private enum class State { PENDING, CONFIRMED, WITHHELD }

    /** What [reconcile] did to the watch. */
    enum class Verdict {
        /** The watch was registered after the snapshot, or was already confirmed or withheld. */
        SKIPPED,

        /** The class is defined, so the watch is now confirmed. */
        CONFIRMED,

        /** The class cannot take a miss now, or the miss no longer applied. */
        PENDING,

        /** The watch took a first miss. */
        MISSED,

        /** The watch took a second miss and is now withheld for good. */
        WITHHELD,
    }

    @Volatile
    private var state = State.PENDING

    @Volatile
    private var registrationSequence = sequence

    private var misses = 0
    private val attempts: MutableList<DefinitionAttempt> = if (attempt == null) mutableListOf() else mutableListOf(attempt)
    private var unwatchable = unwatchable
    private var reported = false

    /** True once the class is confirmed defined. */
    val isConfirmed: Boolean get() = state == State.CONFIRMED

    /** True once two misses withheld the class and nothing has lifted it. */
    val isWithheld: Boolean get() = state == State.WITHHELD

    /** The registration sequence of the class's latest transform. */
    val sequence: Long get() = registrationSequence

    /** Whether the watch still holds a definition attempt. */
    fun hasAttempts(): Boolean = synchronized(this) { attempts.isNotEmpty() }

    /**
     * Records a new transform of the class. Does nothing once the class is confirmed, or when
     * [attempt] is already held. Otherwise it adds [attempt] when not null, marks the watch
     * unwatchable when [unwatchable], clears its misses and a withhold, and takes a sequence from
     * [nextSequence].
     */
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

    /** Confirms the class from any state, and returns true only on the call that changed it. */
    fun confirm(): Boolean =
        synchronized(this) {
            if (state == State.CONFIRMED) return false
            state = State.CONFIRMED
            attempts.clear()
            true
        }

    /**
     * Applies a sweep's finding for this class, after the caller has checked its own evidence.
     * [defined] says the JVM's loaded classes hold it or its loader was collected. [registeredUpTo]
     * is the registration sequence read before that snapshot. [attemptEnded] runs outside the lock.
     *
     * Without a finding of [defined], the class takes a miss only when it is watchable and every
     * attempt it holds has ended. The miss is dropped when, between the read of the attempts and
     * the lock, a retry changed them or made the class unwatchable, the sequence moved past
     * [registeredUpTo], or the class was confirmed or withheld.
     */
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

    /** Marks the class as named, and returns true only on the first call. */
    fun markReported(): Boolean =
        synchronized(this) {
            if (reported) return false
            reported = true
            true
        }
}
