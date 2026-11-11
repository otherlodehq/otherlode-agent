package dev.otherlode

import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.classlisting.ClassLister
import dev.otherlode.classlisting.ClassListingPublisher
import dev.otherlode.classlisting.ClassListingSender
import dev.otherlode.classlisting.ClassListingWatch
import dev.otherlode.config.AgentConfig
import dev.otherlode.config.IncludeRulesRefusal
import dev.otherlode.config.MainClassSuggestion
import dev.otherlode.dependencies.DependencyListingRun
import dev.otherlode.dependencies.DependencyResolver
import dev.otherlode.dependencies.JarClassifier
import dev.otherlode.dependencies.ListedDependency
import dev.otherlode.dependencies.LoadedDependencyCounter
import dev.otherlode.dependencies.StartupClasspathLister
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.ExportScheduler
import dev.otherlode.export.Exporter
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.IngestNotices
import dev.otherlode.export.ManifestConfirmedExporter
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.forNewRun
import dev.otherlode.instrumentation.BootstrapHolder
import dev.otherlode.instrumentation.BootstrapInstallException
import dev.otherlode.instrumentation.BootstrapVisibilityGuard
import dev.otherlode.instrumentation.ClassFileByteCache
import dev.otherlode.instrumentation.LoadedClassSweep
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.instrumentation.OwnRetransformationFailures
import dev.otherlode.instrumentation.ShutdownHooksHook
import dev.otherlode.instrumentation.VerifyErrorHook
import dev.otherlode.instrumentation.branch.BranchDropCounts
import dev.otherlode.instrumentation.branch.ConfiguredCallbackAnnotations
import dev.otherlode.instrumentation.branch.UnreadShapeCounts
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointModules
import dev.otherlode.instrumentation.endpoints.HandlerForwarders
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.instrumentation.staticscan.BaselineReferenceFilter
import dev.otherlode.instrumentation.staticscan.StaticBaselineMismatchDetector
import dev.otherlode.instrumentation.staticscan.StaticBaselinePublisher
import dev.otherlode.instrumentation.staticscan.StaticBaselineScanner
import dev.otherlode.instrumentation.staticscan.StaticBaselineSender
import dev.otherlode.memory.NativeHeapTrimmer
import dev.otherlode.registry.DependencyOrigin
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.FrameworkMarkerRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** `-javaagent:otherlode-agent.jar` entry point. */
object Agent {
    internal val SHUTDOWN_FLUSH_TIMEOUT: Duration = Duration.ofSeconds(10)
    internal val SHUTDOWN_DEADLINE: Duration = Duration.ofSeconds(28)
    internal val EARLY_FLUSH_BUDGET: Duration = Duration.ofSeconds(5)
    internal val HOOK_WAIT_ENDS: Duration = Duration.ofSeconds(18)
    private val TEST_RUN_SCAN_WAIT: Duration = Duration.ofSeconds(15)
    private const val OTEL_BRIDGE_MODULE_NAME = "otel"
    private val log by agentLogger(Agent::class.java.name)
    private val claimed = AtomicBoolean(false)

    /** Never throws. A failed start is logged and the agent stays inert. */
    @JvmStatic
    fun premain(
        agentArgs: String?,
        instrumentation: Instrumentation,
    ) {
        if (!claimed.compareAndSet(false, true)) {
            log.log(Level.WARNING, "otherlode: the agent is already running in this JVM; this second -javaagent is ignored")
            return
        }
        var running: Running? = null
        try {
            running = start(agentArgs, instrumentation)
        } catch (e: Throwable) {
            log.log(Level.ERROR, "otherlode: agent failed to start and is disabled for this JVM", e)
        } finally {
            if (running == null) claimed.set(false)
        }
    }

