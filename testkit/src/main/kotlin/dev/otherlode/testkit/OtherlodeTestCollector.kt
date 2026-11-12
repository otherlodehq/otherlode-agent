package dev.otherlode.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.EndpointTemplateKind
import dev.otherlode.export.InitializationFailure
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import dev.otherlode.export.GeneratedBy as WireGeneratedBy
import dev.otherlode.export.OutsideCaller as WireOutsideCaller
import dev.otherlode.export.ProbeKind as WireProbeKind
import dev.otherlode.export.RoutineKind as WireRoutineKind
import dev.otherlode.export.UnreadShape as WireUnreadShape

/**
 * An embeddable collector for the Otherlode agent's wire protocol. A test runs the agent against
 * [exportUrl], then asks this collector what the agent reported.
 *
 * Class names are dotted binary names, such as `com.acme.OrdersKt` or `com.acme.Outer$Inner`.
 * Results merge the instances heard from, so a result names no instance.
 *
 * A query for a probe, endpoint or dependency that no manifest has mentioned throws
 * [UnknownProbeException], [UnknownEndpointException] or [UnknownDependencyException]. A query that
 * rests on zero counts, such as [neverHit], throws [IllegalStateException] while an instance's zero
 * counts are not evidence; see [neverInitialized].
 *
 * This collector keys probes, endpoints and dependencies on the instance id, not the run id. It
 * answers 400 to a payload it cannot accept, such as one with an empty run id or a second run id
 * under a known instance id, and records the reason in [rejectedPayloads]. After that, the other
 * queries and waits throw [IllegalStateException].
 *
 * Close this with [close].
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
        val outsideCaller: WireOutsideCaller? = null,
    ) {
        fun isLookedThrough(): Boolean = generatedBy != WireGeneratedBy.NONE || unreadShape != WireUnreadShape.NONE
    }

    private data class SupertypesInfo(
        val superClassName: String?,
        val interfaceNames: List<String>,
    )

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
        fun isLookedThrough(): Boolean = generatedBy != WireGeneratedBy.NONE || unreadShape != WireUnreadShape.NONE
    }

    private data class DeclaredClassInfo(
        val serviceInstanceId: String,
        val methods: List<DeclaredMethodInfo>,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val kotlinKind: KotlinKind = KotlinKind.NONE,
    )

    private data class NodeKey(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
    )

    private data class NodeInfo(
        val line: Int,
        val neverLoaded: Boolean,
        val hits: Long,
        val edges: Set<GuardedEdge>,
        val outsideCaller: WireOutsideCaller? = null,
    )

    private data class OutcomeId(
        val branchKey: String?,
        val branchIndex: Int?,
    )

    private data class GuardedEdge(
        val edge: CallEdge,
        val guard: OutcomeId?,
    )

    private data class ResolvedCall(
        val callee: NodeKey,
        val guard: OutcomeId?,
    )

    private class CallGraph(
        val nodes: Map<NodeKey, NodeInfo>,
        val calls: Map<NodeKey, Set<ResolvedCall>>,
        val hitGeneratedCallers: Map<NodeKey, NodeInfo> = emptyMap(),
    ) {
        fun infoOf(key: NodeKey): NodeInfo = nodes[key] ?: hitGeneratedCallers.getValue(key)
    }

    private data class ClusterNode(
        val method: NodeKey,
        val outcome: OutcomeId? = null,
        val isClass: Boolean = false,
    )

    private data class OutcomeKey(
        val serviceInstanceId: String,
        val method: NodeKey,
        val branchIndex: Int,
    )

    private class ClassNode(
        val finding: ClassFinding,
        val methods: List<NodeKey>,
    )

    private data class JudgeableMethod(
        val key: NodeKey,
        val hits: Long,
        val static: Boolean,
        val lambdaBody: Boolean,
    )

    private class ClassJudgement(
        val findings: Map<String, ClassFinding>,
        val covered: Map<String, List<NodeKey>>,
        val inNeverHitCode: Set<NodeKey>,
        val constructed: Set<String>,
    ) {
        val coveredMethods: Set<NodeKey> = covered.values.flatten().toSet()

        val foldedMethods: Set<NodeKey> = coveredMethods + inNeverHitCode
    }

    private data class OutcomeNode(
        val ref: ProbeRef,
        val site: BranchSite?,
        val siteGuard: OutcomeId?,
    )

    private class ClusterGraph(
        val callersOf: Map<ClusterNode, Set<ClusterNode>>,
        val calleesOf: Map<ClusterNode, Set<ClusterNode>>,
    )

    private data class ScanKey(
        val serviceInstanceId: String,
        val scannedAt: Long,
    )

    private data class OmissionTargetKey(
        val targetClassName: String,
        val methodName: String,
        val methodDescriptor: String,
        val parameterIndex: Int?,
    )

    private data class EndpointIdentity(
        val verb: String,
        val routeTemplate: String,
        val templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    )

    private data class InstanceEndpointKey(
        val serviceInstanceId: String,
        val endpointId: Int,
    )

    private data class LocationKey(
        val method: NodeKey,
        val kind: WireProbeKind,
        val parameterIndex: Int?,
        val outcome: OutcomeId?,
    )

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
        val outsideCaller: WireOutsideCaller?,
    ) {
        val newest: Map.Entry<ProbeKey, StoredProbe> get() = members.first()

        val isLookedThrough: Boolean get() = generatedBy != WireGeneratedBy.NONE || unreadShape != WireUnreadShape.NONE
    }

    private data class InstanceKey<T>(
        val serviceInstanceId: String,
        val id: T,
    )

    private data class BaselineReferences(
        val classReferences: List<String>,
        val methods: List<DeclaredMethodInfo>,
    )

    private class ScanProgress(
        val chunkCount: Int,
    ) {
        val received: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val declaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val allInlineOrGeneratedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val declaredClasses: MutableMap<String, DeclaredClassInfo> = ConcurrentHashMap()
        val complete: Boolean get() = received.size >= chunkCount
    }

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private val stateLock = ReentrantReadWriteLock()

    internal fun <T> whileApplying(block: () -> T): T = stateLock.write(block)

    private val deltaBatchSeq = AtomicLong(0)

    private val completeFlushCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val countedFlushSequences = ConcurrentHashMap<String, MutableSet<Long>>()

    private val instancesWithCompleteFinalFlush: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val probeRefComparator: Comparator<ProbeRef> =
        compareBy({ it.className }, { it.methodName }, { it.line }, {
            it.branchIndex
                ?: -1
        })

    private val probesByKey = ConcurrentHashMap<ProbeKey, StoredProbe>()
    private val nameIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()

    private val omissionTargetIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()
    private val hitsByKey = ConcurrentHashMap<ProbeKey, Long>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedClass>()

    private val supertypesByClassName = ConcurrentHashMap<String, SupertypesInfo>()

    private val kotlinKindByClassName = ConcurrentHashMap<String, KotlinKind>()

    private val unreportedByClassName = ConcurrentHashMap<String, UnreportedClass>()

    private val failedClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val consultedDeclaredClasses = ConcurrentHashMap<String, DeclaredClassInfo>()

    private val dynamicallyKnownClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val instancesThatEndedCleanly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()
    private val completedScans: MutableSet<ScanKey> = ConcurrentHashMap.newKeySet()
    private val consultedDeclaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val consultedAllInlineOrGeneratedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val endpointRefsByIdentity = ConcurrentHashMap<EndpointIdentity, EndpointRef>()

    private val endpointKeysByIdentity = ConcurrentHashMap<EndpointIdentity, MutableSet<InstanceEndpointKey>>()
    private val endpointHitsByKey = ConcurrentHashMap<InstanceEndpointKey, Long>()
    private val endpointFrameworkByKey = ConcurrentHashMap<InstanceEndpointKey, String>()
    private val disabledModuleNamesByInstance = ConcurrentHashMap<String, MutableSet<String>>()
    private val disabledEndpointModulesByName = ConcurrentHashMap<String, DisabledEndpointModule>()

    private val dependencyLocations = ConcurrentHashMap<InstanceKey<Int>, DependencyView>()

    private val loadedClassesTotals = ConcurrentHashMap<InstanceKey<Int>, Long>()
    private val externalClassesByName = ConcurrentHashMap<InstanceKey<String>, ExternalClassView>()
    private val classLevelReferences = ConcurrentHashMap<InstanceKey<Int>, List<String>>()
    private val classNamesByClassId = ConcurrentHashMap<InstanceKey<Int>, String>()
    private val baselineReferences = ConcurrentHashMap<InstanceKey<String>, BaselineReferences>()
    private val instancesRecordingReferences: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val initializationFailures = ConcurrentHashMap<InstanceKey<String>, Set<InitializationFailure>>()

    private val supertypesByInstance = ConcurrentHashMap<InstanceKey<String>, SupertypesInfo>()

    private val unwatchedInstances: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val unplacedVerificationFailures = ConcurrentHashMap<String, Long>()

    private val instancesWithDependenciesListed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val instanceIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val loadedClassNamesByInstance = ConcurrentHashMap<String, MutableSet<String>>()

    private val runIdByInstance = ConcurrentHashMap<String, String>()

    private val instanceRanks = ConcurrentHashMap<String, Long>()
    private val instanceRankSeq = AtomicLong(0)

    private val agentVersionByInstance = ConcurrentHashMap<String, String>()
    private val rejections = CopyOnWriteArrayList<String>()

    @Volatile
    private var closed = false

    private val onlyInstance = AtomicReference<String?>()

    /** Base URL to pass as the agent's `exportUrl=` option. */
    public val exportUrl: String = "http://localhost:${server.address.port}"

    /**
     * Blocks until a delta batch arrives that was received after this call began, even an empty
     * one. Throws [TimeoutException] if [timeout] elapses first.
     */
    @Throws(TimeoutException::class)
    public fun awaitNextFlush(timeout: Duration) {
        checkNoRejections()
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "no delta batch arrived within $timeout") { deltaBatchSeq.get() > start }
    }

    /**
     * Blocks until each instance heard from, including one first heard from after this call began,
     * has sent two complete flushes since the call began or has ended with a complete final flush.
     * Once an instance has sent a final flush, it settles only if that final flush is complete. The
     * collector must have heard from at least one instance. A complete flush is a delta batch that carries
     * `flush_complete`. A batch retried under the same nonzero payload sequence counts once.
     *
     * Throws [TimeoutException] if [timeout] elapses first, naming each instance that has not
     * settled and why.
     */
    @Throws(TimeoutException::class)
    public fun awaitSettled(timeout: Duration) {
        checkNoRejections()
        val completeFlushesAtStart = completeFlushCounts.mapValues { it.value.get() }
        awaitUntil(timeout, { settledTimeoutMessage(timeout, completeFlushesAtStart) }) {
            instanceIds.isNotEmpty() && instancesNotSettled(completeFlushesAtStart).isEmpty()
        }
    }

    private fun instancesNotSettled(completeFlushesAtStart: Map<String, Int>): Map<String, String> {
        val reasons = sortedMapOf<String, String>()
        for (instanceId in instanceIds) {
            if (instanceId in instancesWithCompleteFinalFlush) continue
            if (instanceId in instancesThatEndedCleanly) {
                reasons[instanceId] = "ended with a final flush that was not complete"
                continue
            }
            val sinceStart = (completeFlushCounts[instanceId]?.get() ?: 0) - (completeFlushesAtStart[instanceId] ?: 0)
            if (sinceStart < SETTLED_COMPLETE_FLUSHES) {
                reasons[instanceId] = "sent $sinceStart of $SETTLED_COMPLETE_FLUSHES complete flushes since the call began"
            }
        }
        return reasons
    }

    private fun settledTimeoutMessage(
        timeout: Duration,
        completeFlushesAtStart: Map<String, Int>,
    ): String {
        if (instanceIds.isEmpty()) return "no instance was heard from within $timeout"
        val reasons = instancesNotSettled(completeFlushesAtStart)
        return "these instances had not settled within $timeout: " +
            reasons.entries.joinToString("; ") { (instanceId, reason) -> "$instanceId $reason" }
    }

    private fun recordCompleteFlush(
        instanceId: String,
        payloadSequence: Long,
        finalFlush: Boolean,
    ) {
        val firstArrival =
            payloadSequence == 0L ||
                countedFlushSequences.computeIfAbsent(instanceId) { ConcurrentHashMap.newKeySet() }.add(payloadSequence)
        if (firstArrival) completeFlushCounts.computeIfAbsent(instanceId) { AtomicInteger() }.incrementAndGet()
        if (finalFlush) instancesWithCompleteFinalFlush += instanceId
    }

    /**
     * Blocks until a manifest from any instance has mentioned a probe for [className] and
     * [methodName]. Throws [TimeoutException] if [timeout] elapses first.
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
                checkNoRejections()
                check(!closed) { "this collector was closed while waiting: ${timeoutMessage()}" }
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException(timeoutMessage())
                condition.awaitNanos(remaining)
            }
        }
    }

    /**
     * True if any instance reported a nonzero hit total for a METHOD probe of [className] and
     * [methodName]. A null [methodDescriptor] matches any overload. Throws [UnknownProbeException]
     * if no manifest declared such a probe.
     */
    @JvmOverloads
    public fun wasHit(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Boolean = checked { findMethodProbes(className, methodName, methodDescriptor).any { (hitsByKey[it] ?: 0L) > 0L } }

    /**
     * Sums, over each matching METHOD probe and each instance, the highest hit total that instance
     * reported for the probe. A null [methodDescriptor] sums every overload. Throws
     * [UnknownProbeException] if no manifest declared such a probe.
     */
    @JvmOverloads
    public fun hitCount(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Long = checked { findMethodProbes(className, methodName, methodDescriptor).sumOf { hitsByKey[it] ?: 0L } }

    /**
     * Sums, over each omission probe and each instance, the highest omission count reported for the
     * optional parameter at [parameterIndex] of [className] and [methodName]. A null
     * [methodDescriptor] matches any overload. Throws [UnknownProbeException] if no manifest
     * declared such an omission probe.
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
     * Each optional parameter whose omission total, summed over its omission probes and instances,
     * equals its target method's hit total, which means each call took the default. Sorted by class name,
     * method name, then parameter index.
     *
     * Leaves out an overridable parameter, a target with no hits or no method probe, and a
     * parameter with an inline, generated or unread omission probe. The row's line is the highest
     * known line of its omission probes, or `-1`.
     */
    public fun neverSupplied(): List<OptionalParameterRef> =
        checked {
            optionalParameterFindings { omitted, targetHits, overridable -> !overridable && omitted == targetHits }
        }

    /**
     * Each optional parameter whose omission total, summed over its omission probes and instances,
     * is zero while its target method has hits, which means no call took the default. Sorted as [neverSupplied]
     * sorts. Includes an overridable parameter. Leaves out a target with no hits or no method
     * probe, and a parameter with an inline, generated or unread omission probe. Throws
     * [IllegalStateException] while an instance's zero counts are not evidence; see
     * [neverInitialized].
     */
    public fun alwaysSupplied(): List<OptionalParameterRef> =
        checkedZeroCounts {
            optionalParameterFindings {
                omitted,
                _,
                _,
                ->
                omitted == 0L
            }
        }

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
     * Each METHOD or BRANCH probe that no instance reported a hit for, sorted by class name, method
     * name, line, then branch index. Copies of a probe from several instances or class loaders
     * merge into one row: a method by class, name and descriptor, and a branch outcome by its
     * [ProbeRef.branchKey], or by its branch index when it has none. A hit on any copy removes the
     * row. Where copies disagree, [ProbeRef.line] comes from the newest instance.
     *
     * Leaves out probes that cannot be judged or that another result covers, such as inline,
     * generated and optional-argument probes, the METHOD probe of a static initialiser, methods a
     * class finding covers ([neverInitialized], [neverInstantiated]), a never-hit lambda body folded
     * into the never-hit methods that create it, routine outcomes ([neverHitRoutineOutcomes]),
     * unread shapes ([neverHitUnreadShapes]) and classes in [failedToInitialize]. A constructor is
     * a row only when another constructor of its class ran. Leaves out a BRANCH probe whose site lies
     * in a never-hit method that is not an unread shape, even a method that is not a row itself. It
     * also leaves out a BRANCH probe whose guard is an outcome this list would hold if not for these
     * two rules.
     *
     * Throws [IllegalStateException] while an instance's zero counts are not evidence; see
     * [neverInitialized].
     */
    public fun neverHit(): List<ProbeRef> = checkedZeroCounts { neverHitRows().filter { it.routine == null && it.unreadShape == null } }

    /**
     * Each never-hit BRANCH probe that [neverHit] leaves out because its outcome is routine, with
     * its [ProbeRef.routine] kind, sorted as [neverHit] sorts. Leaves out an outcome whose site
     * [neverHit] leaves out. Throws as [neverHit] does.
     */
    public fun neverHitRoutineOutcomes(): List<ProbeRef> =
        checkedZeroCounts { neverHitRows().filter { it.routine != null && it.unreadShape == null } }

    /**
     * Each never-hit METHOD or BRANCH probe that [neverHit] leaves out because it is an unread
     * shape, with its [ProbeRef.unreadShape] family, sorted as [neverHit] sorts. A probe is unread
     * when its method is or its own outcome is. Leaves out an outcome whose site [neverHit] leaves
     * out. [neverHit] leaves out no site in an unread method. Throws as [neverHit] does.
     */
    public fun neverHitUnreadShapes(): List<ProbeRef> = checkedZeroCounts { neverHitRows().filter { it.unreadShape != null } }

    private fun neverHitRows(): List<ProbeRef> {
        val judgement = judgeClasses()
        val locations = mergedLocations()
        val unjudgeable = failedToInitializeNames() + classesWithInitializerRunning()
        val candidates =
            locations.filter {
                it.key.kind != WireProbeKind.OPTIONAL_ARGUMENT &&
                    !it.inline &&
                    it.generatedBy == WireGeneratedBy.NONE &&
                    it.hits <= 0L &&
                    it.key.method.className !in unjudgeable &&
                    isNeverHitRow(it, judgement)
            }
        val folded = foldedSites(locations, candidates)
        return candidates
            .filter { it.key !in folded }
            .map(::neverHitRef)
            .sortedWith(compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }))
    }

    private fun foldedSites(
        locations: List<MergedLocation>,
        candidates: List<MergedLocation>,
    ): Set<LocationKey> {
        val neverHitMethods =
            locations
                .filter {
                    it.key.kind == WireProbeKind.METHOD &&
                        !it.inline &&
                        it.generatedBy == WireGeneratedBy.NONE &&
                        it.unreadShape == WireUnreadShape.NONE &&
                        it.hits <= 0L
                }.mapTo(HashSet()) { it.key.method }
        val guardOutcomes =
            candidates
                .filter {
                    it.key.kind == WireProbeKind.BRANCH &&
                        it.routine == WireRoutineKind.NONE &&
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

    private fun WireProbeKind.isInitializerSlot(): Boolean =
        this == WireProbeKind.INITIALIZER_RETURNED || this == WireProbeKind.INITIALIZER_THREW

    private fun mergedLocations(): List<MergedLocation> {
        val routineKinds = routineKinds()
        val unreadOutcomes = unreadOutcomeShapes()
        val newestFirst =
            compareByDescending<Map.Entry<ProbeKey, StoredProbe>> { instanceRanks[it.key.serviceInstanceId] ?: 0L }
                .thenBy { it.key.serviceInstanceId }
                .thenBy { it.key.classId }
                .thenBy { it.key.probeIndex }
        return probesByKey.entries
            .filter { !it.value.kind.isInitializerSlot() }
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
                    outsideCaller = members.first().value.outsideCaller,
                )
            }
    }

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
     * Each loaded class with a judgeable static initialiser that ran in no instance, sorted by
     * class name. [neverHit] and [unreachedClusters] fold the class's never-hit methods into this
     * finding. Leaves out a class in [failedToInitialize], and a class whose static initialiser has
     * started but not finished in each instance that loaded it.
     *
     * Throws [IllegalStateException] while an instance's zero counts are not evidence. They are not
     * evidence when a manifest from it reports that verifier rejections were not watched, or when it
     * counts `VerifyError` messages in no read format. The message names each such instance and why.
     */
    public fun neverInitialized(): List<ClassFindingRef> = checkedZeroCounts { classFindingRefs(ClassFinding.NEVER_INITIALIZED) }

    /**
     * Each loaded class with a judgeable constructor and a judgeable instance method, where no
     * judgeable constructor and no instance method ran in any instance, sorted by class name.
     * Leaves out a class [neverInitialized] lists, a class in [failedToInitialize], and a class
     * whose static initialiser has started but not finished in each instance that loaded it.
     *
     * [neverHit] and [unreachedClusters] fold the class's never-hit constructors and instance
     * methods into this finding, and a never-hit lambda body of the class whose creators all fold
     * into a class finding. Throws as [neverInitialized] does.
     */
    public fun neverInstantiated(): List<ClassFindingRef> = checkedZeroCounts { classFindingRefs(ClassFinding.NEVER_INSTANTIATED) }

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

    private fun judgeableMethods(): Map<NodeKey, JudgeableMethod> =
        mergedLocations()
            .filter { it.key.kind == WireProbeKind.METHOD && !it.inline && !it.isLookedThrough }
            .associate { it.key.method to JudgeableMethod(it.key.method, it.hits, it.static, it.lambdaBody) }

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

    private fun judgeClasses(): ClassJudgement {
        val judgeable = judgeableMethods()
        val findings = mutableMapOf<String, ClassFinding>()
        val covered = mutableMapOf<String, List<NodeKey>>()
        val constructed = mutableSetOf<String>()
        val instanceMethodRan =
            mergedLocations()
                .filter { it.key.kind == WireProbeKind.METHOD && it.hits > 0L && !it.static }
                .map { it.key.method }
                .filter { it.methodName != CONSTRUCTOR && it.methodName != CLASS_INIT }
                .mapTo(mutableSetOf()) { it.className }
        val withheld = failedToInitializeNames() + classesWithInitializerRunning()
        for ((className, methods) in judgeable.values.groupBy { it.key.className }) {
            val initialisers = methods.filter { it.key.methodName == CLASS_INIT }
            val constructors = methods.filter { it.key.methodName == CONSTRUCTOR }
            val instanceMethods = methods.filter { it.key.methodName != CONSTRUCTOR && it.key.methodName != CLASS_INIT && !it.static }
            if (constructors.any { it.hits > 0L }) constructed += className
            val finding =
                when {
                    className in withheld -> {
                        continue
                    }

                    initialisers.isNotEmpty() && initialisers.all { it.hits == 0L } -> {
                        ClassFinding.NEVER_INITIALIZED
                    }

                    constructors.isNotEmpty() &&
                        className !in constructed &&
                        instanceMethods.isNotEmpty() &&
                        className !in instanceMethodRan -> {
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
            outsideCaller = location.outsideCaller.toTestkit(),
        )

    internal fun agentVersion(serviceInstanceId: String): String? = checked { agentVersionByInstance[serviceInstanceId] }

    /**
     * Each class a manifest reported as skipped, matched but not instrumented, one per class name,
     * sorted by class name.
     */
    public fun skippedClasses(): List<SkippedClass> = checked { skippedByClassName.values.sortedBy { it.className } }

    internal fun endedCleanly(serviceInstanceId: String): Boolean = checked { serviceInstanceId in instancesThatEndedCleanly }

    internal fun instancesEndedCleanly(): Set<String> = checked { instancesThatEndedCleanly.toSet() }

    internal fun unreportedClasses(): List<String> = checked { unreportedByClassName.keys.sorted() }

    /**
     * Class names a manifest reported as failed to load and no manifest named as probed, skipped or
     * unreported, sorted. [neverLoaded] leaves these out.
     */
    public fun failedToLoad(): List<String> = checked { failedToLoadNames().sorted() }

    private fun failedToLoadNames(): Set<String> = failedClassNames.filterTo(mutableSetOf()) { it !in dynamicallyKnownClassNames }

    /**
     * Class names that failed to initialise in some instance and initialised in none, sorted. A
     * class failed to initialise when a manifest names it in `classes_failed_to_initialize`. Some
     * classes whose supertypes reach such a class count too. Lists only classes a manifest named as
     * probed, skipped or unreported. [neverHit] and the class findings leave these out.
     */
    public fun failedToInitialize(): List<String> = checked { failedToInitializeNames().sorted() }

    private fun failedToInitializeNames(): Set<String> {
        if (initializationFailures.isEmpty()) return emptySet()
        val initialized = initializedClassNames()
        val failed = HashSet<String>()
        val namedByInstance = initializationFailures.entries.groupBy { it.key.serviceInstanceId }
        for ((instance, entries) in namedByInstance) {
            val rejected = entries.filter { InitializationFailure.VERIFIER_REJECTED in it.value }.mapTo(HashSet()) { it.key.id }
            val threw = entries.filter { InitializationFailure.STATIC_INITIALIZER_THREW in it.value }.mapTo(HashSet()) { it.key.id }
            val supertypes = supertypesByInstance.filterKeys { it.serviceInstanceId == instance }.mapKeys { it.key.id }
            val withDefaultMethods =
                probesByKey.entries
                    .filter { (key, probe) ->
                        key.serviceInstanceId == instance &&
                            probe.kind == WireProbeKind.METHOD &&
                            !probe.static &&
                            probe.methodName != CLASS_INIT &&
                            probe.methodName != CONSTRUCTOR
                    }.mapTo(HashSet()) { it.value.className }
            failed += rejected
            failed += threw
            for (className in supertypes.keys) {
                if (className !in failed && carriesFailure(className, supertypes, rejected, threw, withDefaultMethods)) failed += className
            }
        }
        return failed.filterTo(HashSet()) { it !in initialized && it in dynamicallyKnownClassNames }
    }

    private fun initializedClassNames(): Set<String> {
        class Evidence {
            var hasSlots = false
            var returned = false
            var anyHit = false
        }

        val evidence = HashMap<InstanceKey<String>, Evidence>()
        for ((key, probe) in probesByKey) {
            val state = evidence.getOrPut(InstanceKey(key.serviceInstanceId, probe.className)) { Evidence() }
            val hit = (hitsByKey[key] ?: 0L) > 0L
            if (probe.kind.isInitializerSlot()) state.hasSlots = true
            if (probe.kind == WireProbeKind.INITIALIZER_RETURNED && hit) state.returned = true
            if (hit) state.anyHit = true
        }
        return evidence
            .filterValues { if (it.hasSlots) it.returned else it.anyHit }
            .keys
            .mapTo(HashSet()) { it.id }
    }

    private fun carriesFailure(
        className: String,
        supertypes: Map<String, SupertypesInfo>,
        rejected: Set<String>,
        threw: Set<String>,
        withDefaultMethods: Set<String>,
    ): Boolean {
        val seen = HashSet<String>()
        val pending = ArrayDeque<String>()
        pending += className
        while (pending.isNotEmpty()) {
            val info = supertypes[pending.removeLast()] ?: continue
            for (superclass in listOfNotNull(info.superClassName)) {
                if (superclass in rejected || superclass in threw) return true
                if (seen.add(superclass)) pending += superclass
            }
            for (superinterface in info.interfaceNames) {
                if (superinterface in rejected || (superinterface in threw && superinterface in withDefaultMethods)) return true
                if (seen.add(superinterface)) pending += superinterface
            }
        }
        return false
    }

    private fun classesWithInitializerRunning(): Set<String> {
        class Initializer {
            var entered = false
            var returnedSlot = false
            var threwSlot = false
            var finished = false
        }

        val initializers = HashMap<InstanceKey<String>, Initializer>()
        val loading = HashMap<String, MutableSet<String>>()
        for ((key, probe) in probesByKey) {
            val instanceClass = InstanceKey(key.serviceInstanceId, probe.className)
            val hit = (hitsByKey[key] ?: 0L) > 0L
            when {
                probe.kind == WireProbeKind.METHOD -> {
                    loading.getOrPut(probe.className) { HashSet() } += key.serviceInstanceId
                    if (probe.methodName == CLASS_INIT) {
                        val initializer = initializers.getOrPut(instanceClass) { Initializer() }
                        if (hit) initializer.entered = true
                    }
                }

                probe.kind.isInitializerSlot() -> {
                    val initializer = initializers.getOrPut(instanceClass) { Initializer() }
                    if (probe.kind == WireProbeKind.INITIALIZER_RETURNED) initializer.returnedSlot = true else initializer.threwSlot = true
                    if (hit) initializer.finished = true
                }
            }
        }
        return loading
            .filter { (className, instances) ->
                instances.all { instance ->
                    initializers[InstanceKey(instance, className)]?.let { it.entered && it.returnedSlot && it.threwSlot && !it.finished } ==
                        true
                }
            }.keys
    }

    internal fun kotlinKind(className: String): KotlinKind? =
        checked { kotlinKindByClassName[className] ?: consultedDeclaredClasses[className]?.kotlinKind }

    /**
     * Class names a complete static baseline declared and no manifest named as probed, skipped or
     * unreported, sorted. Leaves out a class that declares methods, each of them inline, generated
     * or unread, and a class in [failedToLoad]. Throws [IllegalStateException] if no static baseline
     * scan has completed.
     */
    public fun neverLoaded(): List<String> {
        return checked {
            check(completedScans.isNotEmpty()) { "no complete static baseline scan has been received yet" }
            return consultedDeclaredNames
                .filter { it !in dynamicallyKnownClassNames && it !in consultedAllInlineOrGeneratedNames && it !in failedClassNames }
                .sorted()
        }
    }

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
     * Each unreached cluster in the call graph, sorted by [UnreachedCluster.membersTotal]
     * descending, then by root.
     *
     * The nodes are methods merged across instances, never-hit branch outcomes in methods with
     * hits, and classes that hold a [ClassFinding]. Some outcomes, such as routine and unread ones,
     * are not nodes. A method that only a complete static baseline declared carries
     * [ProbeRef.neverLoaded]. A class node stands for the never-hit methods its finding covers. The
     * edges are the call edges of manifests and complete baselines. A call goes to its owner or the
     * nearest supertype that has the method, and a virtual call other than to a constructor also goes
     * to each known subtype that has it. A call guarded by an outcome node counts as a call from that
     * outcome.
     *
     * A root is a never-hit node with no caller, or with a caller that is a method with hits.
     * [RootKind] names its shape. Some nodes, such as static initialisers, are never roots. The
     * cluster is the root plus each never-hit node reachable from it whose callers are all in the
     * cluster. A class or outcome root gives a cluster only when the cluster also holds another class
     * node or a method node it lists. Inline, generated and unread methods are not roots or members. Nor
     * is a method of a class in [failedToLoad] or [failedToInitialize], or of a class whose static
     * initialiser has started but not finished in each instance that loaded it, though it still
     * counts as a caller.
     * Throws [IllegalStateException] as [neverInitialized] does.
     */
    public fun unreachedClusters(): List<UnreachedCluster> {
        return checkedZeroCounts {
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

            val withheld = failedToInitializeNames() + classesWithInitializerRunning() + failedToLoadNames()

            fun isNeverHit(node: ClusterNode) =
                when {
                    node.isClass -> node in classNodes
                    node.outcome != null -> node in outcomes
                    else -> !isHit(node.method) && node.method !in coveredBy && node.method.className !in withheld
                }

            val neverHitNodes =
                graph.nodes.keys
                    .filter {
                        !isHit(it) &&
                            it !in coveredBy &&
                            it.methodName != CLASS_INIT &&
                            it !in unjudged &&
                            it.className !in withheld
                    }.map { ClusterNode(it) } + outcomes.keys + classNodes.keys
            return neverHitNodes
                .mapNotNull { node ->
                    val callers = clusterGraph.callersOf[node].orEmpty()
                    val hitCallers = callers.filter { it.outcome == null && !it.isClass && isHit(it.method) }
                    if (callers.isNotEmpty() && hitCallers.isEmpty()) return@mapNotNull null
                    val kind =
                        when {
                            node.isClass -> RootKind.CLASS_FINDING
                            node.outcome != null -> RootKind.UNTAKEN_OUTCOME
                            callers.isEmpty() && graph.nodes[node.method]?.outsideCaller != null -> RootKind.CALLED_FROM_OUTSIDE_SCOPE
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
                it != root &&
                    it.outcome == null &&
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

    private fun buildClassNodes(
        graph: CallGraph,
        judgement: ClassJudgement,
    ): Map<ClusterNode, ClassNode> {
        val classNodes = mutableMapOf<ClusterNode, ClassNode>()
        val failedToLoad = failedToLoadNames()
        for ((className, finding) in judgement.findings) {
            val methods = judgement.covered[className].orEmpty().filter { (graph.nodes[it]?.hits ?: -1L) == 0L }
            if (methods.isNotEmpty()) classNodes[classNode(className)] = ClassNode(finding, methods)
        }
        graph.nodes
            .filter { (key, info) -> info.neverLoaded && key.className !in judgement.findings && key.className !in failedToLoad }
            .keys
            .groupBy { it.className }
            .forEach { (className, methods) -> classNodes[classNode(className)] = ClassNode(ClassFinding.NEVER_LOADED, methods) }
        return classNodes
    }

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
        val unjudgeable = failedToInitializeNames() + classesWithInitializerRunning()
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
                it.key.kind == WireProbeKind.BRANCH &&
                    outcome != null &&
                    !it.inline &&
                    it.generatedBy == WireGeneratedBy.NONE &&
                    it.unreadShape == WireUnreadShape.NONE &&
                    it.routine == WireRoutineKind.NONE &&
                    it.hits == 0L &&
                    isHit(it.key.method) &&
                    it.key.method.className !in unjudgeable
            }.associate { location ->
                val instance = location.newest.key.serviceInstanceId
                val method = location.key.method
                val branchIndex = location.newest.value.branchIndex
                val rawSite =
                    sitesByMethod[InstanceKey(instance, method)]
                        ?.firstOrNull { site -> site.outcomes.any { it.branchIndex == branchIndex } }
                val guard = rawSite?.guard?.let { branchIds[OutcomeKey(instance, method, it)] }
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
            nodes[nodeKey] =
                NodeInfo(
                    line = location.line,
                    neverLoaded = false,
                    hits = location.hits,
                    edges = edges,
                    outsideCaller = location.outsideCaller,
                )
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

    private fun supertypesOf(className: String): SupertypesInfo? =
        supertypesByClassName[className]
            ?: consultedDeclaredClasses[className]?.let { SupertypesInfo(it.superClassName, it.interfaceNames) }

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
            outsideCaller = info.outsideCaller.toTestkit(),
        )

    /**
     * True if the path endpoint [verb] [routeTemplate] has a call count above zero, summed over
     * instances. It normalises both arguments first, so `wasCalled("get", "/checkout/")` finds
     * `GET /checkout`. A regex or servlet pattern endpoint with the same text does not match.
     * Throws [UnknownEndpointException] if no manifest mentioned this path endpoint.
     */
    public fun wasCalled(
        verb: String,
        routeTemplate: String,
    ): Boolean = checked { callCount(verb, routeTemplate) > 0L }

    /**
     * The path endpoint's call count: the sum, over instances, of the highest total each reported.
     * It normalises [verb] and [routeTemplate] as [wasCalled] normalises them. Throws
     * [UnknownEndpointException] if no manifest mentioned this path endpoint.
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
     * Each endpoint any instance reported whose summed call count is zero, sorted by route
     * template, verb, then path, regex and servlet pattern. Leaves out an endpoint whose handler
     * class is in [failedToInitialize], and one whose framework module each reporting instance
     * disabled ([disabledEndpointModules]). Throws [IllegalStateException] as [neverInitialized]
     * does.
     */
    public fun neverCalled(): List<EndpointRef> =
        checkedZeroCounts {
            val failedToInitialize = failedToInitializeNames()
            endpointRefsByIdentity
                .filterKeys { identity -> (endpointKeysByIdentity[identity]?.sumOf { endpointHitsByKey[it] ?: 0L } ?: 0L) <= 0L }
                .filterKeys { identity -> !endpointModuleDisabledEverywhere(identity) }
                .values
                .filter { it.handlerClass !in failedToInitialize }
                .sortedWith(compareBy({ it.routeTemplate }, { it.verb }, { it.isServletPatternTemplate }, { it.isRegexTemplate }))
        }

    private fun endpointModuleDisabledEverywhere(identity: EndpointIdentity): Boolean {
        val keys = endpointKeysByIdentity[identity].orEmpty()
        return keys.isNotEmpty() &&
            keys.all { key ->
                val framework = endpointFrameworkByKey[key]
                framework != null && framework in disabledModuleNamesByInstance[key.serviceInstanceId].orEmpty()
            }
    }

    /** Each endpoint any instance reported, sorted as [neverCalled] sorts. */
    public fun endpoints(): List<EndpointRef> =
        checked {
            endpointRefsByIdentity.values.sortedWith(
                compareBy({
                    it.routeTemplate
                }, { it.verb }, { it.isServletPatternTemplate }, { it.isRegexTemplate }),
            )
        }

    /** Each endpoint module any instance reported as disabled, one per module name, sorted by module name. */
    public fun disabledEndpointModules(): List<DisabledEndpointModule> =
        checked { disabledEndpointModulesByName.values.sortedBy { it.module } }

    /**
     * Blocks until a manifest from any instance has mentioned the path endpoint [verb]
     * [routeTemplate], normalised as [wasCalled] normalises them. Throws [TimeoutException] if
     * [timeout] elapses first.
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
                    "an endpoint module is disabled and may be why: $listed",
            )
        }
        val otherKind = NON_PATH_KINDS.firstOrNull { endpointKeysByIdentity.containsKey(identity.copy(templateKind = it.first)) }
        if (otherKind != null) {
            return UnknownEndpointException(
                "${identity.verb} ${identity.routeTemplate}: no path endpoint has this template; " +
                    "${otherKind.second} does, which this query does not address: read it from endpoints() or neverCalled()",
            )
        }
        return UnknownEndpointException("${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest")
    }

    /**
     * The status of the dependency carrying `groupId:artifactId`, merged across the instances that
     * listed it. A shaded jar carries several identities and matches any of them. A null or empty
     * [groupId] matches an identity with no group, as in `dependency(null, "commons-lang3")`.
     *
     * The answer depends on what has arrived. [awaitDependency] waits for the entry,
     * [awaitDependenciesListed] for each instance's listing, and [awaitSettled] for hits.
     *
     * When several dependencies carry the identity, this returns the status of the one whose sole
     * identity it is. When no single one is, it throws [IllegalStateException] naming each candidate's
     * [DependencyStatus.identityKey]. Throws [UnknownDependencyException] if no manifest listed the
     * dependency. Throws [IllegalStateException] when the status rests on a zero count and an
     * instance's zero counts are not evidence; see [neverInitialized].
     */
    public fun dependency(
        groupId: String?,
        artifactId: String,
    ): DependencyStatus {
        return checked {
            val wanted = groupId.orEmpty() to artifactId
            val candidates = dependencyReport().findings.filter { wanted in it.identities }
            if (candidates.isEmpty()) throw unknownDependency(wanted)
            val finding =
                candidates.singleOrNull()
                    ?: candidates.singleOrNull { it.identities == listOf(wanted) }
                    ?: throw IllegalStateException(
                        "${wanted.first}:${wanted.second} is carried by ${candidates.size} dependencies: " +
                            candidates.joinToString(", ") { it.identityKey },
                    )
            if (finding.status in STATUSES_RESTING_ON_ZERO_COUNTS && finding.sites.isNotEmpty()) checkZeroCountsAreEvidence()
            return toDependencyStatus(finding)
        }
    }

    /**
     * Blocks until a manifest has listed the dependency carrying `groupId:artifactId`, matched as
     * [dependency] matches. Throws [TimeoutException] if [timeout] elapses first.
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
     * Blocks until at least one instance has been heard from and each instance heard from has sent
     * `dependencies_listed`. Dependency queries such as [unloadedDependencies] and
     * [absentReferences] throw until then. Throws [TimeoutException] if [timeout] elapses first,
     * naming the instances still waiting.
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
     * Each dependency with status [DependencyUsage.UNLOADED], listed from a startup classpath with
     * no class from it loaded, sorted by [DependencyStatus.identityKey]. Needs neither references
     * nor a static baseline.
     *
     * Throws [IllegalStateException] while no instance has been heard from, and until each instance
     * heard from has sent `dependencies_listed`. Call [awaitDependenciesListed] first.
     */
    public fun unloadedDependencies(): List<DependencyStatus> =
        checked { dependenciesWithStatus(DependencyUsage.UNLOADED, needsSplit = false) }

    /**
     * Each loaded dependency with status [DependencyUsage.UNREFERENCED], sorted by
     * [DependencyStatus.identityKey]. This is an observation, not a verdict that the jar can go.
     *
     * Throws [IllegalStateException] as [unloadedDependencies] does. After that check, it throws
     * when no instance sent `references_recorded`, or when a dependency reads
     * [DependencyUsage.NO_LIVE_REFERENCE] or [DependencyUsage.LOADED].
     */
    public fun unreferencedDependencies(): List<DependencyStatus> =
        checked { dependenciesWithStatus(DependencyUsage.UNREFERENCED, needsSplit = true) }

    /**
     * Each dependency with status [DependencyUsage.UNREACHED]: referenced, but from no live site,
     * such as a never-hit method or a never-loaded class. Sorted by [DependencyStatus.identityKey],
     * each with those sites in [DependencyStatus.sites]. Throws [IllegalStateException] as
     * [unreferencedDependencies] does, and while an instance's zero counts are not evidence; see
     * [neverInitialized].
     */
    public fun unreachedDependencies(): List<DependencyStatus> =
        checked {
            dependenciesWithStatus(DependencyUsage.UNREACHED, needsSplit = true)
        }

    /**
     * Each dependency with status [DependencyUsage.FAILED_TO_LOAD]: no live site references it, and
     * a class in [failedToLoad] does. Sorted by [DependencyStatus.identityKey], each with its sites
     * in [DependencyStatus.sites]; a site in a class that failed to load ends `(failed to load)`.
     * Throws [IllegalStateException] as [unreachedDependencies] does.
     */
    public fun failedToLoadDependencies(): List<DependencyStatus> =
        checked {
            dependenciesWithStatus(DependencyUsage.FAILED_TO_LOAD, needsSplit = true)
        }

    /**
     * Each dependency with status [DependencyUsage.FAILED_TO_INITIALIZE]: no live site references
     * it, a class in [failedToInitialize] does, and no class in [failedToLoad] does. Sorted by
     * [DependencyStatus.identityKey], each with its sites in [DependencyStatus.sites]; a site in a
     * class that failed to initialise ends `(failed to initialise)`. Throws [IllegalStateException]
     * as [unreachedDependencies] does.
     */
    public fun failedToInitializeDependencies(): List<DependencyStatus> =
        checked {
            dependenciesWithStatus(DependencyUsage.FAILED_TO_INITIALIZE, needsSplit = true)
        }

    /**
     * Each referenced class no loader could find, sorted by class name, with the sites that
     * reference it. Throws [IllegalStateException] as [unloadedDependencies] does. After that
     * check, it throws when no instance sent `references_recorded`.
     */
    public fun absentReferences(): List<AbsentReference> {
        return checked {
            checkDependenciesListed()
            val report = dependencyReport()
            check(!report.referencesUnavailable) {
                "no instance sent references_recorded, so absent references are unknown: run the agent with includePackages set"
            }
            return report.absentReferences
        }
    }

    private fun dependencyReport(): DependencyReport =
        computeDependencyReport(dependencyViews(), failedToLoad().toSet(), failedToInitializeNames())

    private fun dependenciesWithStatus(
        status: DependencyUsage,
        needsSplit: Boolean,
    ): List<DependencyStatus> {
        if (status in STATUSES_RESTING_ON_ZERO_COUNTS) checkZeroCountsAreEvidence()
        checkDependenciesListed()
        val report = dependencyReport()
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
                    DependencyIdentityRef(groupId, artifactId, finding.versionsByIdentity[groupId to artifactId].orEmpty())
                },
            loadedClassesTotal = finding.loadedClassesTotal,
            classCount = finding.classCount,
            discoverySources = finding.discoverySources.map { it.toTestkit() }.toSortedSet(),
            sites = finding.sites,
        )

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
     * Why each payload this collector answered 400 was rejected, oldest first, such as an empty run
     * id or a body that did not decode. Empty when nothing was rejected. Once it is not empty, the
     * other queries and waits throw [IllegalStateException]; this and [close] still answer.
     */
    public fun rejectedPayloads(): List<String> = rejections.toList()

    /**
     * Stops the server. A wait in progress, or begun after this, throws [IllegalStateException] at
     * once unless its condition holds.
     */
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
                if (batch.flushComplete) recordCompleteFlush(instanceId, batch.payloadSequence, batch.finalFlush)
                deltaBatchSeq.incrementAndGet()
            }
        if (!applied) return
        respond(exchange, 202)
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
                            location.outsideCaller,
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
                for (unreported in manifest.unreportedClasses) {
                    unreportedByClassName.putIfAbsent(unreported.className, unreported)
                    dynamicallyKnownClassNames += unreported.className
                }
                for (failed in manifest.failedClasses) failedClassNames += failed.className
                for (classLocation in manifest.classLocations) {
                    val className = classNamesByClassId[classLocation.classId] ?: continue
                    supertypesByClassName[className] = SupertypesInfo(classLocation.superClassName, classLocation.interfaceNames)
                    supertypesByInstance[InstanceKey(instanceId, className)] =
                        SupertypesInfo(classLocation.superClassName, classLocation.interfaceNames)
                    kotlinKindByClassName[className] = classLocation.kotlinKind
                }
                for (endpointLocation in manifest.endpoints) {
                    val identity =
                        EndpointIdentity(
                            endpointLocation.verb,
                            endpointLocation.routeTemplate,
                            endpointLocation.templateKind,
                        )
                    val key = InstanceEndpointKey(instanceId, endpointLocation.endpointId)
                    endpointKeysByIdentity.computeIfAbsent(identity) { ConcurrentHashMap.newKeySet() }.add(key)
                    endpointFrameworkByKey[key] = endpointLocation.framework
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
                            isRegexTemplate = identity.templateKind == EndpointTemplateKind.REGEX,
                            isServletPatternTemplate = identity.templateKind == EndpointTemplateKind.SERVLET_PATTERN,
                        )
                }
                for (module in manifest.disabledEndpointModules) {
                    disabledModuleNamesByInstance.computeIfAbsent(instanceId) { ConcurrentHashMap.newKeySet() }.add(module.module)
                    disabledEndpointModulesByName.putIfAbsent(
                        module.module,
                        DisabledEndpointModule(module.module, module.reason, module.kind.toTestkit()),
                    )
                }
                storeDependencyData(manifest)
                storeVerifierFacts(manifest)
            }
        if (!applied) return
        respond(exchange, 202)
        signalAll()
    }

    private fun storeVerifierFacts(manifest: ProbeManifest) {
        val instanceId = manifest.resource.serviceInstanceId
        for (failed in manifest.classesFailedToInitialize) {
            initializationFailures.merge(InstanceKey(instanceId, failed.className), setOf(failed.reason)) { known, added -> known + added }
        }
        if (!manifest.verifierRejectionsWatched) unwatchedInstances += instanceId
        unplacedVerificationFailures.merge(instanceId, manifest.unplacedVerificationFailures, ::maxOf)
    }

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
            externalClassesByName.putIfAbsent(
                InstanceKey(instanceId, external.className),
                ExternalClassView(external.dependencyId, external.absent),
            )
        }
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
                                    method.inline ||
                                        method.generatedBy != WireGeneratedBy.NONE ||
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
                    externalClassesByName.putIfAbsent(
                        InstanceKey(instanceId, external.className),
                        ExternalClassView(external.dependencyId, external.absent),
                    )
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
        respond(exchange, 202)
        signalAll()
    }

    private fun handleClassListing(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        try {
            ProtoPayloadCodec.decodeClassListing(bytes)
        } catch (e: Exception) {
            return reject(exchange, undecodable("class listing", e))
        }
        respond(exchange, 202)
    }

    private fun checkNoRejections() {
        val reasons = rejections.toList()
        check(reasons.isEmpty()) {
            "this collector rejected ${reasons.size} payload(s), so its answers are incomplete:\n" + reasons.joinToString("\n") { "  $it" }
        }
    }

    private fun checkZeroCountsAreEvidence() {
        val reasons =
            (unwatchedInstances + unplacedVerificationFailures.keys).sorted().mapNotNull { instance ->
                val why =
                    listOfNotNull(
                        "the agent's hook on VerifyError was not installed".takeIf { instance in unwatchedInstances },
                        unplacedVerificationFailures[instance]?.takeIf { it > 0L }?.let {
                            if (it ==
                                1L
                            ) {
                                "1 VerifyError message in no read format"
                            } else {
                                "$it VerifyError messages in no read format"
                            }
                        },
                    )
                if (why.isEmpty()) null else "$instance: ${why.joinToString("; ")}"
            }
        check(reasons.isEmpty()) {
            "this query rests on zero counts, and a class the verifier rejects fires no probe, so the zero counts of these " +
                "instances are not evidence that code never ran:\n" + reasons.joinToString("\n") { "  $it" }
        }
    }

    private inline fun <T> checkedZeroCounts(query: () -> T): T =
        checked {
            checkZeroCountsAreEvidence()
            query()
        }

    private inline fun <T> checked(query: () -> T): T {
        checkNoRejections()
        return stateLock.read { query() }
    }

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
                    "port, since every fork gets the same -javaagent arguments: run the tests that use the testkit " +
                    "with maxParallelForks = 1, for example in a Test task of their own. A child JVM a test launches " +
                    "with the agent needs a collector of its own, started with OtherlodeTestCollector.start()"
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

    private fun undecodable(
        payload: String,
        failure: Exception,
    ): String =
        "$payload could not be decoded ($failure); the agent may be newer than this testkit, or something other " +
            "than the agent posted to this collector's port"

    @Volatile
    internal var beforeApply: () -> Unit = {}

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
        private const val REDACTED_LITERAL = "…"

        private const val SETTLED_COMPLETE_FLUSHES = 2

        private const val CLASS_INIT = "<clinit>"

        private const val CONSTRUCTOR = "<init>"

        private val STATUSES_RESTING_ON_ZERO_COUNTS =
            setOf(
                DependencyUsage.UNREACHED,
                DependencyUsage.NO_LIVE_REFERENCE,
                DependencyUsage.FAILED_TO_LOAD,
                DependencyUsage.FAILED_TO_INITIALIZE,
            )

        /**
         * Starts a collector bound to `localhost`. [port] `0`, the default, picks a free port; read
         * it back from [exportUrl].
         */
        @JvmStatic
        @JvmOverloads
        public fun start(port: Int = 0): OtherlodeTestCollector = create(port, servesOneJvm = false, testkitVersion = ownVersion())

        internal fun startAsVersion(
            port: Int,
            testkitVersion: String,
        ): OtherlodeTestCollector = create(port, servesOneJvm = false, testkitVersion = testkitVersion)

        internal fun startForOneJvm(port: Int): OtherlodeTestCollector = create(port, servesOneJvm = true, testkitVersion = ownVersion())

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
            httpServer.createContext("/v1/otherlode/class-listing", collector::handleClassListing)
            startDaemon(httpServer)
            return collector
        }

        private fun startDaemon(httpServer: HttpServer) {
            val starter = Thread({ httpServer.start() }, "otherlode-testkit-http-starter").apply { isDaemon = true }
            starter.start()
            starter.join()
        }
    }
}

