package dev.otherlode.instrumentation

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeProbeArrays
import dev.otherlode.config.AgentConfig
import dev.otherlode.export.BodyKind
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.branch.BranchDropCounts
import dev.otherlode.instrumentation.branch.BranchDropReason
import dev.otherlode.instrumentation.branch.BranchProbeAsmVisitorWrapper
import dev.otherlode.instrumentation.branch.BranchSite
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.branch.ConfiguredCallbackAnnotations
import dev.otherlode.instrumentation.branch.DefaultSite
import dev.otherlode.instrumentation.branch.HandlerForwarder
import dev.otherlode.instrumentation.branch.KeptBranchSite
import dev.otherlode.instrumentation.branch.ScalaReleases
import dev.otherlode.instrumentation.branch.SitePairing
import dev.otherlode.instrumentation.branch.SizeGuard
import dev.otherlode.instrumentation.branch.UnreadCause
import dev.otherlode.instrumentation.branch.UnreadShapeCounts
import dev.otherlode.instrumentation.endpoints.HandlerForwarders
import dev.otherlode.instrumentation.staticscan.StaticBaselineMismatchDetector
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.ParameterDescription
import net.bytebuddy.description.modifier.FieldManifestation
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.SyntheticState
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.scaffold.MethodGraph
import net.bytebuddy.dynamic.scaffold.TypeValidation
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.implementation.bytecode.Addition
import net.bytebuddy.implementation.bytecode.ByteCodeAppender
import net.bytebuddy.implementation.bytecode.Duplication
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.collection.ArrayAccess
import net.bytebuddy.implementation.bytecode.constant.ClassConstant
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant
import net.bytebuddy.implementation.bytecode.constant.LongConstant
import net.bytebuddy.implementation.bytecode.constant.TextConstant
import net.bytebuddy.implementation.bytecode.member.FieldAccess
import net.bytebuddy.implementation.bytecode.member.MethodInvocation
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any
import net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader
import net.bytebuddy.matcher.ElementMatchers.isExtensionClassLoader
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.pool.TypePool
import net.bytebuddy.utility.JavaModule
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Weaves method-entry, branch, optional-argument and `<clinit>` probes into every type matched by
 * [AgentConfig.includePackages], except classes the bootstrap and platform loaders define. An empty
 * list matches nothing; the agent refuses to start with one.
 *
 * A matched type is registered with [ProbeRegistry] once, after its rewrite succeeds. The
 * transformer sees classes as they load and does not retransform those already loaded when
 * [install] runs. It is retransformation-capable: another agent's retransformation or a
 * redefinition of a class this agent wove weaves it again from its recorded plan, and the call is
 * refused when the class file is no longer the one it was woven from (see [WovenClasses]). A class
 * this agent did not weave is left alone.
 */
