package dev.otherlode.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.UnreportedClass
import dev.otherlode.registry.RouteTemplateNormalizer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import dev.otherlode.export.GeneratedBy as WireGeneratedBy
import dev.otherlode.export.ProbeKind as WireProbeKind
import dev.otherlode.export.RoutineKind as WireRoutineKind
import dev.otherlode.export.UnreadShape as WireUnreadShape

/**
 * An embeddable collector that speaks the same wire protocol the Otherlode agent sends: delta
 * batches, probe manifests, and static baselines, all protobuf over plain HTTP. An adopter's test
 * runs the real agent against [exportUrl] and then asks this collector what it saw, instead of
 * querying any in-process registry.
 *
 * Every class name in this API is the dotted binary name the manifest carries: for example
 * `com.acme.OrderService`, `com.acme.OrdersKt` for a Kotlin file's top-level functions, or
 * `com.acme.Outer$Inner` for a nested class.
 *
 * A probe query for a class or method this collector has never heard of throws
 * [UnknownProbeException] rather than reading as "confirmed never hit". Collapsing "genuinely
 * dead" and "we have no idea" into the same `false` would be exactly the silent, confident, wrong
 * failure mode the rest of this agent is built to avoid.
 *
 * Endpoint queries ([wasCalled], [callCount], [neverCalled], [endpoints]) follow the same rule,
 * throwing [UnknownEndpointException] for a `(verb, route template)` no manifest has mentioned.
 * An endpoint's identity is the pair alone, never `endpoint_id`: `endpoint_id` is assigned
 * independently by each instance's own registry, so an endpoint registered by several instances
 * merges into one [EndpointRef] whose call count sums every instance's latest total, the same
 * cross-instance aggregation [hitCount] already does for method probes by class and method name.
 *
 * The findings ([neverHit], [neverHitRoutineOutcomes], [neverHitUnreadShapes], [neverInitialized],
 * [neverInstantiated], [unreachedClusters], [neverSupplied], [alwaysSupplied]) judge the merged hits
 * of every instance heard from, by name, as the server judges every in-scope instance: a probe,
 * class or call edge one instance reported is the same one in another, whatever class id each
 * assigned it. A branch outcome is the same outcome by its branch key when it has one, and by its
 * branch index only when it has none. A finding therefore names no instance.
 *
 * Dependency queries ([dependency], [unloadedDependencies], [unreferencedDependencies],
 * [unreachedDependencies], [absentReferences]) apply the collector's dependency rules within this
 * one test JVM and follow the same rule again: a dependency no manifest has listed throws
 * [UnknownDependencyException], and a question the data cannot answer yet, or at all without a
 * complete static baseline, throws [IllegalStateException] instead of returning an empty list.
 * The agent sends a dependency's entry only after its loaded-class counts have been delivered, so
 * [dependency] answers once the entry arrives. The four list queries answer only once every
 * instance heard from has sent `dependencies_listed`; [awaitDependenciesListed] waits for that.
 *
 * Every probe, endpoint and dependency is keyed on its instance id alone, not on the run id every
 * payload carries. That is the same as keying on the run only while each instance id names one
 * run, which holds when this collector hears from the one agent in its own test JVM. A payload with
 * an empty run id, or with a second run id under an instance id this collector has already heard
 * from, would break that assumption. Such a payload is answered 400, nothing from it is kept, and
 * the reason is recorded in [rejectedPayloads]. A payload that does not decode, or that fails
 * while it is applied, is treated the same way, and so is one a collector forwarded after stripping
 * fields it did not know: this collector expects the agent's payloads directly and whole. So is a payload whose agent version
 * differs from this testkit's, when both are known: the two are released as one version. From then on [awaitSettled] and every other
 * query and wait throw [IllegalStateException] listing the recorded reasons, so a test fails
 * even if it never calls [rejectedPayloads].
 *
 * Close this with [close], typically from a `.use { }` block, once a test is done with it.
 */