/**
 * One probe's identity and location, merged across the instances that reported it. Class names
 * follow the format [OtherlodeTestCollector] gives.
 *
 * - [line] is `-1` when no line is known, and `0` for a class root.
 * - [inline] is false in the refs the collector returns.
 * - [parameterIndex], [parameterName], [targetClassName] and [overridable] keep their defaults,
 *   null and false, in the refs the collector returns.
 * - [neverLoaded] is true for an [OtherlodeTestCollector.unreachedClusters] method that only a
 *   complete static baseline declared, and for the class root of a never-loaded class.
 * - [inlinedFromClassName] is the dotted name of the class whose inline function this probe's code
 *   was copied from, or null.
 * - [generatedBy] is null in the refs the collector returns, a generated caller in
 *   [UnreachedCluster.reachedFrom] included.
 * - [branchKey] is an opaque token naming a [ProbeKind.BRANCH] outcome across builds and instances,
 *   or null when the outcome has none.
 * - [routine] is the kind of a routine outcome, or null.
 * - [unreadShape] is the unread-shape family of a probe whose method or own outcome is an unread
 *   shape, or null.
 * - [outsideCaller] says why code outside scope may call the method, or is null. When copies
 *   disagree, it comes from the newest instance.
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
    val outsideCaller: OutsideCaller? = null,
)

/**
 * Why code outside scope may call a method: [kind], and [typeName], dotted. For
 * [OutsideCallerKind.OVERRIDES_METHOD], [typeName] is the out-of-scope type that declares the
 * overridden method. For [OutsideCallerKind.CALLBACK_ANNOTATION], it is the annotation.
 */