    internal class Running(
        val scheduler: ExportScheduler,
        private val instrumentation: Instrumentation,
        private val otherlodeInstrumentation: OtherlodeInstrumentation,
        private val transformer: ResettableClassFileTransformer,
        private val shutdownHook: Thread,
        private val endpointInstrumentation: EndpointInstrumentation? = null,
        val endpointTransformer: ResettableClassFileTransformer? = null,
        val dependencyRegistry: DependencyRegistry = DependencyRegistry(),
        val resource: ResourceAttributes,
        val classListingWorker: Thread? = null,
        val verifierHook: VerifyErrorHook = VerifyErrorHook(),
        val shutdownHooksHook: ShutdownHooksHook? = null,
        val classListingWatch: ClassListingWatch? = null,
        private val nativeHeapTrimmer: NativeHeapTrimmer? = null,
    ) {
        fun stop() {
            nativeHeapTrimmer?.stop()
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
            scheduler.stop()
            classListingWatch?.let(instrumentation::removeTransformer)
            otherlodeInstrumentation.uninstall(instrumentation, transformer)
            if (endpointInstrumentation != null && endpointTransformer != null) {
                endpointInstrumentation.uninstall(instrumentation, endpointTransformer)
            }
            verifierHook.uninstall(instrumentation)
            shutdownHooksHook?.uninstall(instrumentation)
        }
    }

    private fun openLoggerGate() {
        if (BootstrapHolder.isInstalled()) OtherlodeLoggers.openGate()
    }

    private fun suggestionForThisJvm(): MainClassSuggestion? =
        try {
            MainClassSuggestion.fromCommand(System.getProperty("sun.java.command"))
        } catch (e: Exception) {
            null
        }