public class OtherlodeTestCollector internal constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val servesOneJvm: Boolean,
    private val testkitVersion: String,
) : AutoCloseable {
    private data class ProbeKey(
        val serviceInstanceId: String,
        val classId: Int,
        val probeIndex: Int,
    )

    private data class StoredProbe(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
        val line: Int,
        val kind: WireProbeKind,
        val branchIndex: Int?,
        val inline: Boolean,
        val parameterIndex: Int? = null,
        val parameterName: String? = null,
        val overridable: Boolean = false,
        val targetClassName: String? = null,
        val calls: List<CallEdge> = emptyList(),
        val inlinedFromClassName: String? = null,
        val generatedBy: WireGeneratedBy = WireGeneratedBy.NONE,
        val referencedClasses: List<String> = emptyList(),
        val branchKey: String? = null,
        val branchSites: List<BranchSite> = emptyList(),
        val static: Boolean = false,
        val lambdaBody: Boolean = false,
        val parameterNames: List<String> = emptyList(),
        val genericSignature: String = "",
        val extensionReceiver: Boolean = false,
        val unreadShape: WireUnreadShape = WireUnreadShape.NONE,
    ) {
        /** Whether the method is generated or an unread shape: no node, but looked through. */
        fun isLookedThrough(): Boolean = generatedBy != WireGeneratedBy.NONE || unreadShape != WireUnreadShape.NONE
    }

    /** A class's superclass and direct interfaces, by class name, which call-edge resolution walks. */
    private data class SupertypesInfo(
        val superClassName: String?,
        val interfaceNames: List<String>,
    )

    /** One declared method read from a complete static baseline scan. */
    private data class DeclaredMethodInfo(
        val methodName: String,
        val methodDescriptor: String,
        val inline: Boolean,
        val calls: List<CallEdge>,
        val generatedBy: WireGeneratedBy = WireGeneratedBy.NONE,
        val referencedClasses: List<String> = emptyList(),
        val static: Boolean = false,
        val parameterNames: List<String> = emptyList(),
        val genericSignature: String = "",
        val extensionReceiver: Boolean = false,
        val unreadShape: WireUnreadShape = WireUnreadShape.NONE,
        val branchSites: List<BranchSite> = emptyList(),
    ) {
        /** Whether the method is generated or an unread shape: no node, but looked through. */
        fun isLookedThrough(): Boolean = generatedBy != WireGeneratedBy.NONE || unreadShape != WireUnreadShape.NONE
    }

    /**
     * A declared class's methods and supertypes, read from a complete static baseline scan.
     * [serviceInstanceId] names the instance whose scan produced this record, since a declared call
     * edge's guard is a branch index in that instance's copy of the class.
     */
    private data class DeclaredClassInfo(
        val serviceInstanceId: String,
        val methods: List<DeclaredMethodInfo>,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val kotlinKind: KotlinKind = KotlinKind.NONE,
    )

    /** One node of the call graph [unreachedClusters] resolves: a probed method, by identity alone. */
    private data class NodeKey(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
    )

    /**
     * A [NodeKey]'s reporting fields and raw, unresolved outgoing call edges. [neverLoaded] marks a
     * node that exists only because a complete static baseline declared it; such a node has
     * [hits] fixed at zero, since the dynamic tier never registered its class at all.
     */
    private data class NodeInfo(
        val line: Int,
        val neverLoaded: Boolean,
        val hits: Long,
        val edges: Set<GuardedEdge>,
    )

    /**
     * One outcome across instances, as the server's `probe_locations` names it: by [branchKey] when
     * the outcome has one, otherwise by [branchIndex]. [branchIndex] is null whenever [branchKey] is set,
     * since an index only names an outcome within one build.
     */
    private data class OutcomeId(
        val branchKey: String?,
        val branchIndex: Int?,
    )

    /**
     * A raw call edge with its guard resolved to the outcome it names: in the instance that reported
     * a manifest edge, and for a declared edge in the scan's instance, else by the site identity
     * [declaredGuard] matches. [edge] has its own raw guard cleared, since that index means nothing
     * outside one instance. [guard] is null for an edge with no guard or whose guard names no BRANCH
     * probe there.
     */
    private data class GuardedEdge(
        val edge: CallEdge,
        val guard: OutcomeId?,
    )

    /** One resolved call out of a method: the callee node and the outcome guarding the call. */
    private data class ResolvedCall(
        val callee: NodeKey,
        val guard: OutcomeId?,
    )

    private class CallGraph(
        val nodes: Map<NodeKey, NodeInfo>,
        val calls: Map<NodeKey, Set<ResolvedCall>>,
        /**
         * Each generated method with hits, which is no node but is a caller that ran: its calls are
         * in [calls], so what it reaches has a hit caller and joins no other cluster.
         */
        val hitGeneratedCallers: Map<NodeKey, NodeInfo> = emptyMap(),
    ) {
        /** The record behind [key], a node or a hit generated caller. */
        fun infoOf(key: NodeKey): NodeInfo = nodes[key] ?: hitGeneratedCallers.getValue(key)
    }

    /**
     * One node of the cluster graph [unreachedClusters] grows clusters over: a method, or, when
     * [outcome] is set, an outcome node in that method: a never-taken outcome, standing for the
     * code behind it. When [isClass] is true it is a class node, and [method] holds only the class
     * name. See [classNode].
     */
    private data class ClusterNode(
        val method: NodeKey,
        val outcome: OutcomeId? = null,
        val isClass: Boolean = false,
    )

    /** One outcome of one instance's method, by its branch index in that instance. */
    private data class OutcomeKey(
        val serviceInstanceId: String,
        val method: NodeKey,
        val branchIndex: Int,
    )

    /**
     * A class node: a class that holds a class finding, standing for the never-hit [methods] the
     * finding covers. Those methods are never nodes of their own, since they can only run through
     * the class.
     */
    private class ClassNode(
        val finding: ClassFinding,
        val methods: List<NodeKey>,
    )

    /**
     * One judgeable METHOD probe location merged across instances: [hits] summed, and [static] and
     * [lambdaBody] true when any instance's probe said so.
     */
    private data class JudgeableMethod(
        val key: NodeKey,
        val hits: Long,
        val static: Boolean,
        val lambdaBody: Boolean,
    )

    /**
     * What the class-finding rules say about the loaded classes. [findings] holds each class that is
     * never initialised or never instantiated. [covered] names the never-hit methods a finding
     * covers, which can only run through it, lambda bodies that fold into it included.
     * [inNeverHitCode] names the lambda bodies that fold into never-hit methods that are rows of
     * their own. [constructed] holds each class one of whose constructors ran, so a never-hit
     * constructor of it is an unused overload.
     */
    private class ClassJudgement(
        val findings: Map<String, ClassFinding>,
        val covered: Map<String, List<NodeKey>>,
        val inNeverHitCode: Set<NodeKey>,
        val constructed: Set<String>,
    ) {
        val coveredMethods: Set<NodeKey> = covered.values.flatten().toSet()

        /** Every method that is not a row of its own, since a class finding or a never-hit method covers it. */
        val foldedMethods: Set<NodeKey> = coveredMethods + inNeverHitCode
    }

    /**
     * An outcome node: a judgeable outcome with no hits in a method with hits, merged across
     * instances by its method and [OutcomeId]. [ref] is the merged location's ref. [site] is the
     * site that lists the outcome on its method's METHOD probe in the instance of the newest copy,
     * its outcomes and guard renumbered to the merged branch indexes, or null when no manifest
     * listed one. [siteGuard] is that site's guard, resolved in the same instance to the outcome it
     * names, or null when the site has none or it names no BRANCH probe.
     */
    private data class OutcomeNode(
        val ref: ProbeRef,
        val site: BranchSite?,
        val siteGuard: OutcomeId?,
    )

    /** Each node's callers and callees, over method, outcome and class nodes alike. */
    private class ClusterGraph(
        val callersOf: Map<ClusterNode, Set<ClusterNode>>,
        val calleesOf: Map<ClusterNode, Set<ClusterNode>>,
    )

    private data class ScanKey(
        val serviceInstanceId: String,
        val scannedAt: Long,
    )

    /**
     * Groups every omission location naming one optional parameter across instances: see
     * [optionalParameterFindings]. [targetClassName] is the effective target class, already
     * resolved with `targetClassName ?: className`, not the raw wire value.
     */
    private data class OmissionTargetKey(
        val targetClassName: String,
        val methodName: String,
        val methodDescriptor: String,
        val parameterIndex: Int?,
    )

    /** Cross-instance endpoint identity: the pair alone, never `endpoint_id`. See the class KDoc. */
    private data class EndpointIdentity(
        val verb: String,
        val routeTemplate: String,
    )

    private data class InstanceEndpointKey(
        val serviceInstanceId: String,
        val endpointId: Int,
    )

    /**
     * A probe's identity across instances and copies of its class, as the server's `probe_locations`
     * groups it. [outcome] is null for any kind but BRANCH.
     */
    private data class LocationKey(
        val method: NodeKey,
        val kind: WireProbeKind,
        val parameterIndex: Int?,
        val outcome: OutcomeId?,
    )

    /**
     * Every probe sharing one [LocationKey], merged the way the server's `probe_locations` merges
     * its copies. [hits] sums every copy. [inline], [static] and [lambdaBody] hold when any copy says
     * so, [generatedBy] and [inlinedFromClassName] are the greatest any copy gives, and [overridable]
     * holds when any copy says so. [routine] and [unreadShape] are the first non-default value in
     * newest-first order. [newest] is the copy of the newest instance, which supplies [line] and
     * [parameterName]. [branchIndex] is the lowest any copy gives.
     */
    private class MergedLocation(
        val key: LocationKey,
        val members: List<Map.Entry<ProbeKey, StoredProbe>>,
        val hits: Long,
        val line: Int,
        val branchIndex: Int?,
        val inline: Boolean,
        val static: Boolean,
        val lambdaBody: Boolean,
        val overridable: Boolean,
        val generatedBy: WireGeneratedBy,
        val inlinedFromClassName: String?,
        val routine: WireRoutineKind,
        val unreadShape: WireUnreadShape,
        val targetClassName: String?,
        val parameterName: String?,
    ) {
        val newest: Map.Entry<ProbeKey, StoredProbe> get() = members.first()

        /** Whether the location is generated or an unread shape: no node, but looked through. */
        val isLookedThrough: Boolean get() = generatedBy != WireGeneratedBy.NONE || unreadShape != WireUnreadShape.NONE
    }

    /** Scopes a per-instance id (`dependency_id`, `class_id`) or a class name to the instance that reported it. */
    private data class InstanceKey<T>(
        val serviceInstanceId: String,
        val id: T,
    )

    /**
     * One instance's static-baseline references for one declared class: the class-level list and
     * each declared method's. Kept per instance, since unreferenced is told apart from unreached
     * only with a complete baseline from each instance that lists the dependency.
     */
    private data class BaselineReferences(
        val classReferences: List<String>,
        val methods: List<DeclaredMethodInfo>,
    )

    /** Accumulates one static baseline scan's chunks. Only merged into [consultedDeclaredNames] once complete. */
    private class ScanProgress(
        val chunkCount: Int,
    ) {
        val received: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val declaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Declared classes whose every declared method is inline or generated; see
         * [OtherlodeTestCollector.neverLoaded].
         */
        val allInlineOrGeneratedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Methods and supertypes per declared class; only merged into [consultedDeclaredClasses] once complete. */
        val declaredClasses: MutableMap<String, DeclaredClassInfo> = ConcurrentHashMap()
        val complete: Boolean get() = received.size >= chunkCount
    }

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    /**
     * Held for writing while a handler applies one payload, and for reading by every query, so a
     * query never sees part of a payload: a probe without its class's supertypes, say. A handler
     * signals waiters only after releasing it, so it never waits on [lock] while holding this.
     */
    private val stateLock = ReentrantReadWriteLock()

    /** Runs [block] holding [stateLock] for writing, as a handler applying a payload does; for a test. */
    internal fun <T> whileApplying(block: () -> T): T = stateLock.write(block)

    private val deltaBatchSeq = AtomicLong(0)

    /** Orders a [ProbeRef] by class name, method name, line, then branch index, the same order [neverHit] sorts by. */
    private val probeRefComparator: Comparator<ProbeRef> =
        compareBy({ it.className }, { it.methodName }, { it.line }, {
            it.branchIndex
                ?: -1
        })

    private val probesByKey = ConcurrentHashMap<ProbeKey, StoredProbe>()
    private val nameIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()

    /**
     * Omission probes indexed by their target's class, `targetClassName ?: className`, rather than
     * by the probe's own declared class. A Scala constructor default getter's own class is the
     * companion module (`Cc$`), but a query names the constructor's own class (`Cc`).
     */
    private val omissionTargetIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()
    private val hitsByKey = ConcurrentHashMap<ProbeKey, Long>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedClass>()

    /** A class's supertypes, by name, from any manifest. Populated alongside its probes; see [handleManifest]. */
    private val supertypesByClassName = ConcurrentHashMap<String, SupertypesInfo>()

    /**
     * A class's Kotlin kind, by name, from any manifest, for [kotlinKind]. Populated the same way
     * as [supertypesByClassName].
     */
    private val kotlinKindByClassName = ConcurrentHashMap<String, KotlinKind>()

    /** Classes a sweep found loaded where no transformer saw them, by name, from any manifest. */
    private val unreportedByClassName = ConcurrentHashMap<String, UnreportedClass>()

    /** Declared classes from every complete static baseline scan, by name. See [handleStaticBaseline]. */
    private val consultedDeclaredClasses = ConcurrentHashMap<String, DeclaredClassInfo>()

    /**
     * Every class name any manifest has ever mentioned: with probes, as skipped, or as unreported.
     * All three mean the class loaded, which is what a "never loaded" claim asks about.
     */
    private val dynamicallyKnownClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Instance ids whose shutdown hook has sent a delta batch with `final_flush` set. See [endedCleanly]. */
    private val instancesThatEndedCleanly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()
    private val completedScans: MutableSet<ScanKey> = ConcurrentHashMap.newKeySet()
    private val consultedDeclaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Declared classes, from a complete scan, whose every declared method is inline or generated. */
    private val consultedAllInlineOrGeneratedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Latest delivered record per endpoint identity, across every instance; see [handleManifest]. */
    private val endpointRefsByIdentity = ConcurrentHashMap<EndpointIdentity, EndpointRef>()

    /** Every `(instance, endpoint_id)` ever reported for an identity, so [callCount] can sum each instance's latest total. */
    private val endpointKeysByIdentity = ConcurrentHashMap<EndpointIdentity, MutableSet<InstanceEndpointKey>>()
    private val endpointHitsByKey = ConcurrentHashMap<InstanceEndpointKey, Long>()
    private val disabledEndpointModulesByName = ConcurrentHashMap<String, DisabledEndpointModule>()

    // Dependency usage. Everything is per instance: dependency_id and class_id are assigned by each
    // instance's own registry.
    private val dependencyLocations = ConcurrentHashMap<InstanceKey<Int>, DependencyView>()

    private val loadedClassesTotals = ConcurrentHashMap<InstanceKey<Int>, Long>()
    private val externalClassesByName = ConcurrentHashMap<InstanceKey<String>, ExternalClassView>()
    private val classLevelReferences = ConcurrentHashMap<InstanceKey<Int>, List<String>>()
    private val classNamesByClassId = ConcurrentHashMap<InstanceKey<Int>, String>()
    private val baselineReferences = ConcurrentHashMap<InstanceKey<String>, BaselineReferences>()
    private val instancesRecordingReferences: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every instance that sent a manifest with `dependencies_listed` set; see [awaitDependenciesListed]. */
    private val instancesWithDependenciesListed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val instanceIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Per instance, every class a manifest named as probed, skipped or unreported: every class that loaded there. */
    private val loadedClassNamesByInstance = ConcurrentHashMap<String, MutableSet<String>>()

    /** The one run id accepted per instance id; see the class doc and [rejectionFor]. */
    private val runIdByInstance = ConcurrentHashMap<String, String>()

    /** The order each instance was first heard from, later is larger: the testkit's stand-in for the server's run start. */
    private val instanceRanks = ConcurrentHashMap<String, Long>()
    private val instanceRankSeq = AtomicLong(0)

    /** The agent version each instance's accepted payloads named; empty when the agent did not know its own. */
    private val agentVersionByInstance = ConcurrentHashMap<String, String>()
    private val rejections = CopyOnWriteArrayList<String>()

    @Volatile
    private var closed = false

    /** The one instance a collector that [servesOneJvm] accepts: the first it heard from. */
    private val onlyInstance = AtomicReference<String?>()

    /** Base URL to pass as an agent's `exportUrl=` option, for example `http://localhost:54321`. */
    public val exportUrl: String = "http://localhost:${server.address.port}"

    /**
     * Blocks until a delta batch arrives that was received after this call began, including an
     * empty one: the agent sends a delta batch on every flush tick, even when nothing changed, as
     * a liveness heartbeat. Throws [TimeoutException] if [timeout] elapses first.
     */
    @Throws(TimeoutException::class)
    public fun awaitNextFlush(timeout: Duration) {
        checkNoRejections()
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "no delta batch arrived within $timeout") { deltaBatchSeq.get() > start }
    }

    /**
     * Blocks until two delta batches, from any instance, have arrived after this call began.
     * Waiting for two, not one, closes two gaps a single [awaitNextFlush] leaves open: a flush
     * can already be mid-compute when the caller's last action completes, so it can land without
     * that action's hits, and the same flush tick sends its manifest and its delta batch
     * concurrently, so a probe a caller just triggered for the first time may not have a manifest
     * entry yet even once its delta batch arrives. A second batch means at least one full tick
     * started after the action, and gives that tick's concurrent manifest send a whole interval
     * to land. A test that needs the manifest entry itself, not just the count, can still call
     * [awaitProbe] or [awaitEndpoint] for that exact guarantee.
     *
     * Throws [TimeoutException] if [timeout] elapses before two batches arrive.
     */
    @Throws(TimeoutException::class)
    public fun awaitSettled(timeout: Duration) {
        checkNoRejections()
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "fewer than two delta batches arrived within $timeout") { deltaBatchSeq.get() >= start + 2 }
    }

    /**
     * Blocks until some manifest, from any instance, has mentioned a probe for [className] and
     * [methodName]. A manifest and a delta batch from the same flush tick are sent concurrently by
     * the agent, so a newly loaded class's manifest entry can land after that tick's delta batch;
     * this lets a test wait for the manifest specifically. Throws [TimeoutException] if [timeout]
     * elapses first.
     */
    @Throws(TimeoutException::class)
    public fun awaitProbe(
        className: String,
        methodName: String,
        timeout: Duration,
    ) {
        checkNoRejections()
        awaitUntil(timeout, "no manifest ever mentioned $className#$methodName within $timeout") {
            nameIndex[className]?.any { probesByKey[it]?.methodName == methodName } == true
        }
    }

    private fun awaitUntil(
        timeout: Duration,
        timeoutMessage: String,
        predicate: () -> Boolean,
    ) = awaitUntil(timeout, { timeoutMessage }, predicate)

    private fun awaitUntil(
        timeout: Duration,
        timeoutMessage: () -> String,
        predicate: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (!predicate()) {
                // A rejection wakes this wait, so it fails at once rather than at the timeout.
                checkNoRejections()
                check(!closed) { "this collector was closed while waiting: ${timeoutMessage()}" }
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException(timeoutMessage())
                condition.awaitNanos(remaining)
            }
        }
    }

    /**
     * True if any instance ever reported a nonzero `hits_total` for a METHOD-kind probe matching
     * [className] and [methodName]. `methodDescriptor` left null matches any overload.
     *
     * Throws [UnknownProbeException] if no such probe was ever declared; see that type's doc for
     * the four cases it distinguishes.
     */
    @JvmOverloads
    public fun wasHit(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Boolean = checked { findMethodProbes(className, methodName, methodDescriptor).any { (hitsByKey[it] ?: 0L) > 0L } }

    /**
     * Sums, over every matching METHOD-kind probe and every instance, that instance's highest
     * delivered `hits_total` for the probe. `methodDescriptor` left null sums every overload;
     * given, it isolates one.
     *
     * Throws [UnknownProbeException] if no such probe was ever declared.
     */
    @JvmOverloads
    public fun hitCount(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Long = checked { findMethodProbes(className, methodName, methodDescriptor).sumOf { hitsByKey[it] ?: 0L } }

    /**
     * Sums, over the optional parameter at [parameterIndex] of [methodName], every omission any
     * instance reported, each instance's count taken at its highest the way [hitCount] takes it.
     * `methodDescriptor` left null matches any overload declaring an optional parameter at that
     * index; given, it isolates one.
     *
     * Throws [UnknownProbeException] if no such omission probe was ever declared.
     */
    @JvmOverloads
    public fun omissionCount(
        className: String,
        methodName: String,
        parameterIndex: Int,
        methodDescriptor: String? = null,
    ): Long =
        checked {
            findOmissionProbes(className, methodName, methodDescriptor, "index $parameterIndex") { it.parameterIndex == parameterIndex }
                .sumOf { hitsByKey[it] ?: 0L }
        }

    /** Like [omissionCount], but selects the optional parameter by [parameterName] instead of index. */
    @JvmOverloads
    public fun omissionCount(
        className: String,
        methodName: String,
        parameterName: String,
        methodDescriptor: String? = null,
    ): Long =
        checked {
            findOmissionProbes(className, methodName, methodDescriptor, "name \"$parameterName\"") { it.parameterName == parameterName }
                .sumOf { hitsByKey[it] ?: 0L }
        }

    private fun findOmissionProbes(
        className: String,
        methodName: String,
        methodDescriptor: String?,
        parameterDescription: String,
        matchesParameter: (StoredProbe) -> Boolean,
    ): List<ProbeKey> {
        val matches =
            omissionTargetIndex[className]?.filter { key ->
                val probe = probesByKey[key]
                probe != null &&
                    probe.methodName == methodName &&
                    (methodDescriptor == null || probe.methodDescriptor == methodDescriptor) &&
                    matchesParameter(probe)
            } ?: emptyList()
        if (matches.isNotEmpty()) return matches
        throw unknownOmissionProbe(className, methodName, methodDescriptor, parameterDescription)
    }

    private fun unknownOmissionProbe(
        className: String,
        methodName: String,
        methodDescriptor: String?,
        parameterDescription: String,
    ): UnknownProbeException {
        unknownClass(className)?.let { return it }
        if (nameIndex.containsKey(className)) {
            val descriptorSuffix = methodDescriptor?.let { " $it" } ?: ""
            return UnknownProbeException(
                "$className: class is instrumented but has no omission probe for method " +
                    "$methodName$descriptorSuffix, parameter $parameterDescription",
            )
        }
        return UnknownProbeException(
            "$className: never mentioned by any manifest or static baseline " +
                "(not matched by includePackages, misspelled, or not loaded yet)",
        )
    }

    /**
     * Every optional parameter whose combined omission total, summed over every instance, equals its
     * target's hit total summed over every instance: every caller took the default, so the parameter
     * can go. Each instance's omissions never exceed its own target hits, so equal sums mean equal
     * counts in every instance. "Combined" matters because one parameter can carry more than one
     * omission probe: a Scala constructor default gets both a module getter on the companion class
     * and that class's own static forwarder, both resolving to the same target, so their omissions
     * are summed and judged once rather than each read on its own; see [omissionCount]. One row per
     * target class, method, descriptor and parameter index, however many instances reported it.
     *
     * Claimed only when no probe naming the parameter is overridable, since an overridable target's
     * omissions are spread across whichever override actually ran, which the manifest cannot relate
     * back to one total. A target with no method probe at all (an abstract interface method) is
     * skipped, and so is an inline target, the same reason [neverHit] excludes one. When instances
     * disagree about a probe's inline, generated or unread mark, the row is skipped, since the
     * server's merge keeps any such mark. The row's line is the highest of the omission probes'
     * lines, each as its newest instance reports it, leaving out `-1`; it is `-1` when every one is.
     */
    public fun neverSupplied(): List<OptionalParameterRef> =
        checked {
            optionalParameterFindings { omitted, targetHits, overridable -> !overridable && omitted == targetHits }
        }

    /**
     * Every optional parameter whose combined omission total, summed over every instance, stayed at
     * zero while its target was called at least once in some instance: the default value is dead.
     * See [neverSupplied] for why "combined" matters and how instances merge. Claimed for any
     * target, overridable or not. A target with no
     * method probe at all, or an inline target, is skipped, the same as [neverSupplied].
     */
    public fun alwaysSupplied(): List<OptionalParameterRef> = checked { optionalParameterFindings { omitted, _, _ -> omitted == 0L } }

    /**
     * Groups every `OPTIONAL_ARGUMENT` location, merged across instances, by the parameter it
     * names: `(target class, target method name and descriptor, parameter index)`. A group can hold
     * more than one location when a target has more than one omission probe resolving to it, the
     * Scala constructor case [neverSupplied] documents. A group whose target is inline, generated
     * or an unread shape in any location gives no row. [claims] sees the group's summed omission
     * total, its target's summed hit total, and whether any location is overridable.
     */
    private fun optionalParameterFindings(
        claims: (omitted: Long, targetHits: Long, overridable: Boolean) -> Boolean,
    ): List<OptionalParameterRef> =
        mergedLocations()
            .filter { it.key.kind == WireProbeKind.OPTIONAL_ARGUMENT }
            .groupBy {
                OmissionTargetKey(
                    it.targetClassName ?: it.key.method.className,
                    it.key.method.methodName,
                    it.key.method.methodDescriptor,
                    it.key.parameterIndex,
                )
            }.mapNotNull { (groupKey, members) ->
                if (members.any { it.inline || it.isLookedThrough }) return@mapNotNull null
                val targetKeys =
                    findMethodProbesOrNull(groupKey.targetClassName, groupKey.methodName, groupKey.methodDescriptor)
                        ?: return@mapNotNull null
                val targetHits = targetKeys.sumOf { hitsByKey[it] ?: 0L }
                if (targetHits <= 0L) return@mapNotNull null
                val omitted = members.sumOf { it.hits }
                if (!claims(omitted, targetHits, members.any { it.overridable })) return@mapNotNull null
                OptionalParameterRef(
                    className = groupKey.targetClassName,
                    methodName = groupKey.methodName,
                    methodDescriptor = groupKey.methodDescriptor,
                    parameterIndex = groupKey.parameterIndex ?: -1,
                    parameterName = members.mapNotNull { it.parameterName }.maxOrNull() ?: "",
                    line = members.map { it.line }.filter { it >= 0 }.maxOrNull() ?: -1,
                    targetClassName = members.mapNotNull { it.targetClassName }.maxOrNull(),
                )
            }.sortedWith(compareBy({ it.className }, { it.methodName }, { it.parameterIndex }))

    private fun findMethodProbes(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): List<ProbeKey> =
        findMethodProbesOrNull(className, methodName, methodDescriptor)
            ?: throw unknownProbe(className, methodName, methodDescriptor)

    /** Like [findMethodProbes], but returns null instead of throwing when nothing matches. */
    private fun findMethodProbesOrNull(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): List<ProbeKey>? =
        nameIndex[className]
            ?.filter { key ->
                val probe = probesByKey[key]
                probe != null &&
                    probe.kind == WireProbeKind.METHOD &&
                    probe.methodName == methodName &&
                    (methodDescriptor == null || probe.methodDescriptor == methodDescriptor)
            }?.takeIf { it.isNotEmpty() }

    /**
     * Why [className] has no probes at all, when a manifest or a baseline says so: it was skipped,
     * a sweep found it loaded where no transformer saw it, or a complete baseline declared it and
     * no instance loaded it. Null when nothing explains it.
     */
    private fun unknownClass(className: String): UnknownProbeException? {
        skippedByClassName[className]?.let {
            return UnknownProbeException("$className: class was matched but could not be instrumented: ${it.reason}")
        }
        if (unreportedByClassName.containsKey(className)) {
            return UnknownProbeException(
                "$className: class loaded, but no transformer saw it, so it has no probes (the JVM hands a class " +
                    "loaded from inside another class's transform to no transformer)",
            )
        }
        if (className in consultedDeclaredNames && className !in dynamicallyKnownClassNames) {
            return UnknownProbeException("$className: class was declared by the static baseline but never loaded in any instance")
        }
        return null
    }

    private fun unknownProbe(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): UnknownProbeException {
        unknownClass(className)?.let { return it }
        if (nameIndex.containsKey(className)) {
            val descriptorSuffix = methodDescriptor?.let { " $it" } ?: ""
            return UnknownProbeException("$className: class is instrumented but has no probe for method $methodName$descriptorSuffix")
        }
        return UnknownProbeException(
            "$className: never mentioned by any manifest or static baseline " +
                "(not matched by includePackages, misspelled, or not loaded yet)",
        )
    }

    /**
     * Every probe, method or branch, that no instance reported a hit for, sorted by class name,
     * method name, line, then branch index. Probes merge across instances and across copies of a
     * class two loaders defined, as the server merges them: a method is one row by class, name and
     * descriptor, and a branch outcome is one row by its [ProbeRef.branchKey] when it has one and
     * by its branch index when it has none, with the hits of every copy summed. A hit in any
     * instance removes the row.
     *
     * Where copies disagree about a row's fields the server's choice applies. [ProbeRef.line] is the
     * newest instance's, [ProbeRef.branchIndex] is the lowest any copy reports, a row is inline when
     * any copy is, [ProbeRef.generatedBy] is the greatest mark any copy gives and
     * [ProbeRef.inlinedFromClassName] the greatest name. A row is routine or an unread shape when
     * any copy is, with the kind of the newest copy that gives one.
     *
     * A probe belonging to a Kotlin inline function, or a branch inside one, is left out: a
     * Kotlin caller copies the body into its own call site instead of invoking it, so a zero hit
     * total is not evidence the code never ran.
     *
     * A generated probe is left out too: the compiler will emit the method again regardless of
     * what the adopter does, so a zero hit total on a data class's `copy`, an enum's `values`, or
     * the like is not a finding the adopter can act on.
     *
     * An optional-argument probe is left out too, whatever its own count: an omission probe
     * reading zero means a parameter is never omitted, which is [alwaysSupplied], not dead code.
     *
     * The class-finding rules apply as well. A `<clinit>` is never listed, since it is a class
     * state. A constructor is listed only as an unused overload, when another constructor of its
     * class ran. A method that can only run through a class finding ([neverInitialized],
     * [neverInstantiated]) is left out, and so is a never-hit lambda body whose every creator is
     * such a method or a never-hit method listed here. A BRANCH probe in a method left out this way
     * is left out too.
     *
     * A branch site inside code that never ran folds into the row for that code: every BRANCH probe
     * of the site is left out. A site folds when its method was never hit, even a method that is
     * not a row itself such as a lone constructor, or when its guard, the innermost outcome in the
     * same method that must run before the site is reached, is a never-hit outcome that would be a
     * row but for this fold. A guard outcome whose own site folded still counts, so a site two
     * levels under a never-taken outcome folds too. A routine guard folds nothing, since a routine
     * outcome is in no finding, and neither does a guard that ran.
     *
     * A routine outcome is left out too, since nothing about it is worth acting on: the agent read
     * from the bytecode that the outcome only yields a null default, only throws, or is the
     * exception-path copy of a `finally` body. [neverHitRoutineOutcomes] lists those.
     *
     * An unread shape is left out too: a method whose body has the outline of compiler output but
     * matches no shape the agent has read, a branch probe in such a method, and an outcome the agent
     * marked unread. That is a fact about the agent, not about the adopter's code.
     * [neverHitUnreadShapes] lists those.
     */
    public fun neverHit(): List<ProbeRef> = checked { neverHitRows().filter { it.routine == null && it.unreadShape == null } }

    /**
     * Every never-hit BRANCH probe that [neverHit] leaves out only because its outcome is routine,
     * each with its [ProbeRef.routine] kind, sorted as [neverHit] sorts. The server counts these
     * apart and lists them only on request.
     *
     * A routine outcome of a site that folds into code that never ran, by the rule [neverHit]
     * gives, is not listed here either: a site folds before any of its outcomes can count as
     * routine.
     */
    public fun neverHitRoutineOutcomes(): List<ProbeRef> =
        checked { neverHitRows().filter { it.routine != null && it.unreadShape == null } }

    /**
     * Every never-hit METHOD or BRANCH probe that [neverHit] leaves out only because it is an unread
     * shape, each with its [ProbeRef.unreadShape] family, sorted as [neverHit] sorts. A probe is
     * unread when its method is, which a BRANCH probe in that method inherits, or when its own
     * outcome is. The server counts these per family and labels the rows.
     *
     * An unread outcome of a site that folds into a method that never ran, by the rule [neverHit]
     * gives, is not listed here either. The probes of an unread method do not fold: that method is
     * no row of [neverHit], so nothing would carry them.
     */
    public fun neverHitUnreadShapes(): List<ProbeRef> = checked { neverHitRows().filter { it.unreadShape != null } }

    /** Every [neverHit] row with routine outcomes still in. */
    private fun neverHitRows(): List<ProbeRef> {
        val judgement = judgeClasses()
        val locations = mergedLocations()
        val candidates =
            locations.filter {
                it.key.kind != WireProbeKind.OPTIONAL_ARGUMENT && !it.inline && it.generatedBy == WireGeneratedBy.NONE && it.hits <= 0L &&
                    isNeverHitRow(it, judgement)
            }
        val folded = foldedSites(locations, candidates)
        return candidates
            .filter { it.key !in folded }
            .map(::neverHitRef)
            .sortedWith(compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }))
    }

    /**
     * Every location whose site folds into code that never ran. [candidates] are every location that
     * is a never-hit row or routine outcome by every other rule, one per row with its copies' hits
     * summed. A site folds when its judgeable method was never hit in any instance, whether or not
     * the method is a row itself (a constructor that is not an unused overload is not, and its code
     * never ran either). It also folds when its guard outcome is a BRANCH candidate that is neither
     * routine nor an unread shape. The guard is the one the newest copy's own instance lists for the
     * site, resolved in that instance to the outcome it names. A site in an unread method never
     * folds, since that method is not among the judgeable ones.
     */
    private fun foldedSites(
        locations: List<MergedLocation>,
        candidates: List<MergedLocation>,
    ): Set<LocationKey> {
        val neverHitMethods =
            locations
                .filter {
                    it.key.kind == WireProbeKind.METHOD && !it.inline && it.generatedBy == WireGeneratedBy.NONE &&
                        it.unreadShape == WireUnreadShape.NONE && it.hits <= 0L
                }.mapTo(HashSet()) { it.key.method }
        val guardOutcomes =
            candidates
                .filter {
                    it.key.kind == WireProbeKind.BRANCH && it.routine == WireRoutineKind.NONE &&
                        it.unreadShape == WireUnreadShape.NONE
                }.mapTo(HashSet()) { it.key.method to it.key.outcome }
        val guards = siteGuards()
        val outcomeIds = branchOutcomeIds()
        return candidates
            .filter { location ->
                val branchIndex = location.newest.value.branchIndex
                if (location.key.kind != WireProbeKind.BRANCH || branchIndex == null) return@filter false
                val method = location.key.method
                val instance = location.newest.key.serviceInstanceId
                val guard = guards[OutcomeKey(instance, method, branchIndex)]?.let { outcomeIds[OutcomeKey(instance, method, it)] }
                method in neverHitMethods || (guard != null && (method to guard) in guardOutcomes)
            }.mapTo(HashSet()) { it.key }
    }

    /** The [OutcomeId] of every BRANCH probe, by the instance and branch index it is known by there. */
    private fun branchOutcomeIds(): Map<OutcomeKey, OutcomeId> {
        val ids = HashMap<OutcomeKey, OutcomeId>()
        for ((key, probe) in probesByKey) {
            val branchIndex = probe.branchIndex
            if (probe.kind != WireProbeKind.BRANCH || branchIndex == null) continue
            ids[OutcomeKey(key.serviceInstanceId, NodeKey(probe.className, probe.methodName, probe.methodDescriptor), branchIndex)] =
                outcomeIdOf(probe)
        }
        return ids
    }

    private fun outcomeIdOf(probe: StoredProbe): OutcomeId =
        probe.branchKey?.let { OutcomeId(it, null) } ?: OutcomeId(null, probe.branchIndex)

    /**
     * Every probe merged by [LocationKey], each location's copies ordered newest instance first,
     * then by class id and probe index.
     */
    private fun mergedLocations(): List<MergedLocation> {
        val routineKinds = routineKinds()
        val unreadOutcomes = unreadOutcomeShapes()
        val newestFirst =
            compareByDescending<Map.Entry<ProbeKey, StoredProbe>> { instanceRanks[it.key.serviceInstanceId] ?: 0L }
                .thenBy { it.key.serviceInstanceId }
                .thenBy { it.key.classId }
                .thenBy { it.key.probeIndex }
        return probesByKey.entries
            .groupBy { (_, probe) ->
                LocationKey(
                    NodeKey(probe.className, probe.methodName, probe.methodDescriptor),
                    probe.kind,
                    probe.parameterIndex,
                    if (probe.kind == WireProbeKind.BRANCH) outcomeIdOf(probe) else null,
                )
            }.map { (key, entries) ->
                val members = entries.sortedWith(newestFirst)
                MergedLocation(
                    key = key,
                    members = members,
                    hits = members.sumOf { (probeKey, _) -> hitsByKey[probeKey] ?: 0L },
                    line = members.first().value.line,
                    branchIndex = members.mapNotNull { it.value.branchIndex }.minOrNull(),
                    inline = members.any { it.value.inline },
                    static = members.any { it.value.static },
                    lambdaBody = members.any { it.value.lambdaBody },
                    overridable = members.any { it.value.overridable },
                    generatedBy = WireGeneratedBy.entries[members.maxOf { it.value.generatedBy.ordinal }],
                    inlinedFromClassName = members.mapNotNull { it.value.inlinedFromClassName }.maxOrNull(),
                    routine =
                        members
                            .map { (probeKey, probe) -> routineOf(probeKey, probe, routineKinds) }
                            .firstOrNull { it != WireRoutineKind.NONE } ?: WireRoutineKind.NONE,
                    unreadShape =
                        members
                            .map { (probeKey, probe) -> unreadOf(probeKey, probe, unreadOutcomes) }
                            .firstOrNull { it != WireUnreadShape.NONE } ?: WireUnreadShape.NONE,
                    targetClassName = members.mapNotNull { it.value.targetClassName }.maxOrNull(),
                    parameterName = members.first().value.parameterName,
                )
            }
    }

    /**
     * The guard of each outcome's site, from the sites each instance's METHOD probes list. An
     * outcome absent from the map is in a site with no guard, or in no listed site.
     */
    private fun siteGuards(): Map<OutcomeKey, Int> {
        val guards = HashMap<OutcomeKey, Int>()
        for ((key, probe) in probesByKey) {
            if (probe.kind != WireProbeKind.METHOD) continue
            val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
            for (site in probe.branchSites) {
                val guard = site.guard ?: continue
                for (outcome in site.outcomes) guards[OutcomeKey(key.serviceInstanceId, method, outcome.branchIndex)] = guard
            }
        }
        return guards
    }

    /**
     * The kind of every routine outcome, from the sites each instance's METHOD probes list. An
     * outcome absent from the map is not routine.
     */
    private fun routineKinds(): Map<OutcomeKey, WireRoutineKind> {
        val kinds = HashMap<OutcomeKey, WireRoutineKind>()
        for ((key, probe) in probesByKey) {
            if (probe.kind != WireProbeKind.METHOD) continue
            val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
            for (site in probe.branchSites) {
                for (outcome in site.outcomes) {
                    if (outcome.routine == WireRoutineKind.NONE) continue
                    kinds[OutcomeKey(key.serviceInstanceId, method, outcome.branchIndex)] = outcome.routine
                }
            }
        }
        return kinds
    }

    /**
     * The unread shape of every outcome the agent marked unread, from the sites each instance's
     * METHOD probes list. An outcome absent from the map is not marked; its method may still be.
     */
    private fun unreadOutcomeShapes(): Map<OutcomeKey, WireUnreadShape> {
        val shapes = HashMap<OutcomeKey, WireUnreadShape>()
        for ((key, probe) in probesByKey) {
            if (probe.kind != WireProbeKind.METHOD) continue
            val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
            for (site in probe.branchSites) {
                for (outcome in site.outcomes) {
                    if (outcome.unreadShape == WireUnreadShape.NONE) continue
                    shapes[OutcomeKey(key.serviceInstanceId, method, outcome.branchIndex)] = outcome.unreadShape
                }
            }
        }
        return shapes
    }

    /**
     * The unread shape of [probe]: the greater of its own, which a BRANCH probe inherits from its
     * method and an omission probe from its target, and its outcome's, as the server takes the
     * greater of the two. [UnreadShape.NONE] when neither is.
     */
    private fun unreadOf(
        key: ProbeKey,
        probe: StoredProbe,
        unreadOutcomes: Map<OutcomeKey, WireUnreadShape>,
    ): WireUnreadShape {
        val branchIndex = probe.branchIndex
        if (probe.kind != WireProbeKind.BRANCH || branchIndex == null) return probe.unreadShape
        val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
        val outcome = unreadOutcomes[OutcomeKey(key.serviceInstanceId, method, branchIndex)] ?: WireUnreadShape.NONE
        return if (outcome.ordinal > probe.unreadShape.ordinal) outcome else probe.unreadShape
    }

    /** The routine kind of [probe], a BRANCH probe of [key]'s instance, or [RoutineKind.NONE] for any other probe. */
    private fun routineOf(
        key: ProbeKey,
        probe: StoredProbe,
        routineKinds: Map<OutcomeKey, WireRoutineKind>,
    ): WireRoutineKind {
        val branchIndex = probe.branchIndex
        if (probe.kind != WireProbeKind.BRANCH || branchIndex == null) return WireRoutineKind.NONE
        val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
        return routineKinds[OutcomeKey(key.serviceInstanceId, method, branchIndex)] ?: WireRoutineKind.NONE
    }

    /**
     * Whether a judgeable never-hit [probe] is a [neverHit] row under the class-finding rules. A
     * `<clinit>` is a class state, never a row. A constructor is a row only as an unused overload,
     * when another constructor of its class ran. A method a class finding covers is not a row, and
     * neither is a lambda body that folds into its creators. A BRANCH probe in either is not a row
     * either.
     */
    private fun isNeverHitRow(
        location: MergedLocation,
        judgement: ClassJudgement,
    ): Boolean {
        val method = location.key.method
        if (method in judgement.foldedMethods) return false
        if (location.key.kind != WireProbeKind.METHOD) return true
        return when (method.methodName) {
            CLASS_INIT -> false
            CONSTRUCTOR -> method.className in judgement.constructed
            else -> true
        }
    }

    /**
     * Every class that some instance loaded, that has a judgeable static initialiser, and whose
     * static initialiser ran in no instance, sorted by class name. Nothing used its statics and nothing
     * created an instance. A class with no static initialiser is never listed here.
     *
     * Every method of such a class can only run through its initialiser, so [neverHit] and
     * [unreachedClusters] fold them into the class.
     */
    public fun neverInitialized(): List<ClassFindingRef> = checked { classFindingRefs(ClassFinding.NEVER_INITIALIZED) }

    /**
     * Every class that some instance loaded, that is not never initialised, that has a judgeable
     * constructor and a judgeable method that is neither static nor a constructor, and none of
     * whose constructors ran in any instance, sorted by class name. No instance of it or of a subclass ever existed.
     * A class with only static methods is never listed, and neither is an interface, which has no
     * constructor.
     *
     * The constructors and instance methods of such a class can only run through an instance, so
     * [neverHit] and [unreachedClusters] fold them into the class. Its static methods can still run,
     * so they stay rows of their own.
     */
    public fun neverInstantiated(): List<ClassFindingRef> = checked { classFindingRefs(ClassFinding.NEVER_INSTANTIATED) }

    /** The [ClassFindingRef] of each class [judgeClasses] gives [finding], sorted by class name. */
    private fun classFindingRefs(finding: ClassFinding): List<ClassFindingRef> =
        judgeClasses()
            .findings
            .filterValues { it == finding }
            .keys
            .sorted()
            .map { className ->
                ClassFindingRef(
                    className = className,
                    finding = finding,
                    methods =
                        nameIndex[className]
                            .orEmpty()
                            .mapNotNull { probesByKey[it] }
                            .filter { it.kind == WireProbeKind.METHOD && it.methodName != CLASS_INIT }
                            .map { it.methodName }
                            .distinct()
                            .sorted(),
                    instancesLoading = loadedClassNamesByInstance.values.count { className in it },
                )
            }

    /**
     * Every judgeable METHOD probe location, none of inline, generated or an unread shape, merged across instances
     * by class, method and descriptor.
     */
    private fun judgeableMethods(): Map<NodeKey, JudgeableMethod> =
        mergedLocations()
            .filter { it.key.kind == WireProbeKind.METHOD && !it.inline && !it.isLookedThrough }
            .associate { it.key.method to JudgeableMethod(it.key.method, it.hits, it.static, it.lambdaBody) }

    /**
     * Every method some CREATES call edge names, with the methods whose edges name it: its
     * creators. Reads the edges of every METHOD probe and of every complete-baseline declaration.
     * A method never counts as its own creator.
     */
    private fun creatorsOf(): Map<NodeKey, Set<NodeKey>> {
        val creators = mutableMapOf<NodeKey, MutableSet<NodeKey>>()

        fun add(
            creator: NodeKey,
            edges: List<CallEdge>,
        ) {
            for (edge in edges) {
                if (edge.kind != CallEdgeKind.CREATES) continue
                val created = NodeKey(edge.className, edge.methodName, edge.methodDescriptor)
                if (created != creator) creators.getOrPut(created) { mutableSetOf() } += creator
            }
        }
        probesByKey.values
            .filter { it.kind == WireProbeKind.METHOD }
            .forEach { add(NodeKey(it.className, it.methodName, it.methodDescriptor), it.calls) }
        for ((className, declared) in consultedDeclaredClasses) {
            declared.methods.forEach { add(NodeKey(className, it.methodName, it.methodDescriptor), it.calls) }
        }
        return creators
    }

    /**
     * Applies the class-finding rules to every loaded class, over [judgeableMethods]. A class
     * with a judgeable METHOD probe loaded, so none of these is never loaded.
     *
     * A class is never initialised when it has a `<clinit>` and that never ran. Otherwise it is never
     * instantiated when it has a `<init>`, none ran, and it has a method that is neither static nor
     * a constructor. A never-initialised class covers every never-hit method. A never-instantiated
     * class covers each never-hit constructor and each never-hit method that is not static.
     *
     * A never-hit lambda body then folds with its creators, since it can only run once one of them
     * ran. See [foldLambdaBodies].
     */
    private fun judgeClasses(): ClassJudgement {
        val judgeable = judgeableMethods()
        val findings = mutableMapOf<String, ClassFinding>()
        val covered = mutableMapOf<String, List<NodeKey>>()
        val constructed = mutableSetOf<String>()
        for ((className, methods) in judgeable.values.groupBy { it.key.className }) {
            val initialisers = methods.filter { it.key.methodName == CLASS_INIT }
            val constructors = methods.filter { it.key.methodName == CONSTRUCTOR }
            if (constructors.any { it.hits > 0L }) constructed += className
            val finding =
                when {
                    initialisers.isNotEmpty() && initialisers.all { it.hits == 0L } -> {
                        ClassFinding.NEVER_INITIALIZED
                    }

                    constructors.isNotEmpty() &&
                        className !in constructed &&
                        methods.any { it.key.methodName != CONSTRUCTOR && it.key.methodName != CLASS_INIT && !it.static } -> {
                        ClassFinding.NEVER_INSTANTIATED
                    }

                    else -> {
                        continue
                    }
                }
            findings[className] = finding
            covered[className] =
                methods
                    .filter { method ->
                        method.hits == 0L &&
                            (
                                finding == ClassFinding.NEVER_INITIALIZED ||
                                    method.key.methodName == CONSTRUCTOR ||
                                    (method.key.methodName != CLASS_INIT && !method.static)
                            )
                    }.map { it.key }
        }
        val (intoClassFindings, inNeverHitCode) = foldLambdaBodies(judgeable, findings, covered.values.flatten().toSet(), constructed)
        for (lambda in intoClassFindings) covered[lambda.className] = covered.getValue(lambda.className) + lambda
        return ClassJudgement(findings, covered, inNeverHitCode, constructed)
    }

    /**
     * Folds each judgeable never-hit lambda body whose every creator is judgeable and never hit,
     * and is itself covered by a class finding or a row of [neverHit]: a method other than
     * `<clinit>` and a lone constructor. A lambda body some method created is one or the other, so
     * nested lambda bodies fold with the outermost one. A lambda body with no known creator, or with
     * a creator that ran, never folds.
     *
     * Returns the folded lambda bodies in two sets. The first fold into their own class's finding:
     * every creator is covered by a class finding or is in that first set. The rest fold into
     * never-hit methods that are rows of their own.
     */
    private fun foldLambdaBodies(
        judgeable: Map<NodeKey, JudgeableMethod>,
        findings: Map<String, ClassFinding>,
        covered: Set<NodeKey>,
        constructed: Set<String>,
    ): Pair<Set<NodeKey>, Set<NodeKey>> {
        val creators = creatorsOf()

        fun absorbs(creator: NodeKey): Boolean {
            if ((judgeable[creator] ?: return false).hits > 0L) return false
            if (creator in covered) return true
            return when (creator.methodName) {
                CLASS_INIT -> false
                CONSTRUCTOR -> creator.className in constructed
                else -> true
            }
        }
        val folded =
            judgeable.values
                .filter { it.lambdaBody && it.hits == 0L && it.key !in covered }
                .map { it.key }
                .filter { lambda -> creators[lambda].orEmpty().let { it.isNotEmpty() && it.all(::absorbs) } }
                .toSet()
        val intoClassFindings = mutableSetOf<NodeKey>()
        var changed = true
        while (changed) {
            changed = false
            for (lambda in folded - intoClassFindings) {
                if (lambda.className in findings && creators.getValue(lambda).all { it in covered || it in intoClassFindings }) {
                    intoClassFindings += lambda
                    changed = true
                }
            }
        }
        return intoClassFindings to (folded - intoClassFindings)
    }

    /** The [ProbeRef] [neverHit] lists for [location]. */
    private fun neverHitRef(location: MergedLocation): ProbeRef =
        ProbeRef(
            className = location.key.method.className,
            methodName = location.key.method.methodName,
            methodDescriptor = location.key.method.methodDescriptor,
            line = location.line,
            kind = location.key.kind.toTestkit(),
            branchIndex = location.branchIndex,
            inline = location.inline,
            inlinedFromClassName = location.inlinedFromClassName,
            generatedBy = location.generatedBy.toTestkit(),
            branchKey = location.key.outcome?.branchKey,
            routine = location.routine.toTestkit(),
            unreadShape = location.unreadShape.toTestkit(),
        )

    /**
     * The agent version [serviceInstanceId] reported, or null when no payload from it has been
     * accepted. Empty when the agent did not know its own version, as in a test JVM that runs the
     * agent from classes.
     */
    internal fun agentVersion(serviceInstanceId: String): String? = checked { agentVersionByInstance[serviceInstanceId] }

    /** Every class reported as matched but not instrumented by any manifest, distinct by class name, sorted by name. */
    public fun skippedClasses(): List<SkippedClass> = checked { skippedByClassName.values.sortedBy { it.className } }

    /**
     * Whether [serviceInstanceId] has sent a delta batch with `final_flush` set, meaning its
     * shutdown hook ran. False both before that batch arrives and for an instance never seen at
     * all: this collector cannot tell the two apart, since an instance the agent never contacted
     * leaves no other trace either.
     */
    internal fun endedCleanly(serviceInstanceId: String): Boolean = checked { serviceInstanceId in instancesThatEndedCleanly }

    /** Every instance id that has sent a delta batch with `final_flush` set. See [endedCleanly]. */
    internal fun instancesEndedCleanly(): Set<String> = checked { instancesThatEndedCleanly.toSet() }

    /**
     * Class names a sweep reported as loaded but unreported, sorted. Empty until a manifest
     * carries one.
     *
     * These are classes no transformer was offered, so the agent knows only that they loaded.
     * [neverLoaded] already leaves them out; this exposes them so a test can assert the blind
     * spot itself rather than only its absence from a claim.
     */
    internal fun unreportedClasses(): List<String> = checked { unreportedByClassName.keys.sorted() }

    /**
     * What kind of class kotlinc says [className] is, from its manifest record or, for a class
     * that never loaded, a complete static baseline's declaration. [KotlinKind.NONE] for a class
     * with no `kotlin.Metadata`, such as a Java class. Null when no payload has named the class. A
     * report names a [KotlinKind.FILE_FACADE] or a [KotlinKind.MULTIFILE_CLASS_PART] by its
     * source file.
     */
    internal fun kotlinKind(className: String): KotlinKind? =
        checked { kotlinKindByClassName[className] ?: consultedDeclaredClasses[className]?.kotlinKind }

    /**
     * Class names declared by a complete static baseline scan that no manifest, from any
     * instance, has ever mentioned as a probe's class, a skipped class or an unreported class.
     *
     * Throws [IllegalStateException] if no static baseline scan has ever completed: an empty list
     * would read as "nothing is dead", when the real answer is "no idea yet". A class in the
     * unsafe, unreadable, or unprobed baseline buckets is never counted as declared here, so it
     * never appears in this list either. A declared class whose every declared method is inline
     * or generated is also excluded: Kotlin callers never invoke such a class's methods directly,
     * and the compiler will emit a generated method again regardless of what the adopter does, so
     * such a class never loading at all is not evidence it is dead. A class whose every declared
     * method is inline, generated or an unread shape is excluded the same way, since an unread shape
     * is compiler output the agent could not read.
     */
    public fun neverLoaded(): List<String> {
        return checked {
            check(completedScans.isNotEmpty()) { "no complete static baseline scan has been received yet" }
            return consultedDeclaredNames.filter { it !in dynamicallyKnownClassNames && it !in consultedAllInlineOrGeneratedNames }.sorted()
        }
    }

    /**
     * The in-scope callees [methodName] on [className] references in its own bytecode, verbatim as
     * the bytecode names them, deduplicated and sorted by callee class, method name, then
     * descriptor. Every overload of [methodName] contributes its edges. Sources both a loaded
     * class's manifest edges and a never-loaded class's complete-baseline edges, the same union
     * [unreachedClusters] resolves against.
     *
     * Throws [UnknownProbeException] if no manifest probe and no complete-baseline declaration ever
     * named [methodName] on [className]; a known method with no callees returns an empty list.
     */
    internal fun callEdges(
        className: String,
        methodName: String,
    ): List<CallEdge> {
        return checked {
            val fromManifest =
                nameIndex[className].orEmpty().mapNotNull { key ->
                    probesByKey[key]?.takeIf { it.kind == WireProbeKind.METHOD && it.methodName == methodName }
                }
            val fromBaseline = consultedDeclaredClasses[className]?.methods?.filter { it.methodName == methodName }.orEmpty()
            if (fromManifest.isEmpty() && fromBaseline.isEmpty()) {
                throw unknownProbe(className, methodName, null)
            }
            return (fromManifest.flatMap { it.calls } + fromBaseline.flatMap { it.calls })
                .distinct()
                .sortedWith(compareBy({ it.className }, { it.methodName }, { it.methodDescriptor }))
        }
    }

    /**
     * Every unreached cluster in the call graph, applying the collector's cluster rule within this
     * one test JVM. Sorted by [UnreachedCluster.membersTotal] descending, then by root.
     *
     * The graph holds three kinds of node. A method node comes from a manifest METHOD probe, merged
     * across instances with hits summed, or from a non-inline declared method of a class a complete
     * static baseline declared but no manifest ever mentioned. Such a method carries
     * [ProbeRef.neverLoaded] `true` and line `-1`. An outcome node is a BRANCH probe with no hits,
     * merged across instances by its method and branch key, or its branch index when it has no key,
     * in a method node with hits. A BRANCH probe that is inline or generated is never an outcome
     * node, by the rule that keeps its method out of the graph. A class node is a class that holds a
     * class finding: never loaded, [neverInitialized] or [neverInstantiated]. It stands for the
     * never-hit methods that finding covers, which are never method nodes of their own. For a
     * never-loaded class that is every method node of the class.
     *
     * A call edge whose guard names an outcome node counts as a call from that outcome node. Every
     * other edge counts as a call from its method, or from the class node that stands for it. An
     * edge into a covered method goes into its class node, and an edge between two methods one class
     * node stands for is dropped. An outcome node has one caller: the outcome node its site's guard
     * names, or else its method. A guard is a branch index in the instance that reported the edge
     * or the site, so it is resolved to its outcome there before instances merge. A declared edge's
     * guard is resolved in the scan's instance, else by its site key, role and case in the newest
     * instance that lists such an outcome, since the scan kept may come from an instance that never
     * loaded the class.
     *
     * A root is a never-hit node with no caller or with a caller that is a method with hits, a
     * generated method with hits included, since code outside scope may call it. A method
     * root with no caller is [RootKind.UNCALLED], and one with a caller that has hits is
     * [RootKind.REACHED_FROM_HIT]. An outcome root is [RootKind.UNTAKEN_OUTCOME], and a class root is
     * [RootKind.CLASS_FINDING]. A `<clinit>` is never a root, and neither is an unjudged constructor:
     * a never-hit `<init>` of a loaded class no instance constructed that no class finding covers,
     * such as a utility class's private constructor. Like `<clinit>`, it is reached through but never
     * listed or counted, and a class is listed whole without it. The cluster is the root plus every
     * never-hit node reachable from it whose every caller is already in the cluster. An outcome or
     * class root whose cluster holds no method or class node besides the root gives no cluster,
     * since the root's own finding already says all there is.
     *
     * Inline methods are never nodes, so an edge into one resolves to nothing. Edges are the union
     * of manifest and complete-baseline call edges. Resolution walks a callee's owner up through
     * [supertypesByClassName] and complete-baseline supertypes to the first type with a matching
     * node. For a virtual call it also walks down from the owner to every known transitive subtype
     * with one, `<init>` and `<clinit>` excepted. See [computeCallGraph].
     */
    public fun unreachedClusters(): List<UnreachedCluster> {
        return checked {
            val graph = computeCallGraph()

            fun isHit(key: NodeKey) = ((graph.nodes[key] ?: graph.hitGeneratedCallers[key])?.hits ?: 0L) > 0L

            val judgement = judgeClasses()
            val classNodes = buildClassNodes(graph, judgement)
            val coveredBy = classNodes.flatMap { (node, info) -> info.methods.map { it to node } }.toMap()
            val unjudged =
                graph.nodes
                    .filter { (key, info) ->
                        key.methodName == CONSTRUCTOR && !info.neverLoaded && key !in coveredBy && key.className !in judgement.constructed
                    }.keys
            val outcomes = buildOutcomeNodes(::isHit)
            val clusterGraph = buildClusterGraph(graph, outcomes, coveredBy)

            fun isNeverHit(node: ClusterNode) =
                when {
                    node.isClass -> node in classNodes
                    node.outcome != null -> node in outcomes
                    else -> !isHit(node.method) && node.method !in coveredBy
                }

            val neverHitNodes =
                graph.nodes.keys
                    .filter { !isHit(it) && it !in coveredBy && it.methodName != CLASS_INIT && it !in unjudged }
                    .map { ClusterNode(it) } + outcomes.keys + classNodes.keys
            return neverHitNodes
                .mapNotNull { node ->
                    val callers = clusterGraph.callersOf[node].orEmpty()
                    val hitCallers = callers.filter { it.outcome == null && !it.isClass && isHit(it.method) }
                    if (callers.isNotEmpty() && hitCallers.isEmpty()) return@mapNotNull null
                    val kind =
                        when {
                            node.isClass -> RootKind.CLASS_FINDING
                            node.outcome != null -> RootKind.UNTAKEN_OUTCOME
                            callers.isEmpty() -> RootKind.UNCALLED
                            else -> RootKind.REACHED_FROM_HIT
                        }
                    val reachedFrom =
                        if (kind == RootKind.REACHED_FROM_HIT || kind == RootKind.CLASS_FINDING) {
                            hitCallers.map { toProbeRef(graph.infoOf(it.method), it.method) }.sortedWith(probeRefComparator)
                        } else {
                            emptyList()
                        }
                    buildCluster(graph, clusterGraph, outcomes, classNodes, unjudged, node, kind, reachedFrom, ::isNeverHit)
                }.sortedWith(compareByDescending<UnreachedCluster> { it.membersTotal }.thenComparing({ it.root }, probeRefComparator))
        }
    }

    /**
     * Grows [root]'s cluster by fixpoint: repeatedly add a never-hit node reachable from a current
     * member, once every one of that node's callers is itself already in the cluster. Two
     * consequences of this rule, pinned by tests: a node whose callers sit in two different
     * clusters is added to neither, and a cycle of never-hit nodes with no outside caller produces
     * no root at all, so it never reaches this method in the first place. Returns null for an
     * outcome or class root when nothing it would list joined it: an outcome node, a `<clinit>` or a
     * member in [unjudged] is never listed, and class sizes leave out a member in [unjudged].
     */
    private fun buildCluster(
        graph: CallGraph,
        clusterGraph: ClusterGraph,
        outcomes: Map<ClusterNode, OutcomeNode>,
        classNodes: Map<ClusterNode, ClassNode>,
        unjudged: Set<NodeKey>,
        root: ClusterNode,
        rootKind: RootKind,
        reachedFrom: List<ProbeRef>,
        isNeverHit: (ClusterNode) -> Boolean,
    ): UnreachedCluster? {
        val members = mutableSetOf(root)
        var changed = true
        while (changed) {
            changed = false
            for (member in members.toList()) {
                for (target in clusterGraph.calleesOf[member].orEmpty()) {
                    if (target in members || !isNeverHit(target)) continue
                    val callers = clusterGraph.callersOf[target].orEmpty()
                    if (callers.isNotEmpty() && members.containsAll(callers)) {
                        members += target
                        changed = true
                    }
                }
            }
        }
        val listsMoreThanRoot =
            members.any {
                it != root && it.outcome == null &&
                    (it.isClass || (it.method.methodName != CLASS_INIT && it.method !in unjudged))
            }
        if ((root.isClass || root.outcome != null) && !listsMoreThanRoot) return null

        val methodsByClass = mutableMapOf<String, MutableList<NodeKey>>()
        for (member in members) {
            when {
                member.outcome != null -> {}

                member.isClass -> {
                    methodsByClass.getOrPut(member.method.className) { mutableListOf() } +=
                        classNodes.getValue(member).methods
                }

                member.method in unjudged -> {}

                else -> {
                    methodsByClass.getOrPut(member.method.className) { mutableListOf() } += member.method
                }
            }
        }
        val classSize =
            graph.nodes.keys
                .filter { it !in unjudged }
                .groupingBy { it.className }
                .eachCount()
        val wholeClasses = mutableListOf<WholeClass>()
        val memberRefs = mutableListOf<ProbeRef>()
        var neverLoadedClasses = 0
        for ((className, keys) in methodsByClass) {
            val refs =
                keys
                    .filter { it.methodName != CLASS_INIT }
                    .map { toProbeRef(graph.nodes.getValue(it), it) }
                    .sortedWith(probeRefComparator)
            if (keys.any { graph.nodes.getValue(it).neverLoaded }) neverLoadedClasses++
            if (keys.size == classSize[className]) {
                wholeClasses += WholeClass(className, classNodes[classNode(className)]?.finding, refs)
            } else {
                memberRefs += refs
            }
        }
        val rootOutcome = outcomes[root]
        val rootRef =
            when {
                root.isClass -> classRootRef(graph, root, classNodes.getValue(root))
                rootOutcome != null -> rootOutcome.ref
                else -> toProbeRef(graph.nodes.getValue(root.method), root.method)
            }
        return UnreachedCluster(
            root = rootRef,
            rootKind = rootKind,
            members = memberRefs.sortedWith(probeRefComparator),
            neverLoadedClasses = neverLoadedClasses,
            rootSite = rootOutcome?.site,
            reachedFrom = reachedFrom,
            rootFinding = classNodes[root]?.finding,
            wholeClasses = wholeClasses.sortedBy { it.className },
        )
    }

    /**
     * The [ProbeRef] a class root reports: the class alone, with an empty method name and
     * descriptor and line `0`. It is never loaded when every method its class node stands for is.
     */
    private fun classRootRef(
        graph: CallGraph,
        root: ClusterNode,
        info: ClassNode,
    ): ProbeRef {
        val methods = info.methods.map { graph.nodes.getValue(it) }
        return ProbeRef(
            className = root.method.className,
            methodName = "",
            methodDescriptor = "",
            line = 0,
            kind = ProbeKind.METHOD,
            branchIndex = null,
            neverLoaded = methods.all { it.neverLoaded },
        )
    }

    private fun classNode(className: String) = ClusterNode(NodeKey(className, "", ""), isClass = true)

    /**
     * Every class node, keyed by its [ClusterNode]. A never-initialised or never-instantiated class
     * stands for the methods [judgement], from [judgeClasses], says it covers. A class whose method
     * nodes all come from a complete baseline, since no manifest mentioned it, is never loaded and
     * stands for all of them. A class with no such method has no class node.
     */
    private fun buildClassNodes(
        graph: CallGraph,
        judgement: ClassJudgement,
    ): Map<ClusterNode, ClassNode> {
        val classNodes = mutableMapOf<ClusterNode, ClassNode>()
        for ((className, finding) in judgement.findings) {
            val methods = judgement.covered[className].orEmpty().filter { (graph.nodes[it]?.hits ?: -1L) == 0L }
            if (methods.isNotEmpty()) classNodes[classNode(className)] = ClassNode(finding, methods)
        }
        graph.nodes
            .filter { (key, info) -> info.neverLoaded && key.className !in judgement.findings }
            .keys
            .groupBy { it.className }
            .forEach { (className, methods) -> classNodes[classNode(className)] = ClassNode(ClassFinding.NEVER_LOADED, methods) }
        return classNodes
    }

    /**
     * Every outcome node, keyed by its [ClusterNode]: a BRANCH location that is none of inline,
     * generated, in an unread shape or routine, whose hits summed across instances are zero, in a
     * method [isHit] says has hits. Locations merge by method and [OutcomeId], so one outcome
     * reported by several instances under different branch indexes is one node. Its site is looked
     * up by branch index in the sites the METHOD probes of the newest copy's instance list.
     *
     * A routine outcome and an unread-shape outcome are never nodes, since neither is a finding, so a
     * call one guards starts at its method.
     */
    private fun buildOutcomeNodes(isHit: (NodeKey) -> Boolean): Map<ClusterNode, OutcomeNode> {
        val branchIds = branchOutcomeIds()
        val sitesByMethod =
            probesByKey.entries
                .filter { (_, probe) -> probe.kind == WireProbeKind.METHOD && probe.branchSites.isNotEmpty() }
                .groupBy(
                    { (key, probe) ->
                        InstanceKey(key.serviceInstanceId, NodeKey(probe.className, probe.methodName, probe.methodDescriptor))
                    },
                    { (_, probe) -> probe.branchSites },
                ).mapValues { (_, lists) -> lists.flatten() }
        val locations = mergedLocations()
        val mergedIndex =
            locations
                .filter { it.key.kind == WireProbeKind.BRANCH && it.key.outcome != null }
                .associate { (it.key.method to it.key.outcome) to it.branchIndex }

        fun mergedIndexOf(
            instance: String,
            method: NodeKey,
            rawIndex: Int,
        ): Int? = branchIds[OutcomeKey(instance, method, rawIndex)]?.let { mergedIndex[method to it] }
        return locations
            .filter {
                val outcome = it.key.outcome
                it.key.kind == WireProbeKind.BRANCH && outcome != null && !it.inline && it.generatedBy == WireGeneratedBy.NONE &&
                    it.unreadShape == WireUnreadShape.NONE && it.routine == WireRoutineKind.NONE && it.hits == 0L && isHit(it.key.method)
            }.associate { location ->
                val instance = location.newest.key.serviceInstanceId
                val method = location.key.method
                val branchIndex = location.newest.value.branchIndex
                val rawSite =
                    sitesByMethod[InstanceKey(instance, method)]
                        ?.firstOrNull { site -> site.outcomes.any { it.branchIndex == branchIndex } }
                val guard = rawSite?.guard?.let { branchIds[OutcomeKey(instance, method, it)] }
                // The site comes from one instance, so its indexes are renumbered to the merged ones the root
                // and every other ref carry, as the server builds a root site from merged locations.
                val site =
                    rawSite?.copy(
                        outcomes =
                            rawSite.outcomes.mapNotNull { outcome ->
                                mergedIndexOf(instance, method, outcome.branchIndex)?.let { outcome.copy(branchIndex = it) }
                            },
                        guard = rawSite.guard?.let { mergedIndexOf(instance, method, it) },
                    )
                ClusterNode(method, location.key.outcome) to OutcomeNode(neverHitRef(location), site, guard)
            }
    }

    /**
     * Links every resolved call and every outcome node into the cluster graph. A call whose guard
     * names an outcome node in the caller's method starts at that outcome node, and any other call
     * starts at its method. A method [coveredBy] names is replaced by its class node at either end,
     * and a call that then starts and ends at the same node is dropped. An outcome node's one caller
     * is the outcome node its site's guard names, when that is another outcome node, and otherwise
     * its method.
     */
    private fun buildClusterGraph(
        graph: CallGraph,
        outcomes: Map<ClusterNode, OutcomeNode>,
        coveredBy: Map<NodeKey, ClusterNode>,
    ): ClusterGraph {
        val callersOf = mutableMapOf<ClusterNode, MutableSet<ClusterNode>>()
        val calleesOf = mutableMapOf<ClusterNode, MutableSet<ClusterNode>>()

        fun link(
            caller: ClusterNode,
            callee: ClusterNode,
        ) {
            callersOf.getOrPut(callee) { mutableSetOf() } += caller
            calleesOf.getOrPut(caller) { mutableSetOf() } += callee
        }

        fun nodeOf(key: NodeKey) = coveredBy[key] ?: ClusterNode(key)
        for ((caller, calls) in graph.calls) {
            for (call in calls) {
                val guardNode = call.guard?.let { ClusterNode(caller, it) }?.takeIf { it in outcomes }
                val from = guardNode ?: nodeOf(caller)
                val to = nodeOf(call.callee)
                if (from != to) link(from, to)
            }
        }
        for ((node, outcome) in outcomes) {
            val guardNode =
                outcome.siteGuard
                    ?.let { ClusterNode(node.method, it) }
                    ?.takeIf { it != node && it in outcomes }
            link(guardNode ?: ClusterNode(node.method), node)
        }
        return ClusterGraph(callersOf, calleesOf)
    }

    /**
     * Builds every [NodeKey] and resolves its edges against the known supertype graph. An edge
     * resolves to the union of two lookups, either of which may find nothing: the first node up
     * the owner's supertype chain, which is an inherited concrete declaration, and, for a virtual
     * call, every node with the same name and descriptor on a transitive subtype of the owner.
     * Widening starts at the owner, not at the declaring type, for two reasons: an abstract
     * interface method has no node anywhere, so requiring the up-walk to succeed would drop every
     * edge into a pure interface, which is the constructor-injected case supertypes exist for; and
     * a receiver typed as the owner can only be the owner or one of its subtypes, never a sibling
     * under some ancestor. Each resolved call keeps its raw edge's guard.
     * Every resolved edge into a class also implies an edge into that class's `<clinit>` node
     * when it has one, under the same guard: no bytecode ever calls `<clinit>`, the JVM runs it on
     * the class's first active use, and a resolved call into the class is exactly such a use.
     * Without this a never-initialised class's `<clinit>` would be an uncalled root of its own
     * beside the cluster that actually owns it. A call from a method to itself is dropped.
     *
     * A generated method is no node but is not a dead end either: a lookup that lands on one
     * continues along that method's own edges, resolved the same way, so the call reaches what
     * the generated code runs. A Kotlin `-jvm-default=disable` stub is the case that needs it: a
     * call typed as the interface widens down to each implementing class's stub, which is
     * generated, and only the stub's edge reaches the default's code in `$DefaultImpls`. A
     * generated method with hits is a caller in its own right, since code outside scope can call
     * it, as a `HashMap` calls a data class's `hashCode`: its calls are resolved the same way and
     * recorded in [CallGraph.hitGeneratedCallers]. An unread-shape method is treated the same way:
     * looked through, and a caller when it has hits, but never a node.
     */
    private fun computeCallGraph(): CallGraph {
        val nodes = buildNodes()
        val transparent = buildTransparentMethods()
        val known = nodes.keys + transparent.keys
        val reverseSubtypes = buildReverseSubtypes()

        fun resolve(
            edge: CallEdge,
            expanded: MutableSet<NodeKey>,
            into: MutableSet<NodeKey>,
        ) {
            val candidates = mutableSetOf<NodeKey>()
            findDeclaringType(known, edge.className, edge.methodName, edge.methodDescriptor)?.let {
                candidates += NodeKey(it, edge.methodName, edge.methodDescriptor)
            }
            if (edge.virtual && edge.methodName != "<init>" && edge.methodName != "<clinit>") {
                candidates += widenToSubtypes(known, reverseSubtypes, edge.className, edge.methodName, edge.methodDescriptor)
            }
            for (candidate in candidates) {
                val typeInitializer = NodeKey(candidate.className, "<clinit>", "()V")
                if (typeInitializer in nodes) into += typeInitializer
                if (candidate in nodes) {
                    into += candidate
                } else if (expanded.add(candidate)) {
                    transparent[candidate].orEmpty().forEach { resolve(it, expanded, into) }
                }
            }
        }

        val calls = mutableMapOf<NodeKey, Set<ResolvedCall>>()
        for ((nodeKey, info) in nodes) {
            val resolved = mutableSetOf<ResolvedCall>()
            for (guarded in info.edges) {
                val targets = mutableSetOf<NodeKey>()
                resolve(guarded.edge, mutableSetOf(), targets)
                targets -= nodeKey
                targets.mapTo(resolved) { ResolvedCall(it, guarded.guard) }
            }
            calls[nodeKey] = resolved
        }
        val hitGeneratedCallers = mutableMapOf<NodeKey, NodeInfo>()
        mergedLocations()
            .filter { it.key.kind == WireProbeKind.METHOD && !it.inline && it.isLookedThrough && it.hits > 0L }
            .forEach { location ->
                val key = location.key.method
                if (key in nodes) return@forEach
                hitGeneratedCallers[key] = NodeInfo(location.line, neverLoaded = false, location.hits, emptySet())
                val targets = mutableSetOf<NodeKey>()
                transparent[key].orEmpty().forEach { resolve(it, mutableSetOf(key), targets) }
                calls[key] = targets.map { ResolvedCall(it, null) }.toSet()
            }
        return CallGraph(nodes, calls, hitGeneratedCallers)
    }

    /**
     * Every generated or unread-shape method that is not inline, from manifests and complete
     * baselines alike, with its edges: the methods [computeCallGraph] looks through rather than stopping at.
     */
    private fun buildTransparentMethods(): Map<NodeKey, Set<CallEdge>> {
        val transparent = mutableMapOf<NodeKey, MutableSet<CallEdge>>()
        mergedLocations()
            .filter { it.key.kind == WireProbeKind.METHOD && !it.inline && it.isLookedThrough }
            .forEach { location ->
                transparent.getOrPut(location.key.method) { mutableSetOf() } += location.members.flatMap { it.value.calls }
            }
        for ((className, declared) in consultedDeclaredClasses) {
            for (method in declared.methods) {
                if (method.inline || !method.isLookedThrough()) continue
                transparent.getOrPut(NodeKey(className, method.methodName, method.methodDescriptor)) { mutableSetOf() } += method.calls
            }
        }
        return transparent
    }

    /**
     * Every node: a manifest METHOD probe, non-inline, non-generated and not an unread shape, merged across instances
     * by (class, method, descriptor) with hits summed and edges unioned with any matching
     * complete-baseline declaration; plus, for a class a complete scan declared that no manifest
     * ever mentioned, each of its non-inline, non-generated, read declared methods, with zero hits. A
     * generated method, such as a data class's `copy`, is never a node: the compiler will emit it
     * again regardless of what the adopter does, so it can never root or extend an unreached
     * cluster. An unread-shape method is never a node either, since it is compiler output the agent
     * could not read.
     */
    private fun buildNodes(): Map<NodeKey, NodeInfo> {
        val nodes = mutableMapOf<NodeKey, NodeInfo>()
        val branchIds = branchOutcomeIds()

        fun guarded(
            edge: CallEdge,
            instance: String,
            method: NodeKey,
        ) = GuardedEdge(edge.copy(guard = null), edge.guard?.let { branchIds[OutcomeKey(instance, method, it)] })
        for (location in mergedLocations()) {
            if (location.key.kind != WireProbeKind.METHOD || location.inline || location.isLookedThrough) continue
            val nodeKey = location.key.method
            val edges =
                location.members
                    .flatMap { (key, probe) -> probe.calls.map { guarded(it, key.serviceInstanceId, nodeKey) } }
                    .toMutableSet()
            consultedDeclaredClasses[nodeKey.className]?.let { declared ->
                declared.methods
                    .filter { it.methodName == nodeKey.methodName && it.methodDescriptor == nodeKey.methodDescriptor }
                    .forEach { method ->
                        method.calls.mapTo(edges) { edge ->
                            GuardedEdge(
                                edge.copy(guard = null),
                                edge.guard?.let { declaredGuard(it, declared, method, location, branchIds) },
                            )
                        }
                    }
            }
            nodes[nodeKey] = NodeInfo(line = location.line, neverLoaded = false, hits = location.hits, edges = edges)
        }
        for ((className, declared) in consultedDeclaredClasses) {
            if (className in dynamicallyKnownClassNames) continue
            for (method in declared.methods) {
                if (method.inline || method.isLookedThrough()) continue
                val nodeKey = NodeKey(className, method.methodName, method.methodDescriptor)
                if (nodeKey in nodes) continue
                nodes[nodeKey] =
                    NodeInfo(
                        line = -1,
                        neverLoaded = true,
                        hits = 0L,
                        edges = method.calls.mapTo(mutableSetOf()) { GuardedEdge(it.copy(guard = null), null) },
                    )
            }
        }
        return nodes
    }

    /**
     * Resolves a declared edge's [guard], a branch index in the scan's own build, to an outcome. The
     * scan's instance resolves it when it has a BRANCH probe there. A scan can declare a class its
     * instance never loaded, or a method that has no branch probes there, so otherwise the outcome
     * is matched by identity, as the server matches it: the site key of the scan's own site at the
     * guard, and the outcome's role and case, in the newest instance whose METHOD probe lists such
     * an outcome. A site key digests the condition's bytecode, so this holds across builds, where a
     * bare index can name another outcome. It holds only where the role and case name one outcome:
     * nothing resolves for a site with no key, for an outcome that shares its role and case with
     * another in its site on either side, for a case label holding a redacted literal or a
     * placeholder, or for a match with no branch key. An instance whose match fails those tests is
     * passed over for the next.
     */
    private fun declaredGuard(
        guard: Int,
        declared: DeclaredClassInfo,
        method: DeclaredMethodInfo,
        location: MergedLocation,
        branchIds: Map<OutcomeKey, OutcomeId>,
    ): OutcomeId? {
        val nodeKey = location.key.method
        branchIds[OutcomeKey(declared.serviceInstanceId, nodeKey, guard)]?.let { return it }
        val site = method.branchSites.firstOrNull { site -> site.outcomes.any { it.branchIndex == guard } } ?: return null
        val siteKey = site.siteKey ?: return null
        val outcome = site.outcomes.first { it.branchIndex == guard }

        fun BranchOutcome.sameCase(other: BranchOutcome) = role == other.role && caseKey == other.caseKey && caseLabel == other.caseLabel
        if (site.outcomes.count { it.sameCase(outcome) } != 1) return null
        if (outcome.caseLabel.any {
                it.kind == ConditionPartKind.PLACEHOLDER ||
                    (it.kind == ConditionPartKind.STRING_LITERAL && it.text == REDACTED_LITERAL)
            }
        ) {
            return null
        }
        return location.members.firstNotNullOfOrNull { (key, probe) ->
            probe.branchSites
                .filter { it.siteKey == siteKey }
                .flatMap { candidate ->
                    candidate.outcomes
                        .filter { it.sameCase(outcome) }
                        .takeIf { it.size == 1 }
                        .orEmpty()
                }.firstNotNullOfOrNull { match ->
                    branchIds[OutcomeKey(key.serviceInstanceId, nodeKey, match.branchIndex)]?.takeIf { it.branchKey != null }
                }
        }
    }

    /** A class's supertypes from either source: its manifest record, or a complete baseline's declaration. */
    private fun supertypesOf(className: String): SupertypesInfo? =
        supertypesByClassName[className]
            ?: consultedDeclaredClasses[className]?.let { SupertypesInfo(it.superClassName, it.interfaceNames) }

    /** Every known class name's direct subtypes, from either supertypes source, for widening a virtual call down. */
    private fun buildReverseSubtypes(): Map<String, List<String>> {
        val reverse = mutableMapOf<String, MutableList<String>>()
        val classNames = supertypesByClassName.keys + consultedDeclaredClasses.keys
        for (className in classNames) {
            val info = supertypesOf(className) ?: continue
            info.superClassName?.let { reverse.getOrPut(it) { mutableListOf() } += className }
            info.interfaceNames.forEach { reverse.getOrPut(it) { mutableListOf() } += className }
        }
        return reverse
    }

    /**
     * Breadth-first walk from [owner] up through its supertypes to the first type with a key in
     * [nodes] named ([name], [desc]), [owner] itself included. Null if the whole chain, as far as it is
     * known, never reaches one.
     */
    private fun findDeclaringType(
        nodes: Set<NodeKey>,
        owner: String,
        name: String,
        desc: String,
    ): String? {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue += owner
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (NodeKey(current, name, desc) in nodes) return current
            val info = supertypesOf(current) ?: continue
            info.superClassName?.let { queue += it }
            queue += info.interfaceNames
        }
        return null
    }

    /** Every transitive subtype of [owner], excluding itself, that has a matching ([name], [desc]) key in [nodes]. */
    private fun widenToSubtypes(
        nodes: Set<NodeKey>,
        reverseSubtypes: Map<String, List<String>>,
        owner: String,
        name: String,
        desc: String,
    ): Set<NodeKey> {
        val result = mutableSetOf<NodeKey>()
        val visited = mutableSetOf(owner)
        val queue = ArrayDeque<String>()
        queue += reverseSubtypes[owner].orEmpty()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val key = NodeKey(current, name, desc)
            if (key in nodes) result += key
            queue += reverseSubtypes[current].orEmpty()
        }
        return result
    }

    private fun toProbeRef(
        info: NodeInfo,
        key: NodeKey,
    ): ProbeRef =
        ProbeRef(
            className = key.className,
            methodName = key.methodName,
            methodDescriptor = key.methodDescriptor,
            line = info.line,
            kind = ProbeKind.METHOD,
            branchIndex = null,
            neverLoaded = info.neverLoaded,
        )

    /**
     * True if the endpoint identified by [verb] and [routeTemplate] has a summed call count above
     * zero. Both arguments are normalised with [RouteTemplateNormalizer.normalizeVerb] and
     * [RouteTemplateNormalizer.normalize] before lookup, so `wasCalled("get", "/checkout/")` finds
     * the same endpoint a manifest reported as `GET /checkout`.
     *
     * Throws [UnknownEndpointException] if no manifest ever mentioned this endpoint.
     */
    public fun wasCalled(
        verb: String,
        routeTemplate: String,
    ): Boolean = checked { callCount(verb, routeTemplate) > 0L }

    /**
     * The endpoint's call count, summed across every instance that reported it: each instance's
     * own `hits_total` is already merged with max() against every redelivered value it sent, and
     * this sums one such total per instance, mirroring how [hitCount] aggregates method probes
     * across instances by class and method name. [verb] and [routeTemplate] are normalised the
     * same way [wasCalled] normalises them.
     *
     * Throws [UnknownEndpointException] if no manifest ever mentioned this endpoint.
     */
    public fun callCount(
        verb: String,
        routeTemplate: String,
    ): Long {
        return checked {
            val identity = normalizeEndpointIdentity(verb, routeTemplate)
            val keys = endpointKeysByIdentity[identity] ?: throw unknownEndpoint(identity)
            return keys.sumOf { endpointHitsByKey[it] ?: 0L }
        }
    }

    /**
     * Every endpoint at least one instance reported, of any [EndpointDiscoverySource], whose
     * summed call count is zero, sorted by route template then verb. An endpoint discovered by
     * dispatch was called, so it appears here only until its first count arrives.
     */
    public fun neverCalled(): List<EndpointRef> =
        checked {
            endpointRefsByIdentity
                .filterKeys { identity -> (endpointKeysByIdentity[identity]?.sumOf { endpointHitsByKey[it] ?: 0L } ?: 0L) <= 0L }
                .values
                .sortedWith(compareBy({ it.routeTemplate }, { it.verb }))
        }

    /** Every endpoint any instance ever reported, sorted by route template then verb. */
    public fun endpoints(): List<EndpointRef> =
        checked {
            endpointRefsByIdentity.values.sortedWith(compareBy({ it.routeTemplate }, { it.verb }))
        }

    /** Every endpoint module reported as disabled by any instance, distinct by module name, sorted by module name. */
    public fun disabledEndpointModules(): List<DisabledEndpointModule> =
        checked { disabledEndpointModulesByName.values.sortedBy { it.module } }

    /**
     * Blocks until some manifest, from any instance, has mentioned the endpoint identified by
     * [verb] and [routeTemplate], normalised the same way [wasCalled] normalises its arguments.
     * Throws [java.util.concurrent.TimeoutException] if [timeout] elapses first.
     */
    @Throws(TimeoutException::class)
    public fun awaitEndpoint(
        verb: String,
        routeTemplate: String,
        timeout: Duration,
    ) {
        checkNoRejections()
        val identity = normalizeEndpointIdentity(verb, routeTemplate)
        awaitUntil(timeout, "no manifest ever mentioned endpoint $verb $routeTemplate within $timeout") {
            endpointRefsByIdentity.containsKey(identity)
        }
    }

    private fun normalizeEndpointIdentity(
        verb: String,
        routeTemplate: String,
    ): EndpointIdentity = EndpointIdentity(RouteTemplateNormalizer.normalizeVerb(verb), RouteTemplateNormalizer.normalize(routeTemplate))

    private fun unknownEndpoint(identity: EndpointIdentity): UnknownEndpointException {
        val disabled = disabledEndpointModulesByName.values.sortedBy { it.module }
        if (disabled.isNotEmpty()) {
            val listed = disabled.joinToString(", ") { "${it.module}: ${it.reason}" }
            return UnknownEndpointException(
                "${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest; " +
                    "an endpoint module reported itself disabled and may be why: $listed",
            )
        }
        return UnknownEndpointException("${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest")
    }

    /**
     * What the collector's dependency rules say about the dependency carrying `groupId:artifactId`, merged across
     * every instance that listed it. A shaded jar carries several identities and matches any one of
     * them. [groupId] null or empty matches a filename-derived identity, which has no group: for
     * example `dependency(null, "commons-lang3")` for a jar with no `pom.properties`, or
     * `dependency("com.fasterxml.jackson.core", "jackson-databind")` for one with it.
     *
     * The agent sends a dependency's entry only after a confirmed delta send has carried its first
     * loaded-class count, and it holds each reference mapping to the dependency under the same
     * condition. So this answers as soon as the entry has arrived: that instance's
     * counts for it, and its mappings to it, have arrived by then. [awaitDependency] waits for the
     * entry.
     *
     * Two things are not covered by that. The hits and reference sites that tell used from
     * unreached arrive like any probe data, so a test waits for them with [awaitSettled]. And a
     * dependency is judged on the instances whose entry has arrived: with several instances, one
     * that loaded the jar but whose entry has not arrived yet does not count. Call
     * [awaitDependenciesListed] first when that matters.
     *
     * A shaded jar carries the identity of every library it bundles, so the plain jar and a
     * shaded one can both carry `groupId:artifactId`. The dependency whose only identity it is
     * answers then. When no single one is left, this throws [IllegalStateException] naming each
     * candidate's [DependencyStatus.identityKey]. Which candidates there are depends on which
     * entries have arrived, so call [awaitDependenciesListed] first when a shaded jar may carry
     * the identity.
     *
     * Throws [UnknownDependencyException] if no manifest has listed the dependency, naming the
     * identities this collector does know.
     */
    public fun dependency(
        groupId: String?,
        artifactId: String,
    ): DependencyStatus {
        return checked {
            val wanted = groupId.orEmpty() to artifactId
            val candidates = computeDependencyReport(dependencyViews()).findings.filter { wanted in it.identities }
            if (candidates.isEmpty()) throw unknownDependency(wanted)
            val finding =
                candidates.singleOrNull()
                    ?: candidates.singleOrNull { it.identities == listOf(wanted) }
                    ?: throw IllegalStateException(
                        "${wanted.first}:${wanted.second} is carried by ${candidates.size} dependencies: " +
                            candidates.joinToString(", ") { it.identityKey },
                    )
            return toDependencyStatus(finding)
        }
    }

    /**
     * Blocks until some manifest has listed the dependency carrying `groupId:artifactId`, matched
     * the way [dependency] matches. [dependency] then answers without throwing. Throws
     * [TimeoutException] if [timeout] elapses first.
     */
    @Throws(TimeoutException::class)
    public fun awaitDependency(
        groupId: String?,
        artifactId: String,
        timeout: Duration,
    ) {
        checkNoRejections()
        val wanted = groupId.orEmpty() to artifactId
        awaitUntil(timeout, "no manifest listed dependency ${wanted.first}:${wanted.second} within $timeout") {
            dependencyLocations.values.any { view -> view.identities.any { (it.groupId to it.artifactId) == wanted } }
        }
    }

    /**
     * Blocks until at least one instance has been heard from and every instance heard from has
     * sent `dependencies_listed`. The agent sets that flag once its startup listing, and every
     * reference mapping recorded before the listing ended, has reached this collector.
     * [unloadedDependencies], [unreferencedDependencies], [unreachedDependencies] and
     * [absentReferences] answer only after this point. Throws [TimeoutException] if [timeout]
     * elapses first, naming the instances still waiting. An agent whose listing failed never sends
     * the flag, so this times out for it.
     */
    @Throws(TimeoutException::class)
    public fun awaitDependenciesListed(timeout: Duration) {
        checkNoRejections()
        awaitUntil(timeout, { dependenciesListedTimeoutMessage(timeout) }) {
            instanceIds.isNotEmpty() && instancesWaitingForDependencyListing().isEmpty()
        }
    }

    private fun dependenciesListedTimeoutMessage(timeout: Duration): String {
        val waiting = instancesWaitingForDependencyListing()
        if (instanceIds.isEmpty()) return "no instance was heard from within $timeout"
        return "these instances did not send dependencies_listed within $timeout: ${waiting.joinToString(", ")}"
    }

    /**
     * Every dependency some instance listed from its startup classpath with no class from it
     * loaded on any instance, sorted by [DependencyStatus.identityKey]. Needs neither references
     * nor a static baseline.
     *
     * Throws [IllegalStateException] until every instance heard from has sent
     * `dependencies_listed`, and while no instance has been heard from. Before that point an empty
     * list could mean "not listed yet". Call [awaitDependenciesListed] first.
     */
    public fun unloadedDependencies(): List<DependencyStatus> =
        checked { dependenciesWithStatus(DependencyUsage.UNLOADED, needsSplit = false) }

    /**
     * Every loaded dependency nothing in the adopter's code references, sorted by
     * [DependencyStatus.identityKey]. Often a library another library needs, or one reached only
     * through a service lookup, so this is an observation, not a verdict that the jar can go.
     *
     * Throws [IllegalStateException] unless every loaded dependency could be split into
     * unreferenced or unreached: that needs `references_recorded`, which the agent sends only with
     * `includePackages` set, and a complete static baseline (`staticBaselineEnabled=true`) from
     * every instance that lists it. An empty list without them would read as "none" when the
     * real answer is "unknown". Throws the same way as [unloadedDependencies] until every instance
     * heard from has sent `dependencies_listed`, and checks that first.
     */
    public fun unreferencedDependencies(): List<DependencyStatus> =
        checked { dependenciesWithStatus(DependencyUsage.UNREFERENCED, needsSplit = true) }

    /**
     * Every dependency the adopter's code references only from methods never hit or classes never
     * loaded, sorted by [DependencyStatus.identityKey], each with those sites in
     * [DependencyStatus.sites]. Throws [IllegalStateException] under the same conditions as
     * [unreferencedDependencies].
     */
    public fun unreachedDependencies(): List<DependencyStatus> =
        checked {
            dependenciesWithStatus(DependencyUsage.UNREACHED, needsSplit = true)
        }

    /**
     * Every referenced class no loader could find, sorted by class name, with the sites that
     * reference it: code guarded by a check for an optional library, for example.
     *
     * Throws [IllegalStateException] the same way as [unloadedDependencies] until every instance
     * heard from has sent `dependencies_listed`. The agent holds no absent reference back, but it
     * sends none until its listing ends. After that check, this throws when no instance sent
     * `references_recorded`, which the agent sends only with `includePackages` set, since an
     * empty list would then say nothing.
     */
    public fun absentReferences(): List<AbsentReference> {
        return checked {
            checkDependenciesListed()
            val report = computeDependencyReport(dependencyViews())
            check(!report.referencesUnavailable) {
                "no instance sent references_recorded, so absent references are unknown: run the agent with includePackages set"
            }
            return report.absentReferences
        }
    }

    private fun dependenciesWithStatus(
        status: DependencyUsage,
        needsSplit: Boolean,
    ): List<DependencyStatus> {
        checkDependenciesListed()
        val report = computeDependencyReport(dependencyViews())
        if (needsSplit) {
            val unsplit = report.findings.filter { it.status == DependencyUsage.NO_LIVE_REFERENCE || it.status == DependencyUsage.LOADED }
            check(!report.referencesUnavailable && unsplit.isEmpty()) {
                val which =
                    if (report.referencesUnavailable) {
                        "no instance sent references_recorded"
                    } else {
                        "these read ${DependencyUsage.NO_LIVE_REFERENCE} or ${DependencyUsage.LOADED}: " +
                            unsplit.joinToString(", ") { it.identityKey }
                    }
                "unreferenced and unreached need references_recorded (the agent's includePackages set) and a complete static " +
                    "baseline (staticBaselineEnabled=true) from every instance that lists the dependency; $which"
            }
        }
        return report.findings
            .filter { it.status == status }
            .sortedBy { it.identityKey }
            .map(::toDependencyStatus)
    }

    private fun instancesWaitingForDependencyListing(): List<String> = (instanceIds - instancesWithDependenciesListed).sorted()

    private fun checkDependenciesListed() {
        check(instanceIds.isNotEmpty()) {
            "no instance has been heard from, so its dependencies are unknown; call awaitDependenciesListed first"
        }
        val waiting = instancesWaitingForDependencyListing()
        check(waiting.isEmpty()) {
            "these instances have not sent dependencies_listed, so their dependency listing may be incomplete: " +
                "${waiting.joinToString(", ")}; call awaitDependenciesListed first"
        }
    }

    private fun unknownDependency(wanted: Pair<String, String>): UnknownDependencyException {
        val known =
            dependencyLocations.values
                .map { it.identityKey }
                .distinct()
                .sorted()
        if (known.isEmpty()) {
            return UnknownDependencyException(
                "${wanted.first}:${wanted.second}: no manifest has listed any dependency yet; the agent lists the startup " +
                    "classpath on a background thread and delivers each entry at the earliest on the flush after the listing ends; " +
                    "call awaitDependency first",
            )
        }
        return UnknownDependencyException(
            "${wanted.first}:${wanted.second}: never listed by any manifest; known dependencies: ${known.joinToString(", ")}",
        )
    }

    private fun toDependencyStatus(finding: DependencyFinding): DependencyStatus =
        DependencyStatus(
            identityKey = finding.identityKey,
            status = finding.status,
            identities =
                finding.identities.map { (groupId, artifactId) ->
                    DependencyIdentityRef(groupId, artifactId, finding.versionsByIdentity["$groupId:$artifactId"].orEmpty())
                },
            loadedClassesTotal = finding.loadedClassesTotal,
            classCount = finding.classCount,
            discoverySources = finding.discoverySources.map { it.toTestkit() }.toSortedSet(),
            sites = finding.sites,
        )

    /**
     * One [InstanceDependencyView] per instance heard from, built the way the demo's stub
     * collector builds its own, except that an unreported class counts as loaded: a sweep found it
     * in the JVM's loaded set.
     */
    private fun dependencyViews(): List<InstanceDependencyView> =
        instanceIds.sorted().map { instanceId ->
            val probes = probesByKey.filterKeys { it.serviceInstanceId == instanceId }
            val methodReferences =
                probes
                    .filterValues { it.kind == WireProbeKind.METHOD }
                    .map { (key, probe) ->
                        HeldReferences(
                            className = probe.className,
                            methodName = probe.methodName,
                            methodDescriptor = probe.methodDescriptor,
                            origin = ReferenceOrigin.MANIFEST_METHOD,
                            referencedClasses = probe.referencedClasses,
                            inline = probe.inline,
                            hits = hitsByKey[key] ?: 0L,
                        )
                    }
            val classReferences =
                classLevelReferences
                    .filterKeys { it.serviceInstanceId == instanceId }
                    .map { (key, referenced) ->
                        val className = classNamesByClassId[key] ?: "class_id ${key.id}"
                        HeldReferences(className, null, null, ReferenceOrigin.MANIFEST_CLASS, referenced)
                    }
            val declaredReferences =
                baselineReferences
                    .filterKeys { it.serviceInstanceId == instanceId }
                    .flatMap { (key, declared) ->
                        listOf(HeldReferences(key.id, null, null, ReferenceOrigin.BASELINE, declared.classReferences)) +
                            declared.methods.map {
                                HeldReferences(
                                    key.id,
                                    it.methodName,
                                    it.methodDescriptor,
                                    ReferenceOrigin.BASELINE,
                                    it.referencedClasses,
                                    inline = it.inline,
                                )
                            }
                    }
            val instanceScans = scans.filterKeys { it.serviceInstanceId == instanceId }.values
            InstanceDependencyView(
                instanceId = instanceId,
                referencesRecorded = instanceId in instancesRecordingReferences,
                baselineComplete = instanceScans.isNotEmpty() && instanceScans.all { it.complete },
                dependencies = dependencyLocations.filterKeys { it.serviceInstanceId == instanceId }.values.toList(),
                loadedClassesTotal = loadedClassesTotals.filterKeys { it.serviceInstanceId == instanceId }.mapKeys { it.key.id },
                externalClasses = externalClassesByName.filterKeys { it.serviceInstanceId == instanceId }.mapKeys { it.key.id },
                references = methodReferences + classReferences + declaredReferences,
                loadedClassNames = loadedClassNamesByInstance[instanceId].orEmpty().toSet(),
            )
        }

    /**
     * Why each payload this collector answered 400 was rejected, oldest first: an empty run id, a
     * second run id under an instance id already heard from, a payload a collector forwarded after
     * stripping fields it did not know, or a body that did not decode or could not be applied. Empty when nothing was rejected. Once it is not empty, every other
     * query and wait throws [IllegalStateException]; this is the one method a test that expects a
     * rejection can still call. See the class doc.
     */
    public fun rejectedPayloads(): List<String> = rejections.toList()

    /** Stops the server. A wait in progress, or begun after this, fails at once. */
    override fun close() {
        closed = true
        server.stop(0)
        executor.shutdown()
        signalAll()
    }

    private fun handleDeltaBatch(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val batch =
            try {
                ProtoPayloadCodec.decodeDeltaBatch(bytes)
            } catch (e: Exception) {
                return reject(exchange, undecodable("delta batch", e))
            }
        rejectionFor("delta batch", batch.resource)?.let { return reject(exchange, it) }
        val applied =
            applying(exchange, "delta batch") {
                val instanceId = batch.resource.serviceInstanceId
                for (delta in batch.deltas) {
                    val key = ProbeKey(instanceId, delta.classId, delta.probeIndex)
                    hitsByKey.merge(key, delta.hitsTotal, ::maxOf)
                }
                for (delta in batch.endpointDeltas) {
                    val key = InstanceEndpointKey(instanceId, delta.endpointId)
                    endpointHitsByKey.merge(key, delta.hitsTotal, ::maxOf)
                }
                for (delta in batch.dependencyDeltas) {
                    loadedClassesTotals.merge(InstanceKey(instanceId, delta.dependencyId), delta.loadedClassesTotal, ::maxOf)
                }
                if (batch.finalFlush) instancesThatEndedCleanly += instanceId
                instanceIds += instanceId
                deltaBatchSeq.incrementAndGet()
            }
        if (!applied) return
        respond(exchange, 200)
        signalAll()
    }

    private fun handleManifest(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val manifest =
            try {
                ProtoPayloadCodec.decodeProbeManifest(bytes)
            } catch (e: Exception) {
                return reject(exchange, undecodable("manifest", e))
            }
        rejectionFor("manifest", manifest.resource)?.let { return reject(exchange, it) }
        val applied =
            applying(exchange, "manifest") {
                val instanceId = manifest.resource.serviceInstanceId
                // A class's own ClassLocation record is always staged and committed together with its
                // probe locations (see ProbeRegistry.computeManifestDeltas), so every classId this manifest
                // mentions in classLocations also has a matching probe location earlier in this same call.
                val classNamesByClassId = mutableMapOf<Int, String>()
                for (location in manifest.probes) {
                    val key = ProbeKey(instanceId, location.classId, location.probeIndex)
                    probesByKey[key] =
                        StoredProbe(
                            location.className,
                            location.methodName,
                            location.methodDescriptor,
                            location.line,
                            location.kind,
                            location.branchIndex,
                            location.inline,
                            location.parameterIndex,
                            location.parameterName,
                            location.overridable,
                            location.targetClassName,
                            location.calls,
                            location.inlinedFromClassName,
                            location.generatedBy,
                            location.referencedClasses,
                            location.branchKey,
                            location.branchSites,
                            location.static,
                            location.lambdaBody,
                            location.parameterNames,
                            location.genericSignature,
                            location.extensionReceiver,
                            location.unreadShape,
                        )
                    nameIndex.computeIfAbsent(location.className) { ConcurrentHashMap.newKeySet() }.add(key)
                    if (location.kind == WireProbeKind.OPTIONAL_ARGUMENT) {
                        val targetClassName = location.targetClassName ?: location.className
                        omissionTargetIndex.computeIfAbsent(targetClassName) { ConcurrentHashMap.newKeySet() }.add(key)
                    }
                    dynamicallyKnownClassNames += location.className
                    classNamesByClassId[location.classId] = location.className
                }
                for (skipped in manifest.skippedClasses) {
                    skippedByClassName.putIfAbsent(skipped.className, SkippedClass(skipped.className, skipped.reason))
                    dynamicallyKnownClassNames += skipped.className
                }
                // An unreported class loaded and reached no transformer, so the agent has nothing to say
                // about it beyond that. Counting it as known is the whole point: without this it stays a
                // "never loaded" answer for a class that ran. See ADR 0027.
                for (unreported in manifest.unreportedClasses) {
                    unreportedByClassName.putIfAbsent(unreported.className, unreported)
                    dynamicallyKnownClassNames += unreported.className
                }
                for (classLocation in manifest.classLocations) {
                    val className = classNamesByClassId[classLocation.classId] ?: continue
                    supertypesByClassName[className] = SupertypesInfo(classLocation.superClassName, classLocation.interfaceNames)
                    kotlinKindByClassName[className] = classLocation.kotlinKind
                }
                for (endpointLocation in manifest.endpoints) {
                    val identity = EndpointIdentity(endpointLocation.verb, endpointLocation.routeTemplate)
                    val key = InstanceEndpointKey(instanceId, endpointLocation.endpointId)
                    endpointKeysByIdentity.computeIfAbsent(identity) { ConcurrentHashMap.newKeySet() }.add(key)
                    // Upserts unconditionally: a re-delivered record carries a newer handler join or
                    // discovery source, and the latest delivery, from any instance, wins.
                    endpointRefsByIdentity[identity] =
                        EndpointRef(
                            verb = endpointLocation.verb,
                            routeTemplate = endpointLocation.routeTemplate,
                            verbatimTemplate = endpointLocation.verbatimTemplate,
                            framework = endpointLocation.framework,
                            discoverySource = endpointLocation.discoverySource.toTestkit(),
                            handlerClass = endpointLocation.handlerClass,
                            handlerMethod = endpointLocation.handlerMethod,
                            handlerDescriptor = endpointLocation.handlerDescriptor,
                        )
                }
                for (module in manifest.disabledEndpointModules) {
                    disabledEndpointModulesByName.putIfAbsent(module.module, DisabledEndpointModule(module.module, module.reason))
                }
                storeDependencyData(manifest)
            }
        if (!applied) return
        respond(exchange, 200)
        signalAll()
    }

    /** Stores what the dependency rules read from one manifest, keyed by its instance. */
    private fun storeDependencyData(manifest: ProbeManifest) {
        val instanceId = manifest.resource.serviceInstanceId
        instanceIds += instanceId
        if (manifest.referencesRecorded) instancesRecordingReferences += instanceId
        val loaded = loadedClassNamesByInstance.computeIfAbsent(instanceId) { ConcurrentHashMap.newKeySet() }
        for (location in manifest.probes) {
            loaded += location.className
            classNamesByClassId[InstanceKey(instanceId, location.classId)] = location.className
        }
        manifest.skippedClasses.forEach { loaded += it.className }
        manifest.unreportedClasses.forEach { loaded += it.className }
        for (references in manifest.classReferences) {
            classLevelReferences[InstanceKey(instanceId, references.classId)] = references.referencedClasses
        }
        for (external in manifest.externalClasses) {
            externalClassesByName[InstanceKey(instanceId, external.className)] = ExternalClassView(external.dependencyId, external.absent)
        }
        // The entries go in after the mappings and the flag goes in last. A query answers once it
        // sees an entry or the flag, and it runs outside this handler's thread, so it must never
        // see either before the rest of the same manifest.
        for (dependency in manifest.dependencies) {
            val key = InstanceKey(instanceId, dependency.dependencyId)
            dependencyLocations[key] =
                DependencyView(
                    dependencyId = dependency.dependencyId,
                    identities =
                        dependency.identities.map {
                            DependencyIdentityView(it.groupId.orEmpty(), it.artifactId, it.version.orEmpty())
                        },
                    discoverySource = dependency.discoverySource,
                    classCount = dependency.classCount,
                    location = dependency.location,
                )
        }
        if (manifest.dependenciesListed) instancesWithDependenciesListed += instanceId
    }

    private fun handleStaticBaseline(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val baseline =
            try {
                ProtoPayloadCodec.decodeStaticBaseline(bytes)
            } catch (e: Exception) {
                return reject(exchange, undecodable("static baseline", e))
            }
        rejectionFor("static baseline", baseline.resource)?.let { return reject(exchange, it) }
        val applied =
            applying(exchange, "static baseline") {
                val instanceId = baseline.resource.serviceInstanceId
                val scanKey = ScanKey(instanceId, baseline.scannedAt)
                val progress = scans.computeIfAbsent(scanKey) { ScanProgress(baseline.chunkCount) }
                val wasComplete = progress.complete
                progress.received += baseline.chunkIndex
                progress.declaredNames += baseline.declaredClasses.map { it.className }
                progress.allInlineOrGeneratedNames +=
                    baseline.declaredClasses
                        .filter {
                            it.methods.isNotEmpty() &&
                                it.methods.all { method ->
                                    method.inline || method.generatedBy != WireGeneratedBy.NONE ||
                                        method.unreadShape != WireUnreadShape.NONE
                                }
                        }.map { it.className }
                for (declaredClass in baseline.declaredClasses) {
                    progress.declaredClasses[declaredClass.className] =
                        DeclaredClassInfo(
                            serviceInstanceId = instanceId,
                            methods =
                                declaredClass.methods.map {
                                    DeclaredMethodInfo(
                                        it.methodName,
                                        it.methodDescriptor,
                                        it.inline,
                                        it.calls,
                                        it.generatedBy,
                                        it.referencedClasses,
                                        it.static,
                                        it.parameterNames,
                                        it.genericSignature,
                                        it.extensionReceiver,
                                        it.unreadShape,
                                        it.branchSites,
                                    )
                                },
                            superClassName = declaredClass.superClassName,
                            interfaceNames = declaredClass.interfaceNames,
                            kotlinKind = declaredClass.kotlinKind,
                        )
                    baselineReferences[InstanceKey(instanceId, declaredClass.className)] =
                        BaselineReferences(
                            declaredClass.referencedClasses,
                            progress.declaredClasses.getValue(declaredClass.className).methods,
                        )
                }
                for (external in baseline.externalClasses) {
                    externalClassesByName[InstanceKey(instanceId, external.className)] =
                        ExternalClassView(external.dependencyId, external.absent)
                }
                instanceIds += instanceId
                if (!wasComplete && progress.complete) {
                    consultedDeclaredNames += progress.declaredNames
                    consultedAllInlineOrGeneratedNames += progress.allInlineOrGeneratedNames
                    for ((className, info) in progress.declaredClasses) consultedDeclaredClasses.putIfAbsent(className, info)
                    completedScans += scanKey
                }
            }
        if (!applied) return
        respond(exchange, 200)
        signalAll()
    }

    /**
     * Throws [IllegalStateException] listing every reason in [rejectedPayloads] once there is one.
     * Every public query and wait calls this first, so a test fails on a rejected payload even
     * when it never looks at [rejectedPayloads]: a query answered without that payload's data
     * could read as confirmed when it is not.
     */
    private fun checkNoRejections() {
        val reasons = rejections.toList()
        check(reasons.isEmpty()) {
            "this collector rejected ${reasons.size} payload(s), so its answers are incomplete:\n" + reasons.joinToString("\n") { "  $it" }
        }
    }

    /**
     * Runs [query] after [checkNoRejections], under [stateLock]'s read lock, so it never sees a
     * payload half applied.
     */
    private inline fun <T> checked(query: () -> T): T {
        checkNoRejections()
        return stateLock.read { query() }
    }

    /**
     * Null when a payload from [resource] may be kept, otherwise why not. The first run id heard
     * under an instance id is the only one this collector accepts for it; see the class doc.
     */
    private fun rejectionFor(
        payload: String,
        resource: ResourceAttributes,
    ): String? {
        val instanceId = resource.serviceInstanceId
        if (resource.runId.isEmpty()) return "$payload from instance $instanceId has an empty run id"
        if (resource.fieldsStripped) {
            return "$payload from instance $instanceId was forwarded by a collector that stripped fields its bindings " +
                "did not know, so it may be missing data. The testkit must receive the agent's payloads directly"
        }
        if (testkitVersion.isNotEmpty() && resource.agentVersion.isNotEmpty() && testkitVersion != resource.agentVersion) {
            return "$payload from instance $instanceId comes from agent version ${resource.agentVersion}, but this testkit is " +
                "version $testkitVersion. The agent and the testkit are released as one version: use the testkit " +
                "of the agent's version, ${resource.agentVersion}"
        }
        if (servesOneJvm) {
            onlyInstance.compareAndSet(null, instanceId)
            val only = onlyInstance.get()
            if (only != instanceId) {
                return "$payload from instance $instanceId, but this collector serves one test JVM and already heard " +
                    "from instance $only. With Gradle's maxParallelForks above 1 every fork's agent posts to this one " +
                    "port; give each fork its own otherlode.testkit.port and exportUrl, or run one fork. A child JVM a " +
                    "test launches with the agent needs a collector of its own"
            }
        }
        val accepted = runIdByInstance.putIfAbsent(instanceId, resource.runId)
        if (accepted == null || accepted == resource.runId) {
            agentVersionByInstance[instanceId] = resource.agentVersion
            instanceRanks.computeIfAbsent(instanceId) { instanceRankSeq.incrementAndGet() }
            return null
        }
        return "$payload from instance $instanceId has run id ${resource.runId}, but this collector already " +
            "accepted run id $accepted for that instance and keys on the instance alone"
    }

    /**
     * The rejection reason for a [payload] that [failure] stopped from decoding. An agent newer than
     * this testkit sends one when it uses a wire value this testkit's codec does not know.
     */
    private fun undecodable(
        payload: String,
        failure: Exception,
    ): String =
        "$payload could not be decoded ($failure); the agent may be newer than this testkit, or something other " +
            "than the agent posted to this collector's port"

    /** Runs at the start of every payload's apply step; a test sets it to make that step fail. */
    @Volatile
    internal var beforeApply: () -> Unit = {}

    /**
     * Runs [apply] under [stateLock]'s write lock and returns true. A throwable from it is recorded
     * like a rejection, answered 400 and returns false, so a payload this collector could not take
     * in fails every later query instead of leaving it short of that payload's data. A 400 rather
     * than a 500, since the agent would resend the same bytes on a 500 and fail the same way.
     */
    private inline fun applying(
        exchange: HttpExchange,
        payload: String,
        apply: () -> Unit,
    ): Boolean =
        try {
            stateLock.write {
                beforeApply()
                apply()
            }
            true
        } catch (e: Throwable) {
            reject(exchange, "$payload could not be applied ($e)")
            false
        }

    /** Records [reason] in [rejectedPayloads], answers 400, and keeps nothing from the payload. */
    private fun reject(
        exchange: HttpExchange,
        reason: String,
    ) {
        rejections += reason
        respond(exchange, 400)
        signalAll()
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
    ) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun signalAll() {
        lock.withLock { condition.signalAll() }
    }

    public companion object {
        /**
         * The text a collector's redaction writes in place of a string literal (collector ADR 0001).
         * One placeholder can stand for different literals, so a case label holding it never
         * identifies an outcome.
         */
        private const val REDACTED_LITERAL = "…"

        /** A class's static initialiser, a class state and never a method row. */
        private const val CLASS_INIT = "<clinit>"

        private const val CONSTRUCTOR = "<init>"

        /**
         * Starts a collector bound to `localhost`. [port] `0` (the default) picks any free port,
         * read back afterwards from [exportUrl].
         */
        @JvmStatic
        @JvmOverloads
        public fun start(port: Int = 0): OtherlodeTestCollector = create(port, servesOneJvm = false, testkitVersion = ownVersion())

        /** Starts a collector that reads its own version as [testkitVersion], so a test can set what a jar's manifest would carry. */
        internal fun startAsVersion(
            port: Int,
            testkitVersion: String,
        ): OtherlodeTestCollector = create(port, servesOneJvm = false, testkitVersion = testkitVersion)

        /**
         * Starts the collector [dev.otherlode.testkit.junit5.OtherlodeExtension] keeps for its own
         * test JVM. It accepts payloads from one instance only: with Gradle's `maxParallelForks`
         * above 1, every fork's agent posts to the one fixed port, and only one fork's collector
         * can hold it. A child JVM a test launches with the agent is a second instance too, and
         * needs a collector of its own.
         */
        internal fun startForOneJvm(port: Int): OtherlodeTestCollector = create(port, servesOneJvm = true, testkitVersion = ownVersion())

        /** The version in this jar's manifest, empty when the classes run from a directory. */
        private fun ownVersion(): String =
            OtherlodeTestCollector::class.java.`package`
                ?.implementationVersion
                .orEmpty()

        private fun create(
            port: Int,
            servesOneJvm: Boolean,
            testkitVersion: String,
        ): OtherlodeTestCollector {
            val httpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
            val executor =
                Executors.newCachedThreadPool { runnable -> Thread(runnable, "otherlode-testkit-http").apply { isDaemon = true } }
            httpServer.executor = executor
            val collector = OtherlodeTestCollector(httpServer, executor, servesOneJvm, testkitVersion)
            httpServer.createContext("/v1/otherlode/deltas", collector::handleDeltaBatch)
            httpServer.createContext("/v1/otherlode/manifest", collector::handleManifest)
            httpServer.createContext("/v1/otherlode/static-baseline", collector::handleStaticBaseline)
            startDaemon(httpServer)
            return collector
        }

        /**
         * A Java thread inherits daemon status from the thread that creates it, and
         * `HttpServer.start()` creates its dispatcher thread on whichever thread calls it. A
         * JUnit extension that called `httpServer.start()` directly would leave that dispatcher
         * thread non-daemon, which alone would keep the JVM from exiting even after every other
         * thread finished. Starting the server from a short-lived daemon thread, and joining it
         * before returning, makes the dispatcher thread daemon too.
         */
        private fun startDaemon(httpServer: HttpServer) {
            val starter = Thread({ httpServer.start() }, "otherlode-testkit-http-starter").apply { isDaemon = true }
            starter.start()
            starter.join()
        }
    }
}