@ConsistentCopyVisibility
public data class OutsideCaller internal constructor(
    val kind: OutsideCallerKind,
    val typeName: String,
)

/**
 * The shape of an [UnreachedCluster]'s root. A minor release can add values, so a `when` over this
 * enum needs an `else` branch.
 */
public enum class RootKind {
    /**
     * The root is a method, and at least one of its in-scope callers is a method with hits.
     * [UnreachedCluster.reachedFrom] names those callers.
     */
    REACHED_FROM_HIT,

    /** The root is a method with no in-scope caller and no outside caller. */
    UNCALLED,

    /**
     * The root is a method with no in-scope caller and an outside caller, named by
     * [ProbeRef.outsideCaller] on [UnreachedCluster.root].
     */
    CALLED_FROM_OUTSIDE_SCOPE,

    /** The root is a never-hit branch outcome in a method with hits. */
    UNTAKEN_OUTCOME,

    /**
     * The root is a class that holds a class finding, named by [UnreachedCluster.rootFinding].
     * [UnreachedCluster.reachedFrom] names its callers that are methods with hits.
     */
    CLASS_FINDING,
}

/**
 * A finding about a whole class. A class holds at most one, the first that applies in the order
 * listed. A minor release can add values, so a `when` over this enum needs an `else` branch.
 */