class OtherlodeInstrumentation(
    private val config: AgentConfig,
    private val registry: ProbeRegistry,
    private val staticBaselineMismatchDetector: StaticBaselineMismatchDetector = StaticBaselineMismatchDetector(),
    captureClassBytes: Boolean = true,
    private val branchDropCounts: BranchDropCounts = BranchDropCounts(),
    private val unreadShapeCounts: UnreadShapeCounts = UnreadShapeCounts(),
    private val externalClassRegistry: ExternalClassRegistry = ExternalClassRegistry(),
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
    private val classFileCache: ClassFileByteCache = ClassFileByteCache(),
    private val codeSourceClassFile: CodeSourceClassFile = CodeSourceClassFile(),
    private val callbackAnnotations: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations(config.callbackAnnotations),
    private val bootstrapVisibilityGuard: BootstrapVisibilityGuard = BootstrapVisibilityGuard(),
) {
    private val log by agentLogger(OtherlodeInstrumentation::class.java.name)

    private val supertypeGuard = SupertypeGuard(classFileCache)

    private val referencedClassLocator = ReferencedClassLocator()
    private val classBytesCapture: ClassBytesCapture? = if (captureClassBytes) ClassBytesCapture(::isCandidateInternalName) else null
    private val wovenClasses = WovenClasses()

    private val alreadyLoaded = ThreadLocal<Boolean?>()

    private val tableCaches = WeakHashMap<ClassLoader, BranchSiteAnalyzer.CrossClassTableCache>()
    private val bootstrapTableCache = BranchSiteAnalyzer.CrossClassTableCache(TRANSFORM_TABLE_CACHE_ENTRIES)

    private val tableActive: MutableSet<ClassLoader> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var bootstrapTableActive = false

    private fun tableCacheFor(classLoader: ClassLoader?): BranchSiteAnalyzer.CrossClassTableCache {
        if (classLoader == null) {
            bootstrapTableActive = true
            return bootstrapTableCache
        }
        return synchronized(tableCaches) {
            tableActive.add(classLoader)
            tableCaches.getOrPut(classLoader) { BranchSiteAnalyzer.CrossClassTableCache(TRANSFORM_TABLE_CACHE_ENTRIES) }
        }
    }

    /**
     * Releases the table cache and the class-file bytes of every loader not used since the previous
     * call, so a loader is released one or two calls after its last use. Meant for the flush
     * thread. A later transform refills what was released.
     */
    fun releaseQuietCaches() {
        val byteActive = classFileCache.takeActiveLoaders()
        synchronized(tableCaches) {
            val iterator = tableCaches.keys.iterator()
            while (iterator.hasNext()) {
                val loader = iterator.next()
                if (loader !in tableActive && loader !in byteActive) iterator.remove()
            }
            tableActive.clear()
        }
        val bootstrapActive = bootstrapTableActive || null in byteActive
        bootstrapTableActive = false
        if (!bootstrapActive) bootstrapTableCache.clear()
        val keep = Collections.newSetFromMap(IdentityHashMap<ClassLoader?, Boolean>())
        keep.addAll(byteActive)
        if (bootstrapActive) keep.add(null)
        synchronized(tableCaches) { keep.addAll(tableCaches.keys) }
        classFileCache.dropLoadersNotIn(keep)
    }

    internal fun cachedTableCount(classLoader: ClassLoader?): Int =
        if (classLoader == null) bootstrapTableCache.size else synchronized(tableCaches) { tableCaches[classLoader]?.size ?: 0 }

    /**
     * Installs the bootstrap holder, points it at this registry, then registers [ClassBytesCapture]
     * and ByteBuddy's transformer, in that order, both retransformation-capable. Nothing is
     * retransformed.
     *
     * Throws [BootstrapInstallException], before registering anything, if the holder cannot be made
     * bootstrap-visible. Registering throws [UnsupportedOperationException] where retransformation
     * is not supported. The returned transformer's `reset` removes only ByteBuddy's; call
     * [uninstall] to remove both.
     */
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer {
        BootstrapHolder.install(instrumentation)
        OtherlodeProbeArrays.install { className, layoutHash, _, classLoader -> registry.lookup(className, layoutHash, classLoader) }
        val transformer = buildTransformer(instrumentation)
        if (classBytesCapture != null) instrumentation.addTransformer(classBytesCapture, true)
        try {
            instrumentation.addTransformer(transformer, true)
        } catch (t: Throwable) {
            if (classBytesCapture != null) instrumentation.removeTransformer(classBytesCapture)
            throw t
        }
        return transformer
    }

    private fun buildTransformer(instrumentation: Instrumentation): ResettableClassFileTransformer =
        AgentBuilder
            .Default()
            .with(DecoratingTypeStrategy)
            .ignore(any<TypeDescription>(), isBootstrapClassLoader<ClassLoader>().or(isExtensionClassLoader()))
            .or(ignoredNames())
            .or(
                AgentBuilder.RawMatcher { type, classLoader, _, classBeingRedefined, _ ->
                    classBeingRedefined != null && !isWoven(type, classLoader)
                },
            )
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
            .with(classFileCache.locationStrategy())
            .with(AgentBuilder.PoolStrategy.Default.FAST)
            // Must stay before TransformResultListener: an edge that failed after the commit would
            // leave probes registered for a class that runs unwoven.
            .assureReadEdgeTo(instrumentation, OtherlodeProbeArrays::class.java)
            .with(TransformResultListener())
            .type(typeMatcher())
            .transform { builder, typeDescription, classLoader, _, protectionDomain ->
                instrument(builder, typeDescription, classLoader, protectionDomain)
            }
            .makeRaw() as ResettableClassFileTransformer

    private fun ignoredNames(): ElementMatcher.Junction<TypeDescription> =
        nameStartsWith<TypeDescription>(TypeMatchPolicy.BYTE_BUDDY_PACKAGE_PREFIX)
            .and(not(nameStartsWith("${TypeMatchPolicy.BYTE_BUDDY_PACKAGE_PREFIX}renamed.")))
            .or(nameStartsWith("sun.reflect."))
            .or(nameStartsWith("jdk.internal.reflect."))

    private fun isWoven(
        type: TypeDescription,
        classLoader: ClassLoader?,
    ): Boolean {
        if (wovenClasses.find(classLoader, type.name) != null) return true
        classBytesCapture?.take(type.internalName)
        return false
    }

    private class ReweaveRefused(
        className: String,
        val reason: String,
    ) : IllegalStateException("otherlode: $reason; $className cannot be woven again")

    /** Removes both transformers [install] registered. */
    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
        if (classBytesCapture != null) instrumentation.removeTransformer(classBytesCapture)
    }

    private fun isCandidateInternalName(internalName: String): Boolean =
        TypeMatchPolicy.isIncluded(internalName.replace('/', '.'), config.includePackages, config.excludePackages)

    private class PendingRegistration(
        val className: String,
        val layoutHash: Long,
        val probes: List<ProbeMeta>,
        val classLoader: ClassLoader?,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val classReferences: List<String>,
        val externalClasses: Map<String, String?>,
        val sourceFile: String?,
        val bodyKind: BodyKind,
        val sourceName: String?,
        val handlerForwarders: List<HandlerForwarder>,
        val kotlinKind: KotlinKind,
        val plan: WeavePlan,
        val sizeWarnings: List<String>,
    )

    private val pendingRegistration = ThreadLocal<PendingRegistration?>()

    internal fun hasPendingRegistration(): Boolean = pendingRegistration.get() != null

    private inner class TransformResultListener : AgentBuilder.Listener.Adapter() {
        override fun onDiscovery(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            alreadyLoaded.set(loaded)
        }

        override fun onTransformation(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            dynamicType: DynamicType,
        ) {
            val pending = pendingRegistration.get() ?: return
            pendingRegistration.remove()
            registry.inTransform {
                registry.register(
                    pending.className,
                    pending.layoutHash,
                    pending.probes,
                    pending.classLoader,
                    superClassName = pending.superClassName,
                    interfaceNames = pending.interfaceNames,
                    classReferences = pending.classReferences,
                    sourceFile = pending.sourceFile,
                    bodyKind = pending.bodyKind,
                    sourceName = pending.sourceName,
                    kotlinKind = pending.kotlinKind,
                )
            }
            afterCommit(pending.className) { wovenClasses.record(pending.classLoader, pending.className, pending.plan) }
            afterCommit(pending.className) { pending.handlerForwarders.forEach(handlerForwarders::record) }
            afterCommit(pending.className) {
                for ((className, location) in pending.externalClasses) externalClassRegistry.record(className, location)
            }
            for (warning in pending.sizeWarnings) afterCommit(pending.className) { log.log(Level.WARNING, warning) }
        }

        private inline fun afterCommit(
            className: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (t: Throwable) {
                // Rethrowing would define the class unwoven and record it as skipped while its probes stay registered.
                if (t is VirtualMachineError && t !is StackOverflowError) throw t
                try {
                    log.log(Level.WARNING, "otherlode: a step after weaving $className failed; the class is woven", t)
                } catch (_: Throwable) {
                }
            }
        }

        override fun onError(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            throwable: Throwable,
        ) {
            val plan = if (alreadyLoaded.get() == true) wovenClasses.find(classLoader, typeName) else null
            when {
                throwable is ReweaveRefused -> {
                    if (plan?.firstLog(WeavePlan.LOGGED_REFUSAL) != false) logRefusal(throwable.reason)
                }

                alreadyLoaded.get() == true -> {
                    if (plan?.firstLog(WeavePlan.LOGGED_FAILURE) != false) {
                        log.log(
                            Level.WARNING,
                            "otherlode: could not weave $typeName again for a redefinition or retransformation; " +
                                whenNotWoven(plan),
                            throwable,
                        )
                    }
                }

                else -> {
                    val refusal = generateSequence(throwable) { it.cause }.filterIsInstance<SupertypeRefusal>().firstOrNull()
                    if (refusal != null) {
                        try {
                            if (!refusal.logged) log.log(Level.WARNING, "otherlode: $typeName ${refusal.message}")
                        } finally {
                            registry.recordSkipped(typeName, refusal.message.orEmpty())
                        }
                    } else {
                        try {
                            log.log(
                                Level.WARNING,
                                "otherlode: instrumentation failed for $typeName, class will run uninstrumented",
                                throwable,
                            )
                        } finally {
                            registry.recordSkipped(typeName, throwable.message ?: throwable.toString())
                        }
                    }
                }
            }
        }

        private fun whenNotWoven(plan: WeavePlan?): String =
            if (plan != null && plan.majorVersion >= ProbeArrayForm.DYNAMIC_CONSTANT_VERSION) {
                "the JVM redefines the class with its bytes unwoven, so it stops counting"
            } else {
                "the JVM refuses the redefinition or retransformation, and the class keeps running with its probes"
            }

        override fun onComplete(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            pendingRegistration.remove()
            alreadyLoaded.remove()
        }
    }

    private fun typeMatcher(): ElementMatcher.Junction<TypeDescription> =
        TypeMatchPolicy.typeNameMatcher(config.includePackages, config.excludePackages)

    private fun instrument(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        protectionDomain: ProtectionDomain?,
    ): DynamicType.Builder<*> {
        if (alreadyLoaded.get() == true) return reweave(builder, typeDescription, classLoader, protectionDomain)
        bootstrapVisibilityGuard.refusal(classLoader)?.let { throw SupertypeRefusal.blindLoader(it) }
        supertypeGuard.missingSupertype(typeDescription, classLoader)?.let { throw SupertypeRefusal.undefinable(it) }
        val source = readClassBytes(typeDescription, classLoader, protectionDomain)
        val analysedBytes = source.analysed
        val (analysedMethods, receivedMethods, analysis, pairing) = analyse(typeDescription, classLoader, source)
        val methods =
            receivedMethods?.let { received -> analysedMethods.filter { it.key in received } }
                ?: analysedMethods
        if (pairing.unpairedMethods.isNotEmpty()) {
            val (absent, misaligned) = pairing.unpairedMethods.partition { receivedMethods != null && it !in receivedMethods }
            val signatures = { methods: List<Pair<String, String>> -> methods.joinToString { (name, descriptor) -> name + descriptor } }
            val details =
                listOfNotNull(
                    misaligned.takeIf { it.isNotEmpty() }?.let {
                        "the branches of ${signatures(it)} do not line up with its class file, so those methods keep their " +
                            "entry probe and get no branch probes"
                    },
                    absent.takeIf { it.isNotEmpty() }?.let {
                        "${signatures(it)} are missing from the bytes it received, so those methods get no probes at all"
                    },
                )
            log.log(
                Level.INFO,
                "otherlode: ${typeDescription.name} reached this agent rewritten by an earlier transformer; ${details.joinToString("; ")}",
            )
        }
        if (analysedBytes == null) {
            log.log(
                Level.WARNING,
                "otherlode: could not read ${typeDescription.name}'s bytecode; any method of it that is probed at all " +
                    "gets an entry probe with no line number, and the class gets no branch probes, no call edges, " +
                    "no supertypes, and no inline or generated mark",
            )
        }
        if (methods.isEmpty() && analysis.defaultSites.isEmpty() && !analysis.hasTypeInitializer) {
            if (analysedBytes != null) registry.recordNothingToProbe(typeDescription.name)
            return builder
        }
        if (analysis.isKotlinClass && !analysis.hasLineNumbers) {
            log.log(
                Level.WARNING,
                "otherlode: ${typeDescription.name} is a Kotlin class with no line-number table; its probes carry " +
                    "no line numbers, and its inlined copies cannot be traced",
            )
        }
        val branchSites = analysis.sites.filter { pairing.isPaired(it.methodName, it.methodDescriptor) }
        val keptSites = analysis.keptSites.filter { pairing.isPaired(it.site.methodName, it.site.methodDescriptor) }
        val references = ReferencesKept(classLoader)

        val scalaGetterSitesByKey = analysis.scalaGetterSites.associateBy { it.getterName to it.getterDescriptor }
        val methodProbes =
            methods.map {
                val getterSite = scalaGetterSitesByKey[it.key]
                if (getterSite != null) {
                    ProbeMeta(
                        ProbeKind.OPTIONAL_ARGUMENT,
                        getterSite.targetName.interned(),
                        getterSite.targetDescriptor.interned(),
                        line = getterSite.line,
                        inline = false,
                        parameterIndex = getterSite.parameterIndex,
                        parameterName = getterSite.parameterName.internedOrNull(),
                        overridable = getterSite.overridable,
                        targetClassName = getterSite.targetClassName.internedOrNull(),
                        generatedBy = analysis.generatedBy(getterSite.targetName, getterSite.targetDescriptor),
                        unreadShape = analysis.unreadShape(getterSite.targetName, getterSite.targetDescriptor),
                    )
                } else {
                    val sourceSignature = analysis.sourceSignatureOf(it.name, it.descriptor)
                    val paired = pairing.isPaired(it.name, it.descriptor)
                    ProbeMeta(
                        ProbeKind.METHOD,
                        analysis.reportedNameOf(it.name, it.descriptor).interned(),
                        it.descriptor.interned(),
                        line = analysis.firstLineOf(it.name, it.descriptor),
                        inline = analysis.isInline(it.name, it.descriptor),
                        calls =
                            analysis
                                .callsOf(it.name, it.descriptor)
                                .map { edge -> if (paired) edge else edge.copy(guard = null) }
                                .rightSized(),
                        generatedBy = analysis.generatedBy(it.name, it.descriptor),
                        unreadShape = analysis.unreadShape(it.name, it.descriptor),
                        referencedClasses = references.keep(analysis.referencesOf(it.name, it.descriptor)),
                        lambdaBody = analysis.isLambdaBody(it.name, it.descriptor),
                        branchSites = if (paired) analysis.branchSitesOf(it.name, it.descriptor) else emptyList(),
                        static = it.isStatic,
                        parameterNames = sourceSignature.parameterNames.internedAll(),
                        genericSignature = sourceSignature.genericSignature.interned(),
                        extensionReceiver = sourceSignature.extensionReceiver,
                        outsideCaller =
                            OutsideCallers.of(
                                overriddenType = analysis.overriddenOutsideTypeOf(it.name, it.descriptor),
                                annotationType = analysis.callbackAnnotationOf(it.name, it.descriptor),
                                inheritedAnnotationType = analysis.inheritedCallbackAnnotationOf(it.name, it.descriptor),
                            ),
                    )
                }
            }
        recordUnreadShapes(typeDescription.name, analysis.unreadRelease, analysis.unreadCause, methodProbes)
        val branchProbes =
            keptSites.flatMap { kept ->
                val site = kept.site
                kept.outcomes.map { outcome ->
                    ProbeMeta(
                        ProbeKind.BRANCH,
                        analysis.reportedNameOf(site.methodName, site.methodDescriptor).interned(),
                        site.methodDescriptor.interned(),
                        site.line,
                        branchIndex = outcome.branchIndex,
                        inline = analysis.isInline(site.methodName, site.methodDescriptor),
                        inlinedFromClassName = site.inlinedFromClassName.internedOrNull(),
                        branchKey = outcome.branchKey,
                        generatedBy = analysis.generatedBy(site.methodName, site.methodDescriptor),
                        unreadShape = analysis.unreadShape(site.methodName, site.methodDescriptor),
                        siteIndex = site.siteIndex,
                    )
                }
            }
        recordUnreadOutcomes(typeDescription.name, keptSites)
        recordBranchDrops(typeDescription.name, branchSites)
        val sizeWarnings = sizeGuardWarnings(typeDescription.name, analysis, pairing, methods)
        val defaultSites =
            analysis.defaultSites.filter { receivedMethods == null || (it.defaultName to it.defaultDescriptor) in receivedMethods }
        var omissionBase = methodProbes.size + branchProbes.size
        val omissionSiteBases = mutableMapOf<Pair<String, String>, Int>()
        val omissionProbes =
            defaultSites.flatMap { site ->
                omissionSiteBases[site.defaultName to site.defaultDescriptor] = omissionBase
                val slots =
                    (0 until Int.SIZE_BITS)
                        .filter { bit -> (site.optionalBits shr bit) and 1 == 1 }
                        .map { bit ->
                            ProbeMeta(
                                ProbeKind.OPTIONAL_ARGUMENT,
                                site.targetName.interned(),
                                site.targetDescriptor.interned(),
                                line = site.defaultLines[bit] ?: -1,
                                inline = analysis.isInline(site.targetName, site.targetDescriptor),
                                parameterIndex = bit,
                                parameterName = (site.parameterNames[bit] ?: "").interned(),
                                overridable = site.overridable,
                                generatedBy = analysis.generatedBy(site.targetName, site.targetDescriptor),
                                unreadShape = analysis.unreadShape(site.targetName, site.targetDescriptor),
                            )
                        }
                omissionBase += slots.size
                slots
            }
        logUnprobedDefaults(typeDescription, defaultSites, analysis)
        val typeInitializerProbes =
            if (analysis.hasTypeInitializer) {
                val line = analysis.firstLineOf("<clinit>", "()V")
                listOf(
                    ProbeMeta(
                        ProbeKind.METHOD,
                        "<clinit>",
                        "()V",
                        line = line,
                        calls = analysis.callsOf("<clinit>", "()V").rightSized(),
                        referencedClasses = references.keep(analysis.referencesOf("<clinit>", "()V")),
                    ),
                    ProbeMeta(ProbeKind.INITIALIZER_RETURNED, "<clinit>", "()V", line = line),
                    ProbeMeta(ProbeKind.INITIALIZER_THREW, "<clinit>", "()V", line = line),
                )
            } else {
                emptyList()
            }
        val probes = methodProbes + branchProbes + omissionProbes + typeInitializerProbes
        val typeInitializerProbeIndex = if (typeInitializerProbes.isNotEmpty()) probes.size - typeInitializerProbes.size else null

        val layoutHash =
            ProbeLayoutHash.of(
                methods.map { it.name + it.descriptor } +
                    branchSites
                        .filter { it.dropReason == null }
                        .map { "${it.methodName}${it.methodDescriptor}#branch${it.siteIndex}x${it.probedOutcomeCount}" } +
                    defaultSites.map { "${it.defaultName}${it.defaultDescriptor}#optional${it.optionalBits}" } +
                    (if (analysis.hasTypeInitializer) listOf(ProbeLayoutHash.TYPE_INITIALIZER_ENTRY) else emptyList()),
            )
        val plan =
            WeavePlan.Builder(
                classFileHash = if (source.hasClassFile) analysedBytes?.let(WovenClasses::hashOf) else null,
                layoutHash = layoutHash,
                probeCount = probes.size,
                majorVersion = ProbeArrayForm.majorVersionOf(source.received ?: analysedBytes),
                typeInitializerProbeIndex = typeInitializerProbeIndex,
                initializerReturnedProbeIndex = typeInitializerProbeIndex?.plus(1),
                initializerThrewProbeIndex = typeInitializerProbeIndex?.plus(2),
                branchWrapper = branchSites.isNotEmpty(),
                branchBase = methodProbes.size,
                branchSlotCapacity = branchProbes.size,
            )
        methods.forEachIndexed { slot, method -> plan.entrySlot(method.name, method.descriptor, slot) }
        var nextSlot = 0
        val runs = LinkedHashMap<Pair<String, String>, BranchProbeAsmVisitorWrapper.MethodSlots>()
        for (kept in keptSites) {
            val key = kept.site.methodName to kept.site.methodDescriptor
            val run = runs[key] ?: BranchProbeAsmVisitorWrapper.MethodSlots(nextSlot, 0)
            runs[key] = run.copy(count = run.count + kept.outcomes.size)
            nextSlot += kept.outcomes.size
        }
        for ((key, run) in runs) plan.branchRun(key.first, key.second, run)
        if (branchSites.isNotEmpty()) {
            val eligible =
                methods.map { it.key }.filter { (name, descriptor) ->
                    pairing.isPaired(name, descriptor)
                }
            for ((name, descriptor) in eligible) {
                plan.branchEligible(
                    name,
                    descriptor,
                    analysis.trackedSequenceOf(name, descriptor),
                    analysis.droppedOrdinalsOf(name, descriptor),
                    analysis.throwingDefaultOrdinalsOf(name, descriptor),
                    analysis.unprobedOutcomesOf(name, descriptor),
                )
            }
        }
        for (site in defaultSites) {
            val base = omissionSiteBases.getValue(site.defaultName to site.defaultDescriptor)
            plan.defaultSite(site.defaultName, site.defaultDescriptor, DefaultSiteBinding(base, site.optionalBits, site.maskParameterIndex))
        }
        val built = plan.build()
        stage(typeDescription, classLoader, probes, analysis, references, built, sizeWarnings)
        return weave(
            builder,
            typeDescription,
            built,
            built.view(),
            pairing,
            reweaving = false,
            receivedBytes = source.received ?: analysedBytes,
        )
    }

    private class ProbedMethod(
        val name: String,
        val descriptor: String,
        val isStatic: Boolean,
    ) {
        val key: Pair<String, String> = name to descriptor
    }

    private data class ClassFileView(
        val analysedMethods: List<ProbedMethod>,
        val receivedMethods: Set<Pair<String, String>>?,
        val analysis: BranchSiteAnalyzer.Analysis,
        val pairing: SitePairing,
    )

    private fun analyse(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        source: ClassBytesSource,
    ): ClassFileView {
        val analysedBytes = source.analysed
        val isScalaClass = analysedBytes?.let(ScalaClassDetector::isScalaClass) ?: false
        val methodMatcher = methodMatcher(isScalaClass)
        val enhancement = source.analysedReader?.let(JpaEnhancement::of) ?: JpaEnhancement.NONE
        val analysedMethods =
            (
                if (source.receivedDiffers) {
                    describeClassFile(typeDescription, analysedBytes!!, classLoader).declaredMethods.filter(methodMatcher)
                } else {
                    typeDescription.declaredMethods.filter(methodMatcher)
                }
            ).filterNot { enhancement.isUnprobed(it.internalName, it.descriptor) }
                .map { ProbedMethod(it.internalName, it.descriptor, it.isStatic) }
        val receivedMethods =
            if (source.receivedDiffers) typeDescription.declaredMethods.mapTo(HashSet()) { it.internalName to it.descriptor } else null
        val analysis =
            analyzeBytecode(
                analysedBytes,
                source.analysedReader,
                classLoader,
                analysedMethods,
                source.received.takeIf { source.receivedDiffers },
            )
        val pairing =
            if (source.receivedDiffers) {
                val keys = analysedMethods.map { it.key }
                SitePairing
                    .of(
                        analysedBytes!!,
                        source.received!!,
                        keys,
                    ).withUnpaired(loadTimeWrappers(analysedBytes, source.received, keys), keys)
            } else {
                SitePairing.IDENTICAL
            }
        return ClassFileView(analysedMethods, receivedMethods, analysis, pairing)
    }

    private fun loadTimeWrappers(
        classFile: ByteArray,
        received: ByteArray,
        methods: Collection<Pair<String, String>>,
    ): List<Pair<String, String>> =
        try {
            val wrappers = JpaEnhancement.loadTimeWrappers(classFile, received)
            methods.filter { it in wrappers }
        } catch (_: RuntimeException) {
            methods.toList()
        }

    private fun reweave(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        protectionDomain: ProtectionDomain?,
    ): DynamicType.Builder<*> {
        val name = typeDescription.name
        val plan = wovenClasses.find(classLoader, name) ?: throw ReweaveRefused(name, "no plan was stored for $name")
        // take is destructive, so it must not be called a second time for the same class.
        val received = classBytesCapture?.take(typeDescription.internalName)?.let(SubroutineInliner::inline)
        if (plan.hasClassFileHash) {
            val classFile = locateClassBytes(typeDescription, classLoader, protectionDomain)
            if (classFile != null && WovenClasses.hashOf(classFile) != plan.classFileHash) {
                return refuse(
                    builder,
                    name,
                    plan,
                    "the class file of $name differs from the one it was woven from, as after a recompile or a HotSwap",
                )
            }
        }
        val receivedVersion = received?.let(ProbeArrayForm::majorVersionOf)
        if (receivedVersion != null &&
            !ProbeArrayForm.sameForm(plan.majorVersion, receivedVersion, typeDescription.isInterface)
        ) {
            return refuse(
                builder,
                name,
                plan,
                "the bytes for $name arrive at class-file version $receivedVersion, where it was woven at " +
                    "${plan.majorVersion}, and its probes' form cannot follow",
            )
        }
        val view = plan.view()
        val pairing = SitePairing.ofStored(view.sequences, received)
        val frozen =
            view.branchEligible.filter { (name, descriptor) ->
                !pairing.isPaired(name, descriptor) && (view.branchRuns[name to descriptor]?.count ?: 0) > 0
            }
        if (frozen.isNotEmpty() && plan.firstLog(WeavePlan.LOGGED_FROZEN)) {
            log.log(
                Level.INFO,
                "otherlode: ${typeDescription.name} was transformed again with bytes whose branches no longer line up " +
                    "with its class file in ${frozen.joinToString { (name, descriptor) -> name + descriptor }}; those " +
                    "methods keep their entry probe, and their branch counts stay where they were",
            )
        }
        val tooLarge = sizeGuardedAgain(received, view, pairing)
        if (tooLarge.isNotEmpty() && plan.firstLog(WeavePlan.LOGGED_SIZE_GUARD)) {
            log.log(
                Level.WARNING,
                "otherlode: ${typeDescription.name} was transformed again with bytes whose branch probes would not fit " +
                    "a class file in ${tooLarge.joinToString { (name, descriptor) -> name + descriptor }}; those " +
                    "methods keep their entry probe, and their branch counts stay where they were",
            )
        }
        return weave(
            builder,
            typeDescription,
            plan,
            view,
            pairing,
            reweaving = true,
            receivedBytes = received,
            sizeGuarded = tooLarge,
        )
    }

    private fun sizeGuardedAgain(
        received: ByteArray?,
        view: WeavePlan.View,
        pairing: SitePairing,
    ): Set<Pair<String, String>> {
        if (received == null) return emptySet()
        val methods =
            view.branchEligible.filter { key ->
                pairing.isPaired(key.first, key.second) && (view.branchRuns[key]?.count ?: 0) > 0
            }
        return SizeGuard.reweaveGuarded(
            received,
            methods,
            { key -> view.sequences[key] },
            { key -> view.droppedOrdinalsOf(key.first, key.second) },
        )
    }

    private fun logRefusal(reason: String) =
        log.log(
            Level.WARNING,
            "otherlode: $reason; it cannot be woven again, so the JVM refuses the redefinition or retransformation, " +
                "and the class keeps running with its probes",
        )

    private fun refuse(
        builder: DynamicType.Builder<*>,
        className: String,
        plan: WeavePlan,
        reason: String,
    ): DynamicType.Builder<*> {
        if (plan.majorVersion < ProbeArrayForm.DYNAMIC_CONSTANT_VERSION) throw ReweaveRefused(className, reason)
        return refuseWithoutField(builder, reason, plan)
    }

    private fun refuseWithoutField(
        builder: DynamicType.Builder<*>,
        reason: String,
        plan: WeavePlan,
    ): DynamicType.Builder<*> {
        if (plan.firstLog(WeavePlan.LOGGED_REFUSAL)) logRefusal(reason)
        return builder.visit(ProbeArrayMembers.refusalMarkerWrapper())
    }

    private fun weave(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        plan: WeavePlan,
        view: WeavePlan.View,
        pairing: SitePairing,
        reweaving: Boolean,
        receivedBytes: ByteArray?,
        sizeGuarded: Set<Pair<String, String>> = emptySet(),
    ): DynamicType.Builder<*> {
        val form = ProbeArrayForm.of(plan.majorVersion, typeDescription, plan.layoutHash, plan.probeCount)
        var instrumented: DynamicType.Builder<*> = builder
        if (form.hasField) {
            instrumented =
                instrumented.visit(
                    ProbeArrayMembers.wrapper(form, typeDescription.name, plan.layoutHash, plan.probeCount, plan.typeInitializerProbeIndex),
                )
        }

        val typeInitializerSlot = plan.typeInitializerProbeIndex
        val slotBySignature =
            if (!form.hasField && typeInitializerSlot != null) {
                view.entrySlots + (("<clinit>" to "()V") to typeInitializerSlot)
            } else {
                view.entrySlots
            }
        instrumented = instrumented.visit(MethodProbes.entry(form, slotBySignature))

        val returnedSlot = plan.initializerReturnedProbeIndex
        val threwSlot = plan.initializerThrewProbeIndex
        if (returnedSlot != null && threwSlot != null) {
            instrumented =
                instrumented.visit(
                    InitializerProbes.wrapper(form, returnedSlot, threwSlot, BranchProbeAsmVisitorWrapper.carriesFrames(receivedBytes)),
                )
        }

        if (plan.branchWrapper) {
            val eligible =
                view.branchEligible.filterTo(HashSet()) { key -> pairing.isPaired(key.first, key.second) && key !in sizeGuarded }
            instrumented =
                instrumented.visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { name, descriptor -> (name to descriptor) in eligible },
                        probeArray = form,
                        probeIndexBase = plan.branchBase,
                        branchSlotCapacity = if (reweaving) Int.MAX_VALUE else plan.branchSlotCapacity,
                        droppedOrdinalsByMethod = view::droppedOrdinalsOf,
                        throwingDefaultOrdinalsByMethod = view::throwingDefaultOrdinalsOf,
                        unprobedOutcomesByMethod = view::unprobedOutcomesOf,
                        swappedOrdinalsByMethod = pairing::swappedOrdinalsOf,
                        slotsByMethod = { name, descriptor -> view.branchRuns[name to descriptor] },
                        classHasFrames = BranchProbeAsmVisitorWrapper.carriesFrames(receivedBytes),
                    ),
                )
        }

        if (view.defaultSites.isNotEmpty()) {
            instrumented = instrumented.visit(MethodProbes.omission(form, view.defaultSites))
        }

        instrumented = instrumented.visit(SubroutineInliner.wrapper())

        return instrumented
    }

    private fun logUnprobedDefaults(
        typeDescription: TypeDescription,
        defaultSites: List<DefaultSite>,
        analysis: BranchSiteAnalyzer.Analysis,
    ) {
        for (site in defaultSites) {
            if (site.higherMaskTested) {
                log.log(
                    Level.INFO,
                    "otherlode: ${typeDescription.name}#${site.defaultName} tests a mask int past the first; " +
                        "only the first 32 optional parameters are counted",
                )
            }
        }
        for ((name, descriptor) in analysis.unresolvedDefaultSites) {
            log.log(
                Level.INFO,
                "otherlode: ${typeDescription.name}#$name$descriptor looks like a Kotlin default-argument method " +
                    "but its target could not be uniquely resolved, or no mask test was found; no omission probes woven",
            )
        }
        for ((name, descriptor) in analysis.unresolvedScalaGetterSites) {
            log.log(
                Level.INFO,
                "otherlode: ${typeDescription.name}#$name$descriptor looks like a Scala default getter but its target " +
                    "could not be uniquely resolved; reported as an ordinary method probe",
            )
        }
    }

    private fun stage(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        probes: List<ProbeMeta>,
        analysis: BranchSiteAnalyzer.Analysis,
        references: ReferencesKept,
        plan: WeavePlan,
        sizeWarnings: List<String>,
    ) {
        pendingRegistration.set(
            PendingRegistration(
                typeDescription.name,
                plan.layoutHash,
                probes,
                classLoader,
                analysis.superClassName.internedOrNull(),
                analysis.interfaceNames.internedAll(),
                references.keep(analysis.classReferences),
                references.kept(),
                analysis.sourceFile,
                analysis.bodyKind,
                analysis.sourceName,
                analysis.handlerForwarders,
                analysis.kotlinKind,
                plan,
                sizeWarnings,
            ),
        )
        if (staticBaselineMismatchDetector.shouldWarnAbout(typeDescription.name)) {
            log.log(
                Level.WARNING,
                "otherlode: ${typeDescription.name} registered dynamically but was not in the static baseline computed " +
                    "at startup for this process; the static scan cannot see classes a server or plugin loader finds " +
                    "at run time, such as a deployed WAR, so this deployment may have such a blind spot",
            )
        }
    }

    private inner class ReferencesKept(
        private val classLoader: ClassLoader?,
    ) {
        private val found = LinkedHashMap<String, ReferencedClassLocator.Found?>()

        fun keep(names: List<String>): List<String> =
            names
                .filter { name ->
                    if (name !in found) found[name] = referencedClassLocator.locate(name, classLoader)
                    found[name] != null
                }.internedAll()

        fun kept(): Map<String, String?> = found.entries.mapNotNull { (name, where) -> where?.let { name to it.location } }.toMap()
    }

    private fun recordUnreadShapes(
        typeName: String,
        release: String?,
        cause: UnreadCause?,
        methodProbes: List<ProbeMeta>,
    ) {
        val unread = methodProbes.filter { it.kind == ProbeKind.METHOD && it.unreadShape != UnreadShape.NONE }
        if (unread.isEmpty()) return
        val warn =
            unreadShapeCounts.record(
                typeName,
                release,
                cause ?: UnreadCause.VERSION_BLIND,
                unread.groupingBy { it.unreadShape }.eachCount(),
            )
        if (warn) {
            log.log(
                Level.WARNING,
                "otherlode: $typeName was compiled by Scala $release, which this agent has not read; " +
                    "${unread.size} ${if (unread.size == 1) "method that looks" else "methods that look"} like compiler output " +
                    "${if (unread.size == 1) "is" else "are"} reported as unread shapes",
            )
        }
    }

    private fun recordUnreadOutcomes(
        typeName: String,
        keptSites: List<KeptBranchSite>,
    ) {
        val unread =
            keptSites
                .flatMap { it.outcomes }
                .filter { it.unreadShape != UnreadShape.NONE }
                .groupingBy { it.unreadShape }
                .eachCount()
        unreadShapeCounts.recordOutcomes(typeName, unread)
    }

    private fun recordBranchDrops(
        typeName: String,
        branchSites: List<BranchSite>,
    ) {
        val dropsByReason = branchSites.mapNotNull { it.dropReason }.groupingBy { it }.eachCount()
        if (dropsByReason.isEmpty()) return
        branchDropCounts.record(dropsByReason)
        val total = dropsByReason.values.sum()
        val inlinedOutOfScope = dropsByReason[BranchDropReason.INLINED_OUT_OF_SCOPE] ?: 0
        val coroutineMachinery = dropsByReason[BranchDropReason.COROUTINE_MACHINERY] ?: 0
        val switchLowering = dropsByReason[BranchDropReason.SWITCH_LOWERING] ?: 0
        val sizeGuard = dropsByReason[BranchDropReason.SIZE_GUARD] ?: 0
        val jpaWeaving = dropsByReason[BranchDropReason.JPA_WEAVING] ?: 0
        log.log(
            Level.DEBUG,
            "otherlode: $typeName left $total branch sites without a probe: " +
                "$inlinedOutOfScope inlined from out-of-scope code, $coroutineMachinery coroutine machinery, " +
                "$switchLowering switch lowering, $sizeGuard in methods whose branch probes would cross a code-size limit, " +
                "$jpaWeaving added by a JPA enhancer",
        )
    }

    private fun sizeGuardWarnings(
        typeName: String,
        analysis: BranchSiteAnalyzer.Analysis,
        pairing: SitePairing,
        methods: List<ProbedMethod>,
    ): List<String> {
        val probed = methods.mapTo(HashSet()) { it.key }
        val guarded = analysis.sizeGuard.guarded.filter { (key, _) -> pairing.isPaired(key.first, key.second) }
        val entryOnly = analysis.sizeGuard.entryPastLimit.filter { (key, _) -> key in probed }
        return (guarded + entryOnly).map { (key, verdict) -> verdict.message(typeName, key.first, key.second) }
    }

    private class ClassBytesSource(
        val analysed: ByteArray?,
        val received: ByteArray?,
        val receivedDiffers: Boolean,
        val hasClassFile: Boolean,
    ) {
        val analysedReader: ClassReader? by lazy(LazyThreadSafetyMode.NONE) { analysed?.let(::ClassReader) }
    }

    private fun readClassBytes(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        protectionDomain: ProtectionDomain?,
    ): ClassBytesSource {
        // take is destructive, so it must not be called a second time for the same class.
        val received = classBytesCapture?.take(typeDescription.internalName)?.let(SubroutineInliner::inline)
        val classFile = locateClassBytes(typeDescription, classLoader, protectionDomain)
        if (classFile == null && received != null) {
            log.log(Level.DEBUG, "otherlode: no class file found for ${typeDescription.name}; its shape is read from the received bytes")
            unreadShapeCounts.recordReceivedBytesClass(typeDescription.name)
        }
        return ClassBytesSource(
            analysed = classFile ?: received,
            received = received,
            receivedDiffers = classFile != null && received != null && !classFile.contentEquals(received),
            hasClassFile = classFile != null,
        )
    }

    private fun describeClassFile(
        typeDescription: TypeDescription,
        classFile: ByteArray,
        classLoader: ClassLoader?,
    ): TypeDescription {
        val locator =
            ClassFileLocator.Compound(
                ClassFileLocator.Simple.of(typeDescription.name, classFile),
                classFileCache.locatorFor(classLoader),
            )
        return TypePool.Default
            .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
            .describe(typeDescription.name)
            .resolve()
    }

    private fun analyzeBytecode(
        classBytes: ByteArray?,
        classReader: ClassReader?,
        classLoader: ClassLoader?,
        methods: List<ProbedMethod>,
        receivedBytes: ByteArray?,
    ): BranchSiteAnalyzer.Analysis {
        val eligible = methods.mapTo(HashSet()) { it.key }
        val bytes = classBytes ?: return BranchSiteAnalyzer.Analysis.EMPTY
        val lookup = crossClassLookup(classLoader)
        return BranchSiteAnalyzer.analyzeThrough(
            classReader ?: ClassReader(bytes),
            bytes,
            lookup,
            config.includePackages,
            config.excludePackages,
            tableCacheFor(classLoader),
            handlerForwarders.handlerInterfaces,
            resourceLookup(classLoader),
            receivedBytes,
            outsideCallers = true,
            outOfScopeLookup = uncachedLookup(classLoader),
            callbackAnnotations = callbackAnnotations,
        ) { name, descriptor -> (name to descriptor) in eligible }
    }

    private fun uncachedLookup(classLoader: ClassLoader?): (String) -> ByteArray? =
        lookupThrough(ClassFileByteCache.uncachedLocatorFor(classLoader))

    private fun lookupThrough(locator: ClassFileLocator): (String) -> ByteArray? =
        { internalName ->
            try {
                val resolution = locator.locate(internalName.replace('/', '.'))
                if (resolution.isResolved) resolution.resolve() else null
            } catch (_: Exception) {
                null
            }
        }

    private fun crossClassLookup(classLoader: ClassLoader?): (String) -> ByteArray? = lookupThrough(classFileCache.locatorFor(classLoader))

    private fun resourceLookup(classLoader: ClassLoader?): (String) -> ByteArray? =
        { path ->
            try {
                classLoader?.getResourceAsStream(path)?.use { it.readNBytes(ScalaReleases.HEADER_BYTES) }
            } catch (_: Exception) {
                null
            }
        }

    private fun locateClassBytes(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        protectionDomain: ProtectionDomain?,
    ): ByteArray? {
        codeSourceClassFile.read(protectionDomain, typeDescription.internalName)?.let { bytes ->
            try {
                return SubroutineInliner.inline(bytes)
            } catch (_: Exception) {
            }
        }
        val locator = ClassFileByteCache.uncachedLocatorFor(classLoader)
        return try {
            val resolution = locator.locate(typeDescription.name)
            if (resolution.isResolved) SubroutineInliner.inline(resolution.resolve()) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun methodMatcher(isScalaClass: Boolean): ElementMatcher.Junction<MethodDescription> =
        TypeMatchPolicy.methodMatcher(isScalaClass)
}

private const val TRANSFORM_TABLE_CACHE_ENTRIES = 2048
