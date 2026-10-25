package dev.otherlode.registry

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
 * The method or object a framework invokes for an endpoint. [methodName] and [descriptor] are
 * null when only the handler object's class is known, which is what a framework hands over at
 * first dispatch for a handler whose registration API never named a method.
 */
data class HandlerRef(
    val className: String,
    val methodName: String? = null,
    val descriptor: String? = null,
)

/** Stands in for the route object an [EndpointRegistry] key list holds, once that object is held apart. */
private val ANCHOR = Any()
private val EXTENSION_ROUTE = Regex("/\\*\\.[^/*]+")

/**
 * Tracks one probe per HTTP endpoint, counted at the point a framework matches a request to it,
 * before the handler runs. Endpoints need their own probe because a method probe alone cannot say
 * "GET /promo was never served", since a handler can back two endpoints, be a lambda the framework
 * invokes through a hidden class, or live outside `includePackages`.
 *
 * Identity is `(verb, route template, template kind)`, the template normalised by [RouteTemplateNormalizer]
 * only when it is a path.
 * Two registrations that normalise to the same identity, even from different framework objects,
 * are one endpoint with one [EndpointEntry.endpointId] and one count. A regex or servlet pattern
 * template bypasses the normaliser and is kept exactly as given, so it never merges with a template
 * of another kind that reads the same.
 *
 * A framework module also supplies one or more dispatch keys that identify the framework's own
 * route (a Ktor route node, a JDK `HttpContext`, a list of a Spring pattern and verb). [lookup]
 * resolves a key to its entry on the request path, so normalisation runs once per endpoint rather
 * than once per request. A key made of values (a string, or a list of strings) is compared by
 * `equals`. Any other key object is a framework's own route object: it is compared by identity
 * and held weakly, so a routing tree a test or a devtools restart throws away is not kept alive
 * by its bindings. A list holding one such object beside values (Spring's functional key) is
 * held weakly on that object and compared by value otherwise. A key whose object was collected
 * is unbound; its endpoint stays.
 *
 * Follows the same snapshot pattern as [ProbeRegistry]: [computeDeltas]/[advanceDeltas] and
 * [computeManifestEntries]/[advanceManifest] stage state onto the returned snapshot rather than
 * directly onto the registry, so two sends in flight at once (a scheduled flush and a shutdown
 * flush) cannot mark each other's unconfirmed work as delivered.
 *
 * Unlike a class's probe locations, which are sent once and never again, an endpoint record can
 * change after it is first delivered (its discovery source upgrades, or a handler join is
 * learned later). Each entry therefore carries a version bumped on every such change; a manifest
 * snapshot remembers the version it staged per entry, and [advanceManifest] only ever raises
 * `deliveredVersion`, never lowers it. A change landing while a send is in flight is still picked
 * up by the next compute, since the version it bumped to was never staged by that in-flight send.
 *
 * @param dispatchLimit how many identities each framework module may create at dispatch, and how
 *   many request-built keys it may bind there.
 */