public enum class ClassFinding {
    /**
     * A complete static baseline declared the class and no manifest named it as probed, skipped or
     * unreported. Like [OtherlodeTestCollector.neverLoaded], this leaves out a class in
     * [OtherlodeTestCollector.failedToLoad].
     */
    NEVER_LOADED,

    /**
     * The class loaded and its judgeable static initialiser ran in no instance. See
     * [OtherlodeTestCollector.neverInitialized].
     */
    NEVER_INITIALIZED,

    /**
     * The class has a judgeable constructor and a judgeable instance method, and no judgeable
     * constructor and no instance method of it ran. See [OtherlodeTestCollector.neverInstantiated].
     */
    NEVER_INSTANTIATED,
}

/**
 * One class that holds a [finding], as [OtherlodeTestCollector.neverInitialized] or
 * [OtherlodeTestCollector.neverInstantiated] lists it. [methods] names the class's METHOD probes
 * other than `<clinit>`, one entry per method name, sorted. Constructors appear as `<init>`, and
 * it includes inline and generated methods. [instancesLoading] counts the instances that loaded
 * the class.
 */
@ConsistentCopyVisibility
public data class ClassFindingRef internal constructor(
    val className: String,
    val finding: ClassFinding,
    val methods: List<String>,
    val instancesLoading: Int,
)