    internal fun start(
        agentArgs: String?,
        instrumentation: Instrumentation,
        addShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
        startWorker: (Thread) -> Unit = Thread::start,
    ): Running? {
        val config = AgentConfig.parse(agentArgs)
        if (!config.enabled) {
            log.log(Level.INFO, "otherlode: disabled by configuration, nothing will be instrumented or exported")
            return null
        }
        if (config.refusals.isNotEmpty()) {
            config.refusals.forEach { log.log(Level.ERROR, it) }
            return null
        }
        if (config.includePackages.isEmpty()) {
            log.log(Level.ERROR, IncludeRulesRefusal.message(suggestionForThisJvm()))
            return null
        }

        try {
            BootstrapHolder.install(instrumentation)
        } catch (e: BootstrapInstallException) {
            log.log(Level.ERROR, "otherlode: could not install the bootstrap holder; the agent is disabled for this JVM", e)
            return null
        }

        val registry = ProbeRegistry(confirmsDefinitions = true)
        val endpointRegistry = EndpointRegistry()
        val frameworkMarkers = FrameworkMarkerRegistry()
        val dependencyRegistry = DependencyRegistry(indexClassNames = config.staticBaselineEnabled)
        // Both users share one resolver: the loaded-class count and the external-class mapping must agree on a
        // jar's dependency id.
        val dependencyResolver =
            DependencyResolver(dependencyRegistry, JarClassifier(config.includePackages, config.excludePackages))
        val externalClassRegistry =
            ExternalClassRegistry(
                dependencyRegistry::isListingComplete,
                dependencyResolver::resolveLocation,
                dependencyRegistry::isSendable,
            )
        val staticBaselineMismatchDetector = StaticBaselineMismatchDetector()
        val branchDropCounts = BranchDropCounts()
        val unreadShapeCounts = UnreadShapeCounts()
        val callbackAnnotations = ConfiguredCallbackAnnotations(config.callbackAnnotations)

        // Discover before the method tier installs: its analysis writes the forwarder table
        // for these modules' handler interfaces.
        val endpointSelection = discoverEndpointModules(config)
        endpointSelection?.keptOff?.forEach {
            endpointRegistry.recordDisabledModule(it.name, it.reason, DisabledEndpointModuleKind.CONFIGURED_OFF)
        }
        val endpointModules = endpointSelection?.installed?.takeIf { it.isNotEmpty() }
        val handlerForwarders = HandlerForwarders(endpointModules.orEmpty().flatMapTo(sortedSetOf()) { it.handlerInterfaces })

        val classFileCache = ClassFileByteCache()
        val bootstrapVisibilityGuard = BootstrapVisibilityGuard()
        val otherlodeInstrumentation =
            OtherlodeInstrumentation(
                config,
                registry,
                staticBaselineMismatchDetector,
                branchDropCounts = branchDropCounts,
                unreadShapeCounts = unreadShapeCounts,
                callbackAnnotations = callbackAnnotations,
                externalClassRegistry = externalClassRegistry,
                handlerForwarders = handlerForwarders,
                classFileCache = classFileCache,
                bootstrapVisibilityGuard = bootstrapVisibilityGuard,
            )
        val transformer =
            try {
                otherlodeInstrumentation.install(instrumentation)
            } catch (e: BootstrapInstallException) {
                log.log(Level.ERROR, "otherlode: could not install the bootstrap holder; the agent is disabled for this JVM", e)
                return null
            }

        val ownRetransformationFailures = OwnRetransformationFailures()
        var endpointInstrumentation: EndpointInstrumentation? = null
        var endpointTransformer: ResettableClassFileTransformer? = null
        if (endpointModules != null) {
            try {
                val instance =
                    EndpointInstrumentation(
                        endpointRegistry,
                        endpointModules,
                        handlerForwarders = handlerForwarders,
                        classFileCache = classFileCache,
                        ownRetransformationFailures = ownRetransformationFailures,
                        bootstrapVisibilityGuard = bootstrapVisibilityGuard,
                    )
                endpointTransformer = instance.install(instrumentation)
                endpointInstrumentation = instance
            } catch (e: Throwable) {
                log.log(Level.ERROR, "otherlode: endpoint instrumentation failed to install, continuing without endpoint tracking", e)
            }
        } else if (!config.endpointsEnabled) {
            log.log(Level.INFO, "otherlode: endpointsEnabled=false, no framework's endpoints will be instrumented")
        } else if (endpointSelection != null && endpointSelection.keptOff.isEmpty()) {
            log.log(Level.INFO, "otherlode: no endpoint modules were discovered, no framework's endpoints will be instrumented")
        }

        val instrumentedEndpoints = endpointInstrumentation
        val verifierHook = VerifyErrorHook(ownRetransformationFailures)
        verifierHook.install(instrumentation)

        var scheduler: ExportScheduler? = null
        var shutdownHook: Thread? = null
        var shutdownHooksHook: ShutdownHooksHook? = null
        var classListingWatch: ClassListingWatch? = null
        var nativeHeapTrimmer: NativeHeapTrimmer? = null
        val gate = WorkerGate()
        try {
            if (config.classListingEnabled) {
                val watch = ClassListingWatch(config.includePackages, config.excludePackages)
                instrumentation.addTransformer(watch)
                classListingWatch = watch
            }
            val resource = ResourceAttributes.forNewRun(config)
            val httpClient = HttpExporter.lazyClient()
            val ingestNotices = IngestNotices()
            val exporter = HttpExporter(config.exportUrl, config.authToken, httpClient, notices = ingestNotices)

            fun stamped(sender: Exporter): Exporter = ManifestConfirmedExporter(sender) { registry.manifestConfirmed }
            val staticBaselineSender =
                if (config.staticBaselineEnabled) {
                    StaticBaselineSender(
                        stamped(exporter),
                        retryExporter =
                            stamped(
                                HttpExporter(config.exportUrl, config.authToken, httpClient, maxAttempts = 1, notices = ingestNotices),
                            ),
                    )
                } else {
                    null
                }
            val classListingSender =
                if (config.classListingEnabled) {
                    ClassListingSender(
                        stamped(exporter),
                        retryExporter =
                            stamped(
                                HttpExporter(config.exportUrl, config.authToken, httpClient, maxAttempts = 1, notices = ingestNotices),
                            ),
                    )
                } else {
                    null
                }
            val dependencyListing = dependencyListingRun(config, dependencyRegistry)
            val started =
                ExportScheduler(
                    config,
                    resource,
                    registry,
                    endpointRegistry,
                    exporter,
                    branchDropCounts = branchDropCounts,
                    unreadShapeCounts = unreadShapeCounts,
                    callbackAnnotations = callbackAnnotations,
                    loadedClassSweep =
                        LoadedClassSweep(
                            instrumentation,
                            registry,
                            endpointRegistry,
                            config,
                            LoadedDependencyCounter(dependencyRegistry, dependencyResolver::resolve),
                            frameworkMarkers,
                        ),
                    dependencyRegistry = dependencyRegistry,
                    externalClassRegistry = externalClassRegistry,
                    frameworkMarkers = frameworkMarkers,
                    staticBaselineSender = staticBaselineSender,
                    classListingSender = classListingSender,
                    dependencyListing = dependencyListing,
                    startupCleanup = { BootstrapHolder.sweepLeftovers() },
                    releaseQuietCaches = otherlodeInstrumentation::releaseQuietCaches,
                    hasStartingPhase = true,
                    verifierWatch = verifierHook.watch(),
                    classListingBlind = { classListingWatch?.isBlind == true },
                    beforeManifest = { graceMillis, finalFlush -> instrumentedEndpoints?.beforeManifest(graceMillis, finalFlush) },
                )
            scheduler = started
            val scanWorker =
                if (staticBaselineSender != null) {
                    val referenceFilter =
                        BaselineReferenceFilter(dependencyRegistry, externalClassRegistry, dependencyListing = dependencyListing)
                    staticBaselineScanThread(
                        config,
                        resource,
                        exporter,
                        staticBaselineSender,
                        registry,
                        staticBaselineMismatchDetector,
                        referenceFilter,
                        gate,
                    )
                } else {
                    null
                }
            val hooksHook = if (config.testRun) null else ShutdownHooksHook(ownRetransformationFailures)
            shutdownHooksHook = hooksHook
            val hookWatch = if (hooksHook != null && hooksHook.install(instrumentation)) ShutdownHookWatch.fromSeam() else null
            val hook =
                Thread({
                    val hookStarted = System.nanoTime()
                    openLoggerGate()
                    shutdown(
                        config.testRun,
                        scanWorker,
                        TEST_RUN_SCAN_WAIT,
                        flushEarly = { started.flushAtShutdownStart(EARLY_FLUSH_BUDGET) },
                        awaitApplicationHooks = { awaitApplicationHooks(hookWatch, timeLeft(hookStarted, HOOK_WAIT_ENDS)) },
                    ) {
                        val budget =
                            if (config.testRun) {
                                SHUTDOWN_FLUSH_TIMEOUT
                            } else {
                                maxOf(
                                    SHUTDOWN_FLUSH_TIMEOUT,
                                    timeLeft(hookStarted, SHUTDOWN_DEADLINE),
                                )
                            }
                        started.flushOnShutdown(budget)
                    }
                }, "otherlode-shutdown-hook")
            addShutdownHook(hook)
            shutdownHook = hook
            val listingWorker =
                classListingSender?.let {
                    classListingThread(
                        config,
                        resource,
                        it,
                        classListingWatch,
                        instrumentation,
                        gate,
                    )
                }
            scanWorker?.let(startWorker)
            listingWorker?.let(startWorker)
            started.start()
            val trimmer = NativeHeapTrimmer(config.trimNativeHeapInterval)
            nativeHeapTrimmer = trimmer
            trimmer.start()
            val running =
                Running(
                    started,
                    instrumentation,
                    otherlodeInstrumentation,
                    transformer,
                    hook,
                    endpointInstrumentation,
                    endpointTransformer,
                    dependencyRegistry,
                    resource,
                    listingWorker,
                    verifierHook,
                    shutdownHooksHook,
                    classListingWatch,
                    trimmer,
                )
            gate.open()
            return running
        } catch (e: Throwable) {
            fun undo(step: () -> Unit) = runCatching(step).exceptionOrNull()?.let(e::addSuppressed)
            shutdownHook?.let { undo { Runtime.getRuntime().removeShutdownHook(it) } }
            scheduler?.let { undo(it::stop) }
            nativeHeapTrimmer?.let { undo(it::stop) }
            undo(gate::abort)
            undo { otherlodeInstrumentation.uninstall(instrumentation, transformer) }
            if (endpointInstrumentation != null && endpointTransformer != null) {
                undo { endpointInstrumentation.uninstall(instrumentation, endpointTransformer) }
            }
            undo { verifierHook.uninstall(instrumentation) }
            shutdownHooksHook?.let { undo { it.uninstall(instrumentation) } }
            classListingWatch?.let { undo { instrumentation.removeTransformer(it) } }
            throw e
        }
    }

