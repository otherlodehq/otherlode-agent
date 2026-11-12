package dev.otherlode.registry

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.export.ChunkLimits
import dev.otherlode.export.DisabledEndpointModule
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointDelta
import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointLocation
import dev.otherlode.export.EndpointTemplateKind
import dev.otherlode.export.PayloadKind
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.chunkedByWeight
import java.lang.System.Logger.Level
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The method or object a framework invokes for an endpoint. [methodName] and [descriptor] are null
 * when only the handler object's class is known.
 */
data class HandlerRef(
    val className: String,
    val methodName: String? = null,
    val descriptor: String? = null,
)

private val ANCHOR = Any()
private val EXTENSION_ROUTE = Regex("/\\*\\.[^/*]+")

/**
 * Tracks one probe per HTTP endpoint, counted where a framework matches a request to it, before the
 * handler runs.
 *
 * Identity is `(verb, route template, template kind)`, the template normalised by
 * [RouteTemplateNormalizer] only when it is a path. Registrations with the same identity are one
 * endpoint with one [EndpointEntry.endpointId] and one count. A regex or servlet pattern template
 * is kept as given and never merges with a template of another kind.
 *
 * A framework module supplies dispatch keys that identify the framework's own route, and [lookup]
 * resolves a key to its entry. A key made of values (a string, or a list of strings) is compared by
 * `equals`. Any other key object is compared by identity and held weakly. A list holding one such
 * object beside values is held weakly on that object and compared by value otherwise. A key whose
 * object was collected is unbound; its endpoint stays.
 *
 * An endpoint a module declares from inside a class's transform is published only once a source of
 * it is confirmed: the declaring class is confirmed defined, the endpoint was also registered
 * outside a transform or discovered by dispatch, or it has a hit. [confirmFrom] settles the class
 * against the JVM's loaded classes. The dispatch key is bound at once, so a hit before confirmation
 * still counts.
 *
 * @param dispatchLimit how many identities each framework module may create at dispatch, and how
 *   many request-built keys it may bind there.
 */