/**
 * One probe's identity and location, merged across every instance that reported it. See
 * [OtherlodeTestCollector] for the class name format and how instances merge. [inline] marks a
 * Kotlin inline function, or a branch inside one: a Kotlin caller copies the body instead of
 * calling it, so a zero count is no evidence the code never ran.
 * [parameterIndex], [parameterName], [overridable], and [targetClassName] are set only when [kind]
 * is [ProbeKind.OPTIONAL_ARGUMENT]. [neverLoaded] is true only for an
 * [OtherlodeTestCollector.unreachedClusters] member that exists solely because a complete static
 * baseline declared it: its [line] is `-1`, since the static scan records no line.
 * [inlinedFromClassName] is set only for a [ProbeKind.BRANCH] probe that is a kept inlined copy:
 * the dotted name of the class whose inline function the compiler copied it from.
 * [generatedBy] is set when [kind] is [ProbeKind.METHOD], for a [ProbeKind.BRANCH] probe as the
 * mark of the method it sits in, and for an [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's
 * mark. [branchKey] is set only for a [ProbeKind.BRANCH] probe: an opaque lowercase hex token
 * naming this outcome across builds and instances, null when the agent could not name it safely.
 * [routine] is set only for a [ProbeKind.BRANCH] probe whose outcome the agent marked routine.
 * [unreadShape] is set for a probe whose method has the outline of compiler output but a body the
 * agent has not read, for a [ProbeKind.BRANCH] probe in such a method, for an
 * [ProbeKind.OPTIONAL_ARGUMENT] probe whose target is one, and for a [ProbeKind.BRANCH] probe whose
 * own outcome the agent marked unread. It is exclusive with [routine].
 */
