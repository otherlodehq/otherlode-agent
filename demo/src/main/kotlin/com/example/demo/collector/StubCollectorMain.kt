package com.example.demo.collector

import com.example.demo.DemoPorts
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.otherlode.proto.BranchRole
import dev.otherlode.proto.BranchSite
import dev.otherlode.proto.CallEdge
import dev.otherlode.proto.CallEdgeKind
import dev.otherlode.proto.ConditionPart
import dev.otherlode.proto.ConditionPartKind
import dev.otherlode.proto.DeltaBatch
import dev.otherlode.proto.EndpointDiscoverySource
import dev.otherlode.proto.GeneratedBy
import dev.otherlode.proto.InitializationFailure
import dev.otherlode.proto.KotlinKind
import dev.otherlode.proto.LineRange
import dev.otherlode.proto.OutsideCaller
import dev.otherlode.proto.OutsideCallerKind
import dev.otherlode.proto.ProbeKind
import dev.otherlode.proto.ProbeManifest
import dev.otherlode.proto.ResourceAttributes
import dev.otherlode.proto.RoutineKind
import dev.otherlode.proto.StaticBaseline
import dev.otherlode.proto.UnreadShape
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// Class, endpoint and dependency ids are assigned per process, and a restart under a pinned
// instance id is a new process, so every key below is scoped to a run.
private data class Run(
    val serviceInstanceId: String,
    val runId: String,
)

private data class InstanceProbeKey(
    val run: Run,
    val classId: Int,
    val probeIndex: Int,
)

private data class InstanceClassKey(
    val run: Run,
    val className: String,
)

private data class InstanceEndpointKey(
    val run: Run,
    val endpointId: Int,
)

private data class InstanceModuleKey(
    val run: Run,
    val module: String,
)

private data class OmissionTargetKey(
    val run: Run,
    val targetClassName: String,
    val methodName: String,
    val methodDescriptor: String,
    val parameterIndex: Int?,
)

private data class ProbeInfo(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
    val inline: Boolean,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy = GeneratedBy.GENERATED_BY_UNSPECIFIED,
    val siteIndex: Int? = null,
    val static: Boolean = false,
    val lambdaBody: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
    val unreadShape: UnreadShape = UnreadShape.UNREAD_SHAPE_UNSPECIFIED,
    val outsideCaller: OutsideCaller? = null,
)

private data class InstanceMethodKey(
    val run: Run,
    val classId: Int,
    val methodName: String,
    val methodDescriptor: String,
)

private data class CallEdgeInfo(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val virtual: Boolean,
    val guard: Int? = null,
    val creates: Boolean = false,
    val implementedInterface: String? = null,
)

private data class ClassNaming(
    val sourceFile: String?,
    val kotlinKind: KotlinKind,
)

private data class SupertypesInfo(
    val superClassName: String?,
    val interfaceNames: List<String>,
)

private data class InstanceClassIdKey(
    val run: Run,
    val classId: Int,
)

private data class SkippedInfo(
    val reason: String,
    val skippedAt: Long,
)

private data class DeclaredMethodInfo(
    val methodName: String,
    val methodDescriptor: String,
    val inline: Boolean,
    val calls: List<CallEdgeInfo> = emptyList(),
    val generatedBy: GeneratedBy = GeneratedBy.GENERATED_BY_UNSPECIFIED,
    val referencedClasses: List<String> = emptyList(),
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
    val unreadShape: UnreadShape = UnreadShape.UNREAD_SHAPE_UNSPECIFIED,
)

private fun ProbeInfo.isLookedThrough(): Boolean =
    generatedBy != GeneratedBy.GENERATED_BY_UNSPECIFIED || unreadShape != UnreadShape.UNREAD_SHAPE_UNSPECIFIED

private fun DeclaredMethodInfo.isLookedThrough(): Boolean =
    generatedBy != GeneratedBy.GENERATED_BY_UNSPECIFIED || unreadShape != UnreadShape.UNREAD_SHAPE_UNSPECIFIED

private data class InstanceDependencyKey(
    val run: Run,
    val dependencyId: Int,
)

private data class BaselineReferences(
    val classReferences: List<String>,
    val methods: List<DeclaredMethodInfo>,
)

private data class EndpointInfo(
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String?,
    val handlerMethod: String?,
    val handlerDescriptor: String?,
)

private val manifestProbes = ConcurrentHashMap<InstanceProbeKey, ProbeInfo>()
private val manifestBranchSites = ConcurrentHashMap<InstanceMethodKey, List<BranchSite>>()
private val everHit = Collections.newSetFromMap(ConcurrentHashMap<InstanceProbeKey, Boolean>())
private val skippedClasses = ConcurrentHashMap<InstanceClassKey, SkippedInfo>()

private val manifestCallEdges = ConcurrentHashMap<InstanceProbeKey, List<CallEdgeInfo>>()
private val supertypesByClassId = ConcurrentHashMap<InstanceClassIdKey, SupertypesInfo>()

private val classNamingByClassName = ConcurrentHashMap<String, ClassNaming>()

// hits_total is cumulative from process start. Merging with max() keeps a re-delivered or
// reordered batch from counting twice.
private val latestHitsTotal = ConcurrentHashMap<InstanceProbeKey, Long>()

private val manifestEndpoints = ConcurrentHashMap<InstanceEndpointKey, EndpointInfo>()
private val disabledEndpointModules = ConcurrentHashMap<InstanceModuleKey, String>()

private val latestEndpointHitsTotal = ConcurrentHashMap<InstanceEndpointKey, Long>()

private val dynamicallyKnownClassNames = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

private val failedToLoadClassNames = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

private val failedToInitializeByRun = ConcurrentHashMap<Run, MutableMap<String, MutableSet<InitializationFailure>>>()

private val unwatchedRuns = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

private val unplacedVerificationFailuresByRun = ConcurrentHashMap<Run, Long>()

private val allRuns = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

private val agentVersionByRun = ConcurrentHashMap<Run, String>()

private val runsThatEndedCleanly = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())
private val staticallyDeclaredClasses = ConcurrentHashMap<String, List<DeclaredMethodInfo>>()

private val staticallyDeclaredSupertypes = ConcurrentHashMap<String, SupertypesInfo>()
private val staticallyUnreadableClasses = ConcurrentHashMap<String, String>()
private val staticallyUnprobedClasses = ConcurrentHashMap<String, String>()

private data class ScanKey(
    val run: Run,
    val scannedAt: Long,
)

private data class ScanProgress(
    val chunkCount: Int,
    val received: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
) {
    val complete: Boolean get() = received.size == chunkCount
}

private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()

private val dependencyLocations = ConcurrentHashMap<InstanceDependencyKey, DependencyView>()

private val latestLoadedClassesTotal = ConcurrentHashMap<InstanceDependencyKey, Long>()
private val firstLoadedAt = ConcurrentHashMap<InstanceDependencyKey, Long>()
private val externalClasses = ConcurrentHashMap<InstanceClassKey, ExternalClassView>()
private val probeReferencedClasses = ConcurrentHashMap<InstanceProbeKey, List<String>>()
private val classLevelReferences = ConcurrentHashMap<InstanceClassIdKey, List<String>>()
private val baselineReferences = ConcurrentHashMap<InstanceClassKey, BaselineReferences>()

private val runsRecordingReferences = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

private val runsWithDependenciesListed = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())
private val manifestRuns = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

private data class NodeKey(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
)

private data class NodeInfo(
    val line: Int,
    val neverLoaded: Boolean,
    val hits: Long,
    val edges: Set<CallEdgeInfo>,
    val outsideCaller: OutsideCaller? = null,
)

private data class ResolvedCall(
    val callee: NodeKey,
    val guard: Int?,
)

private class CallGraph(
    val nodes: Map<NodeKey, NodeInfo>,
    val calls: Map<NodeKey, Set<ResolvedCall>>,
    val hitGeneratedCallers: Map<NodeKey, NodeInfo> = emptyMap(),
)

private data class ClusterNode(
    val method: NodeKey,
    val branchIndex: Int? = null,
    val isClass: Boolean = false,
)

private fun classNode(className: String) = ClusterNode(NodeKey(className, "", ""), isClass = true)

private enum class ClassFinding(
    val text: String,
) {
    NEVER_LOADED("never loaded"),
    NEVER_INITIALISED("never initialised"),
    NEVER_INSTANTIATED("never instantiated"),
}

private class ClassNodeInfo(
    val finding: ClassFinding,
    val methods: List<NodeKey>,
)

private class ClassJudgement(
    val findings: Map<String, ClassFinding>,
    val covered: Map<String, List<NodeKey>>,
    val inNeverHitCode: Set<NodeKey>,
    val constructed: Set<String>,
) {
    val coveredMethods: Set<NodeKey> = covered.values.flatten().toSet()
}

private data class MethodFacts(
    val hits: Long,
    val static: Boolean,
    val lambdaBody: Boolean,
)

private const val CLASS_INIT = "<clinit>"

private const val CONSTRUCTOR = "<init>"

private data class OutcomeNode(
    val line: Int,
    val site: BranchSite?,
)

private class ClusterGraph(
    val callersOf: Map<ClusterNode, Set<ClusterNode>>,
    val calleesOf: Map<ClusterNode, Set<ClusterNode>>,
)

private enum class ClusterRootKind { REACHED_FROM_HIT, UNCALLED, CALLED_FROM_OUTSIDE_SCOPE, UNTAKEN_OUTCOME, CLASS_FINDING }

private data class ClusterMember(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val neverLoaded: Boolean,
    val outsideCaller: OutsideCaller? = null,
)

private data class UnreachedClusterInfo(
    val root: ClusterMember,
    val rootKind: ClusterRootKind,
    val members: List<ClusterMember>,
    val neverLoadedClasses: Int,
    val rootOutcome: RootOutcome? = null,
    val reachedFrom: List<ClusterMember> = emptyList(),
    val rootFinding: ClassFinding? = null,
    val wholeClasses: List<WholeClassInfo> = emptyList(),
) {
    val membersTotal: Int get() = members.size + wholeClasses.sumOf { it.methodsTotal }
}