    private fun discoverEndpointModules(config: AgentConfig): EndpointModuleSelection? =
        try {
            selectEndpointModules(
                EndpointModules.discover(),
                config.endpointsEnabled,
                config.otelBridgeEnabled,
                config.disabledEndpointModules,
            )
        } catch (e: Throwable) {
            log.log(Level.ERROR, "otherlode: endpoint module discovery failed, continuing without endpoint tracking", e)
            null
        }

    internal class KeptOffModule(
        val name: String,
        val reason: String,
    )

    internal class EndpointModuleSelection(
        val installed: List<EndpointModule>,
        val keptOff: List<KeptOffModule>,
    )

    internal fun selectEndpointModules(
        discovered: List<EndpointModule>,
        endpointsEnabled: Boolean,
        otelBridgeEnabled: Boolean,
        disabledNames: List<String>,
    ): EndpointModuleSelection {
        val knownNames = discovered.map { it.name }
        disabledNames.filter { it !in knownNames }.distinct().forEach { unknown ->
            log.log(
                Level.WARNING,
                "otherlode: disabledEndpointModules entry '$unknown' names no endpoint module and is ignored: " +
                    "the modules are ${knownNames.joinToString(", ")}",
            )
        }
        val candidates = filterEndpointModules(discovered, otelBridgeEnabled)
        if (!endpointsEnabled) {
            return EndpointModuleSelection(emptyList(), candidates.map { KeptOffModule(it.name, "endpointsEnabled=false") })
        }
        val (keptOff, installed) = candidates.partition { it.name in disabledNames }
        if (keptOff.isNotEmpty()) {
            log.log(
                Level.INFO,
                "otherlode: disabledEndpointModules keeps these endpoint modules off: ${keptOff.joinToString(", ") { it.name }}",
            )
        }
        return EndpointModuleSelection(installed, keptOff.map { KeptOffModule(it.name, "disabledEndpointModules names it") })
    }