@ConsistentCopyVisibility
public data class ProbeRef internal constructor(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
    val inline: Boolean = false,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val neverLoaded: Boolean = false,
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy? = null,
    val branchKey: String? = null,
    val routine: RoutineKind? = null,
    val unreadShape: UnreadShape? = null,
)

/**
 * Which of the four root shapes an [UnreachedCluster] has. They call for different fixes, so they
 * are reported apart. Values may be added in a minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class RootKind {
    /**
     * The root is a method, and at least one of its in-scope callers is a method with hits. The
     * caller ran and its call was not behind an untaken outcome. That is mostly an override a
     * virtual call never reached, or a call an exception cut short. [UnreachedCluster.reachedFrom]
     * names the callers.
     */
    REACHED_FROM_HIT,

    /** The root is a method with no in-scope caller at all. Its caller may not exist yet, or may live outside scope. */
    UNCALLED,

    /**
     * The root is a branch outcome that was never hit, in a method with hits. Every method in the
     * cluster runs only through it, so deleting that side of the branch removes the cluster.
     */
    UNTAKEN_OUTCOME,

    /**
     * The root is a class that holds a class finding, named by [UnreachedCluster.rootFinding]. It has
     * no in-scope caller, or at least one caller is a method with hits, which
     * [UnreachedCluster.reachedFrom] names. Such a cluster is listed only when it holds more than
     * the methods the finding folds, since [OtherlodeTestCollector.neverLoaded],
     * [OtherlodeTestCollector.neverInitialized] or [OtherlodeTestCollector.neverInstantiated] already lists
     * the class.
     */
    CLASS_FINDING,
}

