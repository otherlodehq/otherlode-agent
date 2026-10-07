package dev.otherlode.instrumentation.endpoints

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.BootstrapHolder
import dev.otherlode.instrumentation.ClassFileByteCache
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

/**
 * Wires every discovered [EndpointModule]'s registration and dispatch advice into the JVM.
 *
 * This is a separate `AgentBuilder`/transformer pipeline from
 * [dev.otherlode.instrumentation.OtherlodeInstrumentation]: the method tier has its own type
 * strategy and writes its members and branch probes in ASM, while an endpoint module only ever adds
 * advice to an existing method body, which stock `DECORATE` supports (see [install]).
 *
 * When any module names a handler interface, [install] also installs a [LambdaFactoryHook], so a
 * handler written as a lambda or a method reference can be named. [lambdaFactoryShape]
 * is what that hook checks the JDK against. [handlerForwarders] is the forwarder table the method
 * tier fills, which turns a reported pass-through into the method it forwards to.
 *
 * The transformer is retransformation-capable, so it runs after every agent whose transformer is
 * not, and another agent's retransformation of a class it wove reaches it, with bytes that do not
 * carry its advice. It weaves its advice into those bytes again, which changes no schema, and
 * declares nothing a second time; without that the retransformation would drop the advice.
 */
class EndpointInstrumentation(
    private val registry: EndpointRegistry,
    private val modules: List<EndpointModule>,
    lambdaFactoryShape: LambdaFactoryShape = LambdaFactoryShape.JDK,
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
    /** The cache the JAX-RS supertype walk and every other class-file read of this tier goes through. */
    private val classFileCache: ClassFileByteCache = ClassFileByteCache(),
) {
    private val log = System.getLogger(EndpointInstrumentation::class.java.name)
    private val agentClassLoader = EndpointInstrumentation::class.java.classLoader
    private val lambdaFactoryHook = LambdaFactoryHook(lambdaFactoryShape)

    /** Endpoints a module declared during a transform, held until that transform produces bytes. */
    private val pendingDeclarations = PendingDeclarations()

    /**
     * Whether the class ByteBuddy is handling on this thread is already loaded, which makes the call
     * a retransformation or a redefinition. Set from the listener's discovery callback, which
     * ByteBuddy makes before it matches or transforms anything, and cleared when it completes.
     */
    private val alreadyLoaded = ThreadLocal<Boolean?>()

    /**
     * Every module whose raw matcher accepted, or threw on, the class ByteBuddy is handling on this
     * thread. The listener's error callback gets no module, so this is how it knows whose hooks a
     * failed weave lost. One thread has one class in flight (see [PendingDeclarations]), so the
     * record is per thread and is cleared when the class is done.
     */
    private val matchedModules = ThreadLocal<MutableSet<EndpointModule>?>()

    private fun recordMatched(module: EndpointModule) {
        var matched = matchedModules.get()
        if (matched == null) {
            matched = LinkedHashSet()
            matchedModules.set(matched)
        }
        matched += module
    }

    /** Modules whose advice failed to weave again on a retransformation, each logged once. */
    private val reweaveFailureLogged = ConcurrentHashMap.newKeySet<String>()

    /** How many declarations from a transform this thread is holding; for tests. */
    internal fun pendingDeclarationCount(): Int = pendingDeclarations.pendingCount()

    /**
     * One [AdviceBinder] per target classloader, since Spring's own types (unlike the JDK's
     * `HttpServer`, which loads on the bootstrap loader) live on the application's classloader,
     * for example Spring Boot's `LaunchedClassLoader`, not this agent's own. A [WeakHashMap] under
     * [adviceBinderLock] keeps a retired classloader collectible instead of pinning it for the
     * life of the process; the bootstrap loader is a `null` key at the JVM level, which
     * [WeakHashMap] cannot hold, so it is cached separately in [bootLoaderAdviceBinder].
     */
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
     * Installs the bootstrap holder (idempotent if [dev.otherlode.instrumentation.OtherlodeInstrumentation]
     * already installed it), points the endpoint seam at this registry, adds a module read edge
     * from every boot module a module declares needing one, then installs one `AgentBuilder`
     * covering every discovered module's type matcher and advice, registered
     * retransformation-capable with nothing retransformed. Last, it installs the lambda factory
     * hook for every handler interface a module names, if there is one.
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
                // DECORATE only weaves advice into existing method bodies; it never adds a field
                // or a type initializer. ByteBuddy's type validation is left on here: DECORATE
                // never describes the type for it, so what remains is the check of the written
                // bytes against the class-file version, the one guard on the advice an endpoint
                // module weaves.
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                // Describes an already-loaded class from the bytes passed in, which are the ones
                // the advice is woven into, rather than from its Class object. On a first load
                // every strategy reads the pool.
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(classFileCache.locationStrategy())
                .disableClassFormatChanges()
                // Replaces AgentBuilder's own default ignore matcher, which skips bootstrap-loader
                // classes among others. The JDK's own HttpServer classes load on the bootstrap
                // loader, and an endpoint module needs to match them. A class a framework generated
                // at runtime is ignored too: a proxy of a resource class inherits its annotations'
                // meaning and calls the real method through super, so weaving both would count
                // every call twice.
                .ignore(
                    nameStartsWith<TypeDescription>(TypeMatchPolicy.AGENT_PACKAGE_PREFIX)
                        .or(ElementMatcher { type -> TypeMatchPolicy.isRuntimeGenerated(type.name) }),
                ).with(EndpointTransformListener())

        for (module in modules) {
            builder =
                builder.type(rawMatcher(module, ::recordMatched)).transform { typeBuilder, typeDescription, classLoader, _, _ ->
                    // Anything the module declares from here is held until the rewrite produces
                    // bytes; see PendingDeclarations. The mark is where this module's own
                    // declarations start, since another module matching the same class may have
                    // staged some already.
                    val mark = pendingDeclarations.begin()
                    if (alreadyLoaded.get() == true) return@transform reweave(module, typeBuilder, typeDescription, classLoader, mark)
                    try {
                        // A view of the cached binder that records this call's hooks alone; see
                        // AdviceBinder.forCall.
                        val binder = adviceBinderFor(classLoader).forCall()
                        val woven = module.transform(typeBuilder, typeDescription, binder, classLoader)
                        stageDisableForUnmatchedHook(module, binder, typeDescription, mark)
                        woven
                    } catch (t: Throwable) {
                        // This catch is why a throwing module needs the rollback: it returns the
                        // builder unchanged, so the transform still succeeds as far as ByteBuddy
                        // is concerned, the listener commits, and whatever this module declared
                        // before it threw would land in the registry for a class that never got
                        // its advice.
                        //
                        // The rollback covers this class only. moduleFailed below switches the
                        // module off for the whole process, so routes it already declared for
                        // earlier classes keep their manifest rows while their advice stops
                        // counting: those do read as never called. The disabled list is the only
                        // signal for that; see STATUS.md.
                        pendingDeclarations.rollbackTo(mark)
                        log.log(Level.WARNING, "otherlode: endpoint module ${module.name} failed to transform ${typeDescription.name}", t)
                        OtherlodeEndpoints.moduleFailed(module.name, OtherlodeEndpoints.KIND_TRANSFORM_FAILED, t)
                        typeBuilder
                    }
                }
        }

        // AgentBuilder.Default.makeRaw returns a ResettableClassFileTransformer; the interface
        // this chain ends on declares only the supertype.
        val transformer = builder.makeRaw() as ResettableClassFileTransformer
        instrumentation.addTransformer(transformer, true)

        val handlerInterfaces = modules.flatMapTo(sortedSetOf()) { it.handlerInterfaces }
        if (handlerInterfaces.isNotEmpty()) {
            lambdaFactoryHook.install(instrumentation, handlerInterfaces)
        }
        return transformer
    }

    /**
     * Stages a switch-off of [module] when one of the hooks it attached while transforming
     * [typeDescription] matches no method `Advice` can weave: ByteBuddy writes such a class
     * unchanged and reports success, so the lost hook would otherwise go unseen. What the module
     * declared for this class is dropped, and the hooks that did match stay woven but inert once the module is off. The switch-off
     * is staged beside the declarations, so it is committed with the class's transformation and
     * dropped if that fails.
     */
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

    /**
     * Weaves [module]'s advice into the bytes passed in for an already-loaded class, by another
     * agent's retransformation or a redefinition, and drops whatever the module declared while doing
     * it, since the class's endpoints were declared when it first loaded. A module that throws here
     * is not disabled for the process: the class goes without that module's advice until it is next
     * transformed, and the failure is logged once per module.
     */
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
                        "for a redefinition or retransformation; that class runs without endpoint tracking",
                    t,
                )
            }
            typeBuilder
        } finally {
            pendingDeclarations.rollbackTo(mark)
        }

    /** Removes the endpoint advice transformer, and the lambda factory hook if this instance installed one. */
    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
        lambdaFactoryHook.uninstall(instrumentation)
    }

    /**
     * Adds a module read edge from every boot module an [EndpointModule] names in
     * [EndpointModule.bootModulesNeedingSeamRead] to the endpoint seam's own module, the same way
     * OpenTelemetry's agent reaches `jdk.httpserver`. The seam lives on the bootstrap loader's
     * unnamed module precisely so any classloader can reach it, but a named module's own module
     * descriptor still gates what it is allowed to read, so `jdk.httpserver`'s own classes cannot
     * call into the seam without this edge.
     *
     * A boot module name this JDK does not have is logged at INFO and skipped: that framework's
     * advice never matches anything on this JVM.
     */
    private fun addSeamReadEdges(instrumentation: Instrumentation) {
        val bootModuleNames = modules.flatMapTo(sortedSetOf()) { it.bootModulesNeedingSeamRead }
        if (bootModuleNames.isEmpty()) return
        val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module
        for (name in bootModuleNames) {
            val bootModule = ModuleLayer.boot().findModule(name).orElse(null)
            if (bootModule == null) {
                log.log(
                    Level.INFO,
                    "otherlode: boot module '$name' is not present in this JDK, its endpoint module will not match anything",
                )
                continue
            }
            instrumentation.redefineModule(bootModule, setOf(seamModule), emptyMap(), emptyMap(), emptySet(), emptyMap())
        }
    }

    companion object {
        /**
         * The matcher [install] registers for [module], which calls [onMatched] with [module] when it
         * accepts a class or throws on one: its [EndpointModule.classLoaderMatcher] first,
         * so a loader the module rejects never has a class parsed for it, then
         * [EndpointModule.typeMatcher] for the defining loader.
         *
         * The type matcher is built once for each loader and kept weakly by it, since the default
         * form builds a new `namedOneOf` for every call and this runs for every class the JVM
         * loads. A module's matcher must therefore not hold the loader it was built for.
         */
        fun rawMatcher(
            module: EndpointModule,
            onMatched: (EndpointModule) -> Unit = {},
        ): AgentBuilder.RawMatcher {
            val loaderMatcher = module.classLoaderMatcher()
            val byLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, ElementMatcher<in TypeDescription>>())
            val bootstrapMatcher = lazy(LazyThreadSafetyMode.PUBLICATION) { module.typeMatcher(null) }
            return AgentBuilder.RawMatcher { typeDescription, classLoader, _, _, _ ->
                if (!loaderMatcher.matches(classLoader)) return@RawMatcher false
                val matched =
                    try {
                        val typeMatcher: ElementMatcher<in TypeDescription> =
                            if (classLoader == null) {
                                bootstrapMatcher.value
                            } else {
                                byLoader.computeIfAbsent(classLoader) { module.typeMatcher(it) }
                            }
                        typeMatcher.matches(typeDescription)
                    } catch (t: Throwable) {
                        // A module whose matcher cannot say whether it wanted the class may have.
                        onMatched(module)
                        throw t
                    }
                if (matched) onMatched(module)
                matched
            }
        }
    }

    /**
     * Commits what a transform declared once its bytes exist, and reports a transform failure
     * ByteBuddy caught on its own, outside any module's own try/catch.
     *
     * `onTransformation` runs only after `make()` has produced the bytes, so an endpoint declared
     * from inside the transform callback reaches the registry only for a class that really was
     * woven. `onComplete` runs whatever the outcome, so a failed transform's declarations are
     * dropped rather than left staged on the thread.
     */
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
            pendingDeclarations.commit()
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
            // A re-weave keeps the bytes the first weave produced, so its failure loses no hook.
            if (loaded) return
            for (module in matchedModules.get().orEmpty()) {
                if (module.weaveFailureLeavesNothingPartial) continue
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