private data class WholeClassInfo(
    val className: String,
    val finding: ClassFinding?,
    val methodsTotal: Int,
)

private data class RootOutcome(
    val branchIndex: Int,
    val line: Int,
    val site: BranchSite?,
)

/**
 * Stands in for the real collector.
 *
 * On shutdown, it prints two reports: "never hit" (manifest probes with no delta that ever reported a
 * hit) and "never loaded" (classes the static scan found that never once appeared in the reactive
 * manifest at all). It also prints the optional-argument, endpoint, class-finding, unreached-cluster
 * and dependency reports. A constructor prints as `constructor(...)` with its parameter types.
 *
 * Takes the port to bind as its one argument, defaulting to [DemoPorts.COLLECTOR_PORT] for a run
 * by hand. Port 0 binds an ephemeral one; either way the port that was actually bound is printed.
 */
fun main(args: Array<String>) {
    val requestedPort = args.firstOrNull()?.toIntOrNull() ?: DemoPorts.COLLECTOR_PORT
    val server = startStubCollector(requestedPort)
    println("otherlode stub collector listening on ${server.address.port}")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            printNeverHitReport()
            printOmissionReport()
            printEndpointReport()
            printNeverLoadedReport()
            printFailedToLoadReport()
            printFailedToInitialiseReport()
            printClassFindingReport()
            printUnreachedClusterReport()
            printDependencyReport()
        },
    )
}

internal fun startStubCollector(port: Int): HttpServer {
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/v1/otherlode/deltas", ::handleDeltaBatch)
    server.createContext("/v1/otherlode/manifest", ::handleManifest)
    server.createContext("/v1/otherlode/static-baseline", ::handleStaticBaseline)
    server.createContext("/__shutdown", ::handleShutdown)
    server.start()
    return server
}

private fun runOf(resource: ResourceAttributes): Run? =
    resource.runId.takeIf { it.isNotEmpty() }?.let { Run(resource.serviceInstanceId, it) }

// SIGTERM can drop the shutdown-hook report mid-write.
private fun handleShutdown(exchange: HttpExchange) {
    respondOk(exchange)
    Thread { System.exit(0) }.start()
}

private fun handleDeltaBatch(exchange: HttpExchange) {
    val batch = DeltaBatch.parseFrom(exchange.requestBody.readBytes())
    val run = runOf(batch.resource) ?: return respondBadRequest(exchange, "delta batch")
    if (batch.resource.fieldsStripped) return ignoreStripped(exchange, "delta batch", batch.resource)
    if (batch.resource.testRun) return ignoreTestRun(exchange, "delta batch", batch.resource)
    allRuns += run
    for (delta in batch.deltasList) {
        val key = InstanceProbeKey(run, delta.classId, delta.probeIndex)
        everHit += key
        latestHitsTotal.merge(key, delta.hitsTotal, ::maxOf)
    }
    for (delta in batch.endpointDeltasList) {
        val key = InstanceEndpointKey(run, delta.endpointId)
        latestEndpointHitsTotal.merge(key, delta.hitsTotal, ::maxOf)
    }
    for (delta in batch.dependencyDeltasList) {
        val key = InstanceDependencyKey(run, delta.dependencyId)
        latestLoadedClassesTotal.merge(key, delta.loadedClassesTotal, ::maxOf)
        if (delta.firstLoadedAt > 0L) firstLoadedAt.merge(key, delta.firstLoadedAt, ::minOf)
    }
    if (batch.finalFlush) runsThatEndedCleanly += run
    agentVersionByRun[run] = batch.resource.agentVersion
    val totalHits = latestHitsTotal.values.sum()
    val totalEndpointHits = latestEndpointHitsTotal.values.sum()
    val finalFlushSuffix = if (batch.finalFlush) " final=true" else ""
    println(
        "[flush] service=${batch.resource.serviceName}${namespaceField(batch.resource)} instance=${batch.resource.serviceInstanceId} " +
            "agent_version=${batch.resource.agentVersion.ifEmpty { "unknown" }} " +
            "probes_with_activity=${batch.deltasList.size} total_hits=$totalHits " +
            "endpoints_with_activity=${batch.endpointDeltasList.size} total_endpoint_hits=$totalEndpointHits$finalFlushSuffix",
    )
    respondOk(exchange)
}

private fun handleManifest(exchange: HttpExchange) {
    val manifest = ProbeManifest.parseFrom(exchange.requestBody.readBytes())
    val run = runOf(manifest.resource) ?: return respondBadRequest(exchange, "manifest")
    if (manifest.resource.fieldsStripped) return ignoreStripped(exchange, "manifest", manifest.resource)
    if (manifest.resource.testRun) return ignoreTestRun(exchange, "manifest", manifest.resource)
    for (location in manifest.probesList) {
        manifestProbes[InstanceProbeKey(run, location.classId, location.probeIndex)] =
            ProbeInfo(
                className = location.className,
                methodName = location.methodName,
                methodDescriptor = location.methodDescriptor,
                line = location.line,
                kind = location.kind,
                branchIndex = if (location.hasBranchIndex()) location.branchIndex else null,
                inline = location.inline,
                parameterIndex = if (location.hasParameterIndex()) location.parameterIndex else null,
                parameterName = location.parameterName.ifEmpty { null },
                overridable = location.overridable,
                targetClassName = location.targetClassName.ifEmpty { null },
                inlinedFromClassName = location.inlinedFromClassName.ifEmpty { null },
                generatedBy = location.generatedBy,
                siteIndex = if (location.hasSiteIndex()) location.siteIndex else null,
                static = location.static,
                lambdaBody = location.lambdaBody,
                parameterNames = location.parameterNamesList.toList(),
                genericSignature = location.genericSignature,
                extensionReceiver = location.extensionReceiver,
                unreadShape = location.unreadShape,
                outsideCaller = if (location.hasOutsideCaller()) location.outsideCaller else null,
            )
        if (location.branchSitesList.isNotEmpty()) {
            manifestBranchSites[InstanceMethodKey(run, location.classId, location.methodName, location.methodDescriptor)] =
                location.branchSitesList.toList()
        }
        dynamicallyKnownClassNames += location.className
        if (location.callsList.isNotEmpty()) {
            manifestCallEdges[InstanceProbeKey(run, location.classId, location.probeIndex)] =
                location.callsList.map { callEdgeInfo(it) }
        }
        if (location.referencedClassesList.isNotEmpty()) {
            probeReferencedClasses[InstanceProbeKey(run, location.classId, location.probeIndex)] =
                location.referencedClassesList.toList()
        }
    }
    manifestRuns += run
    if (manifest.referencesRecorded) runsRecordingReferences += run
    if (manifest.dependenciesListed) runsWithDependenciesListed += run
    for (dependency in manifest.dependenciesList) {
        dependencyLocations[InstanceDependencyKey(run, dependency.dependencyId)] =
            DependencyView(
                dependencyId = dependency.dependencyId,
                identities = dependency.identitiesList.map { DependencyIdentityView(it.groupId, it.artifactId, it.version) },
                discoverySource = dependency.discoverySource,
                classCount = if (dependency.hasClassCount()) dependency.classCount else null,
                location = dependency.location,
            )
    }
    for (references in manifest.classReferencesList) {
        classLevelReferences[InstanceClassIdKey(run, references.classId)] = references.referencedClassesList.toList()
    }
    for (external in manifest.externalClassesList) {
        externalClasses[InstanceClassKey(run, external.className)] =
            ExternalClassView(if (external.hasDependencyId()) external.dependencyId else null, external.absent)
    }
    for (skipped in manifest.skippedClassesList) {
        skippedClasses[InstanceClassKey(run, skipped.className)] = SkippedInfo(skipped.reason, skipped.skippedAt)
        dynamicallyKnownClassNames += skipped.className
    }
    for (unreported in manifest.unreportedClassesList) {
        dynamicallyKnownClassNames += unreported.className
    }
    for (failed in manifest.failedClassesList) failedToLoadClassNames += failed.className
    for (failed in manifest.classesFailedToInitializeList) {
        failedToInitializeByRun
            .computeIfAbsent(run) { ConcurrentHashMap() }
            .computeIfAbsent(failed.className) { ConcurrentHashMap.newKeySet() } += failed.reason
    }
    if (!manifest.verifierRejectionsWatched) unwatchedRuns += run
    unplacedVerificationFailuresByRun.merge(run, manifest.unplacedVerificationFailures, ::maxOf)
    val classNamesById = manifest.probesList.associate { it.classId to it.className }
    for (classLocation in manifest.classLocationsList) {
        supertypesByClassId[InstanceClassIdKey(run, classLocation.classId)] =
            SupertypesInfo(classLocation.superClassName.ifEmpty { null }, classLocation.interfaceNamesList)
        classNamesById[classLocation.classId]?.let { className ->
            classNamingByClassName[className] = ClassNaming(classLocation.sourceFile.ifEmpty { null }, classLocation.kotlinKind)
        }
    }
    for (endpoint in manifest.endpointsList) {
        manifestEndpoints[InstanceEndpointKey(run, endpoint.endpointId)] =
            EndpointInfo(
                verb = endpoint.verb,
                routeTemplate = endpoint.routeTemplate,
                verbatimTemplate = endpoint.verbatimTemplate,
                framework = endpoint.framework,
                discoverySource = endpoint.discoverySource,
                handlerClass = if (endpoint.hasHandlerClass()) endpoint.handlerClass else null,
                handlerMethod = if (endpoint.hasHandlerMethod()) endpoint.handlerMethod else null,
                handlerDescriptor = if (endpoint.hasHandlerDescriptor()) endpoint.handlerDescriptor else null,
            )
    }
    for (disabled in manifest.disabledEndpointModulesList) {
        disabledEndpointModules[InstanceModuleKey(run, disabled.module)] = "${disabled.kind}: ${disabled.reason}"
    }
    val callEdgeCount = manifest.probesList.sumOf { it.callsList.size }
    println(
        "[manifest] instance=${run.serviceInstanceId} received ${manifest.probesList.size} probe locations " +
            "(known total: ${manifestProbes.size}) and ${manifest.skippedClassesList.size} skipped classes " +
            "(known total: ${skippedClasses.size}), ${manifest.endpointsList.size} endpoints " +
            "(known total: ${manifestEndpoints.size}) and ${manifest.disabledEndpointModulesList.size} disabled endpoint modules, " +
            "$callEdgeCount call edges (known total: ${manifestCallEdges.values.sumOf { it.size }}) and " +
            "${manifest.classLocationsList.size} class location records (known total: ${supertypesByClassId.size}), " +
            "${manifest.dependenciesList.size} dependencies (known total: ${dependencyLocations.size}) and " +
            "${manifest.externalClassesList.size} external classes (known total: ${externalClasses.size}), " +
            "dependencies_listed=${manifest.dependenciesListed}",
    )
    respondOk(exchange)
}