class EndpointRegistry(
    private val dispatchLimit: Int = OtherlodeEndpoints.DISPATCH_LIMIT,
) {
    private val log by agentLogger(EndpointRegistry::class.java.name)

    private data class Identity(
        val verb: String,
        val routeTemplate: String,
        val templateKind: EndpointTemplateKind,
    )

    class EndpointEntry internal constructor(
        val endpointId: Int,
        val verb: String,
        val routeTemplate: String,
        val verbatimTemplate: String,
        val framework: String,
        discoverySource: EndpointDiscoverySource,
        val templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ) {
        @Volatile
        var discoverySource: EndpointDiscoverySource = discoverySource
            internal set

        @Volatile
        var handlerClass: String? = null
            internal set

        @Volatile
        var handlerMethod: String? = null
            internal set

        @Volatile
        var handlerDescriptor: String? = null
            internal set

        /**
         * Hit count, incremented once per matched request by [hit]. The increment is not atomic, so
         * a concurrent hit can be lost.
         */
        @JvmField
        var count: Long = 0

        /** Increments [count] without synchronisation. */
        fun hit() {
            count++
        }

        @Volatile
        internal var anchored: Boolean = false

        @Volatile
        internal var declaringClasses: List<DeclaringState> = emptyList()

        @Volatile
        internal var version: Long = 1

        @Volatile
        internal var deliveredVersion: Long = 0

        @Volatile
        internal var lastSent: Long = 0

        internal var firstSeenAt: Long = 0

        internal var decreaseWarned: Boolean = false

        internal var lastAppliedDeltaSequence: Long = 0
    }

    private class DispatchCount {
        val created = AtomicInteger(0)
        val boundKeys = AtomicInteger(0)
        val createdWarned = AtomicBoolean(false)
        val keysWarned = AtomicBoolean(false)
    }

    private data class DeclaringKey(
        val className: String,
        val loaderId: Int,
    )

    internal class DeclaringState(
        val className: String,
        val classLoaderRef: WeakReference<ClassLoader>?,
        attempt: DefinitionAttempt?,
        sequence: Long,
    ) {
        val endpoints: MutableSet<EndpointEntry> = ConcurrentHashMap.newKeySet()

        val watch: DefinitionWatch = DefinitionWatch(attempt, attempt == null, sequence)
    }

    private class DisabledModuleEntry(
        val reason: String,
        val kind: DisabledEndpointModuleKind,
        val disabledAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    /**
     * One computed delta batch. Pass it to [advanceDeltas] once the send carrying it is confirmed.
     */
    class DeltaSnapshot internal constructor(
        val deltas: List<EndpointDelta>,
        internal val sequence: Long,
        internal val staged: List<Pair<EndpointEntry, Long>>,
    )

    /**
     * One computed manifest chunk. Pass it to [advanceManifest] once the send carrying it is
     * confirmed.
     */
    class ManifestSnapshot internal constructor(
        val endpoints: List<EndpointLocation>,
        internal val stagedEndpoints: List<Pair<EndpointEntry, Long>>,
    )

    /**
     * The disabled modules not yet delivered; [advanceDisabledModules] marks exactly these as
     * delivered.
     */
    class DisabledModulesSnapshot internal constructor(
        val modules: List<DisabledEndpointModule>,
    )

    private val entriesByIdentity = ConcurrentHashMap<Identity, EndpointEntry>()
    private val keyToEntry = ConcurrentHashMap<Any, EndpointEntry>()

    private val collectedKeys = ReferenceQueue<Any>()
    private val disabledModulesByName = ConcurrentHashMap<String, DisabledModuleEntry>()
    private val dispatchCounts = ConcurrentHashMap<String, DispatchCount>()
    private val nextEndpointId = AtomicInteger(0)
    private val nextDeltaSequence = AtomicLong(0)
    private val declaringStates = ConcurrentHashMap<DeclaringKey, DeclaringState>()
    private val lastRegistrationSequence = AtomicLong(0)
    private val unwatchableWarned = AtomicBoolean(false)

    internal var attemptEnded: (DefinitionAttempt) -> Boolean = { it.hasEnded() }

    /** Resolves a dispatch key to its entry, or null when [key] is not bound to any entry yet. */
    fun lookup(key: Any): EndpointEntry? = keyToEntry[lookupKeyOf(key)]

    private fun bind(
        key: Any,
        entry: EndpointEntry,
    ) {
        while (true) {
            val collected = collectedKeys.poll() as? WeakRouteKey ?: break
            collected.mapKey?.let { keyToEntry.remove(it) }
        }
        keyToEntry[storedKeyOf(key)] = entry
    }

    private fun storedKeyOf(key: Any): Any {
        if (isValue(key)) return key
        if (key is List<*>) {
            val anchor = key.singleOrNull { !isValue(it) } ?: return key
            val weak = WeakRouteKey(anchor, collectedKeys)
            return AnchoredKey(weak, key.map { if (it === anchor) ANCHOR else it }).also { weak.mapKey = it }
        }
        return WeakRouteKey(key, collectedKeys).also { it.mapKey = it }
    }

    private fun lookupKeyOf(key: Any): Any {
        if (isValue(key)) return key
        if (key is List<*>) {
            val anchor = key.singleOrNull { !isValue(it) } ?: return key
            return AnchoredKey(StrongRouteKey(anchor), key.map { if (it === anchor) ANCHOR else it })
        }
        return StrongRouteKey(key)
    }

    private fun isValue(key: Any?): Boolean =
        key == null ||
            key is String ||
            key is Number ||
            key is Boolean ||
            key is Char ||
            key is Enum<*> ||
            (key is List<*> && key.all(::isValue))

    private interface RouteKey {
        val route: Any?
        val hash: Int
    }

    private class WeakRouteKey(
        route: Any,
        queue: ReferenceQueue<Any>,
    ) : WeakReference<Any>(route, queue),
        RouteKey {
        override val hash: Int = System.identityHashCode(route)
        override val route: Any? get() = get()

        @Volatile
        var mapKey: Any? = null

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other === this || (other is RouteKey && route != null && route === other.route)
    }

    private class StrongRouteKey(
        override val route: Any,
    ) : RouteKey {
        override val hash: Int = System.identityHashCode(route)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is RouteKey && route === other.route
    }

    private class AnchoredKey(
        val anchor: RouteKey,
        val values: List<Any?>,
    ) {
        override fun hashCode(): Int = 31 * anchor.hashCode() + values.hashCode()

        override fun equals(other: Any?): Boolean = other is AnchoredKey && anchor == other.anchor && values == other.values
    }

    /** How many dispatch keys are bound to an entry. */
    fun boundKeyCount(): Int = keyToEntry.size

    /**
     * Records an endpoint from a framework's own registration hook, finds or creates the entry for
     * its identity, binds [key] to it, and marks it discovered by registration.
     *
     * [verb] and [verbatimTemplate] are normalised with [contextPath] folded in. When
     * [templateKind] is not [EndpointTemplateKind.PATH], [verbatimTemplate] is kept as given and
     * [contextPath] is ignored.
     *
     * An entry first known from a dispatch is upgraded and goes out again on the next manifest
     * compute. [handler], when given, is attached as [attachHandler] does.
     *
     * [declaringClass] is set for a registration made from inside a class's transform. The key is
     * bound at once, but the endpoint is published only once a source of it is confirmed; see the
     * class description. A registration without one is a source in itself.
     */
    fun register(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String? = null,
        handler: HandlerRef? = null,
        templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
        declaringClass: DeclaringClass? = null,
    ): EndpointEntry {
        val entry = findOrCreate(framework, verb, verbatimTemplate, contextPath, templateKind)
        if (declaringClass == null) entry.anchored = true else watch(declaringClass, entry)
        bind(key, entry)
        upgradeDiscoverySource(entry, EndpointDiscoverySource.REGISTRATION)
        if (handler != null) attachHandler(entry, handler)
        return entry
    }

    /**
     * Records an endpoint at the moment a request dispatches to it, for a [key] that [lookup] did
     * not resolve. Finds or creates the entry for the normalised identity, marks a new one as
     * discovered by dispatch, and binds [key] to it. The caller calls [EndpointEntry.hit] itself.
     *
     * [handlerClass] is attached as [attachHandler] does, so it never replaces a join that names a
     * method.
     *
     * A module may create at most the dispatch limit of identities this way. Past it this binds
     * nothing and returns null, and the registry logs one warning per module. An identity that
     * exists already is returned, and one declared by [register] does not count toward the limit.
     * A module may also bind at most that many request-built keys. Past it the entry is still
     * returned, and the key is resolved again on its next request.
     */
    fun recordDispatch(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String? = null,
        handlerClass: String? = null,
        templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ): EndpointEntry? {
        val entry = findOrCreateAtDispatch(framework, verb, verbatimTemplate, contextPath, templateKind) ?: return null
        if (!entry.anchored) entry.anchored = true
        if (!isValue(key) || lookup(key) != null || reserveDispatchKey(framework)) bind(key, entry)
        if (handlerClass != null) attachHandler(entry, HandlerRef(handlerClass))
        return entry
    }

    /**
     * Like [recordDispatch], for a route bridge module that reads an identity another
     * instrumentation layer already resolved. When a different [framework] owns the normalised
     * identity, this binds nothing and returns null.
     *
     * A framework also owns every verb on a template it serves with verb `*`, a `HEAD` request on a
     * template whose `GET` endpoint it owns, and a path template whose servlet pattern it owns:
     * the pattern `*.ext` owns the template of a slash followed by `*.ext`, and the pattern `/`
     * owns the templates `/` and slash-star.
     *
     * A servlet context path escapes this check: a bridge route that includes one does not match
     * the framework module's identity, so this records a second endpoint for that route.
     * The ownership check and the creation are not atomic, so two frameworks racing on one identity
     * can both pass.
     */
    fun recordDispatchIfUnowned(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String? = null,
        handlerClass: String? = null,
        templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ): EndpointEntry? {
        val identity = identityOf(verb, verbatimTemplate, contextPath, templateKind)
        val owners = mutableListOf(identity, identity.copy(verb = "*"))
        if (identity.verb == "HEAD") owners += identity.copy(verb = "GET")
        if (templateKind == EndpointTemplateKind.PATH) {
            servletPatternBehind(identity.routeTemplate)?.let { pattern ->
                val patternIdentity = Identity(identity.verb, pattern, EndpointTemplateKind.SERVLET_PATTERN)
                owners += patternIdentity
                owners += patternIdentity.copy(verb = "*")
                if (patternIdentity.verb == "HEAD") owners += patternIdentity.copy(verb = "GET")
            }
        }
        if (owners.any { entriesByIdentity[it]?.framework.let { owner -> owner != null && owner != framework } }) return null
        return recordDispatch(key, framework, verb, verbatimTemplate, contextPath, handlerClass, templateKind)
    }

    private fun servletPatternBehind(pathTemplate: String): String? =
        when {
            pathTemplate == "/*" || pathTemplate == "/" -> "/"
            EXTENSION_ROUTE.matches(pathTemplate) -> pathTemplate.substring(1)
            else -> null
        }

    private fun identityOf(
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        templateKind: EndpointTemplateKind,
    ): Identity =
        Identity(
            RouteTemplateNormalizer.normalizeVerb(verb),
            if (templateKind ==
                EndpointTemplateKind.PATH
            ) {
                RouteTemplateNormalizer.normalize(verbatimTemplate, contextPath)
            } else {
                verbatimTemplate
            },
            templateKind,
        )

    private fun findOrCreate(
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        templateKind: EndpointTemplateKind,
    ): EndpointEntry {
        val identity = identityOf(verb, verbatimTemplate, contextPath, templateKind)
        return entriesByIdentity.computeIfAbsent(identity) {
            newEntry(identity, verbatimTemplate, framework, EndpointDiscoverySource.REGISTRATION, templateKind)
        }
    }

    private fun findOrCreateAtDispatch(
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        templateKind: EndpointTemplateKind,
    ): EndpointEntry? {
        val identity = identityOf(verb, verbatimTemplate, contextPath, templateKind)
        entriesByIdentity[identity]?.let { return it }
        if (!reserveDispatchIdentity(framework)) return entriesByIdentity[identity]
        val count = checkNotNull(dispatchCounts[framework])
        var created = false
        val entry =
            entriesByIdentity.computeIfAbsent(identity) {
                created = true
                newEntry(identity, verbatimTemplate, framework, EndpointDiscoverySource.DISPATCH, templateKind)
            }
        if (!created) count.created.decrementAndGet()
        return entry
    }

    private fun newEntry(
        identity: Identity,
        verbatimTemplate: String,
        framework: String,
        source: EndpointDiscoverySource,
        templateKind: EndpointTemplateKind,
    ): EndpointEntry =
        EndpointEntry(
            endpointId = nextEndpointId.getAndIncrement(),
            verb = identity.verb,
            routeTemplate = identity.routeTemplate,
            verbatimTemplate = verbatimTemplate,
            framework = framework,
            discoverySource = source,
            templateKind = templateKind,
        )

    private fun reserveDispatchIdentity(framework: String): Boolean {
        val count = dispatchCounts.computeIfAbsent(framework) { DispatchCount() }
        return reserve(count.created, count.createdWarned) {
            "otherlode: endpoint module $framework found $dispatchLimit endpoints at dispatch, its limit; " +
                "it records no new ones, and the endpoints it holds keep counting"
        }
    }

    private fun reserveDispatchKey(framework: String): Boolean {
        val count = dispatchCounts.computeIfAbsent(framework) { DispatchCount() }
        return reserve(count.boundKeys, count.keysWarned) {
            "otherlode: endpoint module $framework bound $dispatchLimit request keys at dispatch, its limit; " +
                "a request with a new key still counts, and the agent resolves its endpoint again each time"
        }
    }

    private fun reserve(
        slots: AtomicInteger,
        warned: AtomicBoolean,
        warning: () -> String,
    ): Boolean {
        while (true) {
            val taken = slots.get()
            if (taken >= dispatchLimit) {
                if (warned.compareAndSet(false, true)) log.log(Level.WARNING, warning())
                return false
            }
            if (slots.compareAndSet(taken, taken + 1)) return true
        }
    }

    private fun upgradeDiscoverySource(
        entry: EndpointEntry,
        source: EndpointDiscoverySource,
    ) {
        if (entry.discoverySource == source) return
        synchronized(entry) {
            if (entry.discoverySource == source) return@synchronized
            if (entry.discoverySource == EndpointDiscoverySource.DISPATCH) dispatchCounts[entry.framework]?.created?.decrementAndGet()
            entry.discoverySource = source
            entry.version++
        }
    }

    /**
     * Sets [entry]'s join to [handler] unless the join already names a method. Any change bumps
     * [EndpointEntry.version], so the entry goes out again on the next manifest compute.
     */
    fun attachHandler(
        entry: EndpointEntry,
        handler: HandlerRef,
    ) {
        synchronized(entry) {
            if (entry.handlerMethod != null) return@synchronized
            val changed =
                entry.handlerClass != handler.className ||
                    entry.handlerMethod != handler.methodName ||
                    entry.handlerDescriptor != handler.descriptor
            if (!changed) return@synchronized
            entry.handlerClass = handler.className
            entry.handlerMethod = handler.methodName
            entry.handlerDescriptor = handler.descriptor
            entry.version++
        }
    }

    /**
     * Records an endpoint module that is not instrumenting: one that switched itself off, or one
     * the configuration kept off. [kind] names which. Idempotent per module name: a repeat call
     * keeps the first kind, reason and timestamp.
     */
    fun recordDisabledModule(
        module: String,
        reason: String,
        kind: DisabledEndpointModuleKind,
    ) {
        disabledModulesByName.computeIfAbsent(module) { DisabledModuleEntry(reason, kind, System.currentTimeMillis()) }
    }

    /** A full snapshot of every tracked endpoint, in [EndpointEntry.endpointId] order. */
    fun endpoints(): List<EndpointLocation> = entriesByIdentity.values.sortedBy { it.endpointId }.map { it.toLocation() }

    /** A full snapshot of every disabled endpoint module. */
    fun disabledModules(): List<DisabledEndpointModule> =
        disabledModulesByName.map { (module, entry) -> DisabledEndpointModule(module, entry.reason, entry.disabledAt, entry.kind) }

    private fun watch(
        declaring: DeclaringClass,
        entry: EndpointEntry,
    ) {
        val attempt = declaring.attempt
        if (attempt == null) warnUnwatchableOnce()
        var created = false
        val state =
            declaringStates.computeIfAbsent(DeclaringKey(declaring.className, declaring.loaderId)) {
                created = true
                DeclaringState(declaring.className, declaring.classLoaderRef, attempt, lastRegistrationSequence.incrementAndGet())
            }
        if (!created) startNewAttempt(state, attempt)
        state.endpoints.add(entry)
        synchronized(entry) {
            if (state !in entry.declaringClasses) entry.declaringClasses = entry.declaringClasses + state
        }
    }

    private fun startNewAttempt(
        state: DeclaringState,
        attempt: DefinitionAttempt?,
    ) {
        if (isConfirmed(state)) return
        state.watch.startAttempt(attempt, attempt == null) { lastRegistrationSequence.incrementAndGet() }
    }

    private fun warnUnwatchableOnce() {
        if (!unwatchableWarned.compareAndSet(false, true)) return
        log.log(
            Level.WARNING,
            "otherlode: the definition attempt of a class that declared endpoints could not be read from its thread's stack; " +
                "such classes are never reported as failed to load",
        )
    }

    private fun isPublishable(entry: EndpointEntry): Boolean =
        entry.anchored || entry.count > 0 || entry.declaringClasses.any { isConfirmed(it) }

    private fun isConfirmed(state: DeclaringState): Boolean {
        if (state.watch.isConfirmed) return true
        if (state.endpoints.any { it.count > 0 && !it.anchored && it.declaringClasses.singleOrNull() === state }) state.watch.confirm()
        return state.watch.isConfirmed
    }

    internal fun registrationSequence(): Long = lastRegistrationSequence.get()

    /**
     * Reconciles every declaring class not yet confirmed defined against [loadedClassNames], the
     * JVM's loaded-class names, by the rule [ProbeRegistry.confirmFrom] applies to a class's
     * probes. A class is confirmed when its name is in that set or its classloader was collected. A
     * class found in neither takes a miss only when every definition attempt it recorded has ended,
     * and two misses withhold it until it declares endpoints again. An unwatchable class never
     * takes a miss.
     *
     * Returns each class the first time this withholds it, with the number of its endpoints that
     * no other source publishes. A class whose endpoints all have another source is withheld
     * without being returned. [registeredUpTo] is the [registrationSequence] value read before
     * [loadedClassNames] was taken.
     */
    fun confirmFrom(
        loadedClassNames: Set<String>,
        registeredUpTo: Long = Long.MAX_VALUE,
    ): List<WithheldEndpointClass> {
        val newlyWithheld = mutableListOf<WithheldEndpointClass>()
        for (state in declaringStates.values) {
            if (state.watch.sequence > registeredUpTo) continue
            if (isConfirmed(state)) continue
            val loaderCollected = state.classLoaderRef != null && state.classLoaderRef.get() == null
            val defined = state.className in loadedClassNames || loaderCollected
            if (state.watch.reconcile(defined, registeredUpTo, attemptEnded) != DefinitionWatch.Verdict.WITHHELD) continue
            val withheld = state.endpoints.count { !isPublishable(it) }
            if (withheld > 0 && state.watch.markReported()) newlyWithheld += WithheldEndpointClass(state.className, withheld)
        }
        return newlyWithheld
    }

    /** How many declaring classes are not yet confirmed defined and not withheld for good. */
    fun unconfirmedClassCount(): Int = declaringStates.values.count { !isConfirmed(it) && !it.watch.isWithheld }

    /** How many declaring classes were never confirmed defined and are withheld for good. */
    fun withheldForGoodClassCount(): Int = declaringStates.values.count { !isConfirmed(it) && it.watch.isWithheld }

    /**
     * Returns batches of at most [maxPerBatch] endpoints whose live count differs from the last
     * successfully sent value. Empty when nothing changed. Nothing is marked as sent; call
     * [advanceDeltas] with each [DeltaSnapshot] once that batch is confirmed delivered.
     */
    fun computeDeltas(maxPerBatch: Int): List<DeltaSnapshot> {
        val batches = mutableListOf<DeltaSnapshot>()
        var deltas = mutableListOf<EndpointDelta>()
        var staged = mutableListOf<Pair<EndpointEntry, Long>>()

        fun seal() {
            if (deltas.isEmpty()) return
            batches += DeltaSnapshot(deltas, nextDeltaSequence.incrementAndGet(), staged)
            deltas = mutableListOf()
            staged = mutableListOf()
        }

        for (entry in entriesByIdentity.values) {
            val current = entry.count
            val lastSent = entry.lastSent
            if (current == lastSent) continue
            if (current < lastSent) warnOnceAboutDecrease(entry, lastSent, current)
            if (current > 0 && entry.firstSeenAt == 0L) entry.firstSeenAt = System.currentTimeMillis()
            if (deltas.size == maxPerBatch) seal()
            deltas += EndpointDelta(entry.endpointId, entry.firstSeenAt, current)
            staged += entry to current
        }
        seal()
        return batches
    }

    private fun warnOnceAboutDecrease(
        entry: EndpointEntry,
        lastSent: Long,
        current: Long,
    ) {
        if (entry.decreaseWarned) return
        entry.decreaseWarned = true
        log.log(
            Level.WARNING,
            "otherlode: endpoint hit count went backward for ${entry.verb} ${entry.routeTemplate} " +
                "(was $lastSent, now $current); reporting it as-is",
        )
    }

    /**
     * Records [snapshot]'s per-entry counts as delivered. Call this only once that batch is
     * confirmed delivered. An older snapshot confirmed after a newer one is ignored per entry, so a
     * confirmed count is never rolled back.
     */
    fun advanceDeltas(snapshot: DeltaSnapshot) {
        for ((entry, count) in snapshot.staged) {
            synchronized(entry) {
                if (snapshot.sequence <= entry.lastAppliedDeltaSequence) return@synchronized
                entry.lastAppliedDeltaSequence = snapshot.sequence
                entry.lastSent = count
            }
        }
    }

    /**
     * Returns chunks of at most [maxPerChunk] endpoints never delivered or changed since last
     * delivered (a discovery-source upgrade, a newly attached handler). An endpoint whose only
     * source is a declaring class not yet confirmed defined is left out until it is. Empty when
     * there is nothing to send. Disabled modules are not here; see [computeDisabledModules].
     *
     * Nothing is marked as delivered here; call [advanceManifest] with each [ManifestSnapshot] once
     * that chunk is confirmed delivered.
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> {
        val pending = mutableListOf<Triple<EndpointEntry, Long, EndpointLocation>>()
        for (entry in entriesByIdentity.values) {
            val version = entry.version
            if (version <= entry.deliveredVersion) continue
            if (!isPublishable(entry)) continue
            pending += Triple(entry, version, entry.toLocation())
        }
        return chunkedByWeight(pending, maxPerChunk, PayloadKind.MANIFEST, ChunkLimits.MANIFEST_FRAME_ALLOWANCE) {
            ProtoPayloadCodec.entryWeight(it.third)
        }.map { chunk -> ManifestSnapshot(chunk.map { it.third }, chunk.map { it.first to it.second }) }
    }

    /**
     * Marks every endpoint [snapshot] staged as delivered, at the version it staged, so a change
     * that lands while the send was in flight goes out on the next compute. Call this only once
     * that chunk is confirmed delivered.
     */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for ((entry, version) in snapshot.stagedEndpoints) {
            synchronized(entry) {
                if (version > entry.deliveredVersion) entry.deliveredVersion = version
            }
        }
    }

    /**
     * Every disabled module not yet delivered, or null when there is none. Nothing is marked as
     * delivered here; call [advanceDisabledModules] with the result once that manifest is confirmed.
     */
    fun computeDisabledModules(): DisabledModulesSnapshot? {
        val modules =
            disabledModulesByName.entries
                .filter { !it.value.manifestIncluded }
                .map { (module, entry) -> DisabledEndpointModule(module, entry.reason, entry.disabledAt, entry.kind) }
        return if (modules.isEmpty()) null else DisabledModulesSnapshot(modules)
    }

    /**
     * Marks every module [snapshot] named as delivered. Call it only once that manifest is
     * confirmed.
     */
    fun advanceDisabledModules(snapshot: DisabledModulesSnapshot) {
        for (module in snapshot.modules) {
            disabledModulesByName[module.module]?.manifestIncluded = true
        }
    }

    private fun EndpointEntry.toLocation(): EndpointLocation =
        EndpointLocation(
            endpointId = endpointId,
            verb = verb,
            routeTemplate = routeTemplate,
            verbatimTemplate = verbatimTemplate,
            framework = framework,
            discoverySource = discoverySource,
            handlerClass = handlerClass,
            handlerMethod = handlerMethod,
            handlerDescriptor = handlerDescriptor,
            templateKind = templateKind,
        )
}
