package dev.otherlode.instrumentation

import dev.otherlode.advice.MaskArgument
import dev.otherlode.advice.MethodEntryAdvice
import dev.otherlode.advice.OmissionBase
import dev.otherlode.advice.OptionalArgumentAdvice
import dev.otherlode.advice.OptionalBits
import dev.otherlode.advice.ProbeArray
import dev.otherlode.advice.ProbeIndex
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
import dev.otherlode.instrumentation.branch.DefaultSite
import dev.otherlode.instrumentation.branch.HandlerForwarder
import dev.otherlode.instrumentation.branch.KeptBranchSite
import dev.otherlode.instrumentation.branch.ScalaReleases
import dev.otherlode.instrumentation.branch.SitePairing
import dev.otherlode.instrumentation.branch.UnreadCause
import dev.otherlode.instrumentation.branch.UnreadShapeCounts
import dev.otherlode.instrumentation.endpoints.HandlerForwarders
import dev.otherlode.instrumentation.staticscan.StaticBaselineMismatchDetector
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.ByteBuddy
import net.bytebuddy.NamingStrategy
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.method.ParameterDescription
import net.bytebuddy.description.modifier.FieldManifestation
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.SyntheticState
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
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
import java.util.WeakHashMap

private fun bindingFor(
    bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    instrumentedMethod: MethodDescription,
): DefaultSiteBinding =
    bindings[instrumentedMethod.internalName to instrumentedMethod.descriptor]
        ?: throw IllegalStateException(
            "otherlode: no omission binding for ${instrumentedMethod.internalName}${instrumentedMethod.descriptor}",
        )

/**
 * Wires method-entry, branch, optional-argument and `<clinit>` probes into every type matched by
 * [AgentConfig.includePackages], less the bootstrap and platform loaders' classes that
 * ByteBuddy's `AgentBuilder` ignores by default. An empty list matches nothing; the agent refuses
 * to start with one.
 *
 * Each matched type is registered with [ProbeRegistry] once, after its rewrite succeeds; see
 * [TransformResultListener]. Its probes reach its own array, which the bootstrap-resident
 * [OtherlodeProbeArrays] asks the registry for, through the [ProbeArrayForm] its class-file
 * version selects. From version 55 every probe loads the array as a dynamic constant and the class
 * gets no member at all. Below it the class gets a `public static final long[]` field, a `<clinit>`
 * prelude that fills it with one call to the holder, and a private accessor every probe calls,
 * which falls back to the holder while the field is still null. A supertype's initializer can run
 * the class's code before the class's own `<clinit>`, and a probe must not read a null array there.
 * Either way a probe is a load of the array and a constant slot index. No lookup by class or
 * method name is involved on any hot path; the one lookup happens once per class.
 *
 * Interfaces are instrumented the same way. The JVM only allows public static final fields on an
 * interface, which rules out setting the field reflectively after load (the JDK refuses
 * reflective writes to static finals); the dynamic constant and the woven `<clinit>` are the
 * mechanisms that work for classes and interfaces alike. An interface below version 52 cannot
 * have a private method, so it gets the field and the prelude and no accessor.
 *
 * This registers a transformer for classes as they load. It does not retransform classes already
 * loaded when [install] runs. That matches the agent's static `premain` attach model, where the
 * transformer is registered before any application class has loaded.
 *
 * The transformer is retransformation-capable, so another agent's retransformation of a woven class
 * reaches it, and so does a redefinition. Either way the class is woven again from the plan its
 * first weave recorded, with nothing committed to the registry a second time, as long as its class
 * file is still the one it was woven from; see [WovenClasses]. When the class file has changed the
 * call is refused. Any class this agent did not weave is left alone.
 */