class EndpointRegistry(
    private val dispatchLimit: Int = OtherlodeEndpoints.DISPATCH_LIMIT,
) {
    private val log = System.getLogger(EndpointRegistry::class.java.name)

    private data class Identity(
        val verb: String,
        val routeTemplate: String,
        val templateKind: EndpointTemplateKind,
    )

    /**
     * One tracked endpoint. Instantiable only by [EndpointRegistry]: the constructor is
     * `internal`, the same pattern [ProbeRegistry.DeltaSnapshot] uses to keep a type public for
     * callers to read while construction stays with the registry that owns its invariants.
     */
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
         * Hit count, incremented once per matched request. A plain, non-atomic increment: the
         * same accepted lost-update tradeoff [ProbeRegistry]'s count arrays make, since an
         * occasional lost count under race does not matter for a total exported once per flush.
         */
        @JvmField
        var count: Long = 0

        /** Increments [count]. No synchronisation, so this never blocks the request path. */
        fun hit() {
            count++
        }

        /** Bumped on any change a collector needs to see again: creation, a discovery-source upgrade, or a new handler join. */
        @Volatile
        internal var version: Long = 1

        /** The highest [version] a confirmed manifest send has carried for this entry. */
        @Volatile
        internal var deliveredVersion: Long = 0

        /** The last cumulative [count] successfully delivered to the collector. */
        @Volatile
        internal var lastSent: Long = 0

        internal var firstSeenAt: Long = 0

        internal var decreaseWarned: Boolean = false

        /** Sequence number of the newest [DeltaSnapshot] applied to [lastSent]; see [advanceDeltas]. */
        internal var lastAppliedDeltaSequence: Long = 0
    }

    private class DispatchCount {
        val created = AtomicInteger(0)
        val boundKeys = AtomicInteger(0)
        val createdWarned = AtomicBoolean(false)
        val keysWarned = AtomicBoolean(false)
    }

    private class DisabledModuleEntry(
        val reason: String,
        val kind: DisabledEndpointModuleKind,
        val disabledAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    /**
     * One computed delta batch, together with the exact per-entry counts it was built from.
     * [advanceDeltas] takes this back rather than reading staging state off the registry, so two
     * flushes can be in flight at once without one marking the other's hits as delivered.
     */
    class DeltaSnapshot internal constructor(
        val deltas: List<EndpointDelta>,
        internal val sequence: Long,
        internal val staged: List<Pair<EndpointEntry, Long>>,
    )

    /**
     * One computed manifest chunk, together with the entries it staged. [advanceManifest] marks
     * exactly those as delivered, each endpoint at the version this snapshot staged, never at
     * whatever version it may have reached since.
     */
    class ManifestSnapshot internal constructor(
        val endpoints: List<EndpointLocation>,
        internal val stagedEndpoints: List<Pair<EndpointEntry, Long>>,
    )

    /** The disabled modules not yet delivered; [advanceDisabledModules] marks exactly these as delivered. */
    class DisabledModulesSnapshot internal constructor(
        val modules: List<DisabledEndpointModule>,
    )

    private val entriesByIdentity = ConcurrentHashMap<Identity, EndpointEntry>()
    private val keyToEntry = ConcurrentHashMap<Any, EndpointEntry>()

    /** Where a route object's [WeakRouteKey] is queued once the object is collected. */
    private val collectedKeys = ReferenceQueue<Any>()
    private val disabledModulesByName = ConcurrentHashMap<String, DisabledModuleEntry>()
    private val dispatchCounts = ConcurrentHashMap<String, DispatchCount>()
    private val nextEndpointId = AtomicInteger(0)
    private val nextDeltaSequence = AtomicLong(0)

    /**
     * Resolves a dispatch key to its entry. This is the hot-path lookup a dispatch hook calls on
     * every request: one [ConcurrentHashMap.get], null when [key] is not bound to any entry yet.
     * A value key is looked up as it is; a route object key costs one small wrapper allocation.
     */
    fun lookup(key: Any): EndpointEntry? = keyToEntry[lookupKeyOf(key)]

    /**
     * Binds [key] to [entry], first unbinding every key whose route object was collected. Runs on
     * registration and dispatch discovery, never on the per-request path.
     */
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

    /** [key] as [keyToEntry] stores it: values as they are, a route object held weakly. */
    private fun storedKeyOf(key: Any): Any {
        if (isValue(key)) return key
        if (key is List<*>) {
            val anchor = key.singleOrNull { !isValue(it) } ?: return key
            val weak = WeakRouteKey(anchor, collectedKeys)
            return AnchoredKey(weak, key.map { if (it === anchor) ANCHOR else it }).also { weak.mapKey = it }
        }
        return WeakRouteKey(key, collectedKeys).also { it.mapKey = it }
    }

    /** [key] as a lookup in [keyToEntry] matches it, without a weak reference. */
    private fun lookupKeyOf(key: Any): Any {
        if (isValue(key)) return key
        if (key is List<*>) {
            val anchor = key.singleOrNull { !isValue(it) } ?: return key
            return AnchoredKey(StrongRouteKey(anchor), key.map { if (it === anchor) ANCHOR else it })
        }
        return StrongRouteKey(key)
    }

    private fun isValue(key: Any?): Boolean =
        key == null || key is String || key is Number || key is Boolean || key is Char || key is Enum<*> ||
            (key is List<*> && key.all(::isValue))

    /** A route object, compared by identity. [WeakRouteKey] stores one and [StrongRouteKey] looks one up; they compare equal. */
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

        /** The [keyToEntry] key this reference is part of, removed once the route is collected. */
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

    /** A key list with its one route object taken out as [anchor] and replaced by [ANCHOR] in [values]. */
    private class AnchoredKey(
        val anchor: RouteKey,
        val values: List<Any?>,
    ) {
        override fun hashCode(): Int = 31 * anchor.hashCode() + values.hashCode()

        override fun equals(other: Any?): Boolean = other is AnchoredKey && anchor == other.anchor && values == other.values
    }

    /** How many dispatch keys are bound to an entry, which a test reads to check keys do not pile up per request. */
    fun boundKeyCount(): Int = keyToEntry.size

    /**
     * Records an endpoint from a framework's own registration hook (Spring's handler-method
     * mapping, Ktor's `handle`, `HttpServer.createContext`). Normalises [verb] and
     * [verbatimTemplate] (with [contextPath] folded in first) into an identity, or, when [templateKind] is not
     * [EndpointTemplateKind.PATH], keeps [verbatimTemplate] as given and ignores [contextPath], finds or creates
     * the entry for that identity, binds [key] to it, and marks it discovered by registration.
     *
     * An entry already discovered by registration is left alone: nothing changed, so there is
     * nothing to re-send. An entry previously known only from a dispatch is upgraded, which bumps
     * its version so the next manifest compute includes it again with the corrected source.
     *
     * [handler], when given, is attached the same way [attachHandler] would.
     */
    fun register(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String? = null,
        handler: HandlerRef? = null,
        templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ): EndpointEntry {
        val entry = findOrCreate(framework, verb, verbatimTemplate, contextPath, templateKind)
        bind(key, entry)
        upgradeDiscoverySource(entry, EndpointDiscoverySource.REGISTRATION)
        if (handler != null) attachHandler(entry, handler)
        return entry
    }

    /**
     * Records an endpoint the moment a request dispatches to it, for a [key] [lookup] did not
     * resolve. Finds or creates the entry for the normalised identity, marking a newly created
     * one as discovered by dispatch, and binds [key] to it.
     *
     * [handlerClass] is attached the way [attachHandler] attaches any join: it never replaces a
     * join that names a method, so a registration hook's fuller join survives this class-only one.
     *
     * A module may create at most the registry's dispatch limit of identities this way. Past it,
     * a new identity is not created: this binds nothing and returns null, and the module logs one
     * warning. An identity that exists already is returned, and one declared by [register] is
     * never limited and does not count toward the limit. A module may also bind at most that many
     * request-built keys this way. Past that limit the entry is still returned, and the key is
     * resolved again on its next request.
     *
     * The caller calls [EndpointEntry.hit] itself once this returns; this method only resolves
     * the entry.
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
        if (!isValue(key) || lookup(key) != null || reserveDispatchKey(framework)) bind(key, entry)
        if (handlerClass != null) attachHandler(entry, HandlerRef(handlerClass))
        return entry
    }

    /**
     * Like [recordDispatch], but for a route bridge module that reads an identity another
     * instrumentation layer already resolved, rather than one it matched itself: the identity
     * might belong to an endpoint a framework module already owns. When a different
     * [framework] owns the normalised identity, this binds nothing and returns null, so the
     * caller counts nothing and the other module's entry is left untouched. A framework also owns
     * every verb on a template it serves with verb `*`, and a `HEAD` request on a template whose
     * `GET` endpoint it owns, since frameworks answer `HEAD` from the `GET` handler. Otherwise
     * this behaves exactly like [recordDispatch].
     *
     * A servlet context path still slips past this check: OpenTelemetry's route includes it and a
     * framework module's identity does not, so the bridge records a second endpoint for such a
     * route.
     *
     * A path template also reads as the servlet pattern OpenTelemetry's servlet instrumentation
     * built it from. It reports an extension mapping `*.ext` with a slash in front, and the
     * default mapping `/` as a slash and a star, or as `/` for a request on the context root. So
     * the template with a slash before an extension mapping is owned by the servlet pattern
     * `*.ext`, and the templates `/` and slash-star are owned by the servlet pattern `/`. A path
     * mapping slash-star and the context root mapping are path templates the servlet module
     * declares as such, and the ordinary identity match owns them.
     *
     * The ownership check and the eventual creation are not one atomic step: a second thread
     * racing to create the same identity under a different framework between the two could still
     * end up owning it. This is the same non-atomic tradeoff the rest of this registry already
     * accepts for a plain count race, and correcting it would need a lock this hot path does not
     * otherwise need.
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

    /** Takes one slot of [slots], or logs [warning] once through [warned] and reports false at the limit. */
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
     * Attaches [handler] to [entry]'s join if none is present, or upgrades a class-only join to a
     * full method join. A full join already present, meaning [EndpointEntry.handlerMethod] is
     * already set, is never overwritten: registration-time joins are trusted over anything a
     * later dispatch might offer.
     *
     * Any actual change bumps [EndpointEntry.version], so the entry is included in the next
     * manifest compute even if it was already delivered.
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
     * Records an endpoint module (one framework's registration/dispatch hooks) that is not
     * instrumenting: one that switched itself off, typically on a linkage failure against an
     * unexpected framework version, or one the configuration kept off. [kind] names which.
     * Idempotent per module name: a repeat call keeps the first
     * kind, reason and timestamp, the same as [ProbeRegistry.recordSkipped].
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

    /**
     * Compares each entry's live count against its last successfully sent value, and returns
     * batches of at most [maxPerBatch] endpoints whose count changed. Returns an empty list when
     * nothing changed: unlike [ProbeRegistry.computeDeltaBatches], an endpoint delta batch has no
     * liveness-heartbeat role, so there is no reason to send an empty one.
     *
     * Nothing is marked as sent here. Each returned [DeltaSnapshot] carries the counts it was
     * built from, and [advanceDeltas] must be called with it explicitly, only once that batch is
     * confirmed delivered.
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

    /**
     * Logs a one-time warning the first time an endpoint's count is seen to drop. Reporting the
     * lower value instead of silently dropping it means a future bug that does trigger this stays
     * visible, the same reasoning behind [ProbeRegistry]'s own decrease warning.
     */
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
     * confirmed delivered; a failed send must not call it, so the next attempt naturally reports
     * the live count again.
     *
     * Snapshots apply newest-wins per entry, not last-caller-wins: an older snapshot confirmed
     * after a newer one is ignored for that entry, so a confirmed higher count is never rolled
     * back to a stale lower one.
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
     * Returns chunks of at most [maxPerChunk] endpoints, covering every endpoint never delivered or
     * changed since it was last delivered (a discovery-source upgrade, a newly attached handler).
     * Returns an empty list when there is nothing to send. Disabled modules are not here; see
     * [computeDisabledModules].
     *
     * Nothing is marked as delivered here. Each returned [ManifestSnapshot] names the version it
     * staged per endpoint, and [advanceManifest] must be called with it explicitly, only once
     * that chunk is confirmed delivered.
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> {
        val pending = mutableListOf<Triple<EndpointEntry, Long, EndpointLocation>>()
        for (entry in entriesByIdentity.values) {
            val version = entry.version
            if (version <= entry.deliveredVersion) continue
            pending += Triple(entry, version, entry.toLocation())
        }
        return chunkedByWeight(pending, maxPerChunk, PayloadKind.MANIFEST, ChunkLimits.MANIFEST_FRAME_ALLOWANCE) {
            ProtoPayloadCodec.entryWeight(it.third)
        }.map { chunk -> ManifestSnapshot(chunk.map { it.third }, chunk.map { it.first to it.second }) }
    }

    /**
     * Marks every endpoint staged by [snapshot] as delivered, at the version [snapshot] staged,
     * using `max()` against whatever [EndpointEntry.deliveredVersion] already holds, so a change
     * that lands while this snapshot's send was in flight is still picked up by the next compute
     * rather than being marked as delivered by a send that never carried it.
     *
     * Call this only after that chunk is confirmed delivered. A failed send must not call it, so
     * the next attempt's compute naturally includes the same entries again.
     */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for ((entry, version) in snapshot.stagedEndpoints) {
            synchronized(entry) {
                if (version > entry.deliveredVersion) entry.deliveredVersion = version
            }
        }
    }

    /**
     * Every disabled module not yet delivered, or null when there is none. A module is sent on a
     * manifest of its own, ahead of any class chunk, because its record is the only thing that
     * tells a collector the endpoints earlier manifests declared are no longer counted. It is
     * never also part of an endpoint chunk. Nothing is marked as delivered here; call
     * [advanceDisabledModules] with the result once that manifest is confirmed.
     */
    fun computeDisabledModules(): DisabledModulesSnapshot? {
        val modules =
            disabledModulesByName.entries
                .filter { !it.value.manifestIncluded }
                .map { (module, entry) -> DisabledEndpointModule(module, entry.reason, entry.disabledAt, entry.kind) }
        return if (modules.isEmpty()) null else DisabledModulesSnapshot(modules)
    }

    /** Marks every module [snapshot] named as delivered. Call it only once that manifest is confirmed. */
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
