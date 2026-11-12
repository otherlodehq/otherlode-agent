package dev.otherlode.instrumentation.branch

import dev.otherlode.export.UnreadShape
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Why a class's plumbing-shaped methods are reported as unread shapes. */
enum class UnreadCause {
    /** The class names a Scala 3 release the agent has not read. */
    UNREAD_RELEASE,

    /** The class names no compiler the agent can read: a Scala 2 class, or a Scala 3 class with no readable `.tasty`. */
    VERSION_BLIND,

    /**
     * The class can hold no hand-written plumbing, such as an enum's companion, so its unmatched plumbing is
     * unread whatever the release.
     */
    UNREAD_STRUCTURE,

    /** The class holds compiler-plugin members beside hand-written ones that the bytecode does not tell apart. */
    PLUGIN_FILLED,
}

/**
 * Thread-safe totals of the methods reported as unread shapes across every class this agent
 * instruments, by [UnreadShape] family, with the Scala 3 releases the agent has not read. Branch
 * outcomes reported as unread shapes, and classes analysed from the bytes they arrived as, are
 * totalled apart.
 */
class UnreadShapeCounts {
    private val countsByFamily: Map<UnreadShape, AtomicLong> =
        UnreadShape.entries.filter { it != UnreadShape.NONE }.associateWith { AtomicLong(0) }
    private val classesSeen = ConcurrentHashMap.newKeySet<String>()
    private val classesWarned = ConcurrentHashMap.newKeySet<String>()
    private val releases = ConcurrentHashMap.newKeySet<String>()
    private val methodsByCause: Map<UnreadCause, AtomicLong> = UnreadCause.entries.associateWith { AtomicLong(0) }
    private val outcomesByFamily: Map<UnreadShape, AtomicLong> =
        UnreadShape.entries.filter { it != UnreadShape.NONE }.associateWith { AtomicLong(0) }
    private val outcomeClassesSeen = ConcurrentHashMap.newKeySet<String>()
    private val receivedBytesClasses = ConcurrentHashMap.newKeySet<String>()

    /**
     * Adds [className]'s unread methods by family, the first time a class of that name is recorded, so
     * a class two loaders define counts once. Notes [release] and [cause]. Returns true the first time
     * [className] is recorded with a non-null [release].
     */
    fun record(
        className: String,
        release: String?,
        cause: UnreadCause,
        methodsByFamily: Map<UnreadShape, Int>,
    ): Boolean {
        if (methodsByFamily.isEmpty()) return false
        if (classesSeen.add(className)) {
            for ((family, count) in methodsByFamily) countsByFamily.getValue(family).addAndGet(count.toLong())
            methodsByCause.getValue(cause).addAndGet(methodsByFamily.values.sum().toLong())
        }
        if (release == null) return false
        releases += release
        return classesWarned.add(className)
    }

    /**
     * Adds [className]'s unread branch outcomes by family, the first time a class of that name is
     * recorded. They are totalled apart from the unread methods, count under no [UnreadCause] and name no
     * release.
     */
    fun recordOutcomes(
        className: String,
        outcomesByFamily: Map<UnreadShape, Int>,
    ) {
        if (outcomesByFamily.isEmpty() || !outcomeClassesSeen.add(className)) return
        for ((family, count) in outcomesByFamily) this.outcomesByFamily.getValue(family).addAndGet(count.toLong())
    }

    /** The number of unread branch outcomes, every family included. */
    fun outcomeTotal(): Long = outcomesByFamily.values.sumOf { it.get() }

    /** How many branch outcomes were unread shapes of [family]. */
    fun outcomeCountOf(family: UnreadShape): Long = outcomesByFamily[family]?.get() ?: 0

    /** How many classes had at least one unread branch outcome. */
    fun outcomeClasses(): Int = outcomeClassesSeen.size

    /** Notes that [className] had no class file and was analysed from the bytes it arrived as. */
    fun recordReceivedBytesClass(className: String) {
        receivedBytesClasses += className
    }

    /** How many classes were analysed from the bytes they arrived as, for want of a class file. */
    fun receivedBytesClasses(): Int = receivedBytesClasses.size

    /** How many of the unread methods were counted under [cause], each class under the cause of its first record. */
    fun countOf(cause: UnreadCause): Long = methodsByCause.getValue(cause).get()

    /** The number of unread methods, every family included. */
    fun total(): Long = countsByFamily.values.sumOf { it.get() }

    /** How many methods were unread shapes of [family]. */
    fun countOf(family: UnreadShape): Long = countsByFamily[family]?.get() ?: 0

    /** The Scala 3 releases not on the read list that a recorded class was compiled by, sorted. */
    fun unreadReleases(): List<String> = releases.sorted()

    /** How many classes had at least one unread method. */
    fun classes(): Int = classesSeen.size
}
