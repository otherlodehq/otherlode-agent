package dev.otherlode.registry

import dev.otherlode.export.ChunkLimits
import dev.otherlode.export.DependencyDelta
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.export.DependencyLocation
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.chunkedByWeight
import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a dependency's bytes live on this instance. Never sent: the wire carries only
 * [DependencyLocation.location], a display string.
 */
sealed interface DependencyOrigin {
    /** A jar file on the classpath, by its absolute path. */
    data class FlatJar(
        val path: Path,
    ) : DependencyOrigin

    /** A jar stored inside another, such as `BOOT-INF/lib/x.jar` inside a Spring Boot fat jar. */
    data class NestedJar(
        val outerJar: Path,
        val entryName: String,
    ) : DependencyOrigin
}

/**
 * Tracks the dependencies this instance has seen, one record per identity key, and offers each
 * record to a manifest until a confirmed send carries it ([advanceManifest]).
 *
 * The identity key ([identityKey]) is the sorted list of a dependency's `group:artifact` pairs,
 * never its version. Ids are per instance, assigned from 0 in registration order.
 *
 * A record is held back from the manifest until a sweep has counted it and that sweep's counts
 * have been delivered ([isSendable]).
 *
 * Constructed with `indexClassNames`, the registry also keeps a class index from class name to
 * dependency id, the first registration of a name winning. [releaseClassIndex] drops it.
 *
 * A location under the home folder is stored with the home written as `~`.
 *
 * Safe to use from several threads.
 */