class OtherlodeInstrumentation(
    private val config: AgentConfig,
    private val registry: ProbeRegistry,
    private val staticBaselineMismatchDetector: StaticBaselineMismatchDetector = StaticBaselineMismatchDetector(),
    /**
     * Whether [install] registers a [ClassBytesCapture] ahead of ByteBuddy's transformer. Always
     * true for the agent; a test switches it off to drive the path taken when the capture has
     * nothing for a class, where the class file stands in for the received bytes.
     */
    captureClassBytes: Boolean = true,
    /** Where dropped branch sites are tallied; see [BranchDropCounts]. */
    private val branchDropCounts: BranchDropCounts = BranchDropCounts(),
    /** Where the methods reported as unread shapes are tallied; see [UnreadShapeCounts]. */
    private val unreadShapeCounts: UnreadShapeCounts = UnreadShapeCounts(),
    /** Where each out-of-scope class a transformed class references was found. */
    private val externalClassRegistry: ExternalClassRegistry = ExternalClassRegistry(),
    /**
     * The forwarder table, and the handler interfaces the analysis looks for. A class's entries are
     * written when its probes are, in [TransformResultListener].
     */
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
) {
    private val log = System.getLogger(OtherlodeInstrumentation::class.java.name)

    private val referencedClassLocator = ReferencedClassLocator()
    private val classBytesCapture: ClassBytesCapture? = if (captureClassBytes) ClassBytesCapture(::isCandidateInternalName) else null
    private val wovenClasses = WovenClasses()

    /**
     * Whether the class ByteBuddy is handling on this thread is already loaded, which makes the call
     * a retransformation or a redefinition. Set from the listener's discovery callback, which
     * ByteBuddy makes before it matches or transforms anything, and cleared when it completes.
     */
    private val alreadyLoaded = ThreadLocal<Boolean?>()

    /**
     * One parsed-table cache per defining classloader, so transforms of classes that reference
     * the same in-scope class parse it once. Keyed weakly: a retired classloader takes its cache
     * with it. The bootstrap loader, which the JVM represents as null, gets its own.
     */
    private val tableCaches = WeakHashMap<ClassLoader, BranchSiteAnalyzer.CrossClassTableCache>()
    private val bootstrapTableCache = BranchSiteAnalyzer.CrossClassTableCache(TRANSFORM_TABLE_CACHE_ENTRIES)

    private fun tableCacheFor(classLoader: ClassLoader?): BranchSiteAnalyzer.CrossClassTableCache {
        if (classLoader == null) return bootstrapTableCache
        return synchronized(tableCaches) {
            tableCaches.getOrPut(classLoader) { BranchSiteAnalyzer.CrossClassTableCache(TRANSFORM_TABLE_CACHE_ENTRIES) }
        }
    }

    /** How many parsed tables the cache for [classLoader] holds; for tests. */
    internal fun cachedTableCount(classLoader: ClassLoader?): Int =
        if (classLoader == null) bootstrapTableCache.size else synchronized(tableCaches) { tableCaches[classLoader]?.size ?: 0 }

    /**
     * Installs the bootstrap holder, points it at this registry, then registers two transformers
     * in this order: [ClassBytesCapture], then ByteBuddy's. Both are registered as
     * retransformation-capable. The JVM calls every transformer that is not capable before every
     * one that is, whatever order the agents were listed in, so this agent runs after JaCoCo's,
     * AspectJ's and Spring's weavers and sees their output as its received bytes. Within the
     * capable group the JVM keeps registration order, so the capture always runs just before
     * ByteBuddy for the same class on the same thread. Nothing is retransformed here.
     *
     * Throws [BootstrapInstallException], before registering anything, if the holder cannot be
     * made bootstrap-visible. Without it every instrumented class would fail in its own
     * `<clinit>`, so not instrumenting at all is the only safe answer. Registering throws
     * [UnsupportedOperationException] where retransformation is not supported, which the agent
     * jar's `Can-Retransform-Classes` manifest attribute rules out.
     *
     * The returned transformer's `reset` only removes ByteBuddy's; call [uninstall] to remove both.
     */
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer {
        BootstrapHolder.install(instrumentation)
        OtherlodeProbeArrays.install { className, layoutHash, _, classLoader -> registry.lookup(className, layoutHash, classLoader) }
        // makeRaw and a plain addTransformer rather than installOn with a retransforming
        // redefinition strategy: installOn would also check retransformation support and run a
        // discovery pass, when all this needs is the registration flag.
        val transformer = buildTransformer()
        if (classBytesCapture != null) instrumentation.addTransformer(classBytesCapture, true)
        try {
            instrumentation.addTransformer(transformer, true)
        } catch (t: Throwable) {
            if (classBytesCapture != null) instrumentation.removeTransformer(classBytesCapture)
            throw t
        }
        return transformer
    }

    private fun buildTransformer(): ResettableClassFileTransformer =
        AgentBuilder
            // ByteBuddy's own default ignores every synthetic method, copying it through
            // unrewritten no matter what a later .visit()/.method() matcher asks for: a Kotlin
            // $default method is exactly such a method, and the omission tier's whole job is to
            // weave advice onto it. Every other tier already gates what it touches through its
            // own explicit matchers (methodMatcher, typeMatcher), so lifting ByteBuddy's blanket
            // exclusion here does not widen what actually gets instrumented.
            .Default(ByteBuddy().ignore(none()))
            .ignore(any<TypeDescription>(), isBootstrapClassLoader<ClassLoader>().or(isExtensionClassLoader()))
            .or(ignoredNames())
            // A class already loaded is left alone unless this agent wove it; see isWoven.
            .or(
                AgentBuilder.RawMatcher { type, classLoader, _, classBeingRedefined, _ ->
                    classBeingRedefined != null && !isWoven(type, classLoader)
                },
            )
            // No LoadedTypeInitializer is ever used, so ByteBuddy has nothing to run after load
            // and no reason to inject its Nexus class into the bootstrap loader via Unsafe.
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            // Describes a class from the bytes passed in even when it is already loaded. The
            // default describes a loaded class from its Class object, which carries the probe field
            // a re-weave is about to define. On a first load every strategy reads the pool.
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
            .with(TransformResultListener())
            .type(typeMatcher())
            .transform { builder, typeDescription, classLoader, _, _ -> instrument(builder, typeDescription, classLoader) }
            // AgentBuilder.Default.makeRaw returns a ResettableClassFileTransformer; the interface
            // this chain ends on declares only the supertype.
            .makeRaw() as ResettableClassFileTransformer

    /**
     * The names in ByteBuddy's own default ignore matcher: its own package (other than the package
     * it renames types into) and the reflection internals. With the bootstrap and platform loader
     * clause beside it in [install], this is `AgentBuilder.Default`'s ignore matcher in byte-buddy
     * 1.18.12 without its synthetic-type clause. That clause would keep a multi-file part from
     * [typeMatcher], which takes that one kind of synthetic class and turns away every other.
     */
    private fun ignoredNames(): ElementMatcher.Junction<TypeDescription> =
        nameStartsWith<TypeDescription>("net.bytebuddy.")
            .and(not(nameStartsWith("${NamingStrategy.BYTE_BUDDY_RENAME_PACKAGE}.")))
            .or(nameStartsWith("sun.reflect."))
            .or(nameStartsWith("jdk.internal.reflect."))

    /**
     * Whether this agent wove the already-loaded [type], so a call for it, a retransformation or a
     * redefinition, weaves it again from its [WeavePlan]. Any other class is left alone, and the
     * bytes captured for it are dropped here, since no transform follows to take them.
     */
    private fun isWoven(
        type: TypeDescription,
        classLoader: ClassLoader?,
    ): Boolean {
        if (wovenClasses.find(classLoader, type.name) != null) return true
        classBytesCapture?.take(type.internalName)
        return false
    }

    /**
     * Thrown from [reweave] when the class file of a woven class that carries the probe field is not
     * the one it was woven from. ByteBuddy reports it to [TransformResultListener.onError] and hands
     * back no bytes, so the JVM refuses the redefinition or retransformation: the bytes it is left
     * with lack the probe field. A class woven with a dynamic constant has no field to miss, so
     * [reweave] refuses it another way.
     */
    private class ReweaveRefused(
        className: String,
    ) : IllegalStateException("otherlode: the class file of $className differs from the one it was woven from")

    /** Removes both transformers [install] registered. */
    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
        if (classBytesCapture != null) instrumentation.removeTransformer(classBytesCapture)
    }

    /** String-only pre-filter for the capture, the package part of [typeMatcher] without resolving a type. */
    private fun isCandidateInternalName(internalName: String): Boolean =
        TypeMatchPolicy.isIncluded(internalName.replace('/', '.'), config.includePackages, config.excludePackages)

    /** One class's probes, held between [instrument] and the transform result; see [TransformResultListener]. */
    private class PendingRegistration(
        val className: String,
        val layoutHash: Long,
        val probes: List<ProbeMeta>,
        val classLoader: ClassLoader?,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val classReferences: List<String>,
        /** Every referenced class kept, dotted, with where it was found; see [ReferencedClassLocator.Found]. */
        val externalClasses: Map<String, String?>,
        val sourceFile: String?,
        val bodyKind: BodyKind,
        val sourceName: String?,
        val handlerForwarders: List<HandlerForwarder>,
        val kotlinKind: KotlinKind,
        /** How the class was woven, kept once the class is committed so a later call can weave it again. */
        val plan: WeavePlan,
    )

    /**
     * Probes computed by [instrument] for the class ByteBuddy is rewriting on this thread.
     *
     * One slot per thread, with no key. ByteBuddy runs the transform callback, the rewrite and
     * the result callbacks back to back on the loading thread, and its `CircularityLock` holds a
     * per-thread entry for the whole of that, so a class load triggered from inside a transform
     * is handed back untransformed rather than re-entering [instrument]. One thread therefore has
     * at most one class in flight. A keyed map would have to key on classloader identity, which
     * [System.identityHashCode] does not make unique, so two loaders sharing a hash could
     * cross-wire each other's probes; a thread cannot collide with itself.
     */
    private val pendingRegistration = ThreadLocal<PendingRegistration?>()

    /** Whether this thread is holding an uncommitted registration; for tests. */
    internal fun hasPendingRegistration(): Boolean = pendingRegistration.get() != null

    /**
     * Moves a class from [pendingRegistration] into [registry] once its bytes exist, and drops
     * the pending entry for a class whose transform never got that far.
     *
     * A transform can fail after [instrument] has already computed that class's probes. ByteBuddy
     * calls the transform callback, then rewrites and validates, and only then reports the
     * outcome, so a failure arrives after the point that produced the probes. Registering in the
     * callback would publish them before the outcome is known: a flush landing in that window
     * sends the class's probes to a collector, and the rollback afterwards cannot take them back.
     * Those probes then sit in the manifest at zero forever, which reads exactly like dead code.
     * "Not instrumented" is an honest state to report; "in the manifest, permanently zero" is not.
     *
     * So no probe reaches [ProbeRegistry] until `onTransformation`, which ByteBuddy calls only
     * after `make()` has produced the bytes. The class is defined after this returns and nothing
     * looks its array up before it runs, so the array is always registered first. This covers the
     * range ByteBuddy can see; a class that fails past `getBytes()`, such as one the verifier
     * rejects, stays out of the manifest until it is confirmed defined.
     * Endpoints are declared on their own path and do not go through this listener.
     *
     * The class's forwarder table entries are written here too, so a transform that failed writes
     * none. They do not wait for the class to be confirmed defined. That confirmation comes at a
     * later flush, and by then the handler the entry names has been registered. An entry for a
     * class that was never defined does no harm: the table is never sent, and no handler of that
     * class can exist to be looked up.
     *
     * The class's [WeavePlan] is kept here too, for a later call to weave it again from. A re-weave
     * stages nothing, so it commits nothing, and a failed one is logged without being recorded as
     * skipped: the class's probes were delivered at its first weave.
     */
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
            for ((className, location) in pending.externalClasses) externalClassRegistry.record(className, location)
            pending.handlerForwarders.forEach(handlerForwarders::record)
            wovenClasses.record(pending.classLoader, pending.className, pending.plan)
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
                    if (plan?.firstLog(WeavePlan.LOGGED_REFUSAL) != false) logRefusal(typeName)
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
                    log.log(Level.WARNING, "otherlode: instrumentation failed for $typeName, class will run uninstrumented", throwable)
                    registry.recordSkipped(typeName, throwable.message ?: throwable.toString())
                }
            }
        }

        /**
         * What follows from a woven class failing to weave again, as opposed to being refused, which
         * hands the JVM no woven bytes. A class with a probe field makes the JVM refuse them, as they
         * lack it, and keeps running with its probes. A class woven with a dynamic constant has
         * nothing the JVM could miss, so it accepts them and the class runs unwoven from then on.
         * That needs ByteBuddy to fail on a plan it wove once already.
         */
        private fun whenNotWoven(plan: WeavePlan?): String =
            if (plan != null && plan.majorVersion >= ProbeArrayForm.DYNAMIC_CONSTANT_VERSION) {
                "the JVM redefines the class with its bytes unwoven, so it stops counting"
            } else {
                "the JVM refuses the redefinition or retransformation, and the class keeps running with its probes"
            }

        /**
         * Runs for every class ByteBuddy considered, whatever the outcome. A pending entry still
         * here was never committed, so this drops it. On a successful transform `onTransformation`
         * already took it and this finds nothing.
         */
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
        TypeMatchPolicy
            .typeNameMatcher(config.includePackages, config.excludePackages)
            .and { typeDescription -> isSafeToInstrument(typeDescription) }

    /**
     * `AgentBuilder` commits to rebasing a type the moment it matches `.type(...)`. This happens
     * before [instrument] (the `.transform()` callback) ever runs, so a type excluded here never
     * reaches that callback at all.
     *
     * That early commitment is why this is the only point that can actually prevent the crash
     * described below, rather than just contain its aftermath. Returning the original builder
     * unchanged from [instrument] does not help: ByteBuddy's later `.make()` call still crashes
     * on the already-rebased type regardless.
     *
     * ByteBuddy refuses to redefine any type that carries a declared annotation whose own
     * `@Target` does not legally support `ElementType.TYPE`. It throws `IllegalStateException`
     * deep inside its own validation. Kotlin's compiler attaches `@kotlin.jvm.JvmName` directly
     * onto the class file for any `@file:JvmName`-annotated source file, even though that
     * annotation's own `@Target` only covers functions, properties, and files, not classes. This
     * trips the same check: legal bytecode, but not a shape ByteBuddy's redefinition path
     * accepts.
     *
     * A class this agent already wove is never turned away here: its probes were delivered at the
     * first weave, so recording it as skipped would contradict them. If the bytes that arrive for it
     * carry such an annotation, ByteBuddy's validation fails and the listener logs the failed
     * re-weave instead.
     */
    private fun isSafeToInstrument(typeDescription: TypeDescription): Boolean {
        if (alreadyLoaded.get() == true) return true
        val unsupported = TypeMatchPolicy.unsafeAnnotation(typeDescription) ?: return true
        registry.recordSkipped(
            typeDescription.name,
            "@${unsupported.annotationType.name} is not a legal annotation on a class per its own @Target",
        )
        return false
    }

    /**
     * Computes [typeDescription]'s probes from its class file and weaves them into [builder]. A
     * class that is already loaded and was woven before goes to [reweave] instead.
     */
    private fun instrument(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        if (alreadyLoaded.get() == true) return reweave(builder, typeDescription, classLoader)
        val source = readClassBytes(typeDescription, classLoader)
        val analysedBytes = source.analysed
        val (analysedMethods, receivedMethods, analysis, pairing) = analyse(typeDescription, classLoader, source)
        val methods =
            receivedMethods?.let { received -> analysedMethods.filter { (it.internalName to it.descriptor) in received } }
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
            // Warned ahead of the guard below, not after it. With no bytes the analysis is empty,
            // so that guard reads as "no methods matched" and returns for a class whose only
            // probe-worthy content is a <clinit> or a $default method. The agent cannot tell that
            // class from a genuinely method-less one without the bytes, and it is the worse case:
            // the static scanner reads the same class off the classpath, sees the <clinit> and
            // declares it, the manifest never mentions it, and a collector calls a class that
            // loaded "never loaded".
            //
            // Every mark a dead-code claim depends on is read from these bytes. The class still
            // gets entry probes where methods matched, so a collector sees methods it can judge,
            // while the marks that would block a claim are all missing: an inline function reads
            // as ordinary code, so does a generated method, the class calls nothing and names no
            // supertypes, and no optional parameter is counted. A Scala class fares worse still,
            // since its lambda bodies are recognised from the same bytes and match no method at
            // all. A stripped line table has its own warning below and leaves the rest of the
            // analysis intact. This case loses all of it and would otherwise say nothing.
            log.log(
                Level.WARNING,
                "otherlode: could not read ${typeDescription.name}'s bytecode; any method of it that is probed at all " +
                    "gets an entry probe with no line number, and the class gets no branch probes, no call edges, " +
                    "no supertypes, and no inline or generated mark",
            )
        }
        if (methods.isEmpty() && analysis.defaultSites.isEmpty() && !analysis.hasTypeInitializer) {
            // Nothing here to probe: a marker interface, a constants holder, a class whose every
            // method the matcher excludes. Recorded so the sweep can tell it apart from a class
            // that went unreported for a reason the agent cannot see. Local only; see
            // ProbeRegistry.recordNothingToProbe.
            //
            // Only when the bytes were readable. Without them the analysis is empty whatever the
            // class holds, and a Scala class whose lambda bodies are its only probe-worthy methods
            // reads as empty too, since the Scala marker comes from those same bytes. Recording
            // that as nothing to probe would tell the sweep the class is accounted for and hide
            // the one case the warning above exists to report.
            if (analysedBytes != null) registry.recordNothingToProbe(typeDescription.name)
            return builder
        }
        if (analysis.isKotlinClass && !analysis.hasLineNumbers) {
            log.log(
                Level.WARNING,
                "otherlode: ${typeDescription.name} is a Kotlin class with no line-number table; an inline function " +
                    "in it cannot be recognised and reads as ordinary code, and its inlined copies cannot be traced",
            )
        }
        // A method whose sites did not pair keeps its entry probe and marks, and its sites are left
        // out entirely rather than reported at zero. Every other site keeps the branch indexes the
        // class file gave it.
        val branchSites = analysis.sites.filter { pairing.isPaired(it.methodName, it.methodDescriptor) }
        val keptSites = analysis.keptSites.filter { pairing.isPaired(it.site.methodName, it.site.methodDescriptor) }
        val references = ReferencesKept(classLoader)

        // A resolved Scala default getter keeps its ordinary method-tier slot and advice; only its
        // manifest row changes, from a METHOD probe under the getter's own name to an
        // OPTIONAL_ARGUMENT probe naming the target it fills a default for.
        val scalaGetterSitesByKey = analysis.scalaGetterSites.associateBy { it.getterName to it.getterDescriptor }
        val methodProbes =
            methods.map {
                val getterSite = scalaGetterSitesByKey[it.internalName to it.descriptor]
                if (getterSite != null) {
                    ProbeMeta(
                        ProbeKind.OPTIONAL_ARGUMENT,
                        getterSite.targetName,
                        getterSite.targetDescriptor,
                        line = getterSite.line,
                        inline = false,
                        parameterIndex = getterSite.parameterIndex,
                        parameterName = getterSite.parameterName,
                        overridable = getterSite.overridable,
                        targetClassName = getterSite.targetClassName,
                        generatedBy = analysis.generatedBy(getterSite.targetName, getterSite.targetDescriptor),
                        unreadShape = analysis.unreadShape(getterSite.targetName, getterSite.targetDescriptor),
                    )
                } else {
                    val sourceSignature = analysis.sourceSignatureOf(it.internalName, it.descriptor)
                    val paired = pairing.isPaired(it.internalName, it.descriptor)
                    ProbeMeta(
                        ProbeKind.METHOD,
                        it.internalName,
                        it.descriptor,
                        line = analysis.firstLineOf(it.internalName, it.descriptor),
                        inline = analysis.isInline(it.internalName, it.descriptor),
                        // An unpaired method's sites are not reported, so no edge may name one as its guard.
                        calls =
                            analysis.callsOf(it.internalName, it.descriptor).map { edge ->
                                if (paired) edge else edge.copy(guard = null)
                            },
                        generatedBy = analysis.generatedBy(it.internalName, it.descriptor),
                        unreadShape = analysis.unreadShape(it.internalName, it.descriptor),
                        referencedClasses = references.keep(analysis.referencesOf(it.internalName, it.descriptor)),
                        lambdaBody = analysis.isLambdaBody(it.internalName, it.descriptor),
                        branchSites = if (paired) analysis.branchSitesOf(it.internalName, it.descriptor) else emptyList(),
                        static = it.isStatic,
                        parameterNames = sourceSignature.parameterNames,
                        genericSignature = sourceSignature.genericSignature,
                        extensionReceiver = sourceSignature.extensionReceiver,
                    )
                }
            }
        recordUnreadShapes(typeDescription.name, analysis.unreadRelease, analysis.unreadCause, methodProbes)
        // Only a kept site gets slots in the array: BranchProbeAsmVisitorWrapper allocates them
        // per kept site, in the same siteIndex order, and branchSlotCapacity below is sized to
        // match. A branch inside an inline method's body is just as invisible to a Kotlin caller
        // as the method probe itself, so it inherits the same flag, and a branch inside a
        // generated method carries that method's mark.
        val branchProbes =
            keptSites.flatMap { kept ->
                val site = kept.site
                kept.outcomes.map { outcome ->
                    ProbeMeta(
                        ProbeKind.BRANCH,
                        site.methodName,
                        site.methodDescriptor,
                        site.line,
                        branchIndex = outcome.branchIndex,
                        inline = analysis.isInline(site.methodName, site.methodDescriptor),
                        inlinedFromClassName = site.inlinedFromClassName,
                        branchKey = outcome.branchKey,
                        generatedBy = analysis.generatedBy(site.methodName, site.methodDescriptor),
                        unreadShape = analysis.unreadShape(site.methodName, site.methodDescriptor),
                        siteIndex = site.siteIndex,
                    )
                }
            }
        recordUnreadOutcomes(typeDescription.name, keptSites)
        recordBranchDrops(typeDescription.name, branchSites)
        // Slots are packed per default site, one per optional parameter, appended after the
        // method and branch slots: bit i's slot is siteBase + bitCount(optionalBits & ((1 << i) - 1)),
        // never one slot per value parameter, so a required parameter's bit (never set) never
        // reserves a slot nobody increments.
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
                                site.targetName,
                                site.targetDescriptor,
                                line = site.defaultLines[bit] ?: -1,
                                inline = analysis.isInline(site.targetName, site.targetDescriptor),
                                parameterIndex = bit,
                                parameterName = site.parameterNames[bit] ?: "",
                                overridable = site.overridable,
                                generatedBy = analysis.generatedBy(site.targetName, site.targetDescriptor),
                                unreadShape = analysis.unreadShape(site.targetName, site.targetDescriptor),
                            )
                        }
                omissionBase += slots.size
                slots
            }
        logUnprobedDefaults(typeDescription, defaultSites, analysis)
        // The type initializer's own probe, when the class declares one, is appended after every
        // other slot category (method, branch, omission). Where the class has a prelude, the prelude
        // increments it right after it fills the counts field; otherwise entry advice on the
        // <clinit> does. Placement past the last other slot is a convenience, not a constraint.
        val typeInitializerProbe =
            if (analysis.hasTypeInitializer) {
                ProbeMeta(
                    ProbeKind.METHOD,
                    "<clinit>",
                    "()V",
                    line = analysis.firstLineOf("<clinit>", "()V"),
                    calls = analysis.callsOf("<clinit>", "()V"),
                    referencedClasses = references.keep(analysis.referencesOf("<clinit>", "()V")),
                )
            } else {
                null
            }
        val probes = methodProbes + branchProbes + omissionProbes + listOfNotNull(typeInitializerProbe)
        val typeInitializerProbeIndex = if (typeInitializerProbe != null) probes.size - 1 else null

        val layoutHash =
            ProbeLayoutHash.of(
                methods.map { it.internalName + it.descriptor } +
                    branchSites
                        .filter { it.dropReason == null }
                        .map { "${it.methodName}${it.methodDescriptor}#branch${it.siteIndex}x${it.probedOutcomeCount}" } +
                    defaultSites.map { "${it.defaultName}${it.defaultDescriptor}#optional${it.optionalBits}" } +
                    (if (analysis.hasTypeInitializer) listOf("<clinit>()V#typeinit") else emptyList()),
            )
        val plan =
            WeavePlan.Builder(
                classFileHash = if (source.hasClassFile) analysedBytes?.let(WovenClasses::hashOf) else null,
                layoutHash = layoutHash,
                probeCount = probes.size,
                majorVersion = ProbeArrayForm.majorVersionOf(source.received ?: analysedBytes),
                typeInitializerProbeIndex = typeInitializerProbeIndex,
                branchWrapper = branchSites.isNotEmpty(),
                branchBase = methodProbes.size,
                branchSlotCapacity = branchProbes.size,
            )
        methods.forEachIndexed { slot, method -> plan.entrySlot(method.internalName, method.descriptor, slot) }
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
                methods.map { it.internalName to it.descriptor }.filter { (name, descriptor) ->
                    pairing.isPaired(name, descriptor)
                }
            // The class file's tracked instructions of each eligible method, so a later weave pairs
            // the bytes that arrive then against them without reading or analysing anything.
            val sequences = analysedBytes?.let { SitePairing.encodedSequences(it, eligible) }.orEmpty()
            for ((name, descriptor) in eligible) {
                plan.branchEligible(
                    name,
                    descriptor,
                    sequences[name to descriptor] ?: IntArray(0),
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
        stage(typeDescription, classLoader, probes, analysis, references, built)
        return weave(builder, typeDescription, built, built.view(), pairing, reweaving = false)
    }

    /** What [analyse] reads from a class's bytes for a first weave. */
    private data class ClassFileView(
        /** The methods the class file declares that the method tier probes. */
        val analysedMethods: MethodList<*>,
        /** The methods the received bytes declare, or null when they are the class file. */
        val receivedMethods: Set<Pair<String, String>>?,
        val analysis: BranchSiteAnalyzer.Analysis,
        val pairing: SitePairing,
    )

    /**
     * Runs the analysis over the class file in [source] and pairs its branch sites with the
     * received bytes.
     *
     * Which methods get probes comes from the class file, so an instance reports the same methods
     * whatever ran ahead of it. The analysis runs regardless of whether any method matched: a type
     * whose only concrete content is a Kotlin $default method (an interface declaring only an
     * abstract method plus its default) would otherwise never reach the omission tier at all.
     */
    private fun analyse(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        source: ClassBytesSource,
    ): ClassFileView {
        val analysedBytes = source.analysed
        // Whether a synthetic method is a probed lambda body depends on whether scalac compiled
        // this class at all (methodMatcher's isScalaClass), which the class file says.
        val isScalaClass = analysedBytes?.let(ScalaClassDetector::isScalaClass) ?: false
        val methodMatcher = methodMatcher(isScalaClass)
        val analysedMethods =
            if (source.receivedDiffers) {
                describeClassFile(typeDescription, analysedBytes!!, classLoader).declaredMethods.filter(methodMatcher)
            } else {
                typeDescription.declaredMethods.filter(methodMatcher)
            }
        val receivedMethods =
            if (source.receivedDiffers) typeDescription.declaredMethods.mapTo(HashSet()) { it.internalName to it.descriptor } else null
        val analysis = analyzeBytecode(analysedBytes, classLoader, analysedMethods)
        val pairing =
            if (source.receivedDiffers) {
                SitePairing.of(analysedBytes!!, source.received!!, analysedMethods.map { it.internalName to it.descriptor })
            } else {
                SitePairing.IDENTICAL
            }
        return ClassFileView(analysedMethods, receivedMethods, analysis, pairing)
    }

    /**
     * Weaves an already-loaded class this agent wove before, for another agent's retransformation
     * or a redefinition, from the [WeavePlan] its first weave recorded.
     *
     * Nothing is read or analysed again but the class file, for one check: when it is readable, the
     * first weave read one, and the two differ, the plan's slots describe other code and this
     * throws [ReweaveRefused]. A class file that has gone, or one that appears for a class first
     * woven from memory, says nothing about the code, so it does not refuse.
     *
     * The bytes that arrive are paired against the class file's tracked instructions the plan
     * stored, and the plan's ordinals and slots are used as they are, so nothing is renumbered. A
     * method whose branches paired at the first weave but do not pair now, because another agent
     * changed its body, keeps its entry probe and gets no branch probes, so its branch counts stay
     * where they were. Nothing is staged for the registry and nothing is tallied again.
     */
    private fun reweave(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        val plan = wovenClasses.find(classLoader, typeDescription.name) ?: throw ReweaveRefused(typeDescription.name)
        // take is destructive, so it must not be called a second time for the same class.
        val received = classBytesCapture?.take(typeDescription.internalName)
        if (plan.hasClassFileHash) {
            val classFile = locateClassBytes(typeDescription, classLoader)
            if (classFile != null && WovenClasses.hashOf(classFile) != plan.classFileHash) {
                if (plan.majorVersion < ProbeArrayForm.DYNAMIC_CONSTANT_VERSION) throw ReweaveRefused(typeDescription.name)
                return refuseWithoutField(builder, typeDescription.name, plan)
            }
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
        return weave(builder, typeDescription, plan, view, pairing, reweaving = true)
    }

    /** Logs that [className]'s class file changed under a woven class, so the redefinition is refused. */
    private fun logRefusal(className: String) =
        log.log(
            Level.WARNING,
            "otherlode: the class file of $className differs from the one it was woven from, as after a recompile or a " +
                "HotSwap; it cannot be woven again, so the JVM refuses the redefinition or retransformation, and the " +
                "class keeps running with its probes",
        )

    /**
     * Refuses a re-weave of a class woven with a dynamic constant, which has no probe field whose
     * absence would make the JVM reject unwoven bytes: it would accept them, and the class would run
     * code the manifest does not describe without counting. So the bytes handed back are the
     * received ones plus one field, which the JVM rejects as a schema change, the outcome a class
     * with a probe field gets (ADR 0053).
     */
    private fun refuseWithoutField(
        builder: DynamicType.Builder<*>,
        className: String,
        plan: WeavePlan,
    ): DynamicType.Builder<*> {
        if (plan.firstLog(WeavePlan.LOGGED_REFUSAL)) logRefusal(className)
        // Public static final, the one shape a field may take on an interface as well as a class.
        return builder.defineField(
            MethodEntryAdvice.REFUSAL_MARKER_FIELD,
            Int::class.javaPrimitiveType!!,
            Visibility.PUBLIC,
            Ownership.STATIC,
            FieldManifestation.FINAL,
            SyntheticState.SYNTHETIC,
        )
    }

    /**
     * Weaves [plan] into [builder]: the probes' route to the counts array, entry advice on every
     * method the plan gave a slot, branch probes on every method the plan gave a run that [pairing]
     * pairs, and omission advice on every `$default` method in the plan.
     *
     * The route is the [ProbeArrayForm] the plan's class-file version selects: a dynamic constant
     * and nothing added to the class from version 55, otherwise the field, its `<clinit>` prelude
     * and an accessor every probe calls.
     *
     * The plan's ordinals are the class file's. Pairing guarantees an eligible method's tracked
     * instructions match the received bytes' one for one, so they name the same instructions there,
     * and the swapped ordinals say which conditionals arrive inverted. Each method's slot count is
     * checked against its run. When [reweaving], the class-wide total is not, since a method may
     * have dropped out.
     */
    private fun weave(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        plan: WeavePlan,
        view: WeavePlan.View,
        pairing: SitePairing,
        reweaving: Boolean,
    ): DynamicType.Builder<*> {
        val form = ProbeArrayForm.of(plan.majorVersion, typeDescription, plan.layoutHash, plan.probeCount)
        var instrumented: DynamicType.Builder<*> = builder
        if (form.hasField) {
            instrumented =
                instrumented
                    .defineField(
                        MethodEntryAdvice.PROBE_ARRAY_FIELD,
                        LongArray::class.java,
                        Visibility.PUBLIC,
                        Ownership.STATIC,
                        FieldManifestation.FINAL,
                        SyntheticState.SYNTHETIC,
                    ).initializer(
                        ProbeArrayInitializer(typeDescription.name, plan.layoutHash, plan.probeCount, plan.typeInitializerProbeIndex),
                    )
        }
        if (form is ProbeArrayForm.Accessor) {
            instrumented =
                instrumented
                    .defineMethod(
                        MethodEntryAdvice.PROBE_ARRAY_ACCESSOR,
                        LongArray::class.java,
                        Visibility.PRIVATE,
                        Ownership.STATIC,
                        SyntheticState.SYNTHETIC,
                    ).intercept(Implementation.Simple(form.accessorBody()))
                    .defineMethod(
                        MethodEntryAdvice.PROBE_ARRAY_SLOW_PATH,
                        LongArray::class.java,
                        Visibility.PRIVATE,
                        Ownership.STATIC,
                        SyntheticState.SYNTHETIC,
                    ).intercept(Implementation.Simple(form.slowPathBody()))
        }

        // One Advice visitor for the whole class, with each method's slot resolved from its
        // signature at weave time. One visitor per method would stack N method visitors, each
        // checking every method against its own matcher, so transform cost would grow with the
        // square of the method count.
        //
        // With a dynamic constant there is no prelude to count the <clinit> probe, so the type
        // initializer is an ordinary entry-probe method whose body starts with that increment.
        val typeInitializerSlot = plan.typeInitializerProbeIndex
        val slotBySignature =
            if (!form.hasField && typeInitializerSlot != null) {
                view.entrySlots + (("<clinit>" to "()V") to typeInitializerSlot)
            } else {
                view.entrySlots
            }
        instrumented =
            instrumented.visit(
                Advice
                    .withCustomMapping()
                    .bind(ProbeArrayMapping(form))
                    .bind(ProbeIndexMapping(slotBySignature))
                    .to(MethodEntryAdvice::class.java)
                    .on { method -> (method.internalName to method.descriptor) in slotBySignature },
            )

        if (plan.branchWrapper) {
            val eligible = view.branchEligible.filterTo(HashSet()) { (name, descriptor) -> pairing.isPaired(name, descriptor) }
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
                    ),
                )
        }

        if (view.defaultSites.isNotEmpty()) {
            val bindings = view.defaultSites
            instrumented =
                instrumented.visit(
                    Advice
                        .withCustomMapping()
                        .bind(ProbeArrayMapping(form))
                        .bind(OmissionBaseMapping(bindings))
                        .bind(OptionalBitsMapping(bindings))
                        .bind(MaskArgumentMapping(bindings))
                        .to(OptionalArgumentAdvice::class.java)
                        .on { method -> (method.internalName to method.descriptor) in bindings },
                )
        }

        return instrumented
    }

    /** Logs what the omission tier could not count in [typeDescription]'s default-argument methods and getters. */
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

    /**
     * Holds a first weave's probes and [plan] for [TransformResultListener] to commit, and warns
     * once if the class was missing from the static baseline.
     */
    private fun stage(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        probes: List<ProbeMeta>,
        analysis: BranchSiteAnalyzer.Analysis,
        references: ReferencesKept,
        plan: WeavePlan,
    ) {
        pendingRegistration.set(
            PendingRegistration(
                typeDescription.name,
                plan.layoutHash,
                probes,
                classLoader,
                analysis.superClassName,
                analysis.interfaceNames,
                references.keep(analysis.classReferences),
                references.kept(),
                analysis.sourceFile,
                analysis.bodyKind,
                analysis.sourceName,
                analysis.handlerForwarders,
                analysis.kotlinKind,
                plan,
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

    /**
     * Filters one class's references down to the ones worth sending, asking
     * [referencedClassLocator] once per distinct name through [classLoader], the loader defining the
     * class: a JDK class or a class read from a classpath directory is dropped from every list, and
     * every other name is kept with where it was found.
     */
    private inner class ReferencesKept(
        private val classLoader: ClassLoader?,
    ) {
        private val found = LinkedHashMap<String, ReferencedClassLocator.Found?>()

        fun keep(names: List<String>): List<String> =
            names.filter { name ->
                if (name !in found) found[name] = referencedClassLocator.locate(name, classLoader)
                found[name] != null
            }

        /** Every name kept so far, with its location, or null for a class no loader could find. */
        fun kept(): Map<String, String?> = found.entries.mapNotNull { (name, where) -> where?.let { name to it.location } }.toMap()
    }

    /**
     * Tallies [unreadShapeCounts] with the METHOD probes of [typeName] that are unread shapes. When
     * the class is keyed on a Scala 3 [release] the agent has not read, logs one WARNING for the
     * class naming the release and the number of methods, the first time the class is recorded.
     * Classes without a release (Scala 2, or no `.tasty`) are counted for the first-flush summary
     * and not warned about one by one.
     */
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

    /** Tallies [unreadShapeCounts] with the outcomes of [typeName]'s [keptSites] that are unread shapes of their own, by family. */
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

    /**
     * Tallies [branchDropCounts] with [typeName]'s dropped sites, grouped by reason, and logs one
     * DEBUG line naming the total and each reason's own count when anything was dropped. A no-op
     * when nothing was.
     */
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
        log.log(
            Level.DEBUG,
            "otherlode: $typeName left $total branch sites without a probe: " +
                "$inlinedOutOfScope inlined from out-of-scope code, $coroutineMachinery coroutine machinery, " +
                "$switchLowering switch lowering",
        )
    }

    /**
     * A class's bytes as one transform sees them.
     *
     * [analysed] is what the analysis reads: the class file, or the received bytes when the class's
     * loader serves no class file for it (bytes defined from memory). Null when neither could be
     * had. [received] is the received bytes [ClassBytesCapture] took, or null when it took none.
     * [receivedDiffers] is true when both the class file and the received bytes were read and are
     * not byte-for-byte equal, which is what an earlier transformer leaves; only then do the
     * methods and branch sites need pairing. [hasClassFile] is whether the loader served a class
     * file at all.
     */
    private class ClassBytesSource(
        val analysed: ByteArray?,
        val received: ByteArray?,
        val receivedDiffers: Boolean,
        val hasClassFile: Boolean,
    )

    /**
     * Takes the received bytes from [classBytesCapture] and reads the class file through
     * [classLoader], for a first weave. With nothing captured (the capture switched off, or a class
     * defined outside the ordinary transformer chain), the class file stands in for the received
     * bytes, since without an earlier transformer they are the same. With no class file, the
     * received bytes are analysed, logged at DEBUG.
     *
     * A re-weave does not stand anything in: with nothing captured it has no bytes to pair against
     * the plan's stored instructions, so every method's branch counts stay where they were. Only a
     * test switches the capture off.
     */
    private fun readClassBytes(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): ClassBytesSource {
        // take is destructive, so it must not be called a second time for the same class.
        val received = classBytesCapture?.take(typeDescription.internalName)
        val classFile = locateClassBytes(typeDescription, classLoader)
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

    /**
     * Describes [classFile] as ByteBuddy would describe the class, so the method matcher judges the
     * methods the compiler wrote rather than the ones an earlier transformer left. Supertypes resolve
     * lazily through [classLoader]'s class files, and nothing is loaded.
     */
    private fun describeClassFile(
        typeDescription: TypeDescription,
        classFile: ByteArray,
        classLoader: ClassLoader?,
    ): TypeDescription {
        val locator =
            ClassFileLocator.Compound(
                ClassFileLocator.Simple.of(typeDescription.name, classFile),
                classFileLocatorFor(classLoader),
            )
        return TypePool.Default
            .WithLazyResolution(TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST)
            .describe(typeDescription.name)
            .resolve()
    }

    /**
     * Runs [BranchSiteAnalyzer] over [classBytes]: branch sites, first lines, call edges,
     * references and the marks the manifest carries. [classBytes] are the class file, so an
     * instance reports the same shape whatever transformer ran ahead of this agent, or the received
     * bytes when there is no class file; see [readClassBytes].
     *
     * If [classBytes] is null, this class gets no branch probes and no method line numbers.
     * Method-entry tracking is unaffected.
     */
    private fun analyzeBytecode(
        classBytes: ByteArray?,
        classLoader: ClassLoader?,
        methods: MethodList<*>,
    ): BranchSiteAnalyzer.Analysis {
        val eligible = methods.map { it.internalName to it.descriptor }.toSet()
        val bytes = classBytes ?: return BranchSiteAnalyzer.Analysis.EMPTY
        val lookup = crossClassLookup(classLoader)
        return BranchSiteAnalyzer.analyze(
            bytes,
            lookup,
            config.includePackages,
            config.excludePackages,
            tableCacheFor(classLoader),
            handlerForwarders.handlerInterfaces,
            resourceLookup(classLoader),
        ) { name, descriptor -> (name to descriptor) in eligible }
    }

    /**
     * Reads another class's bytes as a resource on [classLoader], for every cross-class read
     * [BranchSiteAnalyzer] makes: a Scala constructor default getter's target, a pass-through's
     * callees, a body class's methods, an enum switch's map, a supertype. This only reads
     * bytecode; it never loads the class, the same way [locateClassBytes] resolves the
     * instrumented class's own bytes from the same kind of locator. Any failure, including a class
     * the locator cannot find, is read as a class with no bytes rather than as an
     * instrumentation failure.
     */
    private fun crossClassLookup(classLoader: ClassLoader?): (String) -> ByteArray? {
        val locator = classFileLocatorFor(classLoader)
        return { internalName ->
            try {
                val resolution = locator.locate(internalName.replace('/', '.'))
                if (resolution.isResolved) resolution.resolve() else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Reads the leading bytes of a resource that is not a class, such as a Scala 3 class's `.tasty`
     * file, as a resource on [classLoader]. Any failure, including a missing resource, reads as no
     * resource. The bootstrap loader serves none.
     */
    private fun resourceLookup(classLoader: ClassLoader?): (String) -> ByteArray? =
        { path ->
            try {
                classLoader?.getResourceAsStream(path)?.use { it.readNBytes(ScalaReleases.HEADER_BYTES) }
            } catch (_: Exception) {
                null
            }
        }

    /** Resolves `@ProbeArray` to the class's [ProbeArrayForm] load, inlined into the advice. */
    private class ProbeArrayMapping(
        private val form: ProbeArrayForm,
    ) : Advice.OffsetMapping.Factory<ProbeArray> {
        override fun getAnnotationType(): Class<ProbeArray> = ProbeArray::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<ProbeArray>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, _, _, _, _ -> Advice.OffsetMapping.Target.ForStackManipulation(form.asStackManipulation()) }
    }

    /**
     * Resolves `@ProbeIndex` to the instrumented method's own slot, as a constant folded into the
     * inlined advice, so one [Advice] visitor serves every probed method in the class.
     */
    private class ProbeIndexMapping(
        private val slotBySignature: Map<Pair<String, String>, Int>,
    ) : Advice.OffsetMapping.Factory<ProbeIndex> {
        override fun getAnnotationType(): Class<ProbeIndex> = ProbeIndex::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<ProbeIndex>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                val slot =
                    slotBySignature[instrumentedMethod.internalName to instrumentedMethod.descriptor]
                        ?: throw IllegalStateException(
                            "otherlode: no probe slot for ${instrumentedMethod.internalName}${instrumentedMethod.descriptor}",
                        )
                Advice.OffsetMapping.Target.ForStackManipulation(IntegerConstant.forValue(slot))
            }
    }

    /** Resolves `@OmissionBase` to a woven `$default` method's first omission probe's slot. */
    private class OmissionBaseMapping(
        private val bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ) : Advice.OffsetMapping.Factory<OmissionBase> {
        override fun getAnnotationType(): Class<OmissionBase> = OmissionBase::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<OmissionBase>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                Advice.OffsetMapping.Target.ForStackManipulation(IntegerConstant.forValue(bindingFor(bindings, instrumentedMethod).base))
            }
    }

    /** Resolves `@OptionalBits` to a woven `$default` method's optional-parameter bitmask. */
    private class OptionalBitsMapping(
        private val bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ) : Advice.OffsetMapping.Factory<OptionalBits> {
        override fun getAnnotationType(): Class<OptionalBits> = OptionalBits::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<OptionalBits>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                Advice.OffsetMapping.Target.ForStackManipulation(
                    IntegerConstant.forValue(bindingFor(bindings, instrumentedMethod).optionalBits),
                )
            }
    }

    /**
     * Resolves `@MaskArgument` to a woven `$default` method's first mask `int`, read as a local
     * variable at its own offset: the mask parameter's index differs per method, so it cannot be
     * bound through a fixed `@Advice.Argument` index.
     */
    private class MaskArgumentMapping(
        private val bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ) : Advice.OffsetMapping.Factory<MaskArgument> {
        override fun getAnnotationType(): Class<MaskArgument> = MaskArgument::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<MaskArgument>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                val maskParameterIndex = bindingFor(bindings, instrumentedMethod).maskParameterIndex
                val maskParameter = instrumentedMethod.parameters[maskParameterIndex]
                Advice.OffsetMapping.Target.ForVariable
                    .ReadOnly(maskParameter.type, maskParameter.offset)
            }
    }

    private fun locateClassBytes(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): ByteArray? {
        val locator = classFileLocatorFor(classLoader)
        // Any failure reads as no class file: the transform then analyses the received bytes
        // rather than failing the class over a resource it could do without.
        return try {
            val resolution = locator.locate(typeDescription.name)
            if (resolution.isResolved) resolution.resolve() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun classFileLocatorFor(classLoader: ClassLoader?): ClassFileLocator =
        if (classLoader != null) {
            ClassFileLocator.ForClassLoader.of(classLoader)
        } else {
            ClassFileLocator.ForClassLoader.ofBootLoader()
        }

    private fun methodMatcher(isScalaClass: Boolean): ElementMatcher.Junction<MethodDescription> =
        TypeMatchPolicy.methodMatcher(isScalaClass)

    /**
     * The `<clinit>` prelude that fills the counts field. It compiles to:
     *
     * ```
     * ldc        "<class name>"
     * ldc2_w     <layout hash>
     * ldc        <probe count>
     * ldc        <this class>
     * invokevirtual java/lang/Class.getClassLoader()
     * invokestatic  OtherlodeProbeArrays.resolve(String, long, int, ClassLoader) long[]
     * putstatic  <this class>.$otherlodeProbeCounts
     * ```
     *
     * When [typeInitializerProbeIndex] is not null, one more sequence follows, incrementing that
     * slot directly:
     *
     * ```
     * getstatic  <this class>.$otherlodeProbeCounts
     * <index as int const>
     * dup2
     * laload
     * lconst_1
     * ladd
     * lastore
     * ```
     *
     * Every argument is a constant known at transform time. ByteBuddy runs this ahead of the
     * class's own original static initializer, so a static method probed in this class can be
     * called from that initializer and find the field already set, and the type initializer's own
     * probe is counted whether or not the original body that follows this prelude later throws.
     *
     * The field is still null until this runs, which a supertype's initializer can precede: the
     * class's code runs inside it, before the class's own `<clinit>`. That is why probes in a class
     * that has the field read it through [ProbeArrayForm.Accessor] and not directly.
     */
    private class ProbeArrayInitializer(
        private val className: String,
        private val layoutHash: Long,
        private val probeCount: Int,
        private val typeInitializerProbeIndex: Int? = null,
    ) : ByteCodeAppender {
        override fun apply(
            methodVisitor: MethodVisitor,
            implementationContext: Implementation.Context,
            instrumentedMethod: MethodDescription,
        ): ByteCodeAppender.Size {
            val instrumentedType = implementationContext.instrumentedType
            val field = instrumentedType.declaredFields.filter(named<FieldDescription>(MethodEntryAdvice.PROBE_ARRAY_FIELD)).only
            val fillArray =
                listOf(
                    TextConstant(className),
                    LongConstant.forValue(layoutHash),
                    IntegerConstant.forValue(probeCount),
                    ClassConstant.of(instrumentedType),
                    MethodInvocation.invoke(GET_CLASS_LOADER),
                    MethodInvocation.invoke(RESOLVE),
                    FieldAccess.forField(field).write(),
                )
            val incrementTypeInitializerSlot =
                if (typeInitializerProbeIndex == null) {
                    emptyList()
                } else {
                    listOf(
                        FieldAccess.forField(field).read(),
                        IntegerConstant.forValue(typeInitializerProbeIndex),
                        Duplication.DOUBLE,
                        ArrayAccess.LONG.load(),
                        LongConstant.forValue(1L),
                        Addition.LONG,
                        ArrayAccess.LONG.store(),
                    )
                }
            val size = StackManipulation.Compound(fillArray + incrementTypeInitializerSlot).apply(methodVisitor, implementationContext)
            return ByteCodeAppender.Size(size.maximalSize, instrumentedMethod.stackSize)
        }

        private companion object {
            val GET_CLASS_LOADER: MethodDescription.InDefinedShape =
                TypeDescription.ForLoadedType
                    .of(Class::class.java)
                    .declaredMethods
                    .filter(named<MethodDescription>("getClassLoader").and(takesArguments(0)))
                    .only
            val RESOLVE: MethodDescription.InDefinedShape =
                TypeDescription.ForLoadedType
                    .of(OtherlodeProbeArrays::class.java)
                    .declaredMethods
                    .filter(named<MethodDescription>("resolve"))
                    .only
        }
    }
}

/**
 * Tables held per classloader. Sized for a startup burst, where most of a loader's classes
 * transform in sequence and reference one another; an application larger than this parses its
 * least recently referenced classes again rather than pinning every table.
 */
private const val TRANSFORM_TABLE_CACHE_ENTRIES = 2048