/**
 * A finding about a whole class rather than a method in it. A class holds at most one, the
 * strongest that applies, in the order listed. Values may be added in a minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class ClassFinding {
    /** A complete static baseline declared the class and no manifest ever mentioned it. See [OtherlodeTestCollector.neverLoaded]. */
    NEVER_LOADED,

    /** The class loaded and its static initialiser never ran. See [OtherlodeTestCollector.neverInitialized]. */
    NEVER_INITIALIZED,

    /** The class loaded, has instance methods, and none of its constructors ran. See [OtherlodeTestCollector.neverInstantiated]. */
    NEVER_INSTANTIATED,
}

/**
 * One class that holds a [finding], as [OtherlodeTestCollector.neverInitialized] or
 * [OtherlodeTestCollector.neverInstantiated] lists it. [methods] names the class's METHOD probes, one
 * entry per method name, sorted. It includes constructors as `<init>`, and inline and generated
 * methods, but never `<clinit>`, which is a class state. [instancesLoading] counts the instances
 * that loaded the class.
 */
@ConsistentCopyVisibility
public data class ClassFindingRef internal constructor(
    val className: String,
    val finding: ClassFinding,
    val methods: List<String>,
    val instancesLoading: Int,
)

/**
 * One class an [UnreachedCluster] holds whole: every method node of the class, its `<clinit>`
 * included when it has one and a never-run constructor of a never-constructed class with no finding
 * aside, is in the cluster. [finding] is the class's finding when it holds one, and null otherwise.
 * [methods] lists its methods other than `<clinit>` and such a constructor, sorted the same way
 * [OtherlodeTestCollector.neverHit] sorts its results.
 */