/**
 * One class an [UnreachedCluster] holds whole. [finding] is the class's finding, or null when it
 * holds none. [methods] lists its methods in the cluster, sorted as [OtherlodeTestCollector.neverHit]
 * sorts. It leaves out `<clinit>`, and a never-run constructor of a loaded class when no constructor
 * of that class ran and no class finding covers it.
 */
@ConsistentCopyVisibility
public data class WholeClass internal constructor(
    val className: String,
    val finding: ClassFinding?,
    val methods: List<ProbeRef>,
)

/**
 * One cluster [OtherlodeTestCollector.unreachedClusters] found: a [root] and the never-hit nodes
 * reachable from it whose callers are all in the cluster.
 *
 * [root] is the method's ref for a method root. For a [RootKind.UNTAKEN_OUTCOME] root it is the
 * outcome's [ProbeKind.BRANCH] ref, as [OtherlodeTestCollector.neverHit] lists it. For a
 * [RootKind.CLASS_FINDING] root it names only the class: its method name and descriptor are empty
 * and its line is `0`. [rootFinding] is that class's finding, and null for other kinds.
 *
 * [wholeClasses] lists the classes the cluster holds whole, sorted by class name. [members] lists
 * its other methods, sorted as [OtherlodeTestCollector.neverHit] sorts. Neither lists `<clinit>`, or
 * a never-run constructor of a loaded class when no constructor of that class ran and no class
 * finding covers it. A method root is in its own cluster, and so are the methods a class root stands
 * for; an outcome root is not. [neverLoadedClasses] counts the classes with a method in the cluster
 * marked [ProbeRef.neverLoaded].
 *
 * [reachedFrom] lists the methods with hits that call a [RootKind.REACHED_FROM_HIT] or
 * [RootKind.CLASS_FINDING] root, sorted as [members]. It is empty for other roots.
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
    /** How many methods the cluster lists: [members] plus the methods of [wholeClasses]. */
    public val membersTotal: Int get() = members.size + wholeClasses.sumOf { it.methods.size }

    /** [members] and the methods of [wholeClasses], sorted as [members] is. */
    public val methods: List<ProbeRef>
        get() =
            (members + wholeClasses.flatMap { it.methods }).sortedWith(
                compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }),
            )
}