private fun handleStaticBaseline(exchange: HttpExchange) {
    val baseline = StaticBaseline.parseFrom(exchange.requestBody.readBytes())
    val run = runOf(baseline.resource) ?: return respondBadRequest(exchange, "static baseline")
    if (baseline.resource.fieldsStripped) return ignoreStripped(exchange, "static baseline", baseline.resource)
    if (baseline.resource.testRun) return ignoreTestRun(exchange, "static baseline", baseline.resource)
    for (declaredClass in baseline.declaredClassesList) {
        staticallyDeclaredClasses[declaredClass.className] =
            declaredClass.methodsList.map {
                DeclaredMethodInfo(
                    it.methodName,
                    it.methodDescriptor,
                    it.inline,
                    it.callsList.map(::callEdgeInfo),
                    it.generatedBy,
                    it.referencedClassesList.toList(),
                    it.static,
                    it.parameterNamesList.toList(),
                    it.genericSignature,
                    it.extensionReceiver,
                    it.unreadShape,
                )
            }
        baselineReferences[InstanceClassKey(run, declaredClass.className)] =
            BaselineReferences(declaredClass.referencedClassesList.toList(), staticallyDeclaredClasses.getValue(declaredClass.className))
        staticallyDeclaredSupertypes[declaredClass.className] =
            SupertypesInfo(declaredClass.superClassName.ifEmpty { null }, declaredClass.interfaceNamesList)
        classNamingByClassName.putIfAbsent(
            declaredClass.className,
            ClassNaming(declaredClass.sourceFile.ifEmpty { null }, declaredClass.kotlinKind),
        )
    }
    for (external in baseline.externalClassesList) {
        externalClasses[InstanceClassKey(run, external.className)] =
            ExternalClassView(if (external.hasDependencyId()) external.dependencyId else null, external.absent)
    }
    for (unreadable in baseline.unreadableClassesList) {
        staticallyUnreadableClasses[unreadable.className] = unreadable.reason
    }
    for (unprobed in baseline.unprobedClassesList) {
        staticallyUnprobedClasses[unprobed.className] = unprobed.reason
    }
    val progress =
        scans.computeIfAbsent(ScanKey(run, baseline.scannedAt)) { ScanProgress(baseline.chunkCount) }
    progress.received += baseline.chunkIndex
    val callEdgeCount = baseline.declaredClassesList.sumOf { c -> c.methodsList.sumOf { it.callsList.size } }
    val namespace = namespaceField(baseline.resource)
    println(
        "[static-baseline] service=${baseline.resource.serviceName}$namespace chunk=${baseline.chunkIndex + 1}/${baseline.chunkCount} " +
            "declared_classes=${baseline.declaredClassesList.size} " +
            "unreadable=${baseline.unreadableClassesList.size} " +
            "unprobed=${baseline.unprobedClassesList.size} call_edges=$callEdgeCount",
    )
    respondOk(exchange)
}

private fun callEdgeInfo(edge: CallEdge): CallEdgeInfo =
    CallEdgeInfo(
        edge.className,
        edge.methodName,
        edge.methodDescriptor,
        edge.virtual,
        if (edge.hasGuard()) edge.guard else null,
        edge.kind == CallEdgeKind.CREATES,
        edge.implementedInterface.ifEmpty { null },
    )

private fun respondOk(exchange: HttpExchange) {
    exchange.sendResponseHeaders(202, -1)
    exchange.close()
}

private fun namespaceField(resource: ResourceAttributes): String =
    if (resource.hasServiceNamespace() && resource.serviceNamespace.isNotBlank()) " namespace=${resource.serviceNamespace}" else ""

private fun ignoreTestRun(
    exchange: HttpExchange,
    payload: String,
    resource: ResourceAttributes,
) {
    println(
        "[test-run] ignored $payload: service=${resource.serviceName}${namespaceField(resource)} instance=${resource.serviceInstanceId}",
    )
    respondOk(exchange)
}

private fun ignoreStripped(
    exchange: HttpExchange,
    payload: String,
    resource: ResourceAttributes,
) {
    println(
        "[fields-stripped] skipped $payload: service=${resource.serviceName}${namespaceField(resource)} " +
            "instance=${resource.serviceInstanceId}, a collector stripped fields it did not know",
    )
    respondOk(exchange)
}

