package dev.otherlode.export

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.classlisting.ClassListingSender
import dev.otherlode.config.AgentConfig
import dev.otherlode.dependencies.DependencyListingRun
import dev.otherlode.instrumentation.BootstrapHolder
import dev.otherlode.instrumentation.LoadedClassSweep
import dev.otherlode.instrumentation.VerifierRejectionWatch
import dev.otherlode.instrumentation.branch.BranchDropCounts
import dev.otherlode.instrumentation.branch.BranchDropReason
import dev.otherlode.instrumentation.branch.ConfiguredCallbackAnnotations
import dev.otherlode.instrumentation.branch.UnreadCause
import dev.otherlode.instrumentation.branch.UnreadShapeCounts
import dev.otherlode.instrumentation.staticscan.StaticBaselineSender
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.FrameworkMarkerRegistry
import dev.otherlode.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Flushes the registries to an [Exporter] on a fixed interval. Each instance waits a random offset
 * within the interval before its first flush.
 */
class ExportScheduler(
    private val config: AgentConfig,
    // Stamped on every delta batch and manifest; the static baseline must get the same value.
    private val resource: ResourceAttributes,
    private val registry: ProbeRegistry,
    private val endpointRegistry: EndpointRegistry,
    exporter: Exporter,
    private val random: Random = Random.Default,
    private val maxDeltasPerBatch: Int = DEFAULT_MAX_DELTAS_PER_BATCH,
    private val maxManifestEntriesPerChunk: Int = DEFAULT_MAX_MANIFEST_ENTRIES_PER_CHUNK,
    private val branchDropCounts: BranchDropCounts = BranchDropCounts(),
    private val unreadShapeCounts: UnreadShapeCounts = UnreadShapeCounts(),
    private val callbackAnnotations: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations(config.callbackAnnotations),
    private val loadedClassSweep: LoadedClassSweep? = null,
    private val dependencyRegistry: DependencyRegistry = DependencyRegistry(),
    private val externalClassRegistry: ExternalClassRegistry = ExternalClassRegistry(),
    private val frameworkMarkers: FrameworkMarkerRegistry = FrameworkMarkerRegistry(),
    private val staticBaselineSender: StaticBaselineSender? = null,
    private val classListingSender: ClassListingSender? = null,
    private val dependencyListing: DependencyListingRun = DependencyListingRun {},
    private val startupCleanup: () -> Unit = {},
    private val releaseQuietCaches: () -> Unit = {},
    private val verifierWatch: VerifierRejectionWatch = VerifierRejectionWatch.NOT_WATCHED,
    // Once it returns true it must keep returning true.
    private val classListingBlind: () -> Boolean = { false },
    private val beforeManifest: (graceMillis: Long, finalFlush: Boolean) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val hasStartingPhase: Boolean = false,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val log by agentLogger(ExportScheduler::class.java.name)
    private val exporter: Exporter = ManifestConfirmedExporter(exporter) { registry.manifestConfirmed }

    @Volatile
    private var executor: ScheduledExecutorService? = null
    private val branchDropsLogged = AtomicBoolean(false)
    private val unreadShapesLogged = AtomicBoolean(false)
    private val receivedBytesClassesLogged = AtomicBoolean(false)
    private val callbackAnnotationsChecked = AtomicBoolean(false)

    private val startedAt = clock()
    private val cacheReleaseFailureLogged = AtomicBoolean(false)
    private val beforeManifestFailureLogged = AtomicBoolean(false)
    private val startupCleanupRan = AtomicBoolean(false)

    private val dependenciesListedSent = AtomicBoolean(false)
    private var flushesSinceSweep = 1
    private val sweepLock = Any()
    private val delivery =
        DeliveryState(
            verifierWatch.watched,
            registry::unplacedVerificationFailures,
            classListingBlind,
            monotonicMillis,
            intervalMillis = config.flushInterval.toMillis(),
            starting = hasStartingPhase,
        )

    private val sendPool: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "otherlode-export-send").apply { isDaemon = true }
        }

    private val earlySendPool: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "otherlode-export-early-send").apply { isDaemon = true }
        }

    fun start() {
        val executor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "otherlode-export").apply { isDaemon = true }
            }
        this.executor = executor
        val intervalMillis = config.flushInterval.toMillis()
        val initialDelayMillis = if (intervalMillis > 0) random.nextLong(intervalMillis) else 0L
        executor.scheduleAtFixedRate(::flush, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS)
    }

    /** Stops the schedule and the send pool. [flush] must not be called after this. */
    fun stop() {
        executor?.shutdown()
        sendPool.shutdown()
        earlySendPool.shutdown()
    }

    /**
     * The first flush of a graceful JVM exit: a normal flush, run at once. It stops the scheduled
     * executor first and waits for a flush in flight. It is bounded by [budget] in total: its sends
     * stop being waited for at the deadline, and it starts no hit batch or manifest chunk after that.
     * It sends no static baseline or class listing retry.
     */
    fun flushAtShutdownStart(budget: Duration) {
        val deadlineNanos = System.nanoTime() + budget.toNanos()
        releaseLoggerGate()
        val executor = this.executor
        if (executor != null) {
            executor.shutdown()
            if (!awaitTerminationQuietly(executor, deadlineNanos)) {
                log.log(Level.WARNING, "otherlode: an in-flight flush used up the early shutdown flush's budget; skipping it")
                return
            }
        }
        if (remainingMillis(deadlineNanos) <= 0L) return
        val flushId = delivery.nextFlushId()
        val worker = Thread({ flush(deadlineNanos = deadlineNanos, early = true, flushId = flushId) }, "otherlode-shutdown-early-flush")
        worker.isDaemon = true
        worker.start()
        joinQuietly(worker, deadlineNanos)
    }

    /**
     * Best-effort final flush for a graceful JVM exit, bounded by [budget] in total.
     *
     * It stops the scheduled executor and waits for a flush in flight, then runs the final flush.
     * The budget covers both, and the final flush is skipped when the wait uses it all. An early
     * flush from [flushAtShutdownStart] that is still running its last send does not hold the final
     * flush back. An interrupt of the calling thread does not end any wait.
     */
    fun flushOnShutdown(budget: Duration) {
        val deadlineNanos = System.nanoTime() + budget.toNanos()
        releaseLoggerGate()
        val executor = this.executor
        if (executor != null) {
            executor.shutdown()
            if (!awaitTerminationQuietly(executor, deadlineNanos)) {
                log.log(Level.WARNING, "otherlode: an in-flight flush used up the shutdown budget; skipping the final flush")
                return
            }
        }
        val remaining = remainingMillis(deadlineNanos)
        if (remaining <= 0L) {
            log.log(Level.WARNING, "otherlode: no shutdown budget left for the final flush; skipping it")
            return
        }
        val flushId = delivery.nextFlushId()
        val worker =
            Thread({ flush(final = true, deadlineNanos = deadlineNanos, flushId = flushId) }, "otherlode-shutdown-flush").apply {
                isDaemon =
                    true
            }
        worker.start()
        joinQuietly(worker, deadlineNanos)
        sendPool.shutdown()
        earlySendPool.shutdown()
    }

    private fun awaitTerminationQuietly(
        executor: ScheduledExecutorService,
        deadlineNanos: Long,
    ): Boolean {
        while (true) {
            try {
                return executor.awaitTermination(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.interrupted()
            }
        }
    }

    private fun joinQuietly(
        thread: Thread,
        deadlineNanos: Long,
    ) {
        while (thread.isAlive) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining <= 0L) return
            try {
                thread.join(remaining)
            } catch (_: InterruptedException) {
                Thread.interrupted()
            }
        }
    }

    private fun awaitSend(
        send: Future<Boolean>,
        final: Boolean,
        deadlineNanos: Long,
        onTimeout: () -> Unit,
    ): Boolean {
        if (deadlineNanos == 0L) return send.get()
        val reserve = if (final) CLOSING_RESERVE_MILLIS else 0L
        return try {
            send.get(maxOf(0L, remainingMillis(deadlineNanos) - reserve), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            onTimeout()
            val message =
                if (final) {
                    "otherlode: a send of the shutdown flush ran out of time; closing with the run marked pending"
                } else {
                    "otherlode: a send of the early shutdown flush ran out of time"
                }
            log.log(Level.WARNING, message)
            false
        }
    }

    private fun releaseLoggerGate() {
        if (BootstrapHolder.isInstalled()) OtherlodeLoggers.openGate()
    }

    private fun remainingMillis(deadlineNanos: Long): Long = maxOf(0L, (deadlineNanos - System.nanoTime()) / 1_000_000)

    /**
     * One flush attempt. It sends the manifest entries not yet sent and the delta since the last
     * acknowledged baseline, on two threads at once. The delta batch is sent even when empty. A failed
     * send leaves its state where it is, and a later flush retries it. This never throws.
     *
     * [final] is true only for the flush [flushOnShutdown] runs. It ends with one empty delta batch
     * carrying `finalFlush`, after every other send.
     *
     * [early] marks the flush [flushAtShutdownStart] runs. A nonzero [deadlineNanos], a
     * `System.nanoTime` value, bounds how long a flush waits for each send. A send it stops waiting
     * for is marked pending. An early flush starts no hit batch or manifest chunk after the deadline.
     *
     * [flushId] numbers the flush when the caller drew it. A flush clears a pending state only when
     * no later flush marked it, and a mark is ignored when a later flush has cleared it.
     */
    fun flush(
        final: Boolean = false,
        deadlineNanos: Long = 0L,
        early: Boolean = false,
        flushId: Long = 0L,
    ) {
        val flushStart = clock()
        try {
            if (!final && !early && startupCleanupRan.compareAndSet(false, true)) startupCleanup()
            dependencyListing.runOnce()
            maybeLogBranchDrops()
            maybeLogUnreadShapes()
            maybeLogReceivedBytesClasses()
            maybeLogUnseenCallbackAnnotations(final, flushStart)
            synchronized(sweepLock) { maybeSweep(final) }
            drainVerifierRejections()
            val inScope = registry.inScopeClassCount()
            val unconfirmed = (registry.unconfirmedClassCount() + endpointRegistry.unconfirmedClassCount()).toLong()
            when {
                final -> delivery.endStartingAtExit(inScope, unconfirmed)
                early -> delivery.observeShutdownStart(inScope)
                else -> delivery.observeFlushStart(inScope, unconfirmed)
            }
            if (early && remainingMillis(deadlineNanos) <= 0L) return
            val generation = dependencyRegistry.countGeneration
            val deliveredBefore = dependencyRegistry.deliveredGeneration
            val sends =
                FlushSends(
                    pendingAtStart = delivery.isPending(),
                    hitsWerePending = delivery.hitsPending(),
                    flushId = if (flushId != 0L) flushId else delivery.nextFlushId(),
                    stopsAtNanos = if (early) deadlineNanos else 0L,
                )
            val pool = if (early) earlySendPool else sendPool
            val manifestSend = pool.submit<Boolean> { sendManifestDelta(final, flushStart, sends) }
            val deltaSend = pool.submit<Boolean> { sendDeltaBatch(final, generation, flushStart, sends) }
            var manifestTimedOut = false
            val manifestConfirmed =
                awaitSend(manifestSend, final, deadlineNanos) {
                    delivery.markManifestPending(flushStart, sends.flushId)
                    manifestTimedOut = true
                }
            val deltaConfirmed =
                awaitSend(deltaSend, final, deadlineNanos) {
                    delivery.markPending(flushStart, sends.flushId)
                    sends.unconfirmed()
                }
            var allConfirmed = manifestConfirmed && deltaConfirmed
            val released =
                dependencyRegistry.deliveredGeneration > deliveredBefore && dependencyRegistry.hasSendableUndelivered()

            fun roomForMore(): Boolean =
                when {
                    final -> remainingMillis(deadlineNanos) > 2 * CLOSING_RESERVE_MILLIS
                    deadlineNanos != 0L -> remainingMillis(deadlineNanos) > 0L
                    else -> true
                }
            val room = roomForMore()
            if (released && !manifestTimedOut && room) {
                val releasedSend = pool.submit<Boolean> { sendManifestDelta(final, flushStart, sends) }
                allConfirmed =
                    awaitSend(releasedSend, final, deadlineNanos) { delivery.markManifestPending(flushStart, sends.flushId) } &&
                    allConfirmed
            }
            if (allConfirmed && !room) {
                if (delivery.stampedStateAheadOfDelivered()) delivery.markManifestPending(flushStart, sends.flushId)
            } else if (allConfirmed) {
                val lateManifests =
                    pool.submit<Boolean> {
                        sendUnplacedIfDue(flushStart, sends)
                        sendDependenciesListedIfDue(sends)
                        true
                    }
                awaitSend(lateManifests, final, deadlineNanos) {
                    if (delivery.stampedStateAheadOfDelivered()) delivery.markManifestPending(flushStart, sends.flushId)
                }
            }
            if (final) {
                if (!sendClosingHeartbeat(sends)) allConfirmed = false
            } else {
                if (roomForMore() && !sends.outage && delivery.stateNotYetSaid() && !sendCatchUpHeartbeat(sends)) allConfirmed = false
                if (allConfirmed && !early) {
                    staticBaselineSender?.retryPending()
                    classListingSender?.retryPending()
                }
            }
            if (!final && !early) releaseCaches()
        } catch (t: Throwable) {
            log.log(Level.ERROR, "otherlode: flush failed outside its own send guards, will retry next flush", t)
        }
    }

    private fun drainVerifierRejections() {
        try {
            verifierWatch.drainInto(registry, clock())
        } catch (t: Throwable) {
            registry.recordUnplacedVerificationFailures(1)
            log.log(
                Level.WARNING,
                "otherlode: reading the verifier's rejections failed, so this run's zero counts are no longer evidence",
                t,
            )
        }
    }

    private fun runBeforeManifest(final: Boolean) {
        try {
            beforeManifest(config.flushInterval.toMillis(), final)
        } catch (t: Throwable) {
            if (beforeManifestFailureLogged.compareAndSet(false, true)) {
                log.log(Level.WARNING, "otherlode: the hook that runs before a manifest is built failed", t)
            }
        }
    }

    private fun releaseCaches() {
        try {
            releaseQuietCaches()
        } catch (t: Throwable) {
            if (cacheReleaseFailureLogged.compareAndSet(false, true)) {
                log.log(Level.WARNING, "otherlode: releasing transform-time caches failed; the caches stay as they are", t)
            }
        }
    }

    private fun maybeSweep(final: Boolean) {
        val sweep = loadedClassSweep ?: return
        val countsDependencies = dependencyRegistry.isListingComplete
        val onCadence = --flushesSinceSweep <= 0
        val forwardPassDue = final || onCadence || countsDependencies
        if (!forwardPassDue &&
            registry.unconfirmedClassCount() == 0 &&
            endpointRegistry.unconfirmedClassCount() == 0 &&
            !frameworkMarkers.hasUnseen()
        ) {
            return
        }
        try {
            sweep.run(runForwardPass = forwardPassDue, final = final)
            if (forwardPassDue) flushesSinceSweep = SWEEP_EVERY_N_FLUSHES
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: the loaded-class sweep failed, will retry on a later flush", t)
        }
    }

    private fun maybeLogBranchDrops() {
        if (branchDropsLogged.get()) return
        val total = branchDropCounts.total()
        if (total <= 0) return
        if (branchDropsLogged.compareAndSet(false, true)) {
            val inlinedOutOfScope = branchDropCounts.countOf(BranchDropReason.INLINED_OUT_OF_SCOPE)
            val coroutineMachinery = branchDropCounts.countOf(BranchDropReason.COROUTINE_MACHINERY)
            val switchLowering = branchDropCounts.countOf(BranchDropReason.SWITCH_LOWERING)
            val sizeGuard = branchDropCounts.countOf(BranchDropReason.SIZE_GUARD)
            val jpaWeaving = branchDropCounts.countOf(BranchDropReason.JPA_WEAVING)
            log.log(
                Level.INFO,
                "otherlode: left $total branch sites in ${branchDropCounts.classesWithDrops()} classes without a probe: " +
                    "$inlinedOutOfScope inlined from out-of-scope code, $coroutineMachinery coroutine machinery, " +
                    "$switchLowering switch lowering, $sizeGuard in methods whose branch probes would cross a code-size limit, " +
                    "$jpaWeaving added by a JPA enhancer",
            )
        }
    }

    private fun maybeLogUnreadShapes() {
        if (unreadShapesLogged.get()) return
        val total = unreadShapeCounts.total()
        val outcomes = unreadShapeCounts.outcomeTotal()
        if (total <= 0 && outcomes <= 0) return
        if (!unreadShapesLogged.compareAndSet(false, true)) return
        val parts = mutableListOf<String>()
        if (total > 0) parts += methodsSummary(total)
        if (outcomes > 0) parts += outcomesSummary(outcomes)
        val onlyPluginFilled = outcomes <= 0 && total == unreadShapeCounts.countOf(UnreadCause.PLUGIN_FILLED)
        val newerAgent = if (onlyPluginFilled) "" else " A newer agent may read them."
        log.log(Level.WARNING, "otherlode: ${parts.joinToString(" ")}$newerAgent")
    }

    private fun methodsSummary(total: Long): String {
        val families =
            UnreadShape.entries
                .filter { it != UnreadShape.NONE && unreadShapeCounts.countOf(it) > 0 }
                .joinToString(", ") { "${unreadShapeCounts.countOf(it)} ${familyLabel(it)}" }
        val releases = unreadShapeCounts.unreadReleases()
        val releaseNote =
            if (releases.isEmpty()) "" else " Scala 3 releases this agent has not read: ${releases.joinToString(", ")}."
        val versionBlind = unreadShapeCounts.countOf(UnreadCause.VERSION_BLIND)
        val versionBlindNote =
            if (versionBlind == 0L) {
                ""
            } else {
                " ${count(versionBlind, "of them is", "of them are")} in classes whose compiler the agent cannot tell (Scala 2, " +
                    "or Scala 3 without its .tasty files), where a hand-written method in the shape of compiler plumbing, such as " +
                    "an override of case-class plumbing, is counted too."
            }
        val structure = unreadShapeCounts.countOf(UnreadCause.UNREAD_STRUCTURE)
        val structureNote =
            if (structure == 0L) {
                ""
            } else {
                " ${count(structure, "of them is", "of them are")} compiler output in a shape no hand-written code takes and this " +
                    "agent does not read, such as the plumbing of a Scala enum declared inside a class or a method of a Kotlin " +
                    "multi-file facade that is not a forwarder."
            }
        val pluginFilled = unreadShapeCounts.countOf(UnreadCause.PLUGIN_FILLED)
        val pluginFilledNote =
            if (pluginFilled == 0L) {
                ""
            } else {
                " ${count(pluginFilled, "of them is", "of them are")} in a class where a compiler plugin fills in the members the " +
                    "adopter did not write and the bytecode does not tell them apart, such as a kotlinx.serialization " +
                    "@Serializer(forClass) object."
            }
        val classes = unreadShapeCounts.classes()
        return "${count(total, "method", "methods")} in ${count(classes.toLong(), "class", "classes")} " +
            "${if (total == 1L) "looks" else "look"} like compiler output this agent has not read, and " +
            "${if (total == 1L) "is reported as an unread shape" else "are reported as unread shapes"} rather than dead code " +
            "($families).$releaseNote$versionBlindNote$structureNote$pluginFilledNote"
    }

    private fun outcomesSummary(outcomes: Long): String {
        val families =
            UnreadShape.entries
                .filter { it != UnreadShape.NONE && unreadShapeCounts.outcomeCountOf(it) > 0 }
                .joinToString(", ") { "${unreadShapeCounts.outcomeCountOf(it)} ${familyLabel(it)}" }
        val classes = unreadShapeCounts.outcomeClasses()
        return "${count(outcomes, "branch outcome", "branch outcomes")} in ${count(classes.toLong(), "class", "classes")} " +
            "${if (outcomes == 1L) "sits" else "sit"} in compiler output in a shape this agent does not read, and " +
            "${if (outcomes == 1L) "is reported as an unread shape" else "are reported as unread shapes"} rather than dead code " +
            "($families)."
    }

    private fun maybeLogReceivedBytesClasses() {
        if (receivedBytesClassesLogged.get()) return
        val classes = unreadShapeCounts.receivedBytesClasses()
        if (classes <= 0) return
        if (!receivedBytesClassesLogged.compareAndSet(false, true)) return
        log.log(
            Level.INFO,
            "otherlode: ${count(classes.toLong(), "class", "classes")} had no class file, so " +
                "${if (classes == 1) "its shape was" else "their shapes were"} read from the bytes " +
                "${if (classes == 1) "it" else "they"} arrived as, which another agent may have changed.",
        )
    }

    private fun maybeLogUnseenCallbackAnnotations(
        final: Boolean,
        now: Long,
    ) {
        if (callbackAnnotationsChecked.get()) return
        if (!final && now - startedAt < UNSEEN_CALLBACK_ANNOTATIONS_AFTER.toMillis()) return
        if (!callbackAnnotationsChecked.compareAndSet(false, true)) return
        val unseen = callbackAnnotations.unseen()
        if (unseen.isEmpty()) return
        log.log(
            Level.INFO,
            "otherlode: callbackAnnotations names ${count(unseen.size.toLong(), "annotation", "annotations")} not yet seen on any " +
                "method: ${unseen.joinToString(", ") { it.written }}. A name is matched exactly; check the spelling if its " +
                "classes have loaded.",
        )
    }

    private fun count(
        n: Long,
        one: String,
        many: String,
    ): String = "$n ${if (n == 1L) one else many}"

    private fun familyLabel(family: UnreadShape): String =
        when (family) {
            UnreadShape.NONE -> "none"
            UnreadShape.CASE_CLASS -> "Scala case-class plumbing"
            UnreadShape.STATIC_FORWARDER -> "Scala static forwarders"
            UnreadShape.SCALA_OBJECT -> "Scala object serialization"
            UnreadShape.SCALA_ENUM -> "Scala enum plumbing"
            UnreadShape.MULTIFILE_FACADE -> "Kotlin multi-file facade methods"
            UnreadShape.COROUTINE_MACHINERY -> "coroutine machinery"
            UnreadShape.STRING_SWITCH -> "string switch lowering"
            UnreadShape.KOTLINX_SERIALIZER_OBJECT -> "$KOTLINX_PACKAGE.serialization serializer objects"
            UnreadShape.VALUE_CLASS -> "Scala value class forwarders"
            UnreadShape.TRAIT_FORWARDER -> "Scala trait forwarders"
        }

    private class DeliveryState(
        private val verifierRejectionsWatched: Boolean,
        private val unplacedVerificationFailures: () -> Long,
        private val classListingBlind: () -> Boolean,
        private val monotonic: () -> Long,
        private val intervalMillis: Long,
        starting: Boolean,
    ) {
        private val lock = Any()
        private var startingEnded = !starting
        private val startedMonotonic = monotonic()
        private var quietCount = 0L
        private var quietSince = startedMonotonic
        private var hitsPendingSince = 0L
        private var manifestPendingSince = 0L
        private var hitsMarkedBy = 0L
        private var manifestMarkedBy = 0L
        private var hitsClearedBy = 0L
        private var manifestClearedBy = 0L
        private var lastFlushId = 0L
        private var acceptedSequence = 0L
        private var acceptedPendingSince = 0L
        private var acceptedStarting = false

        fun accepted(
            sequence: Long,
            pendingSince: Long,
            starting: Boolean,
        ) {
            synchronized(lock) {
                if (sequence > acceptedSequence) {
                    acceptedSequence = sequence
                    acceptedPendingSince = pendingSince
                    acceptedStarting = starting
                }
            }
        }

        private var nextSequence = 1L
        private var unplacedDelivered = 0L
        private var blindDelivered = false

        fun nextFlushId(): Long = synchronized(lock) { ++lastFlushId }

        private fun pendingSince(): Long =
            when {
                hitsPendingSince == 0L -> manifestPendingSince
                manifestPendingSince == 0L -> hitsPendingSince
                else -> minOf(hitsPendingSince, manifestPendingSince)
            }

        private fun startingActive(): Boolean {
            if (!startingEnded && monotonic() - startedMonotonic >= STARTING_CAP_INTERVALS * intervalMillis) startingEnded = true
            return !startingEnded
        }

        fun observeFlushStart(
            inScopeCount: Long,
            unconfirmed: Long,
        ) {
            synchronized(lock) {
                if (startingEnded) return
                val now = monotonic()
                if (inScopeCount != quietCount) {
                    quietCount = inScopeCount
                    quietSince = now
                }
                val age = now - startedMonotonic
                val quietFor = now - quietSince
                if (age >= STARTING_CAP_INTERVALS * intervalMillis ||
                    (inScopeCount > 0 && unconfirmed == 0L && age >= intervalMillis && quietFor >= intervalMillis)
                ) {
                    startingEnded = true
                }
            }
        }

        private var shutdownCount = -1L

        fun observeShutdownStart(inScopeCount: Long) {
            synchronized(lock) { shutdownCount = inScopeCount }
        }

        fun endStartingAtExit(
            inScopeCount: Long,
            unconfirmed: Long,
        ) {
            synchronized(lock) {
                if (shutdownCount >= 0L && inScopeCount > 0L && inScopeCount == shutdownCount && unconfirmed == 0L) {
                    startingEnded = true
                }
            }
        }

        fun isPending(): Boolean = synchronized(lock) { pendingSince() != 0L }

        fun hitsPending(): Boolean = synchronized(lock) { hitsPendingSince != 0L }

        fun markPending(
            flushStart: Long,
            flushId: Long,
        ) {
            synchronized(lock) {
                if (flushId < hitsClearedBy) return
                if (hitsPendingSince == 0L) hitsPendingSince = flushStart
                if (flushId > hitsMarkedBy) hitsMarkedBy = flushId
            }
        }

        fun markManifestPending(
            flushStart: Long,
            flushId: Long,
        ) {
            synchronized(lock) {
                if (flushId < manifestClearedBy) return
                if (manifestPendingSince == 0L) manifestPendingSince = flushStart
                if (flushId > manifestMarkedBy) manifestMarkedBy = flushId
            }
        }

        fun clearHits(flushId: Long) {
            synchronized(lock) {
                if (flushId < hitsMarkedBy) return
                hitsPendingSince = 0L
                if (flushId > hitsClearedBy) hitsClearedBy = flushId
            }
        }

        fun clearManifest(flushId: Long) {
            synchronized(lock) {
                if (flushId < manifestMarkedBy) return
                manifestPendingSince = 0L
                if (flushId > manifestClearedBy) manifestClearedBy = flushId
            }
        }

        fun stateNotYetSaid(): Boolean =
            synchronized(lock) {
                acceptedSequence != 0L &&
                    ((pendingSince() == 0L) != (acceptedPendingSince == 0L) || (acceptedStarting && !startingActive()))
            }

        fun stamp(batch: DeltaBatch): DeltaBatch =
            synchronized(lock) {
                batch.copy(
                    payloadSequence = nextSequence++,
                    countsPendingSince = pendingSince(),
                    starting = startingActive(),
                )
            }

        fun stamp(manifest: ProbeManifest): ProbeManifest =
            synchronized(lock) {
                manifest.copy(
                    payloadSequence = nextSequence++,
                    countsPendingSince = pendingSince(),
                    starting = startingActive(),
                    unplacedVerificationFailures = unplacedVerificationFailures(),
                    verifierRejectionsWatched = verifierRejectionsWatched,
                    classListingBlind = classListingBlind(),
                )
            }

        fun confirmStamped(
            total: Long,
            blind: Boolean,
        ) {
            synchronized(lock) {
                if (total > unplacedDelivered) unplacedDelivered = total
                if (blind) blindDelivered = true
            }
        }

        fun stampedStateAheadOfDelivered(): Boolean =
            synchronized(lock) { unplacedVerificationFailures() > unplacedDelivered || (classListingBlind() && !blindDelivered) }
    }

    private fun sendStamped(batch: DeltaBatch) {
        val stamped = delivery.stamp(batch)
        exporter.exportDeltaBatch(stamped)
        delivery.accepted(stamped.payloadSequence, stamped.countsPendingSince, stamped.starting)
    }

    private fun sendStamped(manifest: ProbeManifest) {
        val stamped = delivery.stamp(manifest)
        exporter.exportManifest(stamped)
        delivery.accepted(stamped.payloadSequence, stamped.countsPendingSince, stamped.starting)
        delivery.confirmStamped(stamped.unplacedVerificationFailures, stamped.classListingBlind)
    }

    private fun hasHits(batch: DeltaBatch): Boolean =
        batch.deltas.isNotEmpty() || batch.endpointDeltas.isNotEmpty() || batch.dependencyDeltas.isNotEmpty()

    private class DeltaSend(
        val batch: DeltaBatch,
        val probeSnapshot: ProbeRegistry.DeltaSnapshot?,
        val riders: List<Rider<DeltaBatch>>,
    )

    private class Rider<T>(
        val size: Int,
        val weight: PayloadWeight,
        val attach: (T) -> T,
        val advance: () -> Unit,
    )

    private fun sendDeltaBatch(
        final: Boolean,
        generation: Long,
        flushStart: Long,
        sends: FlushSends,
    ): Boolean {
        var confirmed = false
        try {
            confirmed = sendHitBatches(final, generation, flushStart, sends)
        } catch (t: Throwable) {
            sends.unconfirmed()
            log.log(Level.WARNING, "otherlode: delta export failed, will retry next flush", t)
            if (final) delivery.markPending(flushStart, sends.flushId)
        }
        return confirmed
    }

    private fun sendClosingHeartbeat(sends: FlushSends): Boolean =
        try {
            sendHeartbeat(sends, finalFlush = true, flushComplete = sends.allConfirmed)
            true
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: closing export failed, the run will read as one that went silent", t)
            false
        }

    private fun sendCatchUpHeartbeat(sends: FlushSends): Boolean =
        try {
            sendHeartbeat(sends, finalFlush = false, flushComplete = false)
            true
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: heartbeat export failed, will retry next flush", t)
            false
        }

    private fun sendHitBatches(
        final: Boolean,
        generation: Long,
        flushStart: Long,
        sends: FlushSends,
    ): Boolean {
        val probeBatches = registry.computeDeltaBatches(resource, maxDeltasPerBatch)
        val riders =
            endpointRegistry.computeDeltas(maxDeltasPerBatch).map(::endpointDeltaRider) +
                dependencyRegistry.computeDeltas(maxDeltasPerBatch).map(::dependencyDeltaRider)
        val hitsWerePending = sends.hitsWerePending
        if (sends.pendingAtStart) sendHeartbeat(sends, finalFlush = false, flushComplete = false)
        var allConfirmed = true
        val deltaSends = composeDeltaSends(probeBatches, riders)
        for (send in deltaSends) {
            val hits = hasHits(send.batch)
            if (sends.pastStop()) {
                sends.unconfirmed()
                if (hits) {
                    delivery.markPending(flushStart, sends.flushId)
                    allConfirmed = false
                }
                continue
            }
            if (final && !hits) {
                send.probeSnapshot?.let(registry::advanceBaseline)
                continue
            }
            val completesFlush = !final && !hitsWerePending && send === deltaSends.last() && sends.allConfirmed
            try {
                sendStamped(send.batch.copy(flushComplete = completesFlush))
            } catch (e: ExportFailedException) {
                sends.unconfirmed()
                if (hits) {
                    delivery.markPending(flushStart, sends.flushId)
                    allConfirmed = false
                }
                if (!e.refused) {
                    sends.outage()
                    throw e
                }
                log.log(Level.WARNING, "otherlode: delta export failed, will retry next flush", e)
                continue
            } catch (t: Throwable) {
                sends.unconfirmed()
                sends.outage()
                if (hits) delivery.markPending(flushStart, sends.flushId)
                throw t
            }
            send.probeSnapshot?.let(registry::advanceBaseline)
            send.riders.forEach { it.advance() }
        }
        if (!allConfirmed) return false
        dependencyRegistry.markCountsDelivered(generation)
        delivery.clearHits(sends.flushId)
        if (hitsWerePending && !final) sendHeartbeat(sends, finalFlush = false, flushComplete = sends.allConfirmed)
        return true
    }

    private class FlushSends(
        val pendingAtStart: Boolean,
        val hitsWerePending: Boolean,
        val flushId: Long,
        private val stopsAtNanos: Long,
    ) {
        fun pastStop(): Boolean = stopsAtNanos != 0L && System.nanoTime() - stopsAtNanos >= 0L

        @Volatile
        var allConfirmed = true
            private set

        @Volatile
        var outage = false
            private set

        fun unconfirmed() {
            allConfirmed = false
        }

        fun outage() {
            outage = true
        }
    }

    private fun sendHeartbeat(
        sends: FlushSends,
        finalFlush: Boolean,
        flushComplete: Boolean,
    ) {
        try {
            sendStamped(DeltaBatch(resource, emptyList(), finalFlush = finalFlush, flushComplete = flushComplete))
        } catch (e: ExportFailedException) {
            sends.unconfirmed()
            if (!e.refused) {
                sends.outage()
                throw e
            }
            log.log(Level.WARNING, "otherlode: heartbeat export failed, will retry next flush", e)
        } catch (t: Throwable) {
            sends.unconfirmed()
            sends.outage()
            throw t
        }
    }

    private fun endpointDeltaRider(snapshot: EndpointRegistry.DeltaSnapshot): Rider<DeltaBatch> =
        Rider(
            size = snapshot.deltas.size,
            weight = snapshot.deltas.fold(PayloadWeight.ZERO) { sum, delta -> sum + ProtoPayloadCodec.entryWeight(delta) },
            attach = { it.copy(endpointDeltas = it.endpointDeltas + snapshot.deltas) },
            advance = { endpointRegistry.advanceDeltas(snapshot) },
        )

    private fun dependencyDeltaRider(snapshot: DependencyRegistry.DeltaSnapshot): Rider<DeltaBatch> =
        Rider(
            size = snapshot.deltas.size,
            weight = snapshot.deltas.fold(PayloadWeight.ZERO) { sum, delta -> sum + ProtoPayloadCodec.entryWeight(delta) },
            attach = { it.copy(dependencyDeltas = it.dependencyDeltas + snapshot.deltas) },
            advance = { dependencyRegistry.advanceDeltas(snapshot) },
        )

    private fun composeDeltaSends(
        probeBatches: List<ProbeRegistry.DeltaSnapshot>,
        riders: List<Rider<DeltaBatch>>,
    ): List<DeltaSend> {
        val builders = probeBatches.map { DeltaSendBuilder(it.batch.copy(finalFlush = false), it, it.weight) }.toMutableList()

        for (rider in riders) {
            val target =
                builders.firstOrNull {
                    it.size + rider.size <= maxDeltasPerBatch && PayloadKind.DELTA_BATCH.admits(it.weight + rider.weight)
                } ?: DeltaSendBuilder(DeltaBatch(resource, emptyList()), null).also(builders::add)
            target.batch = rider.attach(target.batch)
            target.size += rider.size
            target.weight += rider.weight
            target.riders += rider
        }
        return builders.map { DeltaSend(it.batch, it.probeSnapshot, it.riders) }
    }

    private class DeltaSendBuilder(
        var batch: DeltaBatch,
        val probeSnapshot: ProbeRegistry.DeltaSnapshot?,
        var weight: PayloadWeight = ProtoPayloadCodec.weightOf(batch),
    ) {
        var size = batch.deltas.size
        val riders = mutableListOf<Rider<DeltaBatch>>()
    }

    private class ManifestSend(
        val manifest: ProbeManifest,
        val probeSnapshot: ProbeRegistry.ManifestSnapshot?,
        val riders: List<Rider<ProbeManifest>>,
    )

    private fun sendManifestDelta(
        final: Boolean,
        flushStart: Long,
        sends: FlushSends,
    ): Boolean {
        runBeforeManifest(final)
        try {
            endpointRegistry.computeDisabledModules()?.let { snapshot ->
                val listed = dependenciesListed
                val rider = disabledModulesRider(snapshot)
                for (send in manifestSends(emptySequence(), listOf(rider), listed)) {
                    sendStamped(send.manifest)
                    send.riders.forEach { it.advance() }
                    if (listed) dependenciesListedSent.set(true)
                }
            }
            val classChunks =
                registry.computeManifestDeltas(resource, maxManifestEntriesPerChunk)
            val riders =
                endpointRegistry.computeManifestEntries(maxManifestEntriesPerChunk).map(::endpointManifestRider) +
                    dependencyRegistry.computeManifestEntries(maxManifestEntriesPerChunk).map(::dependencyManifestRider) +
                    externalClassRegistry.computeManifestEntries(maxManifestEntriesPerChunk).map(::externalClassManifestRider) +
                    listOfNotNull(frameworkMarkers.computeManifestEntries()).map(::frameworkMarkerRider)
            val listed = dependenciesListed
            for (send in manifestSends(classChunks, riders, listed)) {
                if (sends.pastStop()) {
                    delivery.markManifestPending(flushStart, sends.flushId)
                    return false
                }
                sendStamped(send.manifest)
                send.probeSnapshot?.let(registry::advanceManifestBaseline)
                send.riders.forEach { it.advance() }
                if (listed) dependenciesListedSent.set(true)
            }
            if (!delivery.stampedStateAheadOfDelivered()) delivery.clearManifest(sends.flushId)
            return true
        } catch (t: Throwable) {
            delivery.markManifestPending(flushStart, sends.flushId)
            log.log(Level.WARNING, "otherlode: manifest export failed, will retry next flush", t)
            return false
        }
    }

    private fun endpointManifestRider(chunk: EndpointRegistry.ManifestSnapshot): Rider<ProbeManifest> =
        Rider(
            size = chunk.endpoints.size,
            weight = chunk.endpoints.fold(PayloadWeight.ZERO) { sum, entry -> sum + ProtoPayloadCodec.entryWeight(entry) },
            attach = { it.copy(endpoints = it.endpoints + chunk.endpoints) },
            advance = { endpointRegistry.advanceManifest(chunk) },
        )

    private fun frameworkMarkerRider(snapshot: FrameworkMarkerRegistry.ManifestSnapshot): Rider<ProbeManifest> =
        Rider(
            size = snapshot.markers.size,
            weight = snapshot.markers.fold(PayloadWeight.ZERO) { sum, entry -> sum + ProtoPayloadCodec.entryWeight(entry) },
            attach = { it.copy(frameworkMarkers = it.frameworkMarkers + snapshot.markers) },
            advance = { frameworkMarkers.advanceManifest(snapshot) },
        )

    private fun disabledModulesRider(snapshot: EndpointRegistry.DisabledModulesSnapshot): Rider<ProbeManifest> =
        Rider(
            size = snapshot.modules.size,
            weight = snapshot.modules.fold(PayloadWeight.ZERO) { sum, entry -> sum + ProtoPayloadCodec.entryWeight(entry) },
            attach = { it.copy(disabledEndpointModules = it.disabledEndpointModules + snapshot.modules) },
            advance = { endpointRegistry.advanceDisabledModules(snapshot) },
        )

    private fun dependencyManifestRider(chunk: DependencyRegistry.ManifestSnapshot): Rider<ProbeManifest> =
        Rider(
            size = chunk.dependencies.size,
            weight = chunk.dependencies.fold(PayloadWeight.ZERO) { sum, entry -> sum + ProtoPayloadCodec.entryWeight(entry) },
            attach = { it.copy(dependencies = it.dependencies + chunk.dependencies) },
            advance = { dependencyRegistry.advanceManifest(chunk) },
        )

    private fun externalClassManifestRider(chunk: ExternalClassRegistry.ManifestSnapshot): Rider<ProbeManifest> =
        Rider(
            size = chunk.externalClasses.size,
            weight = chunk.externalClasses.fold(PayloadWeight.ZERO) { sum, entry -> sum + ProtoPayloadCodec.entryWeight(entry) },
            attach = { it.copy(externalClasses = it.externalClasses + chunk.externalClasses) },
            advance = { externalClassRegistry.advanceManifest(chunk) },
        )

    private fun manifestSends(
        classChunks: Sequence<ProbeRegistry.ManifestSnapshot>,
        riders: List<Rider<ProbeManifest>>,
        dependenciesListed: Boolean,
    ): Sequence<ManifestSend> =
        sequence {
            val waiting = riders.toMutableList()

            fun finish(builder: ManifestSendBuilder) =
                ManifestSend(
                    builder.manifest.copy(referencesRecorded = referencesRecorded, dependenciesListed = dependenciesListed),
                    builder.probeSnapshot,
                    builder.riders,
                )
            for (chunk in classChunks) {
                val builder = ManifestSendBuilder(chunk.manifest, chunk, chunk.weight)
                val iterator = waiting.iterator()
                while (iterator.hasNext()) {
                    val rider = iterator.next()
                    if (!builder.admits(rider)) continue
                    builder.manifest = rider.attach(builder.manifest)
                    builder.size += rider.size
                    builder.weight += rider.weight
                    builder.riders += rider
                    iterator.remove()
                }
                yield(finish(builder))
            }
            val own = mutableListOf<ManifestSendBuilder>()
            for (rider in waiting) {
                val target =
                    own.firstOrNull { it.admits(rider) }
                        ?: emptyManifest(
                            dependenciesListed,
                        ).let { ManifestSendBuilder(it, null, ProtoPayloadCodec.weightOf(it)) }.also(own::add)
                target.manifest = rider.attach(target.manifest)
                target.size += rider.size
                target.weight += rider.weight
                target.riders += rider
            }
            for (builder in own) yield(finish(builder))
        }

    private val referencesRecorded: Boolean
        get() = config.includePackages.isNotEmpty()

    private val dependenciesListed: Boolean
        get() = dependencyRegistry.isStartupListingDelivered && externalClassRegistry.isBacklogDelivered

    private fun emptyManifest(dependenciesListed: Boolean): ProbeManifest =
        ProbeManifest(
            resource = resource,
            probes = emptyList(),
            referencesRecorded = referencesRecorded,
            dependenciesListed = dependenciesListed,
        )

    private fun sendDependenciesListedIfDue(sends: FlushSends) {
        try {
            if (dependenciesListedSent.get() || !dependenciesListed) return
            sendStamped(emptyManifest(dependenciesListed = true))
            dependenciesListedSent.set(true)
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: sending dependenciesListed failed, will retry next flush", t)
        }
    }

    private fun sendUnplacedIfDue(
        flushStart: Long,
        sends: FlushSends,
    ) {
        try {
            if (!delivery.stampedStateAheadOfDelivered()) return
            val listed = dependenciesListed
            sendStamped(emptyManifest(listed))
            if (listed) dependenciesListedSent.set(true)
            delivery.clearManifest(sends.flushId)
        } catch (t: Throwable) {
            delivery.markManifestPending(flushStart, sends.flushId)
            log.log(Level.WARNING, "otherlode: sending the unplaced verification failure count failed, will retry next flush", t)
        }
    }

    private inner class ManifestSendBuilder(
        var manifest: ProbeManifest,
        val probeSnapshot: ProbeRegistry.ManifestSnapshot?,
        var weight: PayloadWeight,
    ) {
        fun admits(rider: Rider<ProbeManifest>): Boolean =
            size + rider.size <= maxManifestEntriesPerChunk && PayloadKind.MANIFEST.admits(weight + rider.weight)

        // Sums every list the chunker weights, or a rider packed onto this one can overshoot the cap.
        var size =
            manifest.probes.size +
                manifest.probes.sumOf { probe ->
                    probe.calls.size + probe.referencedClasses.size + probe.branchSites.sumOf { it.chunkWeight }
                } +
                manifest.skippedClasses.size + manifest.endpoints.size + manifest.disabledEndpointModules.size +
                manifest.classLocations.size + manifest.classReferences.sumOf { it.referencedClasses.size } +
                manifest.unreportedClasses.size + manifest.failedClasses.size + manifest.classesFailedToInitialize.size +
                manifest.dependencies.size +
                manifest.externalClasses.size +
                manifest.frameworkMarkers.size
        val riders = mutableListOf<Rider<ProbeManifest>>()
    }

    companion object {
        // Built at run time, since `shadowJar` rewrites a constant that starts with `kotlin`.
        private val KOTLINX_PACKAGE = listOf("kot", "linx").joinToString("")

        /** The most flush intervals after start that a run counts as starting. */
        const val STARTING_CAP_INTERVALS: Long = 10

        /** How long after start the unseen callback annotations are first judged. */
        val UNSEEN_CALLBACK_ANNOTATIONS_AFTER: Duration = Duration.ofMinutes(5)

        /** The default upper bound on changed probes per delta POST. */
        const val DEFAULT_MAX_DELTAS_PER_BATCH: Int = 20_000

        /** The default upper bound on manifest entries per POST. */
        const val DEFAULT_MAX_MANIFEST_ENTRIES_PER_CHUNK: Int = 5_000

        /** How many flushes pass between forward-direction sweeps while the dependency listing is incomplete. */
        const val SWEEP_EVERY_N_FLUSHES: Int = 10

        /** What the shutdown flush keeps of its budget for its closing batch. */
        const val CLOSING_RESERVE_MILLIS: Long = 2_000L
    }
}
