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
 * Flushes on a fixed interval. Each instance picks a random offset within
 * that interval before its first flush. Without this, a fleet of instances
 * started around the same time would all flush on the same wall-clock tick,
 * overwhelming the collector at once.
 *
 * Logs through `java.lang.System.Logger`, not a logging framework. This
 * keeps agent-internal warnings off the target app's classpath.
 */
class ExportScheduler(
    private val config: AgentConfig,
    /**
     * Stamped on every delta batch and manifest this scheduler sends. The agent passes the same
     * value to the static baseline, so all three payloads carry one run id. Required, with no
     * default: a scheduler that made its own would name a different run from the baseline.
     */
    private val resource: ResourceAttributes,
    private val registry: ProbeRegistry,
    /** Endpoint hit and manifest state; see [EndpointRegistry]. */
    private val endpointRegistry: EndpointRegistry,
    exporter: Exporter,
    /** Source of the first-flush jitter. Injectable so a test can pin the initial delay to zero. */
    private val random: Random = Random.Default,
    /** Upper bound on changed probes per delta POST; see [ProbeRegistry.computeDeltaBatches]. */
    private val maxDeltasPerBatch: Int = DEFAULT_MAX_DELTAS_PER_BATCH,
    /** Upper bound on manifest entries per POST, each weighed as [ProbeRegistry.computeManifestDeltas] weighs it. */
    private val maxManifestEntriesPerChunk: Int = DEFAULT_MAX_MANIFEST_ENTRIES_PER_CHUNK,
    /** Dropped branch site totals; see [maybeLogBranchDrops]. */
    private val branchDropCounts: BranchDropCounts = BranchDropCounts(),
    /** Unread shape totals; see [maybeLogUnreadShapes]. */
    private val unreadShapeCounts: UnreadShapeCounts = UnreadShapeCounts(),
    /** The adopter's named callback annotations and which were seen; see [maybeLogUnseenCallbackAnnotations]. */
    private val callbackAnnotations: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations(config.callbackAnnotations),
    /**
     * Confirms classes the registry withholds and finds classes that loaded but reached no
     * transformer; see [maybeSweep]. Null when nothing supplied one, which is every test that does
     * not exercise the sweep.
     */
    private val loadedClassSweep: LoadedClassSweep? = null,
    /** Dependencies found on the startup classpath, delivered on the manifest. */
    private val dependencyRegistry: DependencyRegistry = DependencyRegistry(),
    /** Referenced out-of-scope classes, resolved to dependencies and delivered on the manifest. */
    private val externalClassRegistry: ExternalClassRegistry = ExternalClassRegistry(),
    /** Framework markers the sweep saw, delivered on the manifest. */
    private val frameworkMarkers: FrameworkMarkerRegistry = FrameworkMarkerRegistry(),
    /**
     * Static baseline chunks the collector has not confirmed yet, sent again, one per flush, after
     * a flush whose own sends it confirmed. Null when the static baseline is off.
     */
    private val staticBaselineSender: StaticBaselineSender? = null,
    /**
     * Class listing chunks the collector has not confirmed yet, sent again like the static
     * baseline's. Null when the class listing is off.
     */
    private val classListingSender: ClassListingSender? = null,
    /**
     * The startup dependency listing, run at the top of the first flush so that flush's sweep and
     * manifest see every dependency. The default has nothing to run.
     */
    private val dependencyListing: DependencyListingRun = DependencyListingRun {},
    /**
     * Removes leftover files from earlier processes, run at the top of the first scheduled flush
     * so it stays off `premain` and out of the shutdown flushes. The default removes nothing.
     */
    private val startupCleanup: () -> Unit = {},
    /**
     * Releases transform-time caches of loaders that went quiet, called once per flush after its
     * sends, the shutdown flush excepted. The default releases nothing.
     */
    private val releaseQuietCaches: () -> Unit = {},
    /**
     * What the run can see of the verifier's rejections, drained after the sweep on every flush and
     * stamped on every manifest. The default sees none.
     */
    private val verifierWatch: VerifierRejectionWatch = VerifierRejectionWatch.NOT_WATCHED,
    /**
     * Whether the run's class listing missed a root (ADR 0085), stamped on every manifest. Once it
     * returns true it must return true for good. The default is never blind.
     */
    private val classListingBlind: () -> Boolean = { false },
    /**
     * Run before each manifest's disabled endpoint modules and endpoints are read, with the flush
     * interval and whether this is the final flush, so a module that settles what it declares does
     * so before that manifest is built. The default does nothing.
     */
    private val beforeManifest: (graceMillis: Long, finalFlush: Boolean) -> Unit = { _, _ -> },
    /** Milliseconds since the epoch, for the times a flush stamps. Injectable so a test controls it. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whether the run counts as starting from its start until its class loading settles (ADR 0086).
     * The agent sets it. The default is false, which gives a scheduler no starting phase, as for
     * every test that does not exercise it.
     */
    private val hasStartingPhase: Boolean = false,
    /**
     * Milliseconds on a clock that only moves forward, for how long the run has been starting
     * (ADR 0086), so a wall-clock step cannot end it early. Injectable so a test controls it.
     */
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

    /** When this scheduler was built, which is at agent start; see [maybeLogUnseenCallbackAnnotations]. */
    private val startedAt = clock()
    private val cacheReleaseFailureLogged = AtomicBoolean(false)
    private val beforeManifestFailureLogged = AtomicBoolean(false)
    private val startupCleanupRan = AtomicBoolean(false)

    /** Set once a manifest carrying `dependenciesListed = true` was confirmed; see [sendDependenciesListedIfDue]. */
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

    /** Runs the two sends of each flush side by side; see [flush]. Two threads, created once, not two per tick. */
    private val sendPool: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "otherlode-export-send").apply { isDaemon = true }
        }

    /**
     * The early shutdown flush's sends, kept apart from [sendPool] so a send it stopped waiting for
     * never holds a thread the final flush needs. Its threads start only when the early flush runs.
     */
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
     * The first flush of a graceful JVM exit: a normal flush, run at once, so counts the application
     * made so far are on their way while its own shutdown hooks run. The scheduled executor is
     * stopped first and a flush already in flight finishes before this one starts. Bounded by
     * [budget] in total: its sends stop being waited for at the deadline, and it starts no hit
     * batch or manifest chunk after that. It sends no static baseline or class listing retry. A send it
     * stopped waiting for can still be running when [flushOnShutdown] starts. It runs on its own
     * threads, the final flush does not wait for it, and it cannot clear a pending state a later
     * flush set.
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
     * The scheduled executor is stopped first, and any flush already in flight on it is allowed
     * to finish before the final one starts. An early flush from [flushAtShutdownStart] that is
     * still running its last send does not hold the final flush back.
     *
     * [budget] is shared between waiting for the in-flight flush and running the final one. If
     * the in-flight flush is deep in retries against an unreachable collector and uses it all
     * up, the final flush is skipped. That in-flight flush already carried the latest snapshot
     * it could take, so what is lost is bounded by the hits since it started, and shutdown never
     * stretches past what an orchestrator's termination grace period allows.
     *
     * An interrupt of the calling thread does not end any wait here.
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
        // Thread.join(0) waits with no limit, so a budget that ran out while waiting above (or
        // one that was zero to begin with) must skip the final flush rather than start it.
        val remaining = remainingMillis(deadlineNanos)
        if (remaining <= 0L) {
            log.log(Level.WARNING, "otherlode: no shutdown budget left for the final flush; skipping it")
            return
        }
        val flushId = delivery.nextFlushId()
        val worker = Thread({ flush(final = true, deadlineNanos = deadlineNanos, flushId = flushId) }, "otherlode-shutdown-flush").apply { isDaemon = true }
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

    /**
     * Waits for one of a flush's sends. A flush with a deadline waits only until it, and the final
     * flush only until [CLOSING_RESERVE_MILLIS] before it, so its closing batch still has time to go
     * out. A send it stops waiting for runs [onTimeout], which marks what it carried as pending, and
     * counts as unconfirmed.
     */
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
     * One flush attempt. Sends whatever manifest entries haven't gone out
     * yet, and the delta since the last acknowledged baseline.
     *
     * The delta batch is sent even when empty. A collector otherwise has no
     * way to tell an instance that's alive but idle from one that's crashed
     * or lost its network path. An empty batch acts as a liveness heartbeat.
     *
     * Neither send has an explicit retry queue. A failure just leaves the
     * relevant state where it is, so the next tick retries it naturally.
     * [dev.otherlode.export.HttpExporter] still wraps
     * each individual send in its own capped exponential backoff, for a
     * transient failure within one attempt.
     *
     * The two main sends run concurrently, not one after the other. Each can take
     * a while to fail on its own (multiple retries, each with its own
     * timeout) if the collector is unreachable. Run back to back, a single
     * flush's worst case could take roughly twice one flush interval,
     * meaning the liveness heartbeat above would arrive far less often than
     * configured during exactly the outage it exists to report. Running
     * them side by side keeps one flush's worst case close to a single
     * send's worst case instead of the sum of both.
     *
     * Up to two more manifest sends follow on the send pool, once both main sends have ended. A
     * second manifest send runs only when this flush's confirmed delta sends released a dependency
     * that no manifest has carried yet. It carries that dependency and the mappings
     * held on it, so they go out in the same flush as their counts. Then an empty manifest carries
     * `dependenciesListed`, when the flag is due, no confirmed manifest has carried it, and every
     * send of this flush was confirmed. Neither extra send runs during an outage, so a flush's worst
     * case stays that of one send.
     *
     * This method runs under `scheduleAtFixedRate`, which stops calling a
     * task forever the first time it lets a throwable escape, with nothing
     * logged. So `sendManifestDelta` and `sendDeltaBatch` each wrap their
     * own registry call (`compute*`) and exporter call in one catch of
     * `Throwable`, not `Exception`: a `NoClassDefFoundError` from a class
     * first touched on the export path is as fatal to the schedule as any
     * exception, and would otherwise stop every future flush, heartbeat
     * included. The outer catch here covers what the sends cannot, such as
     * a rejected submission after [stop].
     *
     * The run's verifier rejections are drained after the sweep and before the sends, the shutdown
     * flush included. A manifest carrying a higher unplaced total than any confirmed one, or the
     * blind class listing flag when none has, is sent after the main sends when nothing else
     * carried it; see [sendUnplacedIfDue].
     *
     * A flush that starts with pending counts sends an empty delta batch first, on its own, and a
     * flush that clears them ends with another; see [DeliveryState] and [sendDeltaBatch].
     *
     * [final] is true only for the flush [flushOnShutdown] runs. That flush ends with one empty
     * delta batch carrying `finalFlush`, after every hit batch and every manifest send, so a
     * collector can tell an instance that ended cleanly from one that went silent, and the batch
     * carries the pending state those sends left. No other batch carries it.
     *
     * Manifest content the run produced counts as pending counts until it is confirmed, like a hit
     * batch: a manifest send that fails, or stops the sends after it, marks the run pending since
     * the start of the flush, and a pass that confirms every manifest send clears it. When the newest
     * payload a collector accepted no longer says the run's state, as when a send of the flush was
     * refused after the flush's payloads were stamped, or starting ended, the flush sends one more
     * heartbeat that says so, unless a delta batch or heartbeat of it failed without a refusal.
     *
     * The shutdown flush waits for each send only until [CLOSING_RESERVE_MILLIS] before its
     * deadline, then marks what that send carried as pending and closes. A send it stopped waiting
     * for can still finish after the closing batch.
     *
     * [early] marks the flush [flushAtShutdownStart] runs. With a [deadlineNanos] it waits for each
     * send only until the deadline, marks what a send it stopped waiting for carried as pending,
     * and starts no hit batch or manifest chunk after the deadline. Its sends run on their own threads. It
     * sends no static baseline or class listing retry.
     *
     * Each flush has a number, [flushId] when the caller drew it, so the shutdown flushes are
     * numbered in the order they are called. A flush clears a pending state only when no later
     * flush marked it, and a mark is ignored when a later flush has cleared it. An early flush's
     * send that ends after the final flush began therefore cannot say nothing is pending while the
     * final flush's content is not confirmed, and cannot hold the run pending once the final flush
     * confirmed everything, since that flush sent what the early one had not.
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
            // Read after the sweep, so this flush's delta sends carry the counts of this generation.
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
                    awaitSend(releasedSend, final, deadlineNanos) { delivery.markManifestPending(flushStart, sends.flushId) } && allConfirmed
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
                // One chunk per sender per flush, and none from a shutdown flush, so a slow
                // resend never holds up a heartbeat or spends the shutdown budget.
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

    /**
     * Moves the verifier messages collected since the last flush into the registry, before this
     * flush's sends, so what they name goes out on the same manifest. Guarded like the sweep: a
     * failure may have lost what it drained, so it counts one unplaced failure, is logged, and the
     * flush goes on.
     */
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

    /** Calls [beforeManifest]; a failure is logged once and never reaches the flush. */
    private fun runBeforeManifest(final: Boolean) {
        try {
            beforeManifest(config.flushInterval.toMillis(), final)
        } catch (t: Throwable) {
            if (beforeManifestFailureLogged.compareAndSet(false, true)) {
                log.log(Level.WARNING, "otherlode: the hook that runs before a manifest is built failed", t)
            }
        }
    }

    /** Calls [releaseQuietCaches]; a failure is logged once and never reaches the flush. */
    private fun releaseCaches() {
        try {
            releaseQuietCaches()
        } catch (t: Throwable) {
            if (cacheReleaseFailureLogged.compareAndSet(false, true)) {
                log.log(Level.WARNING, "otherlode: releasing transform-time caches failed; the caches stay as they are", t)
            }
        }
    }

    /**
     * Runs the sweep before this flush's sends, so what it finds goes out on the same manifest
     * rather than waiting a whole cycle.
     *
     * The two directions the sweep serves keep different cadences. Confirmation
     * ([ProbeRegistry.confirmFrom], [EndpointRegistry.confirmFrom]) runs on every flush a class
     * awaits it, since a class held back stays held back until it is confirmed, and waiting ten
     * flushes would delay every class's first manifest by that much. The forward, unreported-class
     * direction is the expensive part, an `isCandidate` filter over every class the JVM holds, and
     * runs on the first flush and then every tenth; the shutdown flush always runs it, so an instance that ends cleanly
     * always gives a final answer.
     *
     * Before the dependency listing completes, the walk is skipped entirely, with no call into the
     * sweep at all, when there is nothing for either direction to do: no class or declaring
     * class awaiting confirmation, every framework marker seen, and the forward direction not due.
     * `getAllLoadedClasses` allocates an array of every class the JVM holds. A process that loads
     * no marker leaves one unseen for good, so with the shipped list the walk runs on every flush.
     * Once the listing completes the walk runs on every flush, since the sweep counts the classes
     * loaded from each dependency from the same array, and the forward direction runs with it,
     * since the walk is paid anyway. Confirmation keeps its cadence.
     *
     * Guarded like the sends are: this runs under `scheduleAtFixedRate`, which stops calling a
     * task forever the first time one lets a throwable escape.
     */
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

    /**
     * Logs one INFO line naming the branch sites dropped so far, the first time a flush finds the
     * total above zero. Nothing is logged on a flush that finds no drops yet, and nothing is
     * logged again once it has. See [BranchDropCounts].
     */
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

    /**
     * Logs one WARNING counting the methods and branch outcomes reported as unread shapes, by
     * family, the first time a flush finds any. It names each Scala 3 release the agent has not
     * read and, when some classes name no compiler the agent could read, says how many methods
     * those are and that a hand-written override of case-class plumbing is counted there too.
     * Nothing is logged on a flush that finds none yet, and nothing is logged again once it has.
     * See [UnreadShapeCounts].
     */
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

    /**
     * Logs one INFO line counting the classes analysed from the bytes they arrived as, the first
     * time a flush finds any. A class whose loader serves no class file has no source of shape other
     * than those bytes, and another agent may have changed them. See [UnreadShapeCounts].
     */
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

    /**
     * Logs one INFO line naming each configured callback annotation not yet seen on a method, in the
     * order and spelling the adopter wrote them. It is decided once, at the first flush that runs
     * [UNSEEN_CALLBACK_ANNOTATIONS_AFTER] or more after start, or at the final flush if that comes
     * first, and not revisited. An earlier flush can run before the application has loaded its
     * classes, since the first one is jittered from zero and a test run flushes every second. A
     * correctly spelled name whose classes have not loaded is still named, so this is INFO. See
     * [ConfiguredCallbackAnnotations].
     */
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

    /** [n] followed by [one] or [many]. */
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

    /**
     * The run's delivery state, stamped onto every delta batch and manifest just before it is sent
     * (ADR 0068). [pendingSince] is 0 when every hit the run has sent and all its manifest content
     * was confirmed, otherwise the start of the flush in which the unconfirmed content began.
     * [nextSequence] numbers the payloads from 1.
     *
     * A run that [starting] says so on every payload, apart from the pending state (ADR 0087),
     * until a scheduled flush finds that classes have loaded, that none has loaded for at least one
     * flush interval, that no class awaits the sweep's confirmation, and that the run is at least
     * one interval old; until 10 intervals have passed; or until a final flush that finds no
     * in-scope class loaded since the early shutdown flush and none awaiting confirmation
     * (ADR 0086). Ages are read from [monotonic]. Once ended it never comes back.
     *
     * One lock guards the sequence, the pending state and the starting state. A flush's manifest and delta sends run on two threads, and a
     * payload's number and the state it carries must be drawn together: any payload numbered after
     * a change of the state then read the state after it.
     */
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

        /** Records that a collector accepted the payload stamped with these values. */
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

        /** Numbers a flush, from 1, in the order flushes start. */
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

        /**
         * Called at the start of a scheduled flush, after the sweep, with the in-scope class count
         * and the classes still awaiting the sweep's confirmation. The count's last change is kept
         * with its time, so flushes that run back to back after a slow one do not shorten the quiet
         * window. Ends starting when classes have loaded, the count has not moved for at least one
         * interval, nothing awaits confirmation and the run is at least one interval old, or when
         * the cap has passed. The early shutdown flush never calls it.
         */
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

        /** The in-scope class count the early shutdown flush read after its sweep; -1 when none ran. */
        private var shutdownCount = -1L

        /** Called by the early shutdown flush after its sweep, with the in-scope class count. */
        fun observeShutdownStart(inScopeCount: Long) {
            synchronized(lock) { shutdownCount = inScopeCount }
        }

        /**
         * Called by the final flush after its sweep. Ends starting when the JVM loaded no in-scope
         * class since the early shutdown flush and none awaits confirmation, as for a batch job
         * whose work is done. A JVM stopped while its application was still starting keeps loading
         * through shutdown, so it stays pending. Without an early flush, starting does not end here.
         */
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

        /** Whether hits or manifest content are unconfirmed. */
        fun isPending(): Boolean = synchronized(lock) { pendingSince() != 0L }

        fun hitsPending(): Boolean = synchronized(lock) { hitsPendingSince != 0L }

        /**
         * Called when a hit batch of flush [flushId] was sent and not confirmed. Keeps the earlier
         * start if already pending. Ignored when a later flush confirmed every hit batch, since that
         * flush carried what this one did.
         */
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

        /**
         * Called when manifest content the run produced is not confirmed by flush [flushId]: a send
         * failed, or sends stopped before a chunk was sent. Keeps the earlier start if already pending.
         * Ignored when a later flush confirmed all manifest content.
         */
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

        /**
         * Called when every hit batch of flush [flushId] was confirmed. Clears nothing when a later
         * flush marked hits pending. Manifest content may still hold the run pending.
         */
        fun clearHits(flushId: Long) {
            synchronized(lock) {
                if (flushId < hitsMarkedBy) return
                hitsPendingSince = 0L
                if (flushId > hitsClearedBy) hitsClearedBy = flushId
            }
        }

        /**
         * Called when a pass of flush [flushId] confirmed every manifest send and left no manifest
         * content outstanding. Clears nothing when a later flush marked manifest content pending.
         */
        fun clearManifest(flushId: Long) {
            synchronized(lock) {
                if (flushId < manifestMarkedBy) return
                manifestPendingSince = 0L
                if (flushId > manifestClearedBy) manifestClearedBy = flushId
            }
        }

        /**
         * Whether the newest payload a collector accepted says something the state no longer
         * holds: pending while the state is clean, nothing pending while hits or manifest content
         * are, or starting after starting ended (ADR 0082, ADR 0087). The newest accepted payload
         * is what the server reads, so a run that dies after such a flush would otherwise read its
         * lost hits as evidence. False before any payload was accepted.
         */
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

        /**
         * Stamps the sequence, the pending state, whether the run watches the verifier and the
         * unplaced total, all read under the lock so one manifest carries one consistent view.
         */
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

        /** Records that a manifest carrying [total] unplaced failures, and [blind], was confirmed. */
        fun confirmStamped(
            total: Long,
            blind: Boolean,
        ) {
            synchronized(lock) {
                if (total > unplacedDelivered) unplacedDelivered = total
                if (blind) blindDelivered = true
            }
        }

        /**
         * Whether the registry's unplaced total is above the highest one a confirmed manifest
         * carried, or the listing went blind and no confirmed manifest has said so.
         */
        fun stampedStateAheadOfDelivered(): Boolean =
            synchronized(lock) { unplacedVerificationFailures() > unplacedDelivered || (classListingBlind() && !blindDelivered) }
    }

    /** The one place a delta batch reaches the exporter, so none skips [DeliveryState.stamp]. */
    private fun sendStamped(batch: DeltaBatch) {
        val stamped = delivery.stamp(batch)
        exporter.exportDeltaBatch(stamped)
        delivery.accepted(stamped.payloadSequence, stamped.countsPendingSince, stamped.starting)
    }

    /** The one place a manifest reaches the exporter, so none skips [DeliveryState.stamp]. */
    private fun sendStamped(manifest: ProbeManifest) {
        val stamped = delivery.stamp(manifest)
        exporter.exportManifest(stamped)
        delivery.accepted(stamped.payloadSequence, stamped.countsPendingSince, stamped.starting)
        delivery.confirmStamped(stamped.unplacedVerificationFailures, stamped.classListingBlind)
    }

    private fun hasHits(batch: DeltaBatch): Boolean =
        batch.deltas.isNotEmpty() || batch.endpointDeltas.isNotEmpty() || batch.dependencyDeltas.isNotEmpty()

    /**
     * One outgoing [DeltaBatch], together with the probe snapshot and the endpoint and dependency
     * snapshots it carries. A confirmed send advances exactly these, and nothing else.
     */
    private class DeltaSend(
        val batch: DeltaBatch,
        val probeSnapshot: ProbeRegistry.DeltaSnapshot?,
        val riders: List<Rider<DeltaBatch>>,
    )

    /**
     * A chunk from a registry other than the probe registry, packed onto a probe delta batch or a
     * class manifest chunk: its weight against the entry cap, its encoded weight against the byte
     * and element bounds, how it adds itself to the payload, and how its registry marks it
     * delivered once that payload's send is confirmed.
     */
    private class Rider<T>(
        val size: Int,
        val weight: PayloadWeight,
        val attach: (T) -> T,
        val advance: () -> Unit,
    )

    /**
     * Sends probe, endpoint and dependency deltas together, advancing each snapshot only once its
     * send is confirmed. A refused send (an [ExportFailedException] with [ExportFailedException.refused])
     * does not stop the loop: it is logged, its snapshots stay where they were, and the next batch
     * is sent. Any other failure stops the loop. The sends already confirmed stay advanced, the
     * rest are recomputed and resent on the next flush.
     *
     * Pending counts (ADR 0068) move at two points. A hit batch, one carrying at least one probe,
     * endpoint or dependency delta, that is sent and not confirmed makes the run pending since
     * [flushStart] when it was not already. A flush that sent and confirmed every hit batch it
     * built then clears it and ends with an empty batch stamped after the change. A flush that
     * starts pending leads with an empty batch on its own, so the field reaches the collector even
     * when the hit batch is refused for its size. An empty batch carries no counts: its outcome
     * never moves the state, and a refusal of it counts against nothing. Any other failure of it
     * ends the loop like one of a hit batch.
     *
     * When every hit batch in the loop is confirmed, the counts of counting generation
     * [generation] have reached the collector, and this records it
     * ([DependencyRegistry.markCountsDelivered]).
     *
     * The shutdown flush ([final]) ends with one more empty batch that carries `finalFlush`, which
     * [flush] sends after this loop and the manifest sends, whatever happened to them, so it
     * carries the pending state both left. No other batch of the flush carries `finalFlush`. The
     * shutdown flush sends no empty batch of its own before that one, since the closing batch is
     * its liveness signal, and it replaces the one a clearing flush would send. When anything the
     * loop does not account for throws, such as computing the batches, the shutdown flush marks the
     * run pending before it closes, so the run never ends cleanly with its hits unconfirmed and
     * nothing pending.
     *
     * `flushComplete` is set on at most one batch of a flush, the last one the flush sends, and
     * only when every delta batch the flush sent before it was confirmed, empty batches included.
     * It is decided when the batch is built, from the sends so far. A flush that started clean
     * carries it on the last batch of the loop. A flush that started with pending hits carries it on its
     * closing heartbeat. The shutdown flush carries it on its closing `finalFlush` batch. A failure
     * of any kind, including one before the first send, leaves it off every later batch.
     *
     * Returns whether the loop ran to its end with every hit batch confirmed.
     */
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

    /**
     * The shutdown flush's last send, after the hit batches and the manifest sends, so it carries
     * the pending state they left. Returns false when it failed without a refusal.
     */
    private fun sendClosingHeartbeat(sends: FlushSends): Boolean =
        try {
            sendHeartbeat(sends, finalFlush = true, flushComplete = sends.allConfirmed)
            true
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: closing export failed, the run will read as one that went silent", t)
            false
        }

    /**
     * Tells the collector the run's state, when the newest payload it accepted no longer says it:
     * a send was refused after the flush's payloads were stamped, the last pending state was
     * cleared, or starting ended. Returns false when it failed without a refusal.
     */
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

    /**
     * Whether every delta batch a flush has sent so far was confirmed. A batch that was sent and
     * refused or failed, a failure before any batch was sent, and a delta send the shutdown flush
     * stopped waiting for make it false for the rest of the flush. [outage] is set when a delta
     * batch or a heartbeat failed without a refusal, whatever the status: the collector, or
     * Otherlode behind it, is not taking payloads. The flush's threads and its send pool read and
     * write it. [flushId] numbers the flush for [DeliveryState]. [stopsAtNanos], when not 0, is the
     * time after which the flush starts no send.
     */
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

    /**
     * Sends an empty delta batch. A refusal is logged and the caller goes on; any other failure
     * propagates. Either marks [sends] unconfirmed.
     */
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

    /**
     * Packs endpoint and dependency delta snapshots onto probe delta batches, in [riders] order.
     * Each goes on the first batch with room for it, room being [maxDeltasPerBatch] minus that
     * batch's weight so far, and the encoded bytes and element count of [PayloadKind.DELTA_BATCH]; a
     * snapshot that fits nowhere becomes its own [DeltaBatch] with no probe deltas, which a later
     * rider may then share. At the default [maxDeltasPerBatch], a rider of fixed-size deltas
     * breaks no bound on its own.
     *
     * [probeBatches] is never empty: [ProbeRegistry.computeDeltaBatches] always returns at least
     * one batch as the liveness heartbeat. That batch is where every rider lands when nothing else
     * changed, so a flush with only endpoint or dependency activity still sends exactly one
     * [DeltaBatch], carrying both the heartbeat and those deltas.
     *
     * No batch built here carries `finalFlush`; the shutdown flush's closing batch does.
     */
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

    /**
     * One outgoing [ProbeManifest], together with the probe snapshot and the riders (endpoint,
     * dependency, external-class and framework-marker chunks) it carries. A confirmed send advances exactly these, and nothing else.
     */
    private class ManifestSend(
        val manifest: ProbeManifest,
        val probeSnapshot: ProbeRegistry.ManifestSnapshot?,
        val riders: List<Rider<ProbeManifest>>,
    )

    /**
     * Sends only the probes, endpoints, dependencies, external classes and framework markers not yet
     * included in a successfully delivered manifest.
     *
     * This runs on the first flush, not at agent startup. By the first
     * flush, classes have actually started loading, so there are probes to
     * send. Classes that register later (lazy singletons, or a code path
     * run for the first time) are still picked up on a later flush. They
     * are not permanently left out just because an earlier send already
     * succeeded. The same holds for endpoints: a discovery-source upgrade
     * or a handler join learned after an earlier delivery is picked up the
     * same way. A dependency, and a mapping to it, goes out only once its
     * counts were delivered; see [DependencyRegistry.isSendable].
     *
     * A disabled endpoint module not yet delivered goes first, alone on a manifest, before any
     * class chunk is built. Its record is the only thing that tells a collector the endpoints
     * earlier manifests declared are no longer counted, so it never waits behind a class chunk
     * and never also rides an endpoint chunk.
     *
     * Class chunks are built, sent and advanced one at a time, so the first flush holds one
     * chunk rather than the whole manifest, and the first failure stops the send before the next
     * chunk is built. Riders are computed up front, in chunks their registries bound by weight.
     *
     * `dependenciesListed` is read once, after the riders are computed and before the
     * first send, so every chunk of one call carries the same value.
     *
     * Returns whether every send in the loop was confirmed. A call with
     * nothing to send returns true. A failure, a refusal included, or an exception while computing
     * the chunks marks the run's manifest content pending since [flushStart] (ADR 0068), and a call
     * that confirms everything it computed clears it.
     */
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

    /**
     * Packs endpoint, dependency, external-class and framework-marker chunks onto class manifest chunks, in [riders] order. Each
     * rider goes on the first manifest with room for it, room being [maxManifestEntriesPerChunk]
     * minus that manifest's weight so far, and the encoded bytes and element count of
     * [PayloadKind.MANIFEST]. A rider that fits nowhere, including when there are no
     * class chunks at all, becomes its own [ProbeManifest] with no probes, which a later rider may
     * then share.
     *
     * Lazy: a class chunk is taken from [classChunks] only when the consumer asks for the next
     * send, and riders are placed chunk by chunk, each chunk taking every waiting rider that fits
     * in order. That puts every rider where first-fit over all the chunks at once would, without
     * building a later chunk before an earlier one was sent.
     */
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

    /**
     * Whether this instance records references, stamped on every manifest it sends: true exactly
     * when include rules are set. The agent refuses to start without them, so a running agent
     * always sends true; with an empty list nothing is instrumented and nothing is recorded, and
     * false says so.
     */
    private val referencesRecorded: Boolean
        get() = config.includePackages.isNotEmpty()

    /**
     * Whether a manifest built at this point may say `dependenciesListed`: every startup dependency and every
     * mapping recorded before the listing ended has gone out on a confirmed manifest.
     */
    private val dependenciesListed: Boolean
        get() = dependencyRegistry.isStartupListingDelivered && externalClassRegistry.isBacklogDelivered

    private fun emptyManifest(dependenciesListed: Boolean): ProbeManifest =
        ProbeManifest(
            resource = resource,
            probes = emptyList(),
            referencesRecorded = referencesRecorded,
            dependenciesListed = dependenciesListed,
        )

    /**
     * Sends an empty manifest carrying `dependenciesListed` when it is due and no confirmed
     * manifest has carried it yet. Without this, an instance with nothing else to send would never
     * tell the collector that its listing is complete. [flush] calls this only when every other
     * send of the flush was confirmed. Guarded like the other sends; see [flush].
     */
    private fun sendDependenciesListedIfDue(sends: FlushSends) {
        try {
            if (dependenciesListedSent.get() || !dependenciesListed) return
            sendStamped(emptyManifest(dependenciesListed = true))
            dependenciesListedSent.set(true)
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: sending dependenciesListed failed, will retry next flush", t)
        }
    }

    /**
     * Sends an empty manifest when the run's unplaced verification failures rose above the total
     * any confirmed manifest carried, or its class listing went blind and no confirmed manifest
     * said so, and nothing else of this flush carried it. Every manifest
     * stamps the current total, so a flush with class chunks to send needs no extra one. [flush]
     * calls this only when every other send of the flush was confirmed. Guarded like the other sends.
     */
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

        // Every list the chunker weighted, call edges, references and branch sites included, so
        // packing a rider onto this one cannot overshoot the cap. A bucket missing here reads as
        // weightless and absorbs a full chunk; edges, references and sites nest inside each probe
        // location or class record rather than sitting beside it, which is why they need summing
        // rather than a list size.
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
        /** Built at run time, since `shadowJar` rewrites a constant that starts with `kotlin`. */
        private val KOTLINX_PACKAGE = listOf("kot", "linx").joinToString("")

        /** Flush intervals after the agent's start at which a run stops counting as starting (ADR 0086). */
        const val STARTING_CAP_INTERVALS: Long = 10

        /**
         * How long after start the unseen callback annotations are first judged. Long enough for an
         * application to have started and loaded its handlers.
         */
        val UNSEEN_CALLBACK_ANNOTATIONS_AFTER: Duration = Duration.ofMinutes(5)

        /** A delta is a few dozen bytes on the wire, so this is well under a megabyte per POST. */
        const val DEFAULT_MAX_DELTAS_PER_BATCH: Int = 20_000

        /** A probe location carries class and method strings, so it is roughly ten times a delta's size. */
        const val DEFAULT_MAX_MANIFEST_ENTRIES_PER_CHUNK: Int = 5_000

        /**
         * How many flushes pass between sweeps, about ten minutes at the default interval. Not
         * an option: nobody can pick a better number without knowing what the walk costs on their
         * own application, and the finding it produces does not go stale.
         */
        const val SWEEP_EVERY_N_FLUSHES: Int = 10

        /** What the shutdown flush keeps of its budget for its closing batch. */
        const val CLOSING_RESERVE_MILLIS: Long = 2_000L
    }
}