@ConsistentCopyVisibility
public data class WholeClass internal constructor(
    val className: String,
    val finding: ClassFinding?,
    val methods: List<ProbeRef>,
)

/**
 * A root plus every never-hit method reachable from it through call edges whose every in-scope
 * caller is itself in the cluster, as found by [OtherlodeTestCollector.unreachedClusters]. Deleting
 * [root] removes the whole cluster.
 *
 * [root] is a [ProbeKind.METHOD] ref for a method root. For a [RootKind.UNTAKEN_OUTCOME] root it
 * is the outcome's [ProbeKind.BRANCH] ref, the same ref [OtherlodeTestCollector.neverHit] lists for
 * it, whose class and method name the method that holds the outcome. For a
 * [RootKind.CLASS_FINDING] root it names only the class: its method name and descriptor are empty,
 * its line is `0`, and [ProbeRef.neverLoaded] is true for a never-loaded class. [rootFinding] is
 * then the class's finding, and it is null for every other kind.
 *
 * [wholeClasses] lists each class the cluster holds whole, sorted by class name. [members] lists
 * every other method, sorted the same way [OtherlodeTestCollector.neverHit] sorts its results.
 * Neither lists `<clinit>`, which is a class state, or a never-run constructor of a loaded class
 * that no code constructed and no class finding covers, such as a utility class's private
 * constructor. A method
 * root is in its own cluster, and so are a class root's methods; an outcome root is not.
 * [membersTotal] counts every method the cluster holds, and [methods] lists them all.
 * [neverLoadedClasses] counts the distinct classes with a method in the cluster that exists only
 * because a complete static baseline declared it; see [ProbeRef.neverLoaded].
 *
 * [reachedFrom] is set for a [RootKind.REACHED_FROM_HIT] root, and for a [RootKind.CLASS_FINDING]
 * root that a method with hits calls: the methods with hits that call it, sorted the same way as
 * [members]. It is empty for every other root.
 */
