package dev.otherlode.instrumentation.endpoints

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.BootstrapHolder
import dev.otherlode.instrumentation.BootstrapVisibilityGuard
import dev.otherlode.instrumentation.ClassFileByteCache
import dev.otherlode.instrumentation.OwnRetransformationFailures
import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import net.bytebuddy.utility.JavaModule
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires every discovered [EndpointModule]'s registration and dispatch advice into the JVM through its own
 * `AgentBuilder` pipeline, separate from the method tier's.
 *
 * When any module names a handler interface, [install] also installs a [LambdaFactoryHook]. It checks
 * the JDK against [lambdaFactoryShape].
 *
 * The transformer is retransformation-capable. Another agent's retransformation of a class it wove
 * reaches it with bytes that carry none of its advice, and it weaves the advice in again.
 */
class EndpointInstrumentation(
    private val registry: EndpointRegistry,
    private val modules: List<EndpointModule>,
    lambdaFactoryShape: LambdaFactoryShape = LambdaFactoryShape.JDK,
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
    private val classFileCache: ClassFileByteCache = ClassFileByteCache(),
    ownRetransformationFailures: OwnRetransformationFailures = OwnRetransformationFailures(),
    private val bootstrapVisibilityGuard: BootstrapVisibilityGuard = BootstrapVisibilityGuard(),
) {
    private val log by agentLogger(EndpointInstrumentation::class.java.name)
    private val agentClassLoader = EndpointInstrumentation::class.java.classLoader
    private val lambdaFactoryHook = LambdaFactoryHook(lambdaFactoryShape, ownRetransformationFailures)

    private val pendingDeclarations = PendingDeclarations()

    private val alreadyLoaded = ThreadLocal<Boolean?>()

    private val matchedModules = ThreadLocal<MutableSet<EndpointModule>?>()

    private fun recordMatched(module: EndpointModule) {
        var matched = matchedModules.get()
        if (matched == null) {
            matched = LinkedHashSet()
            matchedModules.set(matched)
        }
        matched += module
    }

    private val reweaveFailureLogged = ConcurrentHashMap.newKeySet<String>()

    private val firstWeaveFailed = WeakHashMap<ClassLoader?, MutableMap<String, MutableSet<String>>>()

    private fun recordFirstWeaveFailed(
        classLoader: ClassLoader?,
        typeName: String,
        module: EndpointModule,
    ) {
        synchronized(firstWeaveFailed) {
            firstWeaveFailed.getOrPut(classLoader) { HashMap() }.getOrPut(typeName) { HashSet() } += module.name
        }
    }

    private fun firstWeaveFailed(
        classLoader: ClassLoader?,
        typeName: String,
        module: EndpointModule,
    ): Boolean = synchronized(firstWeaveFailed) { firstWeaveFailed[classLoader]?.get(typeName)?.contains(module.name) == true }

    internal fun pendingDeclarationCount(): Int = pendingDeclarations.pendingCount()

    private val adviceBinderLock = Any()
    private val adviceBindersByLoader = WeakHashMap<ClassLoader, AdviceBinder>()
    private var bootLoaderAdviceBinder: AdviceBinder? = null

    private fun adviceBinderFor(targetClassLoader: ClassLoader?): AdviceBinder =
        synchronized(adviceBinderLock) {
            if (targetClassLoader == null) {
                bootLoaderAdviceBinder ?: AdviceBinder(agentClassLoader, null).also { bootLoaderAdviceBinder = it }
            } else {
                adviceBindersByLoader.getOrPut(targetClassLoader) { AdviceBinder(agentClassLoader, targetClassLoader) }
            }
        }

    /**
     * Installs the bootstrap holder, points the endpoint seam at this registry, adds a module read edge
     * from every boot module a module declares needing one, and registers one retransformation-capable
     * `AgentBuilder` covering every discovered module, with nothing retransformed. It installs the lambda
     * factory hook when a module names a handler interface. Returns the transformer.
     */
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer {
        BootstrapHolder.install(instrumentation)
        OtherlodeEndpoints.install(RegistryResolver(registry, modules, pendingDeclarations, handlerForwarders))
        addSeamReadEdges(instrumentation)

        if (modules.isEmpty()) {
            log.log(Level.INFO, "otherlode: no endpoint modules were discovered, no framework's endpoints will be instrumented")
        } else {
            log.log(Level.INFO, "otherlode: installing endpoint modules: ${modules.joinToString(", ") { it.name }}")
        }

        var builder: AgentBuilder =
            AgentBuilder
                .Default()
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(classFileCache.locationStrategy())
                .disableClassFormatChanges()
                // The default ignore matcher skips bootstrap classes, which endpoint modules need to match.
                // Runtime-generated proxies are ignored too, since a proxy and its target would count each call twice.
                .ignore(
                    nameStartsWith<TypeDescription>(TypeMatchPolicy.AGENT_PACKAGE_PREFIX)
                        .or(ElementMatcher { type -> TypeMatchPolicy.isRuntimeGenerated(type.name) }),
                ).with(EndpointTransformListener())

        for (module in modules) {
            builder =
                builder.type(rawMatcher(module, ::recordMatched)).transform { typeBuilder, typeDescription, classLoader, _, _ ->
                    val mark = pendingDeclarations.begin(typeDescription.name)
                    if (classLoader != null && bootstrapVisibilityGuard.refusal(classLoader) != null) {
                        pendingDeclarations.rollbackTo(mark)
                        OtherlodeEndpoints.moduleDisabled(
                            module.name,
                            OtherlodeEndpoints.KIND_TRANSFORM_FAILED,
                            "the class loader ${classLoader.javaClass.name} cannot see dev.otherlode.bootstrap",
                        )
                        return@transform typeBuilder
                    }
                    if (alreadyLoaded.get() == true) return@transform reweave(module, typeBuilder, typeDescription, classLoader, mark)
                    try {
                        val binder = adviceBinderFor(classLoader).forCall()
                        val woven = module.transform(typeBuilder, typeDescription, binder, classLoader)
                        stageDisableForUnmatchedHook(module, binder, typeDescription, mark)
                        woven
                    } catch (t: Throwable) {
                        // The builder is returned unchanged, so ByteBuddy sees success: the rollback keeps this
                        // module's partial declarations out of the registry.
                        pendingDeclarations.rollbackTo(mark)
                        log.log(Level.WARNING, "otherlode: endpoint module ${module.name} failed to transform ${typeDescription.name}", t)
                        OtherlodeEndpoints.moduleFailed(module.name, OtherlodeEndpoints.KIND_TRANSFORM_FAILED, t)
                        typeBuilder
                    }
                }
        }

        val transformer = CurrentClassFileTransformer(builder.makeRaw() as ResettableClassFileTransformer, EndpointPreFilter(modules))
        instrumentation.addTransformer(transformer, true)

        val handlerInterfaces = modules.flatMapTo(sortedSetOf()) { it.handlerInterfaces }
        if (handlerInterfaces.isNotEmpty()) {
            lambdaFactoryHook.install(instrumentation, handlerInterfaces)
        }
        return transformer
    }

    private fun stageDisableForUnmatchedHook(
        module: EndpointModule,
        binder: AdviceBinder,
        typeDescription: TypeDescription,
        mark: Int,
    ) {
        for (hook in binder.recordedHooks()) {
            val wovenMethods =
                typeDescription.declaredMethods.count { method: MethodDescription ->
                    hook.methodMatcher.matches(method) && !method.isAbstract && !method.isNative
                }
            if (wovenMethods > 0) continue
            val reason =
                "hook ${hook.adviceClassName.substringAfterLast('.')} (${hook.methodMatcher}) matches no method " +
                    "ByteBuddy can weave on ${typeDescription.name}"
            pendingDeclarations.rollbackTo(mark)
            pendingDeclarations.stage {
                OtherlodeEndpoints.moduleDisabled(module.name, OtherlodeEndpoints.KIND_HOOK_UNMATCHED, reason)
            }
            return
        }
    }

    private fun reweave(
        module: EndpointModule,
        typeBuilder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        mark: Int,
    ): DynamicType.Builder<*> =
        try {
            module.transform(typeBuilder, typeDescription, adviceBinderFor(classLoader).forCall(), classLoader)
        } catch (t: Throwable) {
            if (reweaveFailureLogged.add(module.name)) {
                log.log(
                    Level.WARNING,
                    "otherlode: endpoint module ${module.name} could not weave its advice into ${typeDescription.name} again " +
                        "for a redefinition or retransformation, so it is switched off",
                    t,
                )
            }
            OtherlodeEndpoints.moduleFailed(module.name, OtherlodeEndpoints.KIND_TRANSFORM_FAILED, t)
            typeBuilder
        } finally {
            pendingDeclarations.rollbackTo(mark)
        }

    /**
     * Calls [EndpointModule.beforeManifest] on every module. A module that throws is switched off and
     * the others still run, so this never fails the export.
     */
    fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) {
        for (module in modules) {
            try {
                module.beforeManifest(graceMillis, finalFlush)
            } catch (t: Throwable) {
                log.log(Level.WARNING, "otherlode: endpoint module ${module.name} failed before a manifest was built", t)
                OtherlodeEndpoints.moduleFailed(module.name, t)
            }
        }
    }

    /** Removes the endpoint advice transformer, and the lambda factory hook if this instance installed one. */
    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
        lambdaFactoryHook.uninstall(instrumentation)
    }

    private fun addSeamReadEdges(instrumentation: Instrumentation) {
        val bootModuleNames = modules.flatMapTo(sortedSetOf()) { it.bootModulesNeedingSeamRead }
        if (bootModuleNames.isEmpty()) return
        val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module
        for (name in bootModuleNames) {
            val bootModule = ModuleLayer.boot().findModule(name).orElse(null)
            if (bootModule == null) {
                log.log(Level.DEBUG, "otherlode: module '$name' is not in the boot layer, so it gets no read edge to the endpoint seam")
                continue
            }
            instrumentation.redefineModule(bootModule, setOf(seamModule), emptyMap(), emptyMap(), emptySet(), emptyMap())
        }
    }

    companion object {
        private val matcherLog by agentLogger(EndpointInstrumentation::class.java.name)

        /**
         * The matcher [install] registers for [module]. It calls [onMatched] with [module] when it accepts a
         * class.
         *
         * A matcher that throws switches the module off as [OtherlodeEndpoints.KIND_TRANSFORM_FAILED], and
         * it answers no from then on. The type matcher is built once per loader and held weakly, so a
         * module's matcher must not hold the loader it was built for.
         */
        fun rawMatcher(
            module: EndpointModule,
            onMatched: (EndpointModule) -> Unit = {},
        ): AgentBuilder.RawMatcher {
            val loaderMatcher = module.classLoaderMatcher()
            val byLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, ElementMatcher<in TypeDescription>>())
            val bootstrapMatcher = lazy(LazyThreadSafetyMode.PUBLICATION) { module.typeMatcher(null) }
            val failed = AtomicBoolean(false)
            return AgentBuilder.RawMatcher { typeDescription, classLoader, _, _, _ ->
                val candidates = CurrentClassFile.candidatesOf(typeDescription.internalName)
                if (candidates != null && module !in candidates) return@RawMatcher false
                if (failed.get()) return@RawMatcher false
                val matched =
                    try {
                        if (!loaderMatcher.matches(classLoader)) return@RawMatcher false
                        val classFile = CurrentClassFile.bytesOf(typeDescription.internalName)
                        if (classFile != null && !module.classFileMayMatch(classFile)) return@RawMatcher false
                        val typeMatcher: ElementMatcher<in TypeDescription> =
                            if (classLoader == null) {
                                bootstrapMatcher.value
                            } else {
                                byLoader.computeIfAbsent(classLoader) { module.typeMatcher(it) }
                            }
                        typeMatcher.matches(typeDescription)
                    } catch (t: Throwable) {
                        if (failed.compareAndSet(false, true)) {
                            matcherLog.log(
                                Level.WARNING,
                                "otherlode: endpoint module ${module.name} could not decide whether to match ${typeDescription.name}, " +
                                    "so it is switched off",
                                t,
                            )
                            OtherlodeEndpoints.moduleFailed(module.name, OtherlodeEndpoints.KIND_TRANSFORM_FAILED, t)
                        }
                        false
                    }
                if (matched) onMatched(module)
                matched
            }
        }
    }

    private inner class EndpointTransformListener : AgentBuilder.Listener.Adapter() {
        override fun onDiscovery(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            alreadyLoaded.set(loaded)
            matchedModules.remove()
        }

        override fun onTransformation(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            dynamicType: DynamicType,
        ) {
            pendingDeclarations.commit(classLoader)
        }

        override fun onError(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            throwable: Throwable,
        ) {
            log.log(
                Level.WARNING,
                "otherlode: endpoint instrumentation failed for $typeName, class will run without endpoint tracking",
                throwable,
            )
            // A failed re-weave leaves the class without this tier's advice but with the routes its first weave declared.
            for (module in matchedModules.get().orEmpty()) {
                if (module.weaveFailureLeavesNothingPartial) {
                    if (!loaded) {
                        recordFirstWeaveFailed(classLoader, typeName, module)
                        continue
                    }
                    if (firstWeaveFailed(classLoader, typeName, module)) continue
                }
                OtherlodeEndpoints.moduleFailed(module.name, OtherlodeEndpoints.KIND_TRANSFORM_FAILED, throwable)
            }
        }

        override fun onComplete(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            pendingDeclarations.discard()
            alreadyLoaded.remove()
            matchedModules.remove()
        }
    }
}