    internal fun filterEndpointModules(
        modules: List<EndpointModule>,
        otelBridgeEnabled: Boolean,
    ): List<EndpointModule> = if (otelBridgeEnabled) modules else modules.filterNot { it.name == OTEL_BRIDGE_MODULE_NAME }

    private fun staticBaselineScanThread(
        config: AgentConfig,
        resource: ResourceAttributes,
        exporter: Exporter,
        sender: StaticBaselineSender,
        registry: ProbeRegistry,
        mismatchDetector: StaticBaselineMismatchDetector,
        referenceFilter: BaselineReferenceFilter,
        gate: WorkerGate,
    ): Thread {
        val scanner = StaticBaselineScanner(config.includePackages, config.excludePackages)
        val publisher =
            StaticBaselinePublisher(
                scanner::scan,
                exporter,
                registry,
                mismatchDetector,
                filterReferences = referenceFilter::filter,
                sender = sender,
                includeRules = IncludeRules(config.includePackages, config.excludePackages),
            )
        val worker = Thread({ if (gate.await()) publisher.run(resource) }, "otherlode-static-baseline-scan")
        worker.isDaemon = true
        return worker
    }

    private fun classListingThread(
        config: AgentConfig,
        resource: ResourceAttributes,
        sender: ClassListingSender,
        watch: ClassListingWatch?,
        instrumentation: Instrumentation,
        gate: WorkerGate,
    ): Thread {
        val publisher =
            ClassListingPublisher(
                ClassLister(config.includePackages, config.excludePackages, onRoots = {
                    roots,
                    names,
                    ->
                    watch?.listingRead(roots, names, instrumentation::getAllLoadedClasses)
                })::list,
                IncludeRules(config.includePackages, config.excludePackages),
                sender,
            )
        val worker = Thread({ if (gate.await()) publisher.run(resource) }, "otherlode-class-listing")
        worker.isDaemon = true
        return worker
    }