private fun respondBadRequest(
    exchange: HttpExchange,
    payload: String,
) {
    val message = "rejected $payload: resource.run_id is empty"
    println("[rejected] $message")
    val body = message.toByteArray()
    exchange.sendResponseHeaders(400, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
}

internal fun printNeverHitReport() {
    val failedToInitialize = failedToInitializeClassNames() + classesWithInitializerRunning()
    val judgeableKeys =
        manifestProbes.keys.filter {
            val kind = manifestProbes[it]?.kind
            kind != ProbeKind.OPTIONAL_ARGUMENT && kind != ProbeKind.INITIALIZER_RETURNED && kind != ProbeKind.INITIALIZER_THREW &&
                manifestProbes[it]?.className !in failedToInitialize && (hasZeroCountEvidence(it.run) || it in everHit)
        }
    val neverHitKeys = judgeableKeys.filter { it !in everHit }
    val (inlineNeverHit, notInlineNeverHit) = neverHitKeys.partition { manifestProbes[it]?.inline == true }
    val (generatedNeverHit, notGeneratedNeverHit) =
        notInlineNeverHit.partition { manifestProbes[it]?.generatedBy != GeneratedBy.GENERATED_BY_UNSPECIFIED }
    val (unreadProbeNeverHit, judgeableNeverHit) =
        notGeneratedNeverHit.partition { manifestProbes[it]?.unreadShape != UnreadShape.UNREAD_SHAPE_UNSPECIFIED }
    val judgement = judgeClasses()

    fun methodOf(key: InstanceProbeKey) = manifestProbes.getValue(key).let { NodeKey(it.className, it.methodName, it.methodDescriptor) }
    val (inClassFindings, notInClassFindings) = judgeableNeverHit.partition { methodOf(it) in judgement.coveredMethods }
    val (inNeverHitCode, notFolded) = notInClassFindings.partition { methodOf(it) in judgement.inNeverHitCode }
    val (rowsAndRoutine, classStates) =
        notFolded.partition { key ->
            val info = manifestProbes.getValue(key)
            info.kind != ProbeKind.METHOD ||
                when (info.methodName) {
                    CLASS_INIT -> false
                    CONSTRUCTOR -> info.className in judgement.constructed
                    else -> true
                }
        }
    val folded = foldedSiteProbes(rowsAndRoutine, judgeableNeverHit)
    val (unreadOutcomes, rowsAndRoutineRead) = (rowsAndRoutine - folded).partition { unreadOf(it) != UnreadShape.UNREAD_SHAPE_UNSPECIFIED }
    val (routine, judgeable) = rowsAndRoutineRead.partition { routineOf(it) != RoutineKind.ROUTINE_KIND_UNSPECIFIED }
    val unread = unreadProbeNeverHit + unreadOutcomes
    val judgeableTotal =
        judgeableKeys.count { key ->
            val probe = manifestProbes[key]
            probe != null && !probe.inline && probe.generatedBy == GeneratedBy.GENERATED_BY_UNSPECIFIED &&
                routineOf(key) == RoutineKind.ROUTINE_KIND_UNSPECIFIED && unreadOf(key) == UnreadShape.UNREAD_SHAPE_UNSPECIFIED
        }
    println()
    println("=== otherlode demo: dead code report ===")
    println("runs that sent a final flush: ${runsThatEndedCleanly.size} of ${allRuns.size}")
    printRunsWithoutZeroCountEvidence()
    println("known probes: ${manifestProbes.size}, ever hit: ${everHit.size}, never hit: ${judgeable.size}")
    if (judgeableTotal > 0) {
        println("dead: %.1f%%".format(100.0 * judgeable.size / judgeableTotal))
    }
    sortedForReport(judgeable).forEach { (key, info) ->
        val inlinedFromSuffix = info.inlinedFromClassName?.let { " (inlined from $it)" } ?: ""
        val where = "(instance ${key.run.serviceInstanceId}, class ${key.classId}, probe ${key.probeIndex})"
        val branchIndex = info.branchIndex
        if (branchIndex == null) {
            val method = methodText(info.className, info.methodName, info.methodDescriptor, info.line)
            val kind = if (info.methodName == CONSTRUCTOR) "CONSTRUCTOR, unused overload" else "${info.kind}"
            println("  NEVER HIT: $method [$kind]$inlinedFromSuffix $where")
        } else {
            println("  NEVER HIT: ${branchRow(key, info, branchIndex)}$inlinedFromSuffix $where [${info.kind} branch#$branchIndex]")
        }
    }
    println("inline (not judged): ${inlineNeverHit.size}")
    println("generated (not judged): ${generatedNeverHit.size}")
    println("in class findings (reported by class): ${inClassFindings.size}")
    println("in never-hit methods (lambda bodies reported with their creator): ${inNeverHitCode.size}")
    println("branches in never-hit code (reported with their method or guard): ${folded.size}")
    println("static initialisers and lone constructors (not listed): ${classStates.size}")
    println("ROUTINE OUTCOMES (not judged): ${routine.size}")
    sortedForReport(routine).forEach { (key, info) ->
        val where = "(instance ${key.run.serviceInstanceId}, class ${key.classId}, probe ${key.probeIndex})"
        val kind = routineText(routineOf(key))
        println("  ROUTINE [$kind]: ${branchRow(key, info, info.branchIndex ?: -1)} $where [branch#${info.branchIndex}]")
    }
    println("UNREAD SHAPES (not judged): ${unread.size}")
    sortedForReport(unread).forEach { (key, info) ->
        val where = "(instance ${key.run.serviceInstanceId}, class ${key.classId}, probe ${key.probeIndex})"
        val family = unreadText(unreadOf(key))
        val branchIndex = info.branchIndex
        val row =
            if (branchIndex == null) {
                methodText(info.className, info.methodName, info.methodDescriptor, info.line)
            } else {
                "${branchRow(key, info, branchIndex)} [branch#$branchIndex]"
            }
        println("  UNREAD [$family]: $row $where")
    }
    if (skippedClasses.isNotEmpty()) {
        println("skipped (matched but could not be instrumented): ${skippedClasses.size}")
        skippedClasses.entries
            .sortedWith(compareBy({ it.key.run.serviceInstanceId }, { it.key.className }))
            .forEach { (key, info) -> println("  SKIPPED: ${key.className} (instance ${key.run.serviceInstanceId}) - ${info.reason}") }
    }
    println("=====================================")
}

private fun foldedSiteProbes(
    candidates: List<InstanceProbeKey>,
    neverHit: List<InstanceProbeKey>,
): Set<InstanceProbeKey> {
    fun methodOf(
        key: InstanceProbeKey,
        info: ProbeInfo,
    ) = InstanceMethodKey(key.run, key.classId, info.methodName, info.methodDescriptor)
    val neverHitMethods =
        neverHit.mapNotNullTo(HashSet()) { key ->
            manifestProbes[key]?.takeIf { it.kind == ProbeKind.METHOD }?.let { methodOf(key, it) }
        }
    val guardOutcomes = HashSet<Pair<InstanceMethodKey, Int>>()
    for (key in candidates) {
        val info = manifestProbes[key] ?: continue
        val branchIndex = info.branchIndex
        if (info.kind != ProbeKind.BRANCH || branchIndex == null) continue
        if (routineOf(key) != RoutineKind.ROUTINE_KIND_UNSPECIFIED || unreadOf(key) != UnreadShape.UNREAD_SHAPE_UNSPECIFIED) continue
        guardOutcomes += methodOf(key, info) to branchIndex
    }
    return candidates
        .filter { key ->
            val info = manifestProbes[key] ?: return@filter false
            if (info.kind != ProbeKind.BRANCH) return@filter false
            val method = methodOf(key, info)
            val site = siteOf(key, info)
            method in neverHitMethods || (site != null && site.hasGuard() && (method to site.guard) in guardOutcomes)
        }.toSet()
}

private fun sortedForReport(keys: List<InstanceProbeKey>): List<Pair<InstanceProbeKey, ProbeInfo>> =
    keys
        .mapNotNull { key -> manifestProbes[key]?.let { key to it } }
        .sortedWith(compareBy({ it.second.className }, { it.second.methodName }, { it.second.line }, { it.second.branchIndex ?: -1 }))

private fun siteOf(
    key: InstanceProbeKey,
    info: ProbeInfo,
): BranchSite? =
    info.siteIndex?.let { siteIndex ->
        manifestBranchSites[InstanceMethodKey(key.run, key.classId, info.methodName, info.methodDescriptor)]
            ?.firstOrNull { it.siteIndex == siteIndex }
    }

private fun branchRow(
    key: InstanceProbeKey,
    info: ProbeInfo,
    branchIndex: Int,
): String {
    val description = siteOf(key, info)?.let { describeNeverHitOutcome(it, branchIndex) } ?: "branch at line ${info.line}"
    return "${methodText(info.className, info.methodName, info.methodDescriptor, info.line)} $description"
}

private fun routineOf(key: InstanceProbeKey): RoutineKind {
    val info = manifestProbes[key] ?: return RoutineKind.ROUTINE_KIND_UNSPECIFIED
    val branchIndex = info.branchIndex ?: return RoutineKind.ROUTINE_KIND_UNSPECIFIED
    return siteOf(key, info)?.outcomesList?.firstOrNull { it.branchIndex == branchIndex }?.routine ?: RoutineKind.ROUTINE_KIND_UNSPECIFIED
}

private fun unreadOf(key: InstanceProbeKey): UnreadShape {
    val info = manifestProbes[key] ?: return UnreadShape.UNREAD_SHAPE_UNSPECIFIED
    if (info.unreadShape != UnreadShape.UNREAD_SHAPE_UNSPECIFIED) return info.unreadShape
    val branchIndex = info.branchIndex ?: return UnreadShape.UNREAD_SHAPE_UNSPECIFIED
    return siteOf(key, info)?.outcomesList?.firstOrNull { it.branchIndex == branchIndex }?.unreadShape
        ?: UnreadShape.UNREAD_SHAPE_UNSPECIFIED
}

private fun unreadText(shape: UnreadShape): String =
    when (shape) {
        UnreadShape.UNREAD_SHAPE_CASE_CLASS -> "case class"
        UnreadShape.UNREAD_SHAPE_STATIC_FORWARDER -> "static forwarder"
        UnreadShape.UNREAD_SHAPE_SCALA_OBJECT -> "scala object"
        UnreadShape.UNREAD_SHAPE_SCALA_ENUM -> "scala enum"
        UnreadShape.UNREAD_SHAPE_MULTIFILE_FACADE -> "multifile facade"
        UnreadShape.UNREAD_SHAPE_COROUTINE_MACHINERY -> "coroutine machinery"
        UnreadShape.UNREAD_SHAPE_STRING_SWITCH -> "string switch"
        UnreadShape.UNREAD_SHAPE_KOTLINX_SERIALIZER_OBJECT -> "kotlinx serializer object"
        UnreadShape.UNREAD_SHAPE_VALUE_CLASS -> "value class"
        UnreadShape.UNREAD_SHAPE_TRAIT_FORWARDER -> "trait forwarder"
        else -> shape.name
    }

private fun routineText(kind: RoutineKind): String =
    when (kind) {
        RoutineKind.NULL_DEFAULT -> "default never used"
        RoutineKind.THROW_ONLY -> "only throws"
        RoutineKind.FINALLY_COPY -> "finally copy"
        else -> kind.name
    }

private fun sourceFileNaming(className: String): String? {
    val naming = classNamingByClassName[className] ?: return null
    val isFileKind = naming.kotlinKind == KotlinKind.FILE_FACADE || naming.kotlinKind == KotlinKind.MULTIFILE_CLASS_PART
    return naming.sourceFile.takeIf { isFileKind }
}

private fun isMultifileFacade(className: String): Boolean =
    classNamingByClassName[className]?.kotlinKind == KotlinKind.MULTIFILE_CLASS_FACADE

internal fun classText(className: String): String =
    sourceFileNaming(className) ?: if (isMultifileFacade(className)) "$className (multi-file facade)" else className

internal fun methodText(
    className: String,
    methodName: String,
    methodDescriptor: String,
    line: Int? = null,
): String {
    val shown = if (methodName == CONSTRUCTOR) constructorText(methodDescriptor) else methodName
    val lineSuffix = line?.let { ":$it" } ?: ""
    sourceFileNaming(className)?.let { return "$shown ($it$lineSuffix)" }
    val tag = if (isMultifileFacade(className)) " (multi-file facade)" else ""
    return "$className#$shown$lineSuffix$tag"
}

private fun constructorText(methodDescriptor: String): String {
    val types = mutableListOf<String>()
    var i = methodDescriptor.indexOf('(') + 1
    while (i in 1 until methodDescriptor.length && methodDescriptor[i] != ')') {
        var dimensions = 0
        while (methodDescriptor[i] == '[') {
            dimensions++
            i++
        }
        val type =
            if (methodDescriptor[i] == 'L') {
                val end = methodDescriptor.indexOf(';', i)
                methodDescriptor.substring(i + 1, end).substringAfterLast('/').also { i = end }
            } else {
                PRIMITIVE_NAMES[methodDescriptor[i]] ?: methodDescriptor[i].toString()
            }
        types += type + "[]".repeat(dimensions)
        i++
    }
    return "constructor(${types.joinToString(", ")})"
}

private val PRIMITIVE_NAMES =
    mapOf('Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double")

private fun judgeClasses(): ClassJudgement {
    val withheld = failedToInitializeClassNames() + classesWithInitializerRunning()
    val methods =
        manifestProbes.entries
            .filter { (key, probe) ->
                probe.kind == ProbeKind.METHOD && !probe.inline && !probe.isLookedThrough() &&
                    (hasZeroCountEvidence(key.run) || key in everHit)
            }.groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
            .mapValues { (_, entries) ->
                MethodFacts(
                    entries.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L },
                    entries.any { it.value.static },
                    entries.any { it.value.lambdaBody },
                )
            }
    val instanceMethodRan =
        manifestProbes.entries
            .filter { (key, probe) ->
                probe.kind == ProbeKind.METHOD && !probe.static &&
                    probe.methodName != CONSTRUCTOR && probe.methodName != CLASS_INIT &&
                    (latestHitsTotal[key] ?: 0L) > 0L
            }.mapTo(mutableSetOf()) { it.value.className }
    val findings = mutableMapOf<String, ClassFinding>()
    val covered = mutableMapOf<String, List<NodeKey>>()
    val constructed = mutableSetOf<String>()
    for ((className, classMethods) in methods.entries.groupBy { it.key.className }) {
        val initialisers = classMethods.filter { it.key.methodName == CLASS_INIT }
        val constructors = classMethods.filter { it.key.methodName == CONSTRUCTOR }
        if (constructors.any { it.value.hits > 0L }) constructed += className
        val finding =
            when {
                className in withheld -> {
                    continue
                }

                initialisers.isNotEmpty() && initialisers.all { it.value.hits == 0L } -> {
                    ClassFinding.NEVER_INITIALISED
                }

                constructors.isNotEmpty() &&
                    className !in constructed &&
                    className !in instanceMethodRan &&
                    classMethods.any { it.key.methodName != CONSTRUCTOR && it.key.methodName != CLASS_INIT && !it.value.static } -> {
                    ClassFinding.NEVER_INSTANTIATED
                }

                else -> {
                    continue
                }
            }
        findings[className] = finding
        covered[className] =
            classMethods
                .filter { (key, facts) ->
                    facts.hits == 0L &&
                        (
                            finding == ClassFinding.NEVER_INITIALISED || key.methodName == CONSTRUCTOR ||
                                (key.methodName != CLASS_INIT && !facts.static)
                        )
                }.map { it.key }
    }
    val (intoClassFindings, inNeverHitCode) = foldLambdaBodies(methods, findings, covered.values.flatten().toSet(), constructed)
    for (lambda in intoClassFindings) covered[lambda.className] = covered.getValue(lambda.className) + lambda
    return ClassJudgement(findings, covered, inNeverHitCode, constructed)
}

private fun creatorsOf(): Map<NodeKey, Set<NodeKey>> {
    val creators = mutableMapOf<NodeKey, MutableSet<NodeKey>>()

    fun add(
        creator: NodeKey,
        edges: List<CallEdgeInfo>,
    ) {
        for (edge in edges) {
            if (!edge.creates) continue
            val created = NodeKey(edge.className, edge.methodName, edge.methodDescriptor)
            if (created != creator) creators.getOrPut(created) { mutableSetOf() } += creator
        }
    }
    for ((key, edges) in manifestCallEdges) {
        val info = manifestProbes[key] ?: continue
        add(NodeKey(info.className, info.methodName, info.methodDescriptor), edges)
    }
    for ((className, declared) in staticallyDeclaredClasses) {
        declared.forEach { add(NodeKey(className, it.methodName, it.methodDescriptor), it.calls) }
    }
    return creators
}

private fun foldLambdaBodies(
    methods: Map<NodeKey, MethodFacts>,
    findings: Map<String, ClassFinding>,
    covered: Set<NodeKey>,
    constructed: Set<String>,
): Pair<Set<NodeKey>, Set<NodeKey>> {
    val creators = creatorsOf()

    fun absorbs(creator: NodeKey): Boolean {
        if ((methods[creator] ?: return false).hits > 0L) return false
        if (creator in covered) return true
        return when (creator.methodName) {
            CLASS_INIT -> false
            CONSTRUCTOR -> creator.className in constructed
            else -> true
        }
    }
    val folded =
        methods
            .filter { (key, facts) -> facts.lambdaBody && facts.hits == 0L && key !in covered }
            .keys
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

internal fun printClassFindingReport() {
    println()
    println("=== otherlode demo: class findings ===")
    printRunsWithoutZeroCountEvidence()
    val findings = judgeClasses().findings
    println(
        "never initialised: ${findings.values.count { it == ClassFinding.NEVER_INITIALISED }}, " +
            "never instantiated: ${findings.values.count { it == ClassFinding.NEVER_INSTANTIATED }}",
    )
    for (finding in listOf(ClassFinding.NEVER_INITIALISED, ClassFinding.NEVER_INSTANTIATED)) {
        findings
            .filterValues { it == finding }
            .keys
            .sorted()
            .forEach { className ->
                val probes = manifestProbes.filter { (_, info) -> info.className == className }
                val methods =
                    probes.values
                        .filter { it.kind == ProbeKind.METHOD && it.methodName != CLASS_INIT }
                        .map { if (it.methodName == CONSTRUCTOR) "constructor" else it.methodName }
                        .distinct()
                        .sorted()
                val instances =
                    probes.keys
                        .map { it.run.serviceInstanceId }
                        .distinct()
                        .size
                println(
                    "  ${finding.text.uppercase()}: ${classText(className)} (methods: ${methods.joinToString(", ")}) " +
                        "(instances loading: $instances)",
                )
            }
    }
    println("==================================")
}

private fun describeNeverHitOutcome(
    site: BranchSite,
    branchIndex: Int,
): String? {
    val outcome = site.outcomesList.firstOrNull { it.branchIndex == branchIndex } ?: return null
    val condition = site.conditionList.takeIf { it.isNotEmpty() }?.let { "`${renderCondition(it)}`" }
    val subject = condition ?: "the branch at line ${site.line}"
    val result =
        when (outcome.role) {
            BranchRole.FALL_THROUGH -> {
                if (condition != null) "$condition was never true" else "$subject never fell through"
            }

            BranchRole.TAKEN -> {
                if (condition != null) "$condition was never false" else "$subject never jumped"
            }

            BranchRole.CASE -> {
                when {
                    outcome.caseLabelCount > 0 -> "$subject was never `${renderCondition(outcome.caseLabelList)}`"
                    outcome.hasCaseKey() -> "$subject was never ${outcome.caseKey}"
                    else -> "a case of $subject never ran"
                }
            }

            BranchRole.DEFAULT -> {
                "$subject never reached its default"
            }

            else -> {
                "$subject had an outcome that never ran"
            }
        }
    val guarded =
        when {
            outcome.guardedLinesList.isNotEmpty() && outcome.partlyGuardedLinesList.isNotEmpty() -> {
                "only path to ${renderRanges(outcome.guardedLinesList)}, partly to ${renderRanges(outcome.partlyGuardedLinesList)}"
            }

            outcome.guardedLinesList.isNotEmpty() -> {
                "only path to ${renderRanges(outcome.guardedLinesList)}"
            }

            outcome.partlyGuardedLinesList.isNotEmpty() -> {
                "partly the path to ${renderRanges(outcome.partlyGuardedLinesList)}"
            }

            else -> {
                "guards no code of its own"
            }
        }
    return "$result, $guarded"
}

private fun renderCondition(parts: List<ConditionPart>): String =
    parts.joinToString("") { part ->
        when (part.kind) {
            ConditionPartKind.STRING_LITERAL -> quote(part.text)
            ConditionPartKind.PLACEHOLDER -> "…"
            else -> part.text
        }
    }

private fun quote(value: String): String =
    buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

private fun renderRanges(ranges: List<LineRange>): String =
    ranges.joinToString(", ") { range ->
        val lines = if (range.firstLine == range.lastLine) "${range.firstLine}" else "${range.firstLine}-${range.lastLine}"
        if (range.sourceFile.isEmpty()) "line $lines" else "${range.sourceFile}:$lines"
    }

internal fun printOmissionReport() {
    println()
    println("=== otherlode demo: optional argument report ===")
    val omissionGroups =
        manifestProbes.entries
            .filter { (_, info) ->
                info.kind == ProbeKind.OPTIONAL_ARGUMENT && !info.inline && !info.isLookedThrough()
            }.groupBy { (key, info) ->
                OmissionTargetKey(
                    key.run,
                    info.targetClassName ?: info.className,
                    info.methodName,
                    info.methodDescriptor,
                    info.parameterIndex,
                )
            }
    val neverSupplied = mutableListOf<Pair<OmissionTargetKey, ProbeInfo>>()
    val alwaysSupplied = mutableListOf<Pair<OmissionTargetKey, ProbeInfo>>()
    for ((groupKey, members) in omissionGroups) {
        val targetHits =
            manifestProbes.entries
                .filter { (targetKey, targetInfo) ->
                    targetKey.run == groupKey.run &&
                        targetInfo.kind == ProbeKind.METHOD &&
                        targetInfo.className == groupKey.targetClassName &&
                        targetInfo.methodName == groupKey.methodName &&
                        targetInfo.methodDescriptor == groupKey.methodDescriptor
                }.sumOf { (targetKey, _) -> latestHitsTotal[targetKey] ?: 0L }
        if (targetHits <= 0L) continue
        val omitted = members.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L }
        val representative = members.first().value
        if (!representative.overridable && omitted == targetHits) neverSupplied += groupKey to representative
        if (omitted == 0L && hasZeroCountEvidence(groupKey.run)) alwaysSupplied += groupKey to representative
    }
    println("never supplied: ${neverSupplied.size}, always supplied: ${alwaysSupplied.size}")
    neverSupplied
        .sortedWith(compareBy({ it.first.targetClassName }, { it.first.methodName }, { it.first.parameterIndex }))
        .forEach { (groupKey, info) ->
            println(
                "  NEVER SUPPLIED: ${groupKey.targetClassName}#${groupKey.methodName}(${info.parameterName}) " +
                    "(instance ${groupKey.run.serviceInstanceId})",
            )
        }
    alwaysSupplied
        .sortedWith(compareBy({ it.first.targetClassName }, { it.first.methodName }, { it.first.parameterIndex }))
        .forEach { (groupKey, info) ->
            println(
                "  ALWAYS SUPPLIED: ${groupKey.targetClassName}#${groupKey.methodName}(${info.parameterName}) " +
                    "(instance ${groupKey.run.serviceInstanceId})",
            )
        }
    println("==============================================")
}

internal fun printEndpointReport() {
    println()
    println("=== otherlode demo: endpoint report ===")
    val failedToInitialize = failedToInitializeClassNames()
    val calledCount = manifestEndpoints.count { (key, _) -> (latestEndpointHitsTotal[key] ?: 0L) > 0L }
    println("known endpoints: ${manifestEndpoints.size}, called: $calledCount, never called: ${manifestEndpoints.size - calledCount}")
    manifestEndpoints.entries
        .sortedWith(compareBy({ it.value.routeTemplate }, { it.value.verb }))
        .forEach { (key, info) ->
            val hits = latestEndpointHitsTotal[key] ?: 0L
            val handlerSuffix =
                info.handlerClass?.let { cls -> " handler=$cls${info.handlerMethod?.let { "#$it" } ?: ""}" } ?: ""
            val tag = "[${info.framework}, ${info.discoverySource}]"
            when {
                hits > 0L -> {
                    println("  CALLED: ${info.verb} ${info.routeTemplate} calls=$hits $tag$handlerSuffix")
                }

                !hasZeroCountEvidence(key.run) || info.handlerClass in failedToInitialize -> {
                    println("  NOT JUDGED: ${info.verb} ${info.routeTemplate} $tag$handlerSuffix")
                }

                else -> {
                    println("  NEVER CALLED: ${info.verb} ${info.routeTemplate} $tag$handlerSuffix")
                }
            }
        }
    if (disabledEndpointModules.isNotEmpty()) {
        println("disabled endpoint modules: ${disabledEndpointModules.size}")
        disabledEndpointModules.entries
            .sortedWith(compareBy({ it.key.run.serviceInstanceId }, { it.key.module }))
            .forEach { (key, reason) -> println("  DISABLED: ${key.module} (instance ${key.run.serviceInstanceId}) - $reason") }
    }
    println("====================================")
}

internal fun printNeverLoadedReport() {
    println()
    println("=== otherlode demo: never-loaded report (static baseline) ===")
    val incomplete = scans.filterValues { !it.complete }
    if (incomplete.isNotEmpty()) {
        incomplete.forEach { (key, progress) ->
            println(
                "  INCOMPLETE SCAN: instance ${key.run.serviceInstanceId} scanned_at ${key.scannedAt} received " +
                    "${progress.received.size} of ${progress.chunkCount} chunks; not diffing",
            )
        }
        println("===========================================================")
        return
    }
    val neverLoadedAll = staticallyDeclaredClasses.filterKeys { it !in dynamicallyKnownClassNames && it !in failedToLoadClassNames }
    val (allInlineOrGenerated, neverLoaded) =
        neverLoadedAll.entries.partition { (_, methods) ->
            methods.isNotEmpty() && methods.all { it.inline || it.isLookedThrough() }
        }
    println(
        "statically declared: ${staticallyDeclaredClasses.size}, confirmed loaded: " +
            "${staticallyDeclaredClasses.keys.count { it in dynamicallyKnownClassNames }}, never loaded: ${neverLoaded.size}",
    )
    neverLoaded
        .sortedBy { it.key }
        .forEach { (className, methods) ->
            val methodNames = methods.joinToString(", ") { it.methodName }
            println("  NEVER LOADED: ${classText(className)} (methods: $methodNames)")
        }
    if (allInlineOrGenerated.isNotEmpty()) {
        println("all inline or generated (not judged): ${allInlineOrGenerated.size}")
        allInlineOrGenerated.sortedBy { it.key }.forEach { (className, _) -> println("  ALL INLINE OR GENERATED: ${classText(className)}") }
    }
    if (staticallyUnprobedClasses.isNotEmpty()) {
        println("nothing to probe (in scope, but no concrete methods): ${staticallyUnprobedClasses.size}")
        staticallyUnprobedClasses.entries
            .sortedBy { it.key }
            .forEach { (className, reason) -> println("  UNPROBED: $className - $reason") }
    }
    if (staticallyUnreadableClasses.isNotEmpty()) {
        println("statically unreadable (class file found but could not be parsed): ${staticallyUnreadableClasses.size}")
        staticallyUnreadableClasses.entries
            .sortedBy { it.key }
            .forEach { (className, reason) -> println("  UNREADABLE: $className - $reason") }
    }
    println("===========================================================")
}

internal fun printFailedToLoadReport() {
    println()
    println("=== otherlode demo: failed to load ===")
    val failed = failedToLoadClassNames.filter { it !in dynamicallyKnownClassNames }.sorted()
    println("failed to load: ${failed.size}")
    failed.forEach { println("  FAILED TO LOAD: ${classText(it)}") }
    println("======================================")
}

private fun hasZeroCountEvidence(run: Run): Boolean = run !in unwatchedRuns && (unplacedVerificationFailuresByRun[run] ?: 0L) <= 0L

private fun printRunsWithoutZeroCountEvidence() {
    (unwatchedRuns + unplacedVerificationFailuresByRun.filterValues { it > 0L }.keys)
        .sortedWith(compareBy({ it.serviceInstanceId }, { it.runId }))
        .forEach { run ->
            val why =
                listOfNotNull(
                    "the agent's hook on VerifyError was not installed".takeIf { run in unwatchedRuns },
                    unplacedVerificationFailuresByRun[run]?.takeIf { it > 0L }?.let {
                        if (it ==
                            1L
                        ) {
                            "1 VerifyError message in no read format"
                        } else {
                            "$it VerifyError messages in no read format"
                        }
                    },
                )
            println(
                "zero counts are not evidence for instance ${run.serviceInstanceId} (run ${run.runId}), " +
                    "so its zeros are left out: ${why.joinToString("; ")}",
            )
        }
}

private fun failedToInitializeClassNames(): Set<String> {
    if (failedToInitializeByRun.isEmpty()) return emptySet()
    val initialized = initializedClassNames()
    val failed = HashSet<String>()
    for ((run, named) in failedToInitializeByRun) {
        val classNames = manifestProbes.entries.filter { it.key.run == run }.associate { it.key.classId to it.value.className }
        val supertypes =
            supertypesByClassId.entries
                .filter { it.key.run == run }
                .mapNotNull { (key, info) -> classNames[key.classId]?.let { it to info } }
                .toMap()
        val rejected = named.filterValues { InitializationFailure.VERIFIER_REJECTED in it }.keys
        val threw = named.filterValues { InitializationFailure.STATIC_INITIALIZER_THREW in it }.keys
        val withDefaultMethods =
            manifestProbes.entries
                .filter { (key, probe) ->
                    key.run == run && probe.kind == ProbeKind.METHOD && !probe.static &&
                        probe.methodName != CLASS_INIT && probe.methodName != CONSTRUCTOR
                }.mapTo(HashSet()) { it.value.className }
        failed += named.keys
        for (className in supertypes.keys) {
            if (className !in failed && carriesFailure(className, supertypes, rejected, threw, withDefaultMethods)) failed += className
        }
    }
    return failed.filterTo(HashSet()) { it !in initialized && it in dynamicallyKnownClassNames }
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

private fun initializedClassNames(): Set<String> {
    class Evidence {
        var hasSlots = false
        var returned = false
        var anyHit = false
    }

    val evidence = HashMap<Pair<Run, String>, Evidence>()
    for ((key, probe) in manifestProbes) {
        val state = evidence.getOrPut(key.run to probe.className) { Evidence() }
        val hit = key in everHit
        if (probe.kind == ProbeKind.INITIALIZER_RETURNED || probe.kind == ProbeKind.INITIALIZER_THREW) state.hasSlots = true
        if (probe.kind == ProbeKind.INITIALIZER_RETURNED && hit) state.returned = true
        if (hit) state.anyHit = true
    }
    return evidence.filterValues { if (it.hasSlots) it.returned else it.anyHit }.keys.mapTo(HashSet()) { it.second }
}

private fun classesWithInitializerRunning(): Set<String> {
    class Initializer {
        var entered = false
        var returnedSlot = false
        var threwSlot = false
        var finished = false
    }

    val initializers = HashMap<Pair<Run, String>, Initializer>()
    val loading = HashMap<String, MutableSet<Run>>()
    for ((key, probe) in manifestProbes) {
        val slot = key.run to probe.className
        val hit = key in everHit
        when (probe.kind) {
            ProbeKind.METHOD -> {
                loading.getOrPut(probe.className) { HashSet() } += key.run
                if (probe.methodName == CLASS_INIT) {
                    val initializer = initializers.getOrPut(slot) { Initializer() }
                    if (hit) initializer.entered = true
                }
            }

            ProbeKind.INITIALIZER_RETURNED, ProbeKind.INITIALIZER_THREW -> {
                val initializer = initializers.getOrPut(slot) { Initializer() }
                if (probe.kind == ProbeKind.INITIALIZER_RETURNED) initializer.returnedSlot = true else initializer.threwSlot = true
                if (hit) initializer.finished = true
            }

            else -> {}
        }
    }
    return loading
        .filter { (className, runs) ->
            runs.all { run ->
                initializers[run to className]?.let { it.entered && it.returnedSlot && it.threwSlot && !it.finished } == true
            }
        }.keys
}

internal fun printFailedToInitialiseReport() {
    println()
    println("=== otherlode demo: failed to initialise ===")
    val failed = failedToInitializeClassNames().sorted()
    println("failed to initialise: ${failed.size}")
    failed.forEach { className ->
        val reasons =
            failedToInitializeByRun.values
                .flatMap { it[className].orEmpty() }
                .map { it.name }
                .distinct()
                .sorted()
        val why = if (reasons.isEmpty()) "subtype of a class that failed to initialise" else reasons.joinToString(", ")
        println("  FAILED TO INITIALISE: ${classText(className)} ($why)")
    }
    println("============================================")
}

private val clusterMemberComparator: Comparator<ClusterMember> = compareBy({ it.className }, { it.methodName }, { it.methodDescriptor })

internal fun printUnreachedClusterReport() {
    println()
    println("=== otherlode demo: unreached clusters ===")
    printRunsWithoutZeroCountEvidence()
    val clusters = computeUnreachedClusters()
    println("clusters: ${clusters.size}")
    val routesByHandler = routesByHandler()
    clusters.forEach { cluster ->
        val method = methodText(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor)
        val outcome = cluster.rootOutcome
        val callers = cluster.reachedFrom.joinToString(", ") { methodText(it.className, it.methodName, it.methodDescriptor) }
        val root =
            when (cluster.rootKind) {
                ClusterRootKind.UNTAKEN_OUTCOME -> {
                    val description =
                        outcome?.site?.let { describeNeverHitOutcome(it, outcome.branchIndex) }
                            ?: "branch#${outcome?.branchIndex} never ran"
                    val at = methodText(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor, outcome?.line)
                    "$description, in $at (untaken outcome)"
                }

                ClusterRootKind.REACHED_FROM_HIT -> {
                    "$method (reached from hit, called from $callers)"
                }

                ClusterRootKind.UNCALLED -> {
                    "$method (uncalled)"
                }

                ClusterRootKind.CALLED_FROM_OUTSIDE_SCOPE -> {
                    "$method (called from outside scope: ${outsideCallerReason(cluster.root.outsideCaller)})"
                }

                ClusterRootKind.CLASS_FINDING -> {
                    val calledFrom = if (callers.isEmpty()) "" else ", called from $callers"
                    "${classText(cluster.root.className)} (class finding: ${cluster.rootFinding?.text}$calledFrom)"
                }
            }
        val routes = routesByHandler[NodeKey(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor)]
        val routesSuffix = routes?.let { " routes=${it.joinToString(", ", "[", "]")}" } ?: ""
        println(
            "UNREACHED CLUSTER: root $root, ${cluster.membersTotal} methods, ${cluster.neverLoadedClasses} never-loaded classes$routesSuffix",
        )
        cluster.wholeClasses.forEach { whole ->
            val finding = whole.finding?.let { ", ${it.text}" } ?: ""
            println("  ${classText(whole.className)} (whole class$finding, ${whole.methodsTotal} methods)")
        }
        cluster.members.forEach { member ->
            val suffix = if (member.neverLoaded) " (never loaded)" else ""
            println("  ${methodText(member.className, member.methodName, member.methodDescriptor)}$suffix")
        }
    }
    println("=======================================")
}

private fun outsideCallerReason(outsideCaller: OutsideCaller?): String {
    val simpleName = outsideCaller?.typeName.orEmpty().substringAfterLast('.')
    return when (outsideCaller?.kind) {
        OutsideCallerKind.OVERRIDES_METHOD -> "overrides $simpleName"
        OutsideCallerKind.CALLBACK_ANNOTATION -> "@$simpleName"
        else -> simpleName
    }
}

private fun routesByHandler(): Map<NodeKey, List<String>> =
    manifestEndpoints.values
        .filter { it.handlerClass != null && it.handlerMethod != null && it.handlerDescriptor != null }
        .groupBy(
            { NodeKey(it.handlerClass!!, it.handlerMethod!!, it.handlerDescriptor!!) },
            { "${it.verb} ${it.routeTemplate}" },
        ).mapValues { (_, routes) -> routes.distinct().sorted() }

private fun computeUnreachedClusters(): List<UnreachedClusterInfo> {
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

    val failedToInitialize = failedToInitializeClassNames() + classesWithInitializerRunning()
    val evidenced =
        manifestProbes.entries
            .filter { (key, probe) -> probe.kind == ProbeKind.METHOD && hasZeroCountEvidence(key.run) }
            .mapTo(HashSet()) { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }

    fun isNeverHit(node: ClusterNode) =
        when {
            node.isClass -> {
                node in classNodes
            }

            node.branchIndex != null -> {
                node in outcomes
            }

            else -> {
                !isHit(node.method) && node.method !in coveredBy && node.method.className !in failedToInitialize &&
                    node.method in evidenced
            }
        }

    val neverHitNodes =
        graph.nodes.keys
            .filter {
                !isHit(it) && it !in coveredBy && it.methodName != CLASS_INIT && it !in unjudged &&
                    it.className !in failedToInitialize && it in evidenced
            }.map { ClusterNode(it) } + outcomes.keys + classNodes.keys
    return neverHitNodes
        .mapNotNull { node ->
            val callers = clusterGraph.callersOf[node].orEmpty()
            val hitCallers = callers.filter { it.branchIndex == null && !it.isClass && isHit(it.method) }
            if (callers.isNotEmpty() && hitCallers.isEmpty()) return@mapNotNull null
            val kind =
                when {
                    node.isClass -> ClusterRootKind.CLASS_FINDING
                    node.branchIndex != null -> ClusterRootKind.UNTAKEN_OUTCOME
                    callers.isEmpty() && graph.nodes[node.method]?.outsideCaller != null -> ClusterRootKind.CALLED_FROM_OUTSIDE_SCOPE
                    callers.isEmpty() -> ClusterRootKind.UNCALLED
                    else -> ClusterRootKind.REACHED_FROM_HIT
                }
            val reachedFrom =
                if (kind == ClusterRootKind.REACHED_FROM_HIT || kind == ClusterRootKind.CLASS_FINDING) {
                    hitCallers
                        .map { toClusterMember(graph.nodes[it.method] ?: graph.hitGeneratedCallers.getValue(it.method), it.method) }
                        .sortedWith(clusterMemberComparator)
                } else {
                    emptyList()
                }
            buildUnreachedCluster(graph, clusterGraph, outcomes, classNodes, unjudged, node, kind, reachedFrom, ::isNeverHit)
        }.sortedWith(
            compareByDescending<UnreachedClusterInfo> { it.membersTotal }
                .thenComparing({ it.root }, clusterMemberComparator)
                .thenComparing { cluster -> cluster.rootOutcome?.branchIndex ?: -1 },
        )
}

private fun buildClassNodes(
    graph: CallGraph,
    judgement: ClassJudgement,
): Map<ClusterNode, ClassNodeInfo> {
    val classNodes = mutableMapOf<ClusterNode, ClassNodeInfo>()
    for ((className, finding) in judgement.findings) {
        val methods = judgement.covered[className].orEmpty().filter { (graph.nodes[it]?.hits ?: -1L) == 0L }
        if (methods.isNotEmpty()) classNodes[classNode(className)] = ClassNodeInfo(finding, methods)
    }
    graph.nodes
        .filter { (key, info) -> info.neverLoaded && key.className !in judgement.findings }
        .keys
        .groupBy { it.className }
        .forEach { (className, methods) -> classNodes[classNode(className)] = ClassNodeInfo(ClassFinding.NEVER_LOADED, methods) }
    return classNodes
}

private fun buildUnreachedCluster(
    graph: CallGraph,
    clusterGraph: ClusterGraph,
    outcomes: Map<ClusterNode, OutcomeNode>,
    classNodes: Map<ClusterNode, ClassNodeInfo>,
    unjudged: Set<NodeKey>,
    root: ClusterNode,
    rootKind: ClusterRootKind,
    reachedFrom: List<ClusterMember>,
    isNeverHit: (ClusterNode) -> Boolean,
): UnreachedClusterInfo? {
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
            it != root && it.branchIndex == null && (it.isClass || (it.method.methodName != CLASS_INIT && it.method !in unjudged))
        }
    if ((root.isClass || root.branchIndex != null) && !listsMoreThanRoot) return null

    val methodsByClass = mutableMapOf<String, MutableList<NodeKey>>()
    for (member in members) {
        when {
            member.branchIndex != null -> {}

            member.isClass -> {
                methodsByClass.getOrPut(member.method.className) { mutableListOf() } += classNodes.getValue(member).methods
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
    val wholeClasses = mutableListOf<WholeClassInfo>()
    val memberList = mutableListOf<ClusterMember>()
    var neverLoadedClasses = 0
    for ((className, keys) in methodsByClass) {
        val methods = keys.filter { it.methodName != CLASS_INIT }.map { toClusterMember(graph.nodes.getValue(it), it) }
        if (keys.any { graph.nodes.getValue(it).neverLoaded }) neverLoadedClasses++
        if (keys.size == classSize[className]) {
            wholeClasses += WholeClassInfo(className, classNodes[classNode(className)]?.finding, methods.size)
        } else {
            memberList += methods
        }
    }
    val rootOutcome =
        root.branchIndex?.let { branchIndex ->
            val outcome = outcomes.getValue(root)
            RootOutcome(branchIndex, outcome.line, outcome.site)
        }
    val rootMember =
        if (root.isClass) {
            val methods = classNodes.getValue(root).methods
            ClusterMember(root.method.className, "", "", methods.all { graph.nodes.getValue(it).neverLoaded })
        } else {
            toClusterMember(graph.nodes.getValue(root.method), root.method)
        }
    return UnreachedClusterInfo(
        root = rootMember,
        rootKind = rootKind,
        members = memberList.sortedWith(clusterMemberComparator),
        neverLoadedClasses = neverLoadedClasses,
        rootOutcome = rootOutcome,
        reachedFrom = reachedFrom,
        rootFinding = classNodes[root]?.finding,
        wholeClasses = wholeClasses.sortedBy { it.className },
    )
}

private fun toClusterMember(
    info: NodeInfo,
    key: NodeKey,
): ClusterMember = ClusterMember(key.className, key.methodName, key.methodDescriptor, info.neverLoaded, info.outsideCaller)

private fun buildOutcomeNodes(isHit: (NodeKey) -> Boolean): Map<ClusterNode, OutcomeNode> {
    val unjudgeable = failedToInitializeClassNames() + classesWithInitializerRunning()
    return manifestProbes.entries
        .filter { (key, probe) ->
            probe.kind == ProbeKind.BRANCH &&
                probe.branchIndex != null &&
                !probe.inline &&
                !probe.isLookedThrough() &&
                routineOf(key) == RoutineKind.ROUTINE_KIND_UNSPECIFIED &&
                unreadOf(key) == UnreadShape.UNREAD_SHAPE_UNSPECIFIED
        }.groupBy { (_, probe) -> ClusterNode(NodeKey(probe.className, probe.methodName, probe.methodDescriptor), probe.branchIndex) }
        .filter { (node, entries) ->
            isHit(node.method) && entries.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L } == 0L &&
                entries.any { (key, _) -> hasZeroCountEvidence(key.run) } && node.method.className !in unjudgeable
        }.mapValues { (node, entries) ->
            val site =
                entries.firstNotNullOfOrNull { (key, probe) ->
                    manifestBranchSites[InstanceMethodKey(key.run, key.classId, probe.methodName, probe.methodDescriptor)]
                        ?.firstOrNull { site -> site.outcomesList.any { it.branchIndex == node.branchIndex } }
                }
            OutcomeNode(entries.first().value.line, site)
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
        val siteGuard = outcome.site?.takeIf { it.hasGuard() }?.guard
        val guardNode = siteGuard?.let { ClusterNode(node.method, it) }?.takeIf { it != node && it in outcomes }
        link(guardNode ?: ClusterNode(node.method), node)
    }
    return ClusterGraph(callersOf, calleesOf)
}

private fun computeCallGraph(): CallGraph {
    val scansComplete = scans.values.all { it.complete }
    val declaredClasses = if (scansComplete) staticallyDeclaredClasses else emptyMap()
    val declaredSupertypes = if (scansComplete) staticallyDeclaredSupertypes else emptyMap()
    val nodes = buildClusterNodes(declaredClasses)
    val transparent = buildTransparentMethods(declaredClasses)
    val known = nodes.keys + transparent.keys
    val supertypesByClassName = buildSupertypesByClassName(declaredSupertypes)
    val reverseSubtypes = buildReverseSubtypes(supertypesByClassName)

    fun resolve(
        edge: CallEdgeInfo,
        expanded: MutableSet<NodeKey>,
        into: MutableSet<NodeKey>,
    ) {
        val candidates = mutableSetOf<NodeKey>()
        findDeclaringType(known, supertypesByClassName, edge.className, edge.methodName, edge.methodDescriptor)?.let {
            candidates += NodeKey(it, edge.methodName, edge.methodDescriptor)
        }
        if (edge.virtual && edge.methodName != "<init>" && edge.methodName != "<clinit>") {
            candidates += widenToSubtypes(known, reverseSubtypes, edge.className, edge.methodName, edge.methodDescriptor)
        }
        for (candidate in candidates) {
            // No bytecode calls <clinit>: a resolved call into a class is its first active use, which runs it.
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
        for (edge in info.edges) {
            val targets = mutableSetOf<NodeKey>()
            resolve(edge, mutableSetOf(), targets)
            targets -= nodeKey
            targets.mapTo(resolved) { ResolvedCall(it, edge.guard) }
        }
        calls[nodeKey] = resolved
    }
    val hitGeneratedCallers = mutableMapOf<NodeKey, NodeInfo>()
    manifestProbes.entries
        .filter { (_, probe) -> probe.kind == ProbeKind.METHOD && !probe.inline && probe.isLookedThrough() }
        .groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
        .forEach { (key, entries) ->
            if (key in nodes) return@forEach
            val hits = entries.sumOf { (probeKey, _) -> latestHitsTotal[probeKey] ?: 0L }
            if (hits == 0L) return@forEach
            hitGeneratedCallers[key] = NodeInfo(line = entries.first().value.line, neverLoaded = false, hits = hits, edges = emptySet())
            val targets = mutableSetOf<NodeKey>()
            transparent[key].orEmpty().forEach { resolve(it, mutableSetOf(key), targets) }
            calls[key] = targets.map { ResolvedCall(it, null) }.toSet()
        }
    return CallGraph(nodes, calls, hitGeneratedCallers)
}

private fun buildTransparentMethods(declaredClasses: Map<String, List<DeclaredMethodInfo>>): Map<NodeKey, Set<CallEdgeInfo>> {
    val transparent = mutableMapOf<NodeKey, MutableSet<CallEdgeInfo>>()
    for ((key, probe) in manifestProbes) {
        if (probe.kind != ProbeKind.METHOD || probe.inline || !probe.isLookedThrough()) continue
        transparent.getOrPut(NodeKey(probe.className, probe.methodName, probe.methodDescriptor)) { mutableSetOf() } +=
            manifestCallEdges[key].orEmpty()
    }
    for ((className, methods) in declaredClasses) {
        for (method in methods) {
            if (method.inline || !method.isLookedThrough()) continue
            transparent.getOrPut(NodeKey(className, method.methodName, method.methodDescriptor)) { mutableSetOf() } += method.calls
        }
    }
    return transparent
}

private fun buildClusterNodes(declaredClasses: Map<String, List<DeclaredMethodInfo>>): Map<NodeKey, NodeInfo> {
    val nodes = mutableMapOf<NodeKey, NodeInfo>()
    val manifestGroups =
        manifestProbes.entries
            .filter { (_, probe) -> probe.kind == ProbeKind.METHOD && !probe.inline && !probe.isLookedThrough() }
            .groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
    for ((nodeKey, entries) in manifestGroups) {
        val hits = entries.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L }
        val edges = entries.flatMap { (key, _) -> manifestCallEdges[key].orEmpty() }.toMutableSet()
        declaredClasses[nodeKey.className]
            ?.filter { it.methodName == nodeKey.methodName && it.methodDescriptor == nodeKey.methodDescriptor }
            ?.forEach { edges += it.calls }
        val representative = entries.first().value
        nodes[nodeKey] =
            NodeInfo(
                line = representative.line,
                neverLoaded = false,
                hits = hits,
                edges = edges,
                outsideCaller = representative.outsideCaller,
            )
    }
    for ((className, methods) in declaredClasses) {
        if (className in dynamicallyKnownClassNames) continue
        for (method in methods) {
            if (method.inline || method.isLookedThrough()) continue
            val nodeKey = NodeKey(className, method.methodName, method.methodDescriptor)
            if (nodeKey in nodes) continue
            nodes[nodeKey] = NodeInfo(line = -1, neverLoaded = true, hits = 0L, edges = method.calls.toSet())
        }
    }
    return nodes
}

private fun buildSupertypesByClassName(declaredSupertypes: Map<String, SupertypesInfo>): Map<String, SupertypesInfo> {
    val result = mutableMapOf<String, SupertypesInfo>()
    for ((key, info) in manifestProbes) {
        val supertypes = supertypesByClassId[InstanceClassIdKey(key.run, key.classId)] ?: continue
        result.putIfAbsent(info.className, supertypes)
    }
    for ((className, supertypes) in declaredSupertypes) {
        result.putIfAbsent(className, supertypes)
    }
    return result
}

private fun buildReverseSubtypes(supertypesByClassName: Map<String, SupertypesInfo>): Map<String, List<String>> {
    val reverse = mutableMapOf<String, MutableList<String>>()
    for ((className, info) in supertypesByClassName) {
        info.superClassName?.let { reverse.getOrPut(it) { mutableListOf() } += className }
        info.interfaceNames.forEach { reverse.getOrPut(it) { mutableListOf() } += className }
    }
    return reverse
}

private fun findDeclaringType(
    nodes: Set<NodeKey>,
    supertypesByClassName: Map<String, SupertypesInfo>,
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
        val info = supertypesByClassName[current] ?: continue
        info.superClassName?.let { queue += it }
        queue += info.interfaceNames
    }
    return null
}

private fun widenToSubtypes(
    nodes: Set<NodeKey>,
    reverseSubtypes: Map<String, List<String>>,
    declaringType: String,
    name: String,
    desc: String,
): Set<NodeKey> {
    val result = mutableSetOf<NodeKey>()
    val visited = mutableSetOf(declaringType)
    val queue = ArrayDeque<String>()
    queue += reverseSubtypes[declaringType].orEmpty()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        val key = NodeKey(current, name, desc)
        if (key in nodes) result += key
        queue += reverseSubtypes[current].orEmpty()
    }
    return result
}

internal fun printDependencyReport() {
    val failed = failedToLoadClassNames.filter { it !in dynamicallyKnownClassNames }.toSet()
    formatDependencyReport(computeDependencyReport(dependencyViews(), failed, failedToInitializeClassNames())).forEach(::println)
}

private fun dependencyViews(): List<InstanceDependencyView> {
    val runs = allRuns + manifestRuns + dependencyLocations.keys.map { it.run }
    return runs.sortedWith(compareBy({ it.serviceInstanceId }, { it.runId })).map { run ->
        val probes = manifestProbes.filterKeys { it.run == run }
        val classNamesById = probes.entries.associate { (key, info) -> key.classId to info.className }
        val methodReferences =
            probes
                .filterValues { it.kind == ProbeKind.METHOD }
                .map { (key, info) ->
                    HeldReferences(
                        className = info.className,
                        methodName = info.methodName,
                        methodDescriptor = info.methodDescriptor,
                        origin = ReferenceOrigin.MANIFEST_METHOD,
                        referencedClasses = probeReferencedClasses[key].orEmpty(),
                        inline = info.inline,
                        hits = latestHitsTotal[key] ?: 0L,
                    )
                }
        val classReferences =
            classLevelReferences
                .filterKeys { it.run == run }
                .map { (key, referenced) ->
                    val className = classNamesById[key.classId] ?: "class_id ${key.classId}"
                    HeldReferences(className, null, null, ReferenceOrigin.MANIFEST_CLASS, referenced)
                }
        val declaredReferences =
            baselineReferences
                .filterKeys { it.run == run }
                .flatMap { (key, declared) ->
                    listOf(HeldReferences(key.className, null, null, ReferenceOrigin.BASELINE, declared.classReferences)) +
                        declared.methods.map {
                            HeldReferences(
                                key.className,
                                it.methodName,
                                it.methodDescriptor,
                                ReferenceOrigin.BASELINE,
                                it.referencedClasses,
                                inline = it.inline,
                            )
                        }
                }
        val runScans = scans.filterKeys { it.run == run }.values
        InstanceDependencyView(
            instanceId = run.serviceInstanceId,
            referencesRecorded = run in runsRecordingReferences,
            baselineComplete = runScans.isNotEmpty() && runScans.all { it.complete },
            zeroCountsAreEvidence = hasZeroCountEvidence(run),
            dependenciesListed = run in runsWithDependenciesListed,
            dependencies = dependencyLocations.filterKeys { it.run == run }.values.toList(),
            loadedClassesTotal =
                latestLoadedClassesTotal.filterKeys { it.run == run }.mapKeys { it.key.dependencyId },
            externalClasses = externalClasses.filterKeys { it.run == run }.mapKeys { it.key.className },
            references = methodReferences + classReferences + declaredReferences,
            loadedClassNames =
                probes.values.map { it.className }.toSet() +
                    skippedClasses.keys.filter { it.run == run }.map { it.className },
        )
    }
}