/**
 * One optional parameter that [OtherlodeTestCollector.neverSupplied] or
 * [OtherlodeTestCollector.alwaysSupplied] lists. [className], [methodName] and [methodDescriptor]
 * name the method the parameter belongs to, as its omission probes report it. [parameterIndex] is
 * `-1` when unknown, [parameterName] is empty when unknown, and [line] is `-1` when no line is
 * known. [targetClassName] is the target class the manifests reported, the greatest when they
 * differ, or null when none reported one.
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
 * Thrown when a query names a class or method [OtherlodeTestCollector] has no probe for, so that an
 * unknown probe does not read as never hit. The message says which case applies, such as a skipped
 * class, a class only a static baseline declared, or a method the class has no probe for.
 */
public class UnknownProbeException internal constructor(
    message: String,
) : RuntimeException(message)

private val NON_PATH_KINDS =
    listOf(
        EndpointTemplateKind.REGEX to "a regex endpoint",
        EndpointTemplateKind.SERVLET_PATTERN to "a servlet pattern endpoint",
    )

/**
 * One endpoint's identity and display fields. [verb] and [routeTemplate] identify the endpoint, and
 * [verbatimTemplate] is a display spelling of the template. [handlerClass], [handlerMethod] and
 * [handlerDescriptor] come from the last manifest entry this collector received for the endpoint,
 * from any instance, and are null when that entry names no handler.
 *
 * [isRegexTemplate] is true for a regular-expression endpoint, and [isServletPatternTemplate] for a
 * servlet pattern endpoint. Each is a different endpoint from a path endpoint whose template reads
 * the same. The `(verb, route template)` queries, such as [OtherlodeTestCollector.wasCalled],
 * address path endpoints only.
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
    val isRegexTemplate: Boolean,
    val isServletPatternTemplate: Boolean,
)

/**
 * Thrown when a query names a `(verb, route template)` [OtherlodeTestCollector] has no path
 * endpoint for, so that an unknown endpoint does not read as never called. The message says which
 * case applies, such as a disabled endpoint module or a regex or servlet pattern endpoint with the
 * same template.
 */
public class UnknownEndpointException internal constructor(
    message: String,
) : RuntimeException(message)