class DependencyRegistry(
    indexClassNames: Boolean = false,
    private val homeDirectory: String? = System.getProperty("user.home"),
) {
    private val homePrefixes: List<String> =
        listOfNotNull(homeDirectory, realPathOf(homeDirectory))
            .distinctBy { it.trimEnd('/', '\\') }

    /** How the startup listing ended, as [awaitListing] saw it. */
    enum class ListingOutcome {
        /** Finished, with every dependency it found registered. */
        COMPLETE,

        /** Failed; nothing it found is registered. */
        FAILED,

        /** Still running when the wait ran out. */
        TIMED_OUT,
    }

    /** One tracked dependency. */
    class DependencyEntry internal constructor(
        val dependencyId: Int,
        val identities: List<DependencyIdentity>,
        val identitySource: DependencyIdentitySource,
        val location: String,
        val discoverySource: DependencyDiscoverySource,
        val classCount: Int?,
        val origin: DependencyOrigin?,
    ) {
        @Volatile
        internal var delivered: Boolean = false

        internal val loadedClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        internal var firstLoadedAt: Long = 0

        @Volatile
        internal var countedInGeneration: Long = 0

        internal var lastDelivered: Long = 0

        internal var lastAppliedDeltaSequence: Long = 0

        internal fun toLocation(): DependencyLocation =
            DependencyLocation(dependencyId, identities, identitySource, location, discoverySource, classCount)
    }

    /**
     * One computed manifest chunk. Pass it to [advanceManifest] once the send carrying it is
     * confirmed.
     */
    class ManifestSnapshot internal constructor(
        val dependencies: List<DependencyLocation>,
        internal val staged: List<DependencyEntry>,
    )

    /**
     * One computed delta batch. Pass it to [advanceDeltas] once the send carrying it is confirmed.
     */
    class DeltaSnapshot internal constructor(
        val deltas: List<DependencyDelta>,
        internal val sequence: Long,
        internal val staged: List<Pair<DependencyEntry, Long>>,
    )

    private val entriesByKey = ConcurrentHashMap<List<String>, DependencyEntry>()
    private val entriesById = ConcurrentHashMap<Int, DependencyEntry>()
    private val entriesByOrigin = ConcurrentHashMap<DependencyOrigin, DependencyEntry>()
    private val judgedNotDependencies: MutableSet<DependencyOrigin> = ConcurrentHashMap.newKeySet()
    private val nextDependencyId = AtomicInteger(0)
    private val nextDeltaSequence = AtomicLong(0)

    @Volatile
    private var classIndex: ConcurrentHashMap<String, Int>? = if (indexClassNames) ConcurrentHashMap() else null
    private val listingEnded = CountDownLatch(1)

    @Volatile
    private var listingComplete = false
    private var listingFailed = false

    private val countGenerations = AtomicLong(0)
    private val deliveredGenerations = AtomicLong(0)

    @Volatile
    private var startupListingDelivered = false

    /**
     * True once the startup listing has finished and every dependency it found is registered. Stays
     * false when the listing failed.
     */
    val isListingComplete: Boolean
        get() = listingComplete

    /**
     * Records that the startup listing finished and releases every [awaitListing]. Does nothing
     * after [markListingFailed].
     */
    fun markListingComplete() {
        synchronized(this) {
            if (listingFailed) return
            listingComplete = true
        }
        listingEnded.countDown()
    }

    /**
     * Records that the startup listing failed and releases every [awaitListing].
     * [isListingComplete] stays false. Does nothing after [markListingComplete].
     */
    fun markListingFailed() {
        synchronized(this) {
            if (!listingComplete) listingFailed = true
        }
        listingEnded.countDown()
    }

    /** Waits up to [timeout] for the startup listing to end, and says how it ended. */
    fun awaitListing(timeout: Duration): ListingOutcome {
        val ended = listingEnded.await(timeout.toNanos(), TimeUnit.NANOSECONDS)
        return when {
            listingComplete -> ListingOutcome.COMPLETE
            ended -> ListingOutcome.FAILED
            else -> ListingOutcome.TIMED_OUT
        }
    }

    /**
     * The id of the dependency the class index maps [className] (dotted) to, or null. Always null
     * without the index.
     */
    fun dependencyForClass(className: String): Int? = classIndex?.get(className)

    /** How many class names the class index holds; 0 without the index or once it is released. */
    val classIndexSize: Int
        get() = classIndex?.size ?: 0

    /** Drops the class index and stops keeping one. */
    fun releaseClassIndex() {
        classIndex = null
    }

    /**
     * Registers a dependency and returns its id. A dependency whose identity key is already
     * registered keeps its record, and this returns that record's id. [identities] must not be
     * empty.
     *
     * With the class index kept, each of [classNames] not yet indexed is mapped to the returned id.
     */
    fun register(
        identities: List<DependencyIdentity>,
        identitySource: DependencyIdentitySource,
        location: String,
        discoverySource: DependencyDiscoverySource,
        classCount: Int? = null,
        origin: DependencyOrigin? = null,
        classNames: Collection<String> = emptyList(),
    ): Int {
        require(identities.isNotEmpty()) { "a dependency at $location needs at least one identity" }
        val shown = withAnyHomeAsTilde(location, homePrefixes)
        val id = registerEntry(identities, identitySource, shown, discoverySource, classCount, origin)
        classIndex?.let { index -> for (className in classNames) index.putIfAbsent(className, id) }
        return id
    }

    private fun registerEntry(
        identities: List<DependencyIdentity>,
        identitySource: DependencyIdentitySource,
        location: String,
        discoverySource: DependencyDiscoverySource,
        classCount: Int?,
        origin: DependencyOrigin?,
    ): Int =
        entriesByKey
            .computeIfAbsent(identityKey(identities)) {
                val entry =
                    DependencyEntry(
                        nextDependencyId.getAndIncrement(),
                        identities,
                        identitySource,
                        location,
                        discoverySource,
                        classCount,
                        origin,
                    )
                entriesById[entry.dependencyId] = entry
                if (origin != null) entriesByOrigin.putIfAbsent(origin.canonical(), entry)
                entry
            }.dependencyId

    /**
     * The id of the dependency registered with [origin], compared by canonical path, or null when
     * none was.
     */
    fun idForOrigin(origin: DependencyOrigin): Int? = entriesByOrigin[origin.canonical()]?.dependencyId

    /**
     * Records that the startup listing judged the jar at [origin] not a dependency, so the sweep
     * does not read it again.
     */
    fun recordNotADependency(origin: DependencyOrigin) {
        judgedNotDependencies += origin.canonical()
    }

    /**
     * Whether the startup listing judged the jar at [origin] not a dependency, compared by
     * canonical path.
     */
    fun isJudgedNotADependency(origin: DependencyOrigin): Boolean = origin.canonical() in judgedNotDependencies

    /** The id of the dependency registered under [identityKey], or null. */
    fun idForKey(identityKey: List<String>): Int? = entriesByKey[identityKey]?.dependencyId

    /**
     * Adds [className] to the distinct class names seen from dependency [dependencyId]. A name
     * already seen, from any loader, adds nothing. An unknown id is ignored.
     */
    fun recordLoaded(
        dependencyId: Int,
        className: String,
    ) {
        entriesById[dependencyId]?.loadedClassNames?.add(className)
    }

    /**
     * Returns batches of at most [maxPerBatch] deltas, covering every dependency whose distinct
     * loaded-class total differs from its last delivered total. Empty when nothing changed. Stamps
     * a dependency's first-loaded time the first time a compute sees its total above zero.
     *
     * Nothing is marked delivered here; see [advanceDeltas].
     */
    fun computeDeltas(maxPerBatch: Int): List<DeltaSnapshot> {
        val batches = mutableListOf<DeltaSnapshot>()
        var deltas = mutableListOf<DependencyDelta>()
        var staged = mutableListOf<Pair<DependencyEntry, Long>>()
        for (entry in entries()) {
            val total = entry.loadedClassNames.size.toLong()
            if (total == synchronized(entry) { entry.lastDelivered }) continue
            if (total > 0 && entry.firstLoadedAt == 0L) entry.firstLoadedAt = System.currentTimeMillis()
            if (deltas.size == maxPerBatch) {
                batches += DeltaSnapshot(deltas, nextDeltaSequence.incrementAndGet(), staged)
                deltas = mutableListOf()
                staged = mutableListOf()
            }
            deltas += DependencyDelta(entry.dependencyId, entry.firstLoadedAt, total)
            staged += entry to total
        }
        if (deltas.isNotEmpty()) batches += DeltaSnapshot(deltas, nextDeltaSequence.incrementAndGet(), staged)
        return batches
    }

    /**
     * Records the totals [snapshot] staged as delivered. Call this only once the send carrying it
     * is confirmed. An older snapshot confirmed after a newer one is ignored per entry, so a
     * confirmed total is never rolled back.
     */
    fun advanceDeltas(snapshot: DeltaSnapshot) {
        for ((entry, total) in snapshot.staged) {
            synchronized(entry) {
                if (snapshot.sequence <= entry.lastAppliedDeltaSequence) return@synchronized
                entry.lastAppliedDeltaSequence = snapshot.sequence
                entry.lastDelivered = total
            }
        }
    }

    /**
     * The newest counting generation, or 0 before any sweep has counted. Read it after a sweep and
     * before the delta sends that carry the sweep's counts, then pass it to [markCountsDelivered].
     */
    val countGeneration: Long
        get() = countGenerations.get()

    /**
     * The newest counting generation whose counts a confirmed delta send carried; see
     * [markCountsDelivered].
     */
    val deliveredGeneration: Long
        get() = deliveredGenerations.get()

    /**
     * Records that a counting sweep finished. This opens the next counting generation and stamps it
     * on every record that no earlier generation has stamped.
     */
    @Synchronized
    fun markCounted() {
        val generation = countGenerations.get() + 1
        for (entry in entriesByKey.values) {
            if (entry.countedInGeneration == 0L) entry.countedInGeneration = generation
        }
        countGenerations.set(generation)
    }

    /**
     * Records that every delta send computed after the sweep of [generation] was confirmed. The
     * delivered generation only rises: an older [generation] changes nothing.
     */
    fun markCountsDelivered(generation: Long) {
        deliveredGenerations.accumulateAndGet(generation, ::maxOf)
    }

    /**
     * Whether dependency [dependencyId] may go out on a manifest: a sweep counted it, and the
     * counts of that sweep have been delivered. False for an unknown id.
     */
    fun isSendable(dependencyId: Int): Boolean = entriesById[dependencyId]?.let(::isSendable) ?: false

    private fun isSendable(entry: DependencyEntry): Boolean {
        val generation = entry.countedInGeneration
        return generation in 1..deliveredGenerations.get()
    }

    /**
     * Whether any record is sendable ([isSendable]) and has not gone out on a confirmed manifest.
     */
    fun hasSendableUndelivered(): Boolean = entriesByKey.values.any { !it.delivered && isSendable(it) }

    /**
     * True once the startup listing is complete and every record it registered has gone out on a
     * confirmed manifest. A record discovered by load never holds it back. Stays false after a
     * failed listing. Once true, it stays true.
     */
    val isStartupListingDelivered: Boolean
        get() {
            if (startupListingDelivered) return true
            if (!listingComplete) return false
            val delivered =
                entriesByKey.values.all { it.discoverySource != DependencyDiscoverySource.STARTUP_CLASSPATH || it.delivered }
            if (delivered) startupListingDelivered = true
            return delivered
        }

    /** Every registered dependency, in id order. */
    fun entries(): List<DependencyEntry> = entriesByKey.values.sortedBy { it.dependencyId }

    /**
     * Returns chunks of at most [maxPerChunk] dependencies, covering every dependency not yet
     * delivered whose counts have been delivered ([isSendable]), in id order. Empty when there is
     * nothing to send. Nothing is marked delivered here; see [advanceManifest].
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> =
        chunkedByWeight(
            entries().filter { !it.delivered && isSendable(it) }.map { it to it.toLocation() },
            maxPerChunk,
            PayloadKind.MANIFEST,
            ChunkLimits.MANIFEST_FRAME_ALLOWANCE,
        ) { ProtoPayloadCodec.entryWeight(it.second) }
            .map { chunk -> ManifestSnapshot(chunk.map { it.second }, chunk.map { it.first }) }

    /**
     * Marks every dependency [snapshot] staged as delivered. Call this only once the send carrying
     * it is confirmed; a failed send leaves them for the next compute.
     */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for (entry in snapshot.staged) entry.delivered = true
    }

    companion object {
        /**
         * The sorted, distinct `group:artifact` pairs of [identities], with an empty group for an
         * identity that has none. The version is never part of it.
         */
        fun identityKey(identities: List<DependencyIdentity>): List<String> =
            identities.map { "${it.groupId.orEmpty()}:${it.artifactId}" }.distinct().sorted()

        private fun DependencyOrigin.canonical(): DependencyOrigin =
            when (this) {
                is DependencyOrigin.FlatJar -> DependencyOrigin.FlatJar(canonicalPath(path))
                is DependencyOrigin.NestedJar -> DependencyOrigin.NestedJar(canonicalPath(outerJar), entryName)
            }

        private fun canonicalPath(path: Path): Path =
            runCatching { path.toFile().canonicalFile.toPath() }.getOrDefault(path.toAbsolutePath())
    }
}

internal fun withHomeAsTilde(
    location: String,
    home: String?,
    ignoreCase: Boolean = File.separatorChar == '\\',
): String {
    val folder = home?.trimEnd('/', '\\') ?: return location
    if (folder.isEmpty() || !location.startsWith(folder, ignoreCase)) return location
    val rest = location.substring(folder.length)
    return when {
        rest.isEmpty() -> "~"
        rest[0] == '/' || rest[0] == '\\' -> "~$rest"
        else -> location
    }
}

internal fun withAnyHomeAsTilde(
    location: String,
    homes: List<String>,
): String {
    for (home in homes.sortedByDescending { it.trimEnd('/', '\\').length }) {
        val shown = withHomeAsTilde(location, home)
        if (shown != location) return shown
    }
    return location
}

private fun realPathOf(home: String?): String? {
    if (home.isNullOrBlank()) return null
    return try {
        Path.of(home).toRealPath().toString()
    } catch (_: IOException) {
        null
    } catch (_: InvalidPathException) {
        null
    }
}
