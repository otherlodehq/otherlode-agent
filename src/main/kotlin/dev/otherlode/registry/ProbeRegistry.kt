package dev.otherlode.registry

import dev.otherlode.agentLogger
import dev.otherlode.export.BodyKind
import dev.otherlode.export.ClassFailedToInitialize
import dev.otherlode.export.ClassLocation
import dev.otherlode.export.ClassReferences
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.FailedClass
import dev.otherlode.export.InitializationFailure
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.PayloadWeight
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.SkippedClass
import dev.otherlode.export.UnreportedClass
import java.lang.System.Logger.Level
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds one probe-count `long[]` per class, keyed by class name, probe-layout hash and defining
 * classloader identity, and does the delta and manifest accounting for them. A probe hit is a plain
 * `arr[index]++`, so counts are approximate under concurrency: increments can be lost and a read can
 * see a count fall. A probe that ran at least once stays at one or more on 64-bit HotSpot.
 *
 * The registry reports a class whose manifest entries alone break a bound of [PayloadKind.MANIFEST]
 * as a skipped class, and leaves its probe deltas out of every delta batch.
 *
 * @property confirmsDefinitions When true, [computeManifestDeltas] and [manifest] withhold a class's
 * probe locations and its [ClassLocation] and [ClassReferences] records until the class is confirmed
 * defined: by a probe count above zero, or in [confirmFrom] by its name or a collected classloader. When
 * false, [confirmFrom] tracks nothing.
 */