    internal fun shutdown(
        testRun: Boolean,
        scanWorker: Thread?,
        scanWait: Duration,
        flushEarly: () -> Unit = {},
        awaitApplicationHooks: () -> Unit = {},
        flush: () -> Unit,
    ) {
        // The JVM starts java.util.logging's hook with this one, and that hook resets every handler.
        StandardErrorLogging.on = true
        if (!testRun) {
            guarded("early flush", flushEarly)
            guarded("wait for the application's shutdown hooks", awaitApplicationHooks)
            Thread.interrupted()
        }
        // The flush runs before the scan wait, so a JVM halted during the wait still sends the final manifest.
        flush()
        if (!testRun || scanWorker == null) return
        scanWorker.join(scanWait.toMillis())
        if (scanWorker.isAlive) {
            log.log(
                Level.WARNING,
                "otherlode: the static baseline scan did not end within ${scanWait.seconds}s of shutdown, so this test run may send no complete scan",
            )
        }
    }

    private fun guarded(
        step: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: the $step failed at shutdown: $t")
        }
    }

    private fun timeLeft(
        startedNanos: Long,
        endsAfter: Duration,
    ): Duration = Duration.ofNanos(maxOf(0L, startedNanos + endsAfter.toNanos() - System.nanoTime()))

    internal fun awaitApplicationHooks(
        watch: ShutdownHookWatch?,
        cap: Duration,
    ) {
        if (watch == null) return
        if (!watch.awaitOthers(Thread.currentThread(), cap)) {
            log.log(
                Level.WARNING,
                "otherlode: the application's shutdown hooks were still running ${HOOK_WAIT_ENDS.seconds}s into shutdown, " +
                    "so the final flush runs now and code they run later will not be counted",
            )
        }
    }

    private fun dependencyListingRun(
        config: AgentConfig,
        registry: DependencyRegistry,
    ): DependencyListingRun {
        val lister =
            StartupClasspathLister(
                config.includePackages,
                config.excludePackages,
                onNotADependency = registry::recordNotADependency,
                keepClassNames = config.staticBaselineEnabled,
            )
        return DependencyListingRun {
            recordAgentJar(
                Agent::class.java.protectionDomain.codeSource
                    ?.location,
                registry,
            )
            runDependencyListing(lister::list, registry)
        }
    }

    internal fun recordAgentJar(
        location: URL?,
        registry: DependencyRegistry,
    ) {
        try {
            if (location == null || location.protocol != "file") return
            val path = Paths.get(location.toURI())
            if (Files.isRegularFile(path)) registry.recordNotADependency(DependencyOrigin.FlatJar(path.toAbsolutePath()))
        } catch (e: Exception) {
            log.log(Level.DEBUG, "otherlode: could not read the agent's own location $location", e)
        }
    }

    internal fun runDependencyListing(
        list: () -> List<ListedDependency>,
        registry: DependencyRegistry,
    ) {
        try {
            val listed = list()
            for (dependency in listed) {
                registry.register(
                    dependency.identities,
                    dependency.identitySource,
                    dependency.location,
                    DependencyDiscoverySource.STARTUP_CLASSPATH,
                    dependency.classCount,
                    dependency.origin,
                    dependency.classNames,
                )
            }
            registry.markListingComplete()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: the startup dependency listing failed; no dependencies will be reported", t)
            registry.markListingFailed()
        }
    }
}

internal class WorkerGate {
    private val decided = CountDownLatch(1)

    @Volatile
    private var opened = false

    fun open() {
        opened = true
        decided.countDown()
    }

    fun abort() {
        decided.countDown()
    }

    fun await(): Boolean =
        try {
            decided.await()
            opened
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