@ConsistentCopyVisibility
public data class UnreachedCluster internal constructor(
    val root: ProbeRef,
    val rootKind: RootKind,
    val members: List<ProbeRef>,
    val neverLoadedClasses: Int,
    internal val rootSite: BranchSite? = null,
    val reachedFrom: List<ProbeRef> = emptyList(),
    val rootFinding: ClassFinding? = null,
    val wholeClasses: List<WholeClass> = emptyList(),
) {
    /** How many methods the cluster holds: [members] plus the methods of [wholeClasses]. */
    public val membersTotal: Int get() = members.size + wholeClasses.sumOf { it.methods.size }

    /** Every method the cluster holds, [members] and the methods of [wholeClasses], sorted the same way as [members]. */
    public val methods: List<ProbeRef>
        get() =
            (members + wholeClasses.flatMap { it.methods }).sortedWith(
                compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }),
            )
}

/**
 * One optional parameter's identity, as found by [OtherlodeTestCollector.neverSupplied] or
 * [OtherlodeTestCollector.alwaysSupplied]. [className], [methodName], and [methodDescriptor] name the
 * target function the parameter belongs to, not the synthetic `$default` method its omission
 * probe actually sits in: for a Scala constructor default getter, [className] is the constructor's
 * own class, not the companion module class the getter's slot lives on. [targetClassName] is the
 * raw value the manifests reported, the greatest when they differ, and null unless the target
 * crosses a class boundary.
 */