open class ProbeRegistry(
    private val confirmsDefinitions: Boolean = false,
) {
    private val log by agentLogger(ProbeRegistry::class.java.name)

    private data class RegistryKey(
        val className: String,
        val layoutHash: Long,
        val classLoaderId: Int,
    )

    private class ManifestData(
        val probes: List<ProbeMeta>,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val classReferences: List<String>,
        val sourceFile: String?,
        val bodyKind: BodyKind,
        val sourceName: String?,
        val kotlinKind: KotlinKind,
    )

    private class ClassEntry(
        val classId: Int,
        val className: String,
        val kinds: ByteArray,
        val counts: LongArray,
        val classLoaderRef: WeakReference<ClassLoader>?,
        manifestData: ManifestData,
        attempt: DefinitionAttempt?,
        unwatchable: Boolean,
        sequence: Long,
    ) {
        // The manifest send clears it and the delta send on another thread reads it, so a reader takes it
        // once into a local.
        @Volatile
        var manifestData: ManifestData? = manifestData

        val threwIndex: Int = kinds.indexOf(ProbeKind.INITIALIZER_THREW.ordinal.toByte())

        var lastSent: LongArray = LongArray(counts.size)

        var lastAppliedSequence: Long = 0
        val firstSeenAt: LongArray = LongArray(counts.size)

        val decreaseWarned: BooleanArray = BooleanArray(counts.size)
        var manifestIncluded: Boolean = false

        val watch: DefinitionWatch = DefinitionWatch(attempt, unwatchable, sequence)

        @Volatile
        var manifestWeight: PayloadWeight? = null

        @Volatile
        var oversize: Boolean? = null
        var oversizeLogged = false
    }

    private class SkippedEntry(
        val reason: String,
        val skippedAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    private class UnreportedEntry(
        val firstSeenUnreportedAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    private class FailedEntry(
        val withheldAt: Long,
    ) {
        var manifestIncluded: Boolean = false

        @Volatile
        var staged: Boolean = false
    }

    private class InitializationFailureEntry(
        val observedAt: Long,
    ) {
        @Volatile
        var manifestIncluded: Boolean = false
    }

    /**
     * One computed delta batch with the per-class snapshots it was built from. Pass it to
     * [advanceBaseline] once the batch is confirmed delivered.
     */
    class DeltaSnapshot internal constructor(
        val batch: DeltaBatch,
        internal val sequence: Long,
        internal val staged: List<Pair<Any, LongArray>>,
        internal val weight: PayloadWeight,
    )

    /**
     * One computed manifest chunk with the classes it staged. Pass it to
     * [advanceManifestBaseline] once the chunk is confirmed delivered.
     */
    class ManifestSnapshot internal constructor(
        val manifest: ProbeManifest,
        internal val stagedEntries: List<Any>,
        internal val stagedSkipped: List<Any>,
        internal val stagedUnreported: List<Any>,
        internal val stagedFailed: List<Any> = emptyList(),
        internal val stagedInitializationFailures: List<Any> = emptyList(),
        internal val weight: PayloadWeight,
    )

    private val entriesByKey = ConcurrentHashMap<RegistryKey, ClassEntry>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedEntry>()
    private val manifestFrames = ConcurrentHashMap<ResourceAttributes, PayloadWeight>()
    private val unreportedByClassName = ConcurrentHashMap<String, UnreportedEntry>()
    private val failedByClassName = ConcurrentHashMap<String, FailedEntry>()
    private val initializationFailures = ConcurrentHashMap<Pair<String, InitializationFailure>, InitializationFailureEntry>()
    private val unplacedVerificationFailures = AtomicLong(0)
    private val nothingToProbeClassNames = ConcurrentHashMap.newKeySet<String>()
    private val nextClassId = AtomicInteger(0)
    private val nextSnapshotSequence = AtomicLong(0)
    private val lastRegistrationSequence = AtomicLong(0)
    private val inScopeClasses = AtomicLong(0)

    /**
     * How many in-scope class records the registry has added: one per registered name, layout hash and
     * classloader, and one per class name skipped, found with nothing to probe or found unreported. A class
     * can count more than once. It never decreases.
     */
    fun inScopeClassCount(): Long = inScopeClasses.get()

    /**
     * Registers a class and returns the array its probes increment. A repeat call with the same
     * [className], [layoutHash] and [classLoader] returns the same array.
     *
     * [superClassName], [interfaceNames], [sourceFile], [bodyKind], [sourceName], [kotlinKind] and
     * [classReferences] travel on the manifest with the class and play no part in the key. In a
     * registry that confirms definitions, a repeat call for a class not yet confirmed starts a new definition
     * attempt, which clears its misses and lifts a withhold, and each call takes the name out of the failed set
     * unless a manifest chunk has staged or carried its failure. A call inside [inTransform] that cannot read
     * the class's definition attempt makes the class unwatchable, so it is never named failed.
     */
    open fun register(
        className: String,
        layoutHash: Long,
        probes: List<ProbeMeta>,
        classLoader: ClassLoader? = null,
        superClassName: String? = null,
        interfaceNames: List<String> = emptyList(),
        classReferences: List<String> = emptyList(),
        sourceFile: String? = null,
        bodyKind: BodyKind = BodyKind.NONE,
        sourceName: String? = null,
        kotlinKind: KotlinKind = KotlinKind.NONE,
    ): LongArray {
        val key = RegistryKey(className, layoutHash, System.identityHashCode(classLoader))
        var created = false
        val attempt = if (confirmsDefinitions) captureAttempt() else null
        val unwatchable = confirmsDefinitions && inTransform.get() && attempt == null
        if (unwatchable) warnUnwatchableOnce()
        val entry =
            entriesByKey.computeIfAbsent(key) {
                created = true
                inScopeClasses.incrementAndGet()
                ClassEntry(
                    classId = nextClassId.getAndIncrement(),
                    className = className,
                    kinds = ByteArray(probes.size) { probes[it].kind.ordinal.toByte() },
                    counts = LongArray(probes.size),
                    classLoaderRef = weakClassLoaderRef(classLoader),
                    manifestData =
                        ManifestData(
                            probes = probes,
                            superClassName = superClassName,
                            interfaceNames = interfaceNames,
                            classReferences = classReferences,
                            sourceFile = sourceFile,
                            bodyKind = bodyKind,
                            sourceName = sourceName,
                            kotlinKind = kotlinKind,
                        ),
                    attempt = attempt,
                    unwatchable = unwatchable,
                    sequence = lastRegistrationSequence.incrementAndGet(),
                )
            }
        if (confirmsDefinitions) {
            if (!created) startNewAttempt(entry, attempt, unwatchable)
            forgetUnsentFailure(className)
        }
        return entry.counts
    }

    private fun startNewAttempt(
        entry: ClassEntry,
        attempt: DefinitionAttempt?,
        unwatchable: Boolean,
    ) {
        if (isConfirmed(entry)) return
        entry.watch.startAttempt(attempt, unwatchable) { lastRegistrationSequence.incrementAndGet() }
    }

    private val unwatchableWarned = AtomicBoolean(false)

    private val inTransform = ThreadLocal.withInitial { false }

    /**
     * Runs [block] as the agent's own transformer call, so a [register] inside it that cannot read its
     * definition attempt makes the class unwatchable instead of counting misses.
     */
    fun <T> inTransform(block: () -> T): T {
        val outer = inTransform.get()
        inTransform.set(true)
        try {
            return block()
        } finally {
            if (outer) inTransform.set(true) else inTransform.remove()
        }
    }

    private fun warnUnwatchableOnce() {
        if (!unwatchableWarned.compareAndSet(false, true)) return
        log.log(
            System.Logger.Level.WARNING,
            "otherlode: the definition attempt of a woven class could not be read from its thread's stack; " +
                "such classes are never reported as failed to load",
        )
    }

    private fun forgetUnsentFailure(className: String) {
        if (failedByClassName.isEmpty()) return
        failedByClassName.computeIfPresent(className) { _, failed -> if (failed.manifestIncluded || failed.staged) failed else null }
    }

    internal open fun captureAttempt(): DefinitionAttempt? = DefinitionAttempt.capture()

    internal open fun attemptEnded(attempt: DefinitionAttempt): Boolean = attempt.hasEnded()

    internal fun registrationSequence(): Long = lastRegistrationSequence.get()

    internal open fun weakClassLoaderRef(classLoader: ClassLoader?): WeakReference<ClassLoader>? = classLoader?.let { WeakReference(it) }

    /**
     * The array [register] returned for this [className], [layoutHash] and [classLoader], or null if
     * none is registered.
     */
    fun lookup(
        className: String,
        layoutHash: Long,
        classLoader: ClassLoader?,
    ): LongArray? = entriesByKey[RegistryKey(className, layoutHash, System.identityHashCode(classLoader))]?.counts

    /**
     * Names of every registered class, across all classloaders.
     */
    fun registeredClassNames(): Set<String> = entriesByKey.keys.mapTo(HashSet()) { it.className }

    /**
     * Of [candidates], the names that are not registered, not recorded as skipped and not recorded as
     * having nothing to probe.
     */
    fun unaccountedFrom(candidates: Collection<String>): List<String> {
        val registered = registeredClassNames()
        return candidates.filter {
            it !in registered && !skippedByClassName.containsKey(it) && it !in nothingToProbeClassNames
        }
    }

    /**
     * Records a class the agent matched and found nothing in to probe. It is local only and never
     * reaches the wire.
     */
    fun recordNothingToProbe(className: String) {
        if (nothingToProbeClassNames.add(className)) inScopeClasses.incrementAndGet()
    }

    /**
     * Records a class the sweep found loaded but unreported, keeping the time it was first found.
     * Returns true the first time for a class name.
     */
    fun recordUnreported(className: String): Boolean {
        var added = false
        unreportedByClassName.computeIfAbsent(className) {
            added = true
            inScopeClasses.incrementAndGet()
            UnreportedEntry(System.currentTimeMillis())
        }
        return added
    }

    /**
     * Records a class the agent withheld for good because the JVM never defined it, keeping the time it
     * was withheld. Returns true the first time for a class name.
     */
    fun recordFailed(className: String): Boolean {
        var added = false
        failedByClassName.computeIfAbsent(className) {
            added = true
            FailedEntry(System.currentTimeMillis())
        }
        return added
    }

    /**
     * Drops each class from the unreported set that is registered or recorded as skipped or as
     * having nothing to probe, and returns how many were dropped. It does not retract a row
     * already delivered.
     */
    fun purgeAccountedFor(): Int {
        val names = unreportedByClassName.keys.toList()
        if (names.isEmpty()) return 0
        val stillUnaccounted = unaccountedFrom(names).toSet()
        var removed = 0
        for (name in names) {
            if (name !in stillUnaccounted && unreportedByClassName.remove(name) != null) removed++
        }
        return removed
    }

    fun unreportedClassCount(): Int = unreportedByClassName.size

    private fun isConfirmed(entry: ClassEntry): Boolean {
        if (entry.watch.isConfirmed) return true
        if (entry.counts.any { it > 0L }) {
            entry.watch.confirm()
            forgetUnsentFailure(entry.className)
        }
        return entry.watch.isConfirmed
    }

    /**
     * Reconciles the classes not yet confirmed defined against [loadedClassNames], the JVM's loaded-class
     * names, and returns the names withheld for good by this call. It confirms a class when its name is
     * in the set or its classloader was collected. A class found in neither takes a miss only when every
     * definition attempt recorded for it has ended, and an unwatchable class never takes one. Two misses
     * withhold a class for good and record it as failed ([recordFailed]). A name is returned once. A failure
     * that no manifest chunk has staged or carried leaves the failed set when an entry of that name is not
     * withheld.
     *
     * [registeredUpTo] is the registration sequence read before [loadedClassNames] was taken. It leaves
     * alone a class registered after it. A registry that does not confirm definitions returns an empty
     * list and records nothing.
     */
    open fun confirmFrom(
        loadedClassNames: Set<String>,
        registeredUpTo: Long = Long.MAX_VALUE,
    ): List<String> {
        if (!confirmsDefinitions) return emptyList()
        val newlyWithheld = mutableListOf<String>()
        for (entry in entriesByKey.values) {
            if (entry.watch.sequence > registeredUpTo) continue
            if (isConfirmed(entry)) continue
            val loaderCollected = entry.classLoaderRef != null && entry.classLoaderRef.get() == null
            val defined = entry.className in loadedClassNames || loaderCollected
            if (entry.watch.reconcile(defined, registeredUpTo, ::attemptEnded) != DefinitionWatch.Verdict.WITHHELD) continue
            recordFailed(entry.className)
            if (entry.watch.markReported()) newlyWithheld += entry.className
        }
        dropSupersededFailures()
        return newlyWithheld
    }

    private fun dropSupersededFailures() {
        if (failedByClassName.values.none { !it.manifestIncluded }) return
        for (entry in entriesByKey.values) {
            if (!entry.watch.isWithheld) forgetUnsentFailure(entry.className)
        }
    }

    /**
     * How many registered classes are neither confirmed defined nor withheld for good. Zero when the
     * registry does not confirm definitions.
     */
    fun unconfirmedClassCount(): Int =
        if (!confirmsDefinitions) 0 else entriesByKey.values.count { !it.watch.isWithheld && !isConfirmed(it) }

    /**
     * How many registered classes were never confirmed defined and are withheld for good.
     */
    fun withheldForGoodClassCount(): Int = entriesByKey.values.count { it.watch.isWithheld && !isConfirmed(it) }

    /**
     * Records a class the agent matched but could not instrument, so it reaches the wire as a skipped
     * class with [reason]. A repeat call for a class name keeps the first reason and time.
     */
    fun recordSkipped(
        className: String,
        reason: String,
    ) {
        skippedByClassName.computeIfAbsent(className) {
            inScopeClasses.incrementAndGet()
            SkippedEntry(reason, System.currentTimeMillis())
        }
    }

    /**
     * Returns the probes whose count differs from the last delivered count, as one batch. Each
     * [ProbeDelta] carries the cumulative count (`hits_total`), not the change. This marks nothing
     * delivered, so pass the result to [advanceBaseline] once the batch is confirmed delivered.
     *
     * Throws [IllegalArgumentException] when the changed probes need more than one batch.
     */
    open fun computeDeltaBatch(resource: ResourceAttributes): DeltaSnapshot = computeDeltaBatches(resource, Int.MAX_VALUE).single()

    /**
     * Like [computeDeltaBatch], but splits the changed probes into batches of at most
     * [maxDeltasPerBatch] deltas. A batch also closes when the next class would take it past a bound of
     * [PayloadKind.DELTA_BATCH]. It never splits a class across batches, so a class with more changed
     * probes than the cap gets a batch of its own. A class reported as skipped for size has no deltas.
     *
     * Always returns at least one batch.
     */
    open fun computeDeltaBatches(
        resource: ResourceAttributes,
        maxDeltasPerBatch: Int,
    ): List<DeltaSnapshot> {
        val batches = mutableListOf<DeltaSnapshot>()
        var deltas = mutableListOf<ProbeDelta>()
        var staged = mutableListOf<Pair<Any, LongArray>>()
        val frame = ProtoPayloadCodec.weightOf(DeltaBatch(resource, emptyList()))
        var weight = frame

        fun seal() {
            batches += DeltaSnapshot(DeltaBatch(resource, deltas), nextSnapshotSequence.incrementAndGet(), staged, weight)
            deltas = mutableListOf()
            staged = mutableListOf()
            weight = frame
        }
        for (entry in entriesByKey.values) {
            if (breaksBound(entry, resource)) continue
            val snapshot = entry.counts.copyOf()
            val entryDeltas = changedProbesOf(entry, snapshot)
            if (entryDeltas.isEmpty()) continue
            val entryWeight = entryDeltas.fold(PayloadWeight.ZERO) { sum, delta -> sum + ProtoPayloadCodec.entryWeight(delta) }
            val full = deltas.size + entryDeltas.size > maxDeltasPerBatch || !PayloadKind.DELTA_BATCH.admits(weight + entryWeight)
            if (deltas.isNotEmpty() && full) seal()
            deltas += entryDeltas
            weight += entryWeight
            staged += entry to snapshot
        }
        if (deltas.isNotEmpty() || batches.isEmpty()) seal()
        return batches
    }

    private fun changedProbesOf(
        entry: ClassEntry,
        snapshot: LongArray,
    ): List<ProbeDelta> {
        val deltas = mutableListOf<ProbeDelta>()
        for (index in snapshot.indices) {
            val current = snapshot[index]
            val lastSent = entry.lastSent[index]
            if (current == lastSent) continue
            if (current < lastSent) {
                warnOnceAboutDecrease(entry, index, lastSent, current)
            }
            if (current > 0 && entry.firstSeenAt[index] == 0L) {
                entry.firstSeenAt[index] = System.currentTimeMillis()
            }
            deltas +=
                ProbeDelta(
                    classId = entry.classId,
                    probeIndex = index,
                    kind = PROBE_KINDS[entry.kinds[index].toInt()],
                    firstSeenAt = entry.firstSeenAt[index],
                    hitsTotal = current,
                )
        }
        return deltas
    }

    private fun warnOnceAboutDecrease(
        entry: ClassEntry,
        index: Int,
        lastSent: Long,
        current: Long,
    ) {
        if (entry.decreaseWarned[index]) return
        entry.decreaseWarned[index] = true
        log.log(
            Level.WARNING,
            "otherlode: probe count went backward for ${entry.className}#$index " +
                "(was $lastSent, now $current); reporting it as-is",
        )
    }

    /**
     * Records [snapshot]'s per-class counts as delivered. Call it only after the snapshot's batch is
     * confirmed delivered. It ignores a snapshot older than one already applied to a class, for that
     * class.
     */
    fun advanceBaseline(snapshot: DeltaSnapshot) {
        for ((entryRef, counts) in snapshot.staged) {
            val entry = entryRef as ClassEntry
            synchronized(entry) {
                if (snapshot.sequence <= entry.lastAppliedSequence) return@synchronized
                entry.lastAppliedSequence = snapshot.sequence
                entry.lastSent = counts
            }
        }
    }

    /**
     * A manifest of every registered class, unchunked. The agent exports [computeManifestDeltas]
     * instead. A registry that confirms definitions leaves out unconfirmed classes. [resource] names the instance
     * and run the manifest's `class_id` values belong to.
     *
     * Throws [IllegalStateException] naming the class when it meets one whose manifest was delivered.
     */
    fun manifest(resource: ResourceAttributes): ProbeManifest {
        val published =
            entriesByKey.values
                .filter { !confirmsDefinitions || isConfirmed(it) }
                .map { entry ->
                    entry to
                        (
                            entry.manifestData
                                ?: throw IllegalStateException(
                                    "the manifest of ${entry.className} was delivered, so its probe locations are gone",
                                )
                        )
                }
        val locations = published.flatMap { (entry, data) -> locationsOf(entry, data) }
        val skipped =
            skippedByClassName.map { (className, entry) ->
                SkippedClass(className, entry.reason, entry.skippedAt)
            }
        val classLocations = published.map { (entry, data) -> classLocationOf(entry, data) }
        val classReferences = published.mapNotNull { (entry, data) -> classReferencesOf(entry, data) }
        val unreported =
            unreportedByClassName.map { (className, entry) ->
                UnreportedClass(className, entry.firstSeenUnreportedAt)
            }
        val failed =
            failedByClassName.map { (className, entry) ->
                FailedClass(className, entry.withheldAt)
            }
        recordInitializationFailures()
        return ProbeManifest(
            resource,
            locations,
            skipped,
            classLocations = classLocations,
            unreportedClasses = unreported,
            classReferences = classReferences,
            failedClasses = failed,
            classesFailedToInitialize =
                initializationFailures.map { (key, entry) -> ClassFailedToInitialize(key.first, key.second, entry.observedAt) },
        )
    }

    private fun recordInitializationFailures() {
        for (entry in entriesByKey.values) {
            if (entry.threwIndex < 0 || entry.counts[entry.threwIndex] <= 0L) continue
            initializationFailures.computeIfAbsent(entry.className to InitializationFailure.STATIC_INITIALIZER_THREW) {
                InitializationFailureEntry(System.currentTimeMillis())
            }
        }
    }

    /**
     * Records that the JVM's verifier rejected [className] ([InitializationFailure.VERIFIER_REJECTED]),
     * first seen at [observedAt] in milliseconds since the epoch. Every name is recorded, in scope or
     * not, and sent once.
     */
    fun recordVerifierRejected(
        className: String,
        observedAt: Long,
    ) {
        initializationFailures.computeIfAbsent(className to InitializationFailure.VERIFIER_REJECTED) {
            InitializationFailureEntry(observedAt)
        }
    }

    /**
     * Adds [count] verification failures whose class the agent could not name to the run's total.
     */
    fun recordUnplacedVerificationFailures(count: Long) {
        if (count > 0) unplacedVerificationFailures.addAndGet(count)
    }

    /**
     * The run's total of verification failures that named no class the agent could read.
     */
    fun unplacedVerificationFailures(): Long = unplacedVerificationFailures.get()

    private fun locationsOf(
        entry: ClassEntry,
        data: ManifestData,
    ): List<ProbeLocation> =
        data.probes.mapIndexed { index, meta ->
            ProbeLocation(
                classId = entry.classId,
                probeIndex = index,
                kind = meta.kind,
                className = entry.className,
                methodName = meta.methodName,
                methodDescriptor = meta.methodDescriptor,
                line = meta.line,
                branchIndex = meta.branchIndex,
                inline = meta.inline,
                parameterIndex = meta.parameterIndex,
                parameterName = meta.parameterName,
                overridable = meta.overridable,
                targetClassName = meta.targetClassName,
                calls = meta.calls,
                inlinedFromClassName = meta.inlinedFromClassName,
                generatedBy = meta.generatedBy,
                unreadShape = meta.unreadShape,
                referencedClasses = meta.referencedClasses,
                branchKey = meta.branchKey,
                lambdaBody = meta.lambdaBody,
                branchSites = meta.branchSites,
                siteIndex = meta.siteIndex,
                static = meta.static,
                parameterNames = meta.parameterNames,
                genericSignature = meta.genericSignature,
                extensionReceiver = meta.extensionReceiver,
                outsideCaller = meta.outsideCaller,
            )
        }

    private fun classLocationOf(
        entry: ClassEntry,
        data: ManifestData,
    ): ClassLocation =
        ClassLocation(
            entry.classId,
            data.superClassName,
            data.interfaceNames,
            data.sourceFile,
            data.bodyKind,
            data.sourceName,
            data.kotlinKind,
        )

    private fun classReferencesOf(
        entry: ClassEntry,
        data: ManifestData,
    ): ClassReferences? = data.classReferences.takeIf { it.isNotEmpty() }?.let { ClassReferences(entry.classId, it) }

    /**
     * Returns the classes not yet delivered in a manifest, as one chunk. It always returns a snapshot,
     * possibly with nothing in it. This marks nothing delivered, so pass the result to
     * [advanceManifestBaseline] once the manifest is confirmed delivered.
     *
     * Throws [IllegalArgumentException] when the manifest needs more than one chunk.
     */
    open fun computeManifestDelta(resource: ResourceAttributes): ManifestSnapshot {
        val chunks = computeManifestDeltas(resource, Int.MAX_VALUE).toList()
        require(chunks.size <= 1) { "the manifest needs ${chunks.size} chunks, so it has no single form" }
        return chunks.singleOrNull()
            ?: ProbeManifest(resource, emptyList()).let {
                ManifestSnapshot(it, emptyList(), emptyList(), emptyList(), weight = ProtoPayloadCodec.weightOf(it))
            }
    }

    /**
     * Like [computeManifestDelta], but yields chunks of at most [maxEntriesPerChunk] entries. A chunk
     * also closes when the next class would take it past a bound of [PayloadKind.MANIFEST]. It never
     * splits a class across chunks, so a class with more entries than the cap gets a chunk of its own.
     * It records a class whose entries alone break a bound as skipped with a fixed reason and stages it
     * without its probes.
     *
     * It builds chunks lazily. A consumer that advances each chunk before asking for the next holds at
     * most one chunk's locations. Yields nothing when there is nothing to send.
     */
    open fun computeManifestDeltas(
        resource: ResourceAttributes,
        maxEntriesPerChunk: Int,
    ): Sequence<ManifestSnapshot> =
        sequence {
            recordInitializationFailures()
            val kind = PayloadKind.MANIFEST
            val frame = ProtoPayloadCodec.weightOf(ProbeManifest(resource, emptyList()))
            var locations = mutableListOf<ProbeLocation>()
            var skipped = mutableListOf<SkippedClass>()
            var classLocations = mutableListOf<ClassLocation>()
            var classReferences = mutableListOf<ClassReferences>()
            var stagedEntries = mutableListOf<Any>()
            var stagedSkipped = mutableListOf<Any>()
            var unreported = mutableListOf<UnreportedClass>()
            var stagedUnreported = mutableListOf<Any>()
            var failed = mutableListOf<FailedClass>()
            var stagedFailed = mutableListOf<Any>()
            var initializationFailed = mutableListOf<ClassFailedToInitialize>()
            var stagedInitializationFailed = mutableListOf<Any>()

            var chunkWeight = 0
            var encoded = frame

            fun full(
                entries: Int,
                weight: PayloadWeight,
            ): Boolean = chunkWeight > 0 && (chunkWeight + entries > maxEntriesPerChunk || !kind.admits(encoded + weight))

            fun takeChunk(): ManifestSnapshot {
                val chunk =
                    ManifestSnapshot(
                        ProbeManifest(
                            resource,
                            locations,
                            skipped,
                            classLocations = classLocations,
                            unreportedClasses = unreported,
                            classReferences = classReferences,
                            failedClasses = failed,
                            classesFailedToInitialize = initializationFailed,
                        ),
                        stagedEntries,
                        stagedSkipped,
                        stagedUnreported,
                        stagedFailed,
                        stagedInitializationFailed,
                        weight = encoded,
                    )
                locations = mutableListOf()
                skipped = mutableListOf()
                classLocations = mutableListOf()
                classReferences = mutableListOf()
                stagedEntries = mutableListOf()
                stagedSkipped = mutableListOf()
                unreported = mutableListOf()
                stagedUnreported = mutableListOf()
                failed = mutableListOf()
                stagedFailed = mutableListOf()
                initializationFailed = mutableListOf()
                stagedInitializationFailed = mutableListOf()
                chunkWeight = 0
                encoded = frame
                return chunk
            }
            for (entry in entriesByKey.values) {
                if (entry.manifestIncluded) continue
                if (confirmsDefinitions && !isConfirmed(entry)) continue
                var data = entry.manifestData ?: continue
                if (breaksBound(entry, resource)) {
                    recordOversize(entry)
                    stagedEntries += entry
                    continue
                }
                val classWeight = manifestWeightOf(entry, data)
                val weight =
                    data.probes.size +
                        data.probes.sumOf { meta ->
                            meta.calls.size + meta.referencedClasses.size + meta.branchSites.sumOf { it.chunkWeight }
                        } + 1 + data.classReferences.size
                if (full(weight, classWeight)) {
                    yield(takeChunk())
                    // The consumer may have delivered this class while the chunk was yielded.
                    data = entry.manifestData ?: continue
                }
                stagedEntries += entry
                locations += locationsOf(entry, data)
                classLocations += classLocationOf(entry, data)
                classReferencesOf(entry, data)?.let { classReferences += it }
                chunkWeight += weight
                encoded += classWeight
            }
            for ((className, entry) in skippedByClassName) {
                if (entry.manifestIncluded) continue
                val skippedClass = SkippedClass(className, entry.reason, entry.skippedAt)
                val skippedWeight = ProtoPayloadCodec.entryWeight(skippedClass)
                if (full(1, skippedWeight)) {
                    yield(takeChunk())
                    if (entry.manifestIncluded) continue
                }
                stagedSkipped += entry
                skipped += skippedClass
                chunkWeight += 1
                encoded += skippedWeight
            }
            for ((className, entry) in unreportedByClassName) {
                if (entry.manifestIncluded) continue
                val unreportedClass = UnreportedClass(className, entry.firstSeenUnreportedAt)
                val unreportedWeight = ProtoPayloadCodec.entryWeight(unreportedClass)
                if (full(1, unreportedWeight)) {
                    yield(takeChunk())
                    if (entry.manifestIncluded) continue
                }
                stagedUnreported += entry
                unreported += unreportedClass
                chunkWeight += 1
                encoded += unreportedWeight
            }
            for ((className, entry) in failedByClassName) {
                if (entry.manifestIncluded) continue
                val failedClass = FailedClass(className, entry.withheldAt)
                val failedWeight = ProtoPayloadCodec.entryWeight(failedClass)
                if (full(1, failedWeight)) {
                    yield(takeChunk())
                    if (entry.manifestIncluded) continue
                }
                val stillFailed = failedByClassName.computeIfPresent(className) { _, current -> current.also { it.staged = true } }
                if (stillFailed !== entry) continue
                stagedFailed += entry
                failed += failedClass
                chunkWeight += 1
                encoded += failedWeight
            }
            for ((key, entry) in initializationFailures) {
                if (entry.manifestIncluded) continue
                val initializationFailure = ClassFailedToInitialize(key.first, key.second, entry.observedAt)
                val initializationWeight = ProtoPayloadCodec.entryWeight(initializationFailure)
                if (full(1, initializationWeight)) {
                    yield(takeChunk())
                    if (entry.manifestIncluded) continue
                }
                stagedInitializationFailed += entry
                initializationFailed += initializationFailure
                chunkWeight += 1
                encoded += initializationWeight
            }
            if (chunkWeight > 0 || stagedEntries.isNotEmpty()) yield(takeChunk())
        }

    private fun manifestWeightOf(
        entry: ClassEntry,
        data: ManifestData,
    ): PayloadWeight =
        entry.manifestWeight ?: run {
            val locations =
                locationsOf(entry, data).fold(PayloadWeight.ZERO) { sum, location ->
                    sum +
                        ProtoPayloadCodec.entryWeight(location)
                }
            val classLocation = ProtoPayloadCodec.entryWeight(classLocationOf(entry, data))
            val references = classReferencesOf(entry, data)?.let { ProtoPayloadCodec.entryWeight(it) } ?: PayloadWeight.ZERO
            (locations + classLocation + references).also { entry.manifestWeight = it }
        }

    private fun breaksBound(
        entry: ClassEntry,
        resource: ResourceAttributes,
    ): Boolean {
        entry.oversize?.let { return it }
        val data = entry.manifestData ?: return false
        val frame = manifestFrames.getOrPut(resource) { ProtoPayloadCodec.weightOf(ProbeManifest(resource, emptyList())) }
        val oversize = PayloadKind.MANIFEST.exceededBound(frame + manifestWeightOf(entry, data)) != null
        entry.oversize = oversize
        return oversize
    }

    private fun recordOversize(entry: ClassEntry) {
        skippedByClassName.computeIfAbsent(entry.className) { SkippedEntry(OVERSIZE_REASON, System.currentTimeMillis()) }
        if (!entry.oversizeLogged) {
            entry.oversizeLogged = true
            log.log(
                Level.WARNING,
                "otherlode: ${entry.className} is reported as skipped: its manifest entries alone exceed the ingest limits " +
                    "on encoded bytes or elements, so its methods are not reported",
            )
        }
    }

    /**
     * Marks every class staged by [snapshot] as included and releases its probe locations and other
     * manifest-only data, which cannot be sent again. Call it only after the manifest is confirmed
     * delivered.
     */
    fun advanceManifestBaseline(snapshot: ManifestSnapshot) {
        if (snapshot.stagedEntries.isNotEmpty()) manifestConfirmed = true
        for (entry in snapshot.stagedEntries) {
            (entry as ClassEntry).manifestIncluded = true
            entry.manifestData = null
            entry.manifestWeight = null
        }
        for (entry in snapshot.stagedSkipped) (entry as SkippedEntry).manifestIncluded = true
        for (entry in snapshot.stagedUnreported) (entry as UnreportedEntry).manifestIncluded = true
        for (entry in snapshot.stagedFailed) (entry as FailedEntry).manifestIncluded = true
        for (entry in snapshot.stagedInitializationFailures) (entry as InitializationFailureEntry).manifestIncluded = true
    }

    /**
     * True once [advanceManifestBaseline] has recorded a manifest that held at least one class,
     * including a class too large to send that travelled as a skipped record. It never goes back to
     * false.
     */
    @Volatile
    var manifestConfirmed: Boolean = false
        private set

    internal fun classesHoldingMetadata(): Int = entriesByKey.values.count { it.manifestData != null }

    internal fun classesHoldingAttempts(): Int = entriesByKey.values.count { it.watch.hasAttempts() }

    internal companion object {
        const val OVERSIZE_REASON: String = "too large to report: its manifest entries exceed the ingest limits"

        private val PROBE_KINDS: List<ProbeKind> = ProbeKind.entries
    }
}