@ConsistentCopyVisibility
public data class OptionalParameterRef internal constructor(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val parameterIndex: Int,
    val parameterName: String,
    val line: Int,
    val targetClassName: String? = null,
)

/**
 * Thrown when a query names a class or method [OtherlodeTestCollector] has no probe for, instead of
 * reading as "confirmed never hit". The message names one of four cases, checked in this order:
 *
 * 1. The class was matched by `includePackages` but ByteBuddy could not instrument it, so it was
 *    reported as skipped.
 * 2. The class was declared by a complete static baseline scan, but no manifest from any instance
 *    ever mentioned it: it never loaded during the observation window.
 * 3. The class is instrumented and has manifest probes, but none match the requested method name
 *    or descriptor.
 * 4. The class was never mentioned anywhere at all: not matched by `includePackages`, misspelled,
 *    or not loaded yet.
 */
public class UnknownProbeException internal constructor(
    message: String,
) : RuntimeException(message)

/**
 * One endpoint's identity and display fields, as reported by a manifest. [verb] and
 * [routeTemplate] are the normalised identity; [verbatimTemplate] keeps the framework's own
 * spelling for display. [handlerClass], [handlerMethod], and [handlerDescriptor] are null until a
 * framework hook joins a handler to the endpoint: the method the framework invokes for it where
 * the framework exposes one, or the handler object's class where only the object is known.
 */
@ConsistentCopyVisibility
public data class EndpointRef internal constructor(
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String?,
    val handlerMethod: String?,
    val handlerDescriptor: String?,
)

/**
 * Thrown when a query names a `(verb, route template)` [OtherlodeTestCollector] has no endpoint for,
 * instead of reading as "confirmed never called". The message names one of two cases, checked in
 * this order:
 *
 * 1. At least one endpoint module reported itself disabled. The message names every disabled
 *    module and its reason, since the unmentioned endpoint may belong to one of them.
 * 2. No manifest, from any instance, ever mentioned this endpoint.
 */
public class UnknownEndpointException internal constructor(
    message: String,
) : RuntimeException(message)
