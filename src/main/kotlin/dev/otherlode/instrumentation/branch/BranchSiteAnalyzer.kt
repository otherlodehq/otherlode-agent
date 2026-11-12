package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BodyKind
import dev.otherlode.export.CallEdge
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.ConditionPart
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.CallbackAnnotations
import dev.otherlode.instrumentation.JpaEnhancement
import dev.otherlode.instrumentation.ScalaClassDetector
import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.instrumentation.interned
import dev.otherlode.instrumentation.internedAll
import dev.otherlode.instrumentation.internedOrNull
import dev.otherlode.instrumentation.rightSized
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.RecordComponentVisitor
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.jar.asm.TypePath
import net.bytebuddy.jar.asm.signature.SignatureReader
import net.bytebuddy.jar.asm.signature.SignatureVisitor
import dev.otherlode.export.BranchSite as BranchSitePayload

/**
 * Finds every [ConditionalJump], every `TABLESWITCH`/`LOOKUPSWITCH`, and every Kotlin `$default`
 * method in a class's original bytecode, plus each method's first source line.
 *
 * Only methods [methodFilter] accepts contribute branch sites, first lines, and inline marks, apart from
 * the first line of `<clinit>`. It finds and resolves a `$default` method regardless of [methodFilter].
 *
 * This is read-only.
 *
 * A switch's outcome count is one per case entry whose target is not the default label, plus the default.
 * The filler entries of a sparse `TABLESWITCH` jump to the default label, so they count as the default outcome.
 */
object BranchSiteAnalyzer {
    /** Everything one pass over a class's bytecode yields. */
    class Analysis(
        val sites: List<BranchSite>,
        private val firstLineByMethod: Map<Pair<String, String>, Int>,
        private val inlineMethods: Set<Pair<String, String>> = emptySet(),
        val defaultSites: List<DefaultSite> = emptyList(),
        val unresolvedDefaultSites: List<Pair<String, String>> = emptyList(),
        val scalaGetterSites: List<ScalaGetterSite> = emptyList(),
        val unresolvedScalaGetterSites: List<Pair<String, String>> = emptyList(),
        /** Whether the class's own original bytecode declares a `<clinit>`. */
        val hasTypeInitializer: Boolean = false,
        private val callEdgesByMethod: Map<Pair<String, String>, List<CallEdge>> = emptyMap(),
        /** Dotted, as the class file's own super_class entry names it. Null for `java.lang.Object` and on [EMPTY]. */
        val superClassName: String? = null,
        /** Dotted, as the class file's own interfaces entries name them. */
        val interfaceNames: List<String> = emptyList(),
        private val droppedOrdinalsByMethod: Map<Pair<String, String>, Set<Int>> = emptyMap(),
        private val generatedByMethod: Map<Pair<String, String>, GeneratedBy> = emptyMap(),
        /** Whether any method this analysis visited carried at least one `LineNumberTable` entry. */
        val hasLineNumbers: Boolean = false,
        /**
         * Whether the class carries a class-level annotation shaped like `kotlin.Metadata`: one package
         * segment, then `Metadata`.
         */
        val isKotlinClass: Boolean = false,
        private val referencesByMethod: Map<Pair<String, String>, List<String>> = emptyMap(),
        /**
         * The out-of-scope classes the class references outside any probed method, dotted: its header,
         * fields and record components, methods with no body, re-kinded Scala default getters, and
         * pass-throughs nothing in the class reaches.
         */
        val classReferences: List<String> = emptyList(),
        private val lambdaBodies: Set<Pair<String, String>> = emptySet(),
        /**
         * The class file's `SourceFile` attribute exactly as it appears, or null when it has none
         * or the bytes were never read.
         */
        val sourceFile: String? = null,
        /** What kind of body class this is, from [BodyKindRule]. [BodyKind.NONE] on [EMPTY]. */
        val bodyKind: BodyKind = BodyKind.NONE,
        /** The source name of a [BodyKind.LOCAL_CLASS], and null for every other kind. */
        val sourceName: String? = null,
        /**
         * The forwarder table's entries this class yields: each pass-through that a framework can
         * report as a handler for one of [analyze]'s handler interfaces, with the one probed method
         * it forwards to. Empty when no handler interface was given.
         */
        val handlerForwarders: List<HandlerForwarder> = emptyList(),
        /** The class's own name, dotted, as its header names it. Empty on [EMPTY]. */
        val className: String = "",
        private val siteGuards: Map<Int, SiteGuards> = emptyMap(),
        private val throwingDefaultOrdinalsByMethod: Map<Pair<String, String>, Set<Int>> = emptyMap(),
        /**
         * What kind of class kotlinc says this is, from the `k` element of its `kotlin.Metadata` by
         * [KotlinKind.ofMetadataKind]. [KotlinKind.NONE] when it has none, and on [EMPTY].
         */
        val kotlinKind: KotlinKind = KotlinKind.NONE,
        private val sourceSignatures: Map<Pair<String, String>, SourceSignature> = emptyMap(),
        private val unprobedOutcomesByMethod: Map<Pair<String, String>, Map<Int, Int>> = emptyMap(),
        private val unreadShapeByMethod: Map<Pair<String, String>, UnreadShape> = emptyMap(),
        /**
         * The Scala 3 release that compiled this class when its [unreadShape] methods are unread
         * because the agent has not read that release. Null for every other class, version-blind
         * unread shapes (Scala 2, or no `.tasty`) included.
         */
        val unreadRelease: String? = null,
        /** Why this class's [unreadShape] methods are unread; null when it has none. */
        val unreadCause: UnreadCause? = null,
        /** What [SizeGuard] bounded and dropped. */
        val sizeGuard: SizeGuardResult = SizeGuardResult.NONE,
        private val trackedSequencesByMethod: Map<Pair<String, String>, IntArray> = emptyMap(),
        private val overriddenOutsideTypes: Map<Pair<String, String>, String> = emptyMap(),
        private val callbackAnnotations: Map<Pair<String, String>, String> = emptyMap(),
        private val inheritedCallbackAnnotations: Map<Pair<String, String>, String> = emptyMap(),
        private val reportedNames: Map<Pair<String, String>, String> = emptyMap(),
    ) {
        /**
         * The name [name]/[descriptor] reports under in a manifest or a baseline: the adopter's name
         * for an OpenJPA accessor moved to `pc<name>`, and [name] for every other method.
         */
        fun reportedNameOf(
            name: String,
            descriptor: String,
        ): String = reportedNames[name to descriptor] ?: name

        /**
         * The callback annotation [name]/[descriptor] carries, dotted and as written on the method
         * or on one of its parameters, or null. A method that is a constructor or `<clinit>`, one
         * that gets no probe other than an OpenJPA wrapper, and every method when [analyze] was not
         * asked for outside callers, has none. A moved OpenJPA accessor takes its wrapper's.
         */
        fun callbackAnnotationOf(
            name: String,
            descriptor: String,
        ): String? = callbackAnnotations[name to descriptor]

        /**
         * The callback annotation [name]/[descriptor] inherits from a method it overrides, dotted and
         * as written on that supertype method, or null. It is null for a method with a callback
         * annotation of its own, which an inherited one never replaces, for a method that gets no
         * probe other than an OpenJPA wrapper, and for every method when [analyze] was not asked for
         * outside callers. A moved OpenJPA accessor takes its wrapper's.
         */
        fun inheritedCallbackAnnotationOf(
            name: String,
            descriptor: String,
        ): String? = inheritedCallbackAnnotations[name to descriptor]

        /**
         * The out-of-scope type whose method [name]/[descriptor] overrides or implements, dotted, or
         * null. It is the type [OverrideWalk] finds for the method's own name and descriptor, or,
         * when there is none, the type its bridge's match gives. Always null for a method that gets
         * no probe other than an OpenJPA wrapper, for a bridge, and for every method when [analyze]
         * was not asked for outside callers. A moved OpenJPA accessor takes its wrapper's.
         */
        fun overriddenOutsideTypeOf(
            name: String,
            descriptor: String,
        ): String? = overriddenOutsideTypes[name to descriptor]

        /**
         * The class file's tracked instructions of [name]/[descriptor] as [SitePairing.encodedSequences]
         * encodes them, or an empty array for a method with none. [analyze] records only a method
         * its `methodFilter` accepted.
         */
        fun trackedSequenceOf(
            name: String,
            descriptor: String,
        ): IntArray = trackedSequencesByMethod[name to descriptor] ?: NO_SEQUENCE

        /**
         * Each kept site of [sites], in site index order, with its outcomes numbered, given roles
         * and keyed by [KeptBranchSite.of], and with its guard and guarded lines.
         */
        val keptSites: List<KeptBranchSite> by lazy {
            KeptBranchSite.of(sites, className, siteGuards) { name, descriptor -> reportedNames[name to descriptor] ?: name }
        }

        private val keptSitesByMethod by lazy { keptSites.groupBy { it.site.methodName to it.site.methodDescriptor } }

        /**
         * The method's kept sites as the manifest and the static baseline send them, in site index
         * order. Empty for any method with no kept site, `<clinit>` included.
         */
        fun branchSitesOf(
            name: String,
            descriptor: String,
        ): List<BranchSitePayload> = keptSitesByMethod[name to descriptor]?.map { it.toPayload() } ?: emptyList()

        /**
         * Whether the method is a lambda body: [methodFilter][analyze] accepted it, an `invokedynamic`
         * in this class names it as the `LambdaMetafactory` implementation, and its name passes
         * [TypeMatchPolicy.isLambdaBodyName]. The `invokedynamic` may name it through a pass-through.
         * In a Scala class, a method with Scala 3's lifted-lambda name qualifies without one. Always
         * false on [EMPTY].
         */
        fun isLambdaBody(
            name: String,
            descriptor: String,
        ): Boolean = (name to descriptor) in lambdaBodies

        /**
         * The out-of-scope classes this method references, dotted, first seen first: its own
         * bytecode, signature and annotations, plus those of every pass-through it reaches, as
         * [callsOf] follows them. Empty for any method that does not get a METHOD probe. The list
         * still includes JDK classes.
         */
        fun referencesOf(
            name: String,
            descriptor: String,
        ): List<String> = referencesByMethod[name to descriptor] ?: emptyList()

        /**
         * First line-number-table entry of the method, or -1 when [analyze]'s method filter left
         * it out, the class carries no debug info, or the bytes were never read. `<clinit>` gets
         * its first line although the filter always leaves it out.
         */
        fun firstLineOf(
            name: String,
            descriptor: String,
        ): Int = firstLineByMethod[name to descriptor] ?: -1

        /**
         * Whether the method is a Kotlin inline function, detected from its own `$i$f$<name>`
         * marker local. Always false on [EMPTY], and false when the class carries no debug info.
         */
        fun isInline(
            name: String,
            descriptor: String,
        ): Boolean = (name to descriptor) in inlineMethods

        /**
         * The in-scope call edges read from this method's own bytecode, empty for any method that
         * does not get a METHOD probe. Each edge carries its guard.
         */
        fun callsOf(
            name: String,
            descriptor: String,
        ): List<CallEdge> = callEdgesByMethod[name to descriptor] ?: emptyList()

        /**
         * The ordinals of [name]/[descriptor]'s dropped sites: each site's encounter index among the
         * method's tracked conditionals and switches, dropped or kept, counted from zero.
         */
        fun droppedOrdinalsOf(
            name: String,
            descriptor: String,
        ): Set<Int> = droppedOrdinalsByMethod[name to descriptor] ?: emptySet()

        /**
         * The ordinals of [name]/[descriptor]'s kept switches whose default only throws, numbered as in
         * [droppedOrdinalsOf].
         */
        fun throwingDefaultOrdinalsOf(
            name: String,
            descriptor: String,
        ): Set<Int> = throwingDefaultOrdinalsByMethod[name to descriptor] ?: emptySet()

        /**
         * By ordinal, numbered as in [droppedOrdinalsOf], the outcome offset of each kept conditional of
         * [name]/[descriptor] that gets no probe; see [BranchSite.unprobedOutcome].
         */
        fun unprobedOutcomesOf(
            name: String,
            descriptor: String,
        ): Map<Int, Int> = unprobedOutcomesByMethod[name to descriptor] ?: emptyMap()

        /**
         * The method's parameter names, generic signature and extension-receiver flag, read from
         * its own class file. [SourceSignature.NONE] for `<clinit>`, on [EMPTY], and for a method
         * the class does not declare.
         */
        fun sourceSignatureOf(
            name: String,
            descriptor: String,
        ): SourceSignature = sourceSignatures[name to descriptor] ?: SourceSignature.NONE

        /**
         * What generated this method, judged from its bytecode shape alone.
         * [GeneratedBy.NONE] on [EMPTY], and for any method none of the shape rules matched.
         */
        fun generatedBy(
            name: String,
            descriptor: String,
        ): GeneratedBy = generatedByMethod[name to descriptor] ?: GeneratedBy.NONE

        /**
         * The family of compiler output this method has the outline of while its body matches no
         * shape the agent has read, or [UnreadShape.NONE]. Never set for a method with a
         * [generatedBy] mark.
         */
        fun unreadShape(
            name: String,
            descriptor: String,
        ): UnreadShape = unreadShapeByMethod[name to descriptor] ?: UnreadShape.NONE

        companion object {
            val EMPTY = Analysis(emptyList(), emptyMap())

            private val NO_SEQUENCE = IntArray(0)
        }
    }

    private data class DefaultCandidate(
        val defaultName: String,
        val defaultDescriptor: String,
        val optionalBits: Int,
        val higherMaskTested: Boolean,
        val fillLines: Map<Int, Int> = emptyMap(),
    )

    /**
     * Parsed method tables of classes referenced across a class boundary, shared between analyses.
     * Bounded and least-recently-used. Safe to share between threads. It remembers a class whose
     * bytes it cannot read as unreadable.
     *
     * It also holds the [TypeHeader] of each type the override walk reads and, for each annotation
     * type, whether it reaches a listed or named annotation ([CallbackAnnotationFinder.Reach]).
     * The same limit bounds each separately.
     */
    class CrossClassTableCache(
        private val maxEntries: Int,
    ) {
        private val tables =
            object : LinkedHashMap<String, Any>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > maxEntries
            }

        /** The number of tables held, unreadable entries included. */
        val size: Int
            get() = synchronized(tables) { tables.size }

        private val headers =
            object : LinkedHashMap<String, Any>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > maxEntries
            }

        /** The number of type headers held, unreadable ones included. */
        val headerCount: Int
            get() = synchronized(headers) { headers.size }

        private val annotationAnswers =
            object : LinkedHashMap<String, Int>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > maxEntries
            }

        internal fun recallAnnotationAnswer(key: String): Int? = synchronized(annotationAnswers) { annotationAnswers[key] }

        internal fun rememberAnnotationAnswer(
            key: String,
            answer: Int,
        ) {
            synchronized(annotationAnswers) { annotationAnswers[key] = answer }
        }

        /** Forgets every table, header, annotation answer and release held; the next lookup reads again. */
        fun clear() {
            synchronized(tables) { tables.clear() }
            synchronized(headers) { headers.clear() }
            synchronized(annotationAnswers) { annotationAnswers.clear() }
            synchronized(releases) { releases.clear() }
        }

        internal fun getOrRead(
            internalName: String,
            read: () -> MethodTable?,
        ): MethodTable? {
            synchronized(tables) { tables[internalName] }?.let { return it as? MethodTable }
            val table = read()
            synchronized(tables) { tables[internalName] = table ?: UNREADABLE }
            return table
        }

        internal fun getOrReadHeader(
            internalName: String,
            read: () -> TypeHeader?,
        ): TypeHeader? {
            synchronized(headers) { headers[internalName] }?.let { return it as? TypeHeader }
            val header = read()
            synchronized(headers) { headers[internalName] = header ?: UNREADABLE }
            return header
        }

        private val releases =
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
            }

        internal fun getOrReadRelease(
            internalName: String,
            read: () -> String?,
        ): String? {
            synchronized(releases) { releases[internalName] }?.let { return it.takeIf { release -> release != NO_RELEASE } }
            val release = read()
            synchronized(releases) { releases[internalName] = release ?: NO_RELEASE }
            return release
        }

        private companion object {
            val UNREADABLE = Any()
            const val NO_RELEASE = ""
        }
    }

    internal data class RawCandidate(
        val owner: String,
        val name: String,
        val descriptor: String,
        val virtualRaw: Boolean,
        val kind: CallEdgeKind = CallEdgeKind.CALL,
        val capturedCount: Int = 0,
        val functionalInterface: String? = null,
        val ordinal: Int = -1,
        val newOrdinal: Int = -1,
    )

    private data class CrossClassDefaultTarget(
        val name: String,
        val descriptor: String,
        val virtual: Boolean,
    )

    private open class CallCandidateMethodVisitor(
        private val ownerInternalName: String,
        private val candidatesForMethod: MutableList<RawCandidate>,
        referencesForMethod: MutableSet<String>,
        private val instructionOrdinal: () -> Int = { -1 },
    ) : MethodVisitor(Opcodes.ASM9) {
        private val references = ReferenceCollector(referencesForMethod, remembersDescriptors = true)

        private val pendingNews = ArrayList<Pair<String, Int>>()

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            references.internalName(owner)
            references.descriptor(descriptor)
            val virtualRaw = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
            val newOrdinal = if (opcode == Opcodes.INVOKESPECIAL && name == "<init>") takePendingNew(owner) else -1
            candidatesForMethod +=
                RawCandidate(owner, name, descriptor, virtualRaw, ordinal = instructionOrdinal(), newOrdinal = newOrdinal)
        }

        private fun takePendingNew(owner: String): Int {
            val index = pendingNews.indexOfLast { it.first == owner }
            return if (index < 0) -1 else pendingNews.removeAt(index).second
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            references.descriptor(descriptor)
            references.handle(bootstrapMethodHandle)
            bootstrapMethodArguments.forEach(references::constant)
            lambdaCandidateOrNull(descriptor, bootstrapMethodHandle, bootstrapMethodArguments)?.let {
                candidatesForMethod += it.copy(ordinal = instructionOrdinal())
            }
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            references.internalName(type)
            if (opcode == Opcodes.NEW) {
                val ordinal = instructionOrdinal()
                if (ordinal >= 0) pendingNews += type to ordinal
            }
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            references.descriptor(descriptor)
        }

        override fun visitLdcInsn(value: Any?) {
            references.constant(value)
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            references.internalName(type)
        }

        override fun visitAnnotation(
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitAnnotationDefault(): AnnotationVisitor = references.annotationDefault()

        override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitTryCatchAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitLocalVariableAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            start: Array<out Label>,
            end: Array<out Label>,
            index: IntArray,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            fieldName: String,
            fieldDescriptor: String,
        ) {
            references.internalName(owner)
            references.descriptor(fieldDescriptor)
            if (owner == ownerInternalName) return
            if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) return
            candidatesForMethod += RawCandidate(owner, "<clinit>", "()V", virtualRaw = false, ordinal = instructionOrdinal())
        }
    }

    private fun lambdaCandidateOrNull(
        invokedDescriptor: String,
        bootstrapMethodHandle: Handle,
        bootstrapMethodArguments: Array<out Any>,
    ): RawCandidate? {
        if (bootstrapMethodHandle.owner != "java/lang/invoke/LambdaMetafactory") return null
        if (bootstrapMethodHandle.name != "metafactory" && bootstrapMethodHandle.name != "altMetafactory") return null
        val implementationHandle = bootstrapMethodArguments.getOrNull(1) as? Handle ?: return null
        val virtualRaw = implementationHandle.tag == Opcodes.H_INVOKEVIRTUAL || implementationHandle.tag == Opcodes.H_INVOKEINTERFACE
        return RawCandidate(
            implementationHandle.owner,
            implementationHandle.name,
            implementationHandle.desc,
            virtualRaw,
            CallEdgeKind.CREATES,
            capturedCount(invokedDescriptor, implementationHandle.tag),
            returnTypeOf(invokedDescriptor).removePrefix("L").removeSuffix(";"),
        )
    }

    internal fun capturedCount(
        invokedDescriptor: String,
        implementationTag: Int,
    ): Int {
        val captured = parseParameterDescriptors(invokedDescriptor).size
        val receiver =
            when (implementationTag) {
                Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL -> 1
                else -> 0
            }
        return (captured - receiver).coerceAtLeast(0)
    }

    /** How many probe slots a switch with these case targets and this default owns. */
    fun switchOutcomeCount(
        dflt: Label,
        labels: Array<out Label>,
    ): Int = labels.count { it !== dflt } + 1

    private fun isDefaultShaped(
        name: String,
        descriptor: String,
    ): Boolean = name.endsWith("\$default") || (name == "<init>" && descriptor.endsWith("DefaultConstructorMarker;)V"))

    private fun isConstructorAccessor(
        ownerInternalName: String,
        descriptor: String,
        candidates: List<RawCandidate>,
    ): Boolean {
        val params = parseParameterDescriptors(descriptor)
        if (params.isEmpty()) return false
        val forwardedDescriptor = params.dropLast(1).joinToString("", "(", ")V")
        val ownConstructorCalls = candidates.filter { it.owner == ownerInternalName && it.name == "<init>" }
        return ownConstructorCalls.singleOrNull()?.descriptor == forwardedDescriptor
    }

    private val kotlinMetadataDescriptorShape = Regex("^L[^/;]+/Metadata;$")

    /**
     * [lookup] resolves another class's bytes by internal name, for a constructor default getter
     * whose target lives on a different class from the getter itself, and for the map class an enum
     * switch reads its case labels from. It defaults to always returning null, which leaves such a
     * getter unresolved and such a switch as plain sites instead of failing analysis. It treats a
     * [lookup] that throws as one that returned null.
     *
     * [handlerInterfaces] names, by `Class.getName()`, the functional interfaces a framework takes
     * a handler as. The analysis yields [Analysis.handlerForwarders] only for those.
     *
     * [resourceLookup] reads the leading bytes of a resource that is not a class, by path, through
     * the same loader [lookup] reads classes through. It finds the `.tasty` file that names the
     * Scala 3 release which compiled a class. One that returns null, or throws, leaves the class
     * version-blind.
     *
     * [receivedBytes] are the bytes the rewrite will walk when an earlier transformer changed them;
     * [SizeGuard] tests the class file's 65535 limit against them as well as against [classBytes].
     */
    fun analyze(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray? = { null },
        includePackages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
        tableCache: CrossClassTableCache? = null,
        handlerInterfaces: Set<String> = emptySet(),
        resourceLookup: (path: String) -> ByteArray? = { null },
        receivedBytes: ByteArray? = null,
        outsideCallers: Boolean = false,
        methodFilter: (name: String, descriptor: String) -> Boolean,
    ): Analysis =
        analyzeThrough(
            ClassReader(classBytes),
            classBytes,
            lookup,
            includePackages,
            excludePackages,
            tableCache,
            handlerInterfaces,
            resourceLookup,
            receivedBytes,
            null,
            outsideCallers,
            methodFilter = methodFilter,
        )

    internal fun analyzeThrough(
        classReader: ClassReader,
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
        includePackages: List<String>,
        excludePackages: List<String>,
        tableCache: CrossClassTableCache?,
        handlerInterfaces: Set<String>,
        resourceLookup: (path: String) -> ByteArray?,
        receivedBytes: ByteArray?,
        fingerprintVisitor: ((onResult: (ConditionFingerprinter.MethodResult) -> Unit) -> MethodVisitor)? = null,
        outsideCallers: Boolean = false,
        outOfScopeLookup: ((internalName: String) -> ByteArray?)? = null,
        callbackAnnotations: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
        methodFilter: (name: String, descriptor: String) -> Boolean,
    ): Analysis {
        val readClass = readOnce(lookup)
        val jpa = JpaEnhancement.of(classReader)
        val wrapperKeys = jpa.reportedNames.map { (moved, name) -> name to moved.second }.toSet()
        var fingerprints: ConditionFingerprinter.FingerprintCollection? = null
        val sites = mutableListOf<BranchSite>()
        val firstLines = mutableMapOf<Pair<String, String>, Int>()
        val inlineMethods = mutableSetOf<Pair<String, String>>()
        var nextSiteIndex = 0
        var hasLineNumbers = false
        var isKotlinClass = false
        var isActivityInterfaceClass = false
        var kotlinKind = KotlinKind.NONE
        var isScalaClass = false

        var internalClassName = ""
        var sourceFile: String? = null
        var hasEnclosingMethod = false
        var ownInnerClassEntry: BodyKindRule.OwnInnerClassEntry? = null
        var classAccess = 0
        var superInternalName: String? = null
        var interfaceInternalNames: List<String> = emptyList()
        var smap = KotlinSmap.EMPTY
        val methodAccess = mutableMapOf<Pair<String, String>, Int>()
        val methodsWithLineNumbers = mutableSetOf<Pair<String, String>>()
        val localNames = mutableMapOf<Pair<String, String>, MutableMap<Int, String>>()
        val defaultCandidates = mutableListOf<DefaultCandidate>()
        val defaultShapedNames = mutableListOf<Pair<String, String>>()
        val rawCandidatesByMethod = mutableMapOf<Pair<String, String>, MutableList<RawCandidate>>()
        val eligibleMethodKeys = mutableSetOf<Pair<String, String>>()
        val droppedOrdinalsByMethod = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
        val rawReferencesByMethod = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val instructionsByMethod = mutableMapOf<Pair<String, String>, () -> MethodInstructions>()
        val trackedSequencesByMethod = mutableMapOf<Pair<String, String>, IntArray>()
        val rawClassReferences = LinkedHashSet<String>()
        val classReferenceCollector = ReferenceCollector(rawClassReferences)
        val sourceSignatures = mutableMapOf<Pair<String, String>, SourceSignature>()
        val annotationsByMethod = mutableMapOf<Pair<String, String>, MethodAnnotations>()

        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    internalClassName = name
                    classAccess = access
                    superInternalName = superName
                    interfaceInternalNames = interfaces?.toList() ?: emptyList()
                    classReferenceCollector.internalName(superName)
                    interfaces?.forEach(classReferenceCollector::internalName)
                    classReferenceCollector.signature(signature)
                }

                override fun visitSource(
                    source: String?,
                    debug: String?,
                ) {
                    sourceFile = source
                    smap = KotlinSmapParser.parse(debug, internalClassName)
                }

                override fun visitOuterClass(
                    owner: String,
                    name: String?,
                    descriptor: String?,
                ) {
                    hasEnclosingMethod = true
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    if (name == internalClassName) ownInnerClassEntry = BodyKindRule.OwnInnerClassEntry(innerName)
                }

                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    val references = classReferenceCollector.annotation(descriptor, visible)
                    if (visible && descriptor == ACTIVITY_INTERFACE_DESCRIPTOR) isActivityInterfaceClass = true
                    if (!kotlinMetadataDescriptorShape.matches(descriptor)) return references
                    isKotlinClass = true
                    kotlinKind = KotlinKind.ofMetadataKind(null)
                    return MetadataKindReader(references) { kotlinKind = KotlinKind.ofMetadataKind(it) }
                }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? = classReferenceCollector.annotation(descriptor, visible)

                override fun visitAttribute(attribute: Attribute) {
                    if (ScalaClassDetector.isScalaAttribute(attribute)) isScalaClass = true
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor = classLevelMemberReferences(classReferenceCollector, descriptor, signature)

                override fun visitRecordComponent(
                    name: String,
                    descriptor: String,
                    signature: String?,
                ): RecordComponentVisitor = recordComponentReferences(classReferenceCollector, descriptor, signature)

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val visitor = methodVisitor(access, name, descriptor, signature, exceptions)
                    val named =
                        if (name == "<clinit>") {
                            visitor
                        } else {
                            ParameterNameReader(access, descriptor, visitor) { names ->
                                sourceSignatures[name to descriptor] = SourceSignature.of(names, signature)
                            }
                        }
                    val main =
                        if (outsideCallers && (name to descriptor).let { it in eligibleMethodKeys || it in wrapperKeys } &&
                            name != "<init>" && name != "<clinit>"
                        ) {
                            AnnotationRecorder(named, callbackAnnotations) { annotationsByMethod[name to descriptor] = it }
                        } else {
                            named
                        }
                    val collection =
                        fingerprints ?: ConditionFingerprinter
                            .FingerprintCollection(
                                internalClassName,
                                when {
                                    isKotlinClass -> SourceLanguage.KOTLIN
                                    isScalaClass -> SourceLanguage.SCALA
                                    else -> SourceLanguage.JAVA
                                },
                                enumTest(internalClassName, classAccess, readClass),
                                EnumSwitchMappings(readClass),
                                fingerprintVisitor,
                            ).also { fingerprints = it }
                    return collection.methodVisitor(name, descriptor, main)
                }

                private fun methodVisitor(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    methodAccess[name to descriptor] = access
                    val localNamesForMethod = localNames.getOrPut(name to descriptor) { mutableMapOf() }
                    val defaultShaped = isDefaultShaped(name, descriptor)
                    if (defaultShaped) defaultShapedNames += name to descriptor
                    val eligible = methodFilter(name, descriptor) && !jpa.isUnprobed(name, descriptor)
                    val isTypeInitializer = name == "<clinit>" && descriptor == "()V"
                    if (eligible) eligibleMethodKeys += name to descriptor
                    val candidatesForMethod = rawCandidatesByMethod.getOrPut(name to descriptor) { mutableListOf() }
                    val referencesForMethod = rawReferencesByMethod.getOrPut(name to descriptor) { LinkedHashSet() }
                    recordSignatureReferences(referencesForMethod, descriptor, signature, exceptions)

                    if (!eligible && !defaultShaped) {
                        return object : CallCandidateMethodVisitor(internalClassName, candidatesForMethod, referencesForMethod) {
                            override fun visitLocalVariable(
                                localName: String,
                                localDescriptor: String,
                                localSignature: String?,
                                start: Label,
                                end: Label,
                                index: Int,
                            ) {
                                localNamesForMethod.putIfAbsent(index, localName)
                            }

                            override fun visitLineNumber(
                                line: Int,
                                start: Label,
                            ) {
                                hasLineNumbers = true
                                methodsWithLineNumbers += name to descriptor
                                if (isTypeInitializer) firstLines.putIfAbsent(name to descriptor, line)
                            }
                        }
                    }

                    fun siteVisitor(instructionOrdinal: () -> Int) =
                        DefaultSiteAwareMethodVisitor(
                            name = name,
                            descriptor = descriptor,
                            isStatic = access and Opcodes.ACC_STATIC != 0,
                            eligible = eligible,
                            weavedBranches = jpa.isPersistenceWeaved && jpa.hasLineNumbers(name, descriptor),
                            clearChecks = jpa.clearCheckOrdinals(name, descriptor),
                            defaultShaped = defaultShaped,
                            ownerInternalName = internalClassName,
                            ownerSuperInternalName = superInternalName,
                            localNamesForMethod = localNamesForMethod,
                            sites = sites,
                            firstLines = firstLines,
                            inlineMethods = inlineMethods,
                            onSiteIndexUsed = { nextSiteIndex++ },
                            nextSiteIndex = { nextSiteIndex },
                            onDefaultCandidate = { defaultCandidates += it },
                            candidatesForMethod = candidatesForMethod,
                            referencesForMethod = referencesForMethod,
                            smap = { smap },
                            includePackages = includePackages,
                            excludePackages = excludePackages,
                            onSiteDropped = { ordinal ->
                                droppedOrdinalsByMethod.getOrPut(name to descriptor) { mutableSetOf() } += ordinal
                            },
                            onLineNumberSeen = {
                                hasLineNumbers = true
                                methodsWithLineNumbers += name to descriptor
                            },
                            instructionOrdinal = instructionOrdinal,
                        )

                    if (!eligible) return siteVisitor { -1 }
                    return InstructionRecorder(
                        { recorder -> siteVisitor(recorder::lastOrdinal) },
                        onTracked = { if (it.isNotEmpty()) trackedSequencesByMethod[name to descriptor] = it },
                    ) {
                        instructionsByMethod[name to descriptor] = it
                    }
                }
            }

        classReader.accept(classVisitor, ClassReader.SKIP_FRAMES)

        val throwingDefaultOrdinalsByMethod = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
        val unprobedOutcomesByMethod = mutableMapOf<Pair<String, String>, MutableMap<Int, Int>>()
        attachConditionFingerprints(
            sites,
            fingerprints?.results() ?: if (fingerprints == null) emptyMap() else null,
            droppedOrdinalsByMethod,
            throwingDefaultOrdinalsByMethod,
            unprobedOutcomesByMethod,
        )
        val sizeGuard =
            SizeGuard.apply(sites, classBytes, receivedBytes, methodFilter, droppedOrdinalsByMethod, classReader) { key ->
                instructionsByMethod[key]?.invoke()?.opcodes
            }
        val guardsByMethod =
            analyzeGuards(
                sites,
                instructionsByMethod,
                sourceFile,
                smap,
                includePackages,
                excludePackages,
                throwableTest(internalClassName, superInternalName, readClass),
            )

        val maskTested = defaultCandidates.mapTo(mutableSetOf()) { it.defaultName to it.defaultDescriptor }
        val constructorAccessors =
            defaultShapedNames.filterTo(mutableSetOf()) { (name, descriptor) ->
                name == "<init>" &&
                    (name to descriptor) !in maskTested &&
                    isConstructorAccessor(internalClassName, descriptor, rawCandidatesByMethod[name to descriptor].orEmpty())
            }
        val defaultSites = resolveDefaultSites(internalClassName, classAccess, methodAccess, localNames, defaultCandidates)
        val resolved = defaultSites.mapTo(mutableSetOf()) { it.defaultName to it.defaultDescriptor }
        val unresolvedDefaultSites = defaultShapedNames.distinct().filterNot { it in resolved || it in constructorAccessors }

        val getterCandidateNames =
            methodAccess.entries
                .filter { (_, access) -> access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0 }
                .map { it.key }
                .filter { (name, _) -> scalaGetterPattern.matches(name) }
        val scalaGetterSites =
            resolveScalaGetterSites(internalClassName, classAccess, methodAccess, localNames, firstLines, getterCandidateNames, readClass)
        val resolvedGetters = scalaGetterSites.mapTo(mutableSetOf()) { it.getterName to it.getterDescriptor }
        val unresolvedScalaGetterSites = getterCandidateNames.filterNot { it in resolvedGetters }
        val hasTypeInitializer = ("<clinit>" to "()V") in methodAccess
        val generatedMarks =
            computeGeneratedBy(
                classBytes,
                internalClassName,
                superInternalName,
                methodAccess,
                methodsWithLineNumbers,
                kotlinKind,
                isScalaClass,
                isKotlinClass,
                interfaceInternalNames,
                readClass,
            ) { name -> ScalaReleases.releaseOf(name, resourceLookup, tableCache) }
        val generatedByMethod = generatedMarks.generated

        val callEdgeEntryPoints = if (hasTypeInitializer) eligibleMethodKeys + ("<clinit>" to "()V") else eligibleMethodKeys
        val resolvedCalls =
            resolveCallEdges(
                internalClassName = internalClassName,
                methodAccess = methodAccess,
                rawCandidatesByMethod = rawCandidatesByMethod,
                eligibleMethodKeys = callEdgeEntryPoints,
                lookup = readClass,
                includePackages = includePackages,
                excludePackages = excludePackages,
                tableCache = tableCache,
                rawReferencesByMethod = rawReferencesByMethod,
                handlerInterfaces = handlerInterfaces,
                guardsByMethod = guardsByMethod,
                isOwnClassBodyClass = hasEnclosingMethod,
                forwarderKeys = generatedMarks.passThroughKeys(),
                classAccess = classAccess,
                superInternalName = superInternalName,
                enhancement = jpa,
            )
        val references =
            placeReferences(
                internalClassName = internalClassName,
                methodAccess = methodAccess,
                entryPoints = callEdgeEntryPoints,
                resolvedCalls = resolvedCalls,
                rawReferencesByMethod = rawReferencesByMethod,
                rawClassReferences = rawClassReferences,
                rekindedGetters = resolvedGetters,
                includePackages = includePackages,
                excludePackages = excludePackages,
            )

        val lambdaBodies = findLambdaBodies(internalClassName, methodAccess, rawCandidatesByMethod, eligibleMethodKeys, isScalaClass)
        val bodyClass = BodyKindRule.classify(hasEnclosingMethod, superInternalName, ownInnerClassEntry, isKotlinClass)
        val shouldWalk = outsideCallers && eligibleMethodKeys.isNotEmpty()
        val outsideCallerKeys = eligibleMethodKeys + wrapperKeys
        val overrideWalk =
            OverrideWalk(
                includePackages,
                excludePackages,
                headerReader(lookup, outOfScopeLookup, includePackages, excludePackages, tableCache, callbackAnnotations),
            )
        val sameClassCallees = { bridge: Pair<String, String> ->
            rawCandidatesByMethod[bridge]
                .orEmpty()
                .filter {
                    it.kind == CallEdgeKind.CALL && it.owner == internalClassName && (it.name to it.descriptor) != bridge &&
                        it.name != BOX_IMPL && it.name != UNBOX_IMPL
                }.mapTo(mutableSetOf()) { it.name to it.descriptor }
        }
        val overriddenOutsideTypes =
            if (shouldWalk) {
                overrideWalk.overriddenTypes(
                    internalClassName,
                    superInternalName,
                    interfaceInternalNames,
                    methodAccess,
                    outsideCallerKeys,
                    sameClassCallees,
                )
            } else {
                emptyMap()
            }

        val callbackFinder =
            CallbackAnnotationFinder(
                classBytesReader(lookup, outOfScopeLookup, includePackages, excludePackages),
                tableCache,
                callbackAnnotations,
            )
        val onInterface = classAccess and Opcodes.ACC_INTERFACE != 0 || internalClassName.endsWith(DEFAULT_IMPLS_SUFFIX)
        val callbackAnnotationByMethod =
            annotationsByMethod
                .mapNotNull { (key, annotations) ->
                    callbackFinder.first(annotations, onInterface)?.let { key to it.replace('/', '.') }
                }.toMap()
        val inheritedCallbackAnnotations =
            if (shouldWalk) {
                overrideWalk.inheritedAnnotations(
                    internalClassName,
                    superInternalName,
                    interfaceInternalNames,
                    methodAccess,
                    outsideCallerKeys,
                    OverrideWalk.OwnLabels({ annotationsByMethod[it] }, { it in callbackAnnotationByMethod }),
                    callbackFinder,
                    sameClassCallees,
                    isActivityInterface = classAccess and Opcodes.ACC_INTERFACE != 0 && isActivityInterfaceClass,
                )
            } else {
                emptyMap()
            }

        return Analysis(
            sites,
            firstLines,
            inlineMethods,
            defaultSites,
            unresolvedDefaultSites,
            scalaGetterSites,
            unresolvedScalaGetterSites,
            hasTypeInitializer,
            resolvedCalls.edgesByMethod,
            superInternalName?.replace('/', '.'),
            interfaceInternalNames.map { it.replace('/', '.') },
            droppedOrdinalsByMethod,
            generatedByMethod,
            hasLineNumbers,
            isKotlinClass,
            references.byMethod,
            references.onClass,
            lambdaBodies,
            sourceFile,
            bodyClass.kind,
            bodyClass.sourceName,
            resolvedCalls.handlerForwarders,
            internalClassName.replace('/', '.'),
            guardsByMethod.values
                .flatMap { it.sites.entries }
                .associate { it.key to it.value },
            throwingDefaultOrdinalsByMethod,
            kotlinKind,
            sourceSignatures,
            unprobedOutcomesByMethod,
            generatedMarks.unread,
            generatedMarks.unreadRelease,
            generatedMarks.cause,
            sizeGuard,
            trackedSequencesByMethod,
            jpa.fromWrappers(overriddenOutsideTypes),
            jpa.fromWrappers(callbackAnnotationByMethod),
            jpa.fromWrappers(inheritedCallbackAnnotations),
            jpa.reportedNames,
        )
    }

    private fun headerReader(
        lookup: (internalName: String) -> ByteArray?,
        outOfScopeLookup: ((internalName: String) -> ByteArray?)?,
        includePackages: List<String>,
        excludePackages: List<String>,
        tableCache: CrossClassTableCache?,
        callbackAnnotations: ConfiguredCallbackAnnotations,
    ): (String) -> TypeHeader? {
        val bytesOf = classBytesReader(lookup, outOfScopeLookup, includePackages, excludePackages)
        val read = { internalName: String -> bytesOf(internalName)?.let { TypeHeader.parse(it, callbackAnnotations) } }
        if (tableCache != null) return { internalName -> tableCache.getOrReadHeader(internalName) { read(internalName) } }
        val own = HashMap<String, TypeHeader?>()
        return { internalName -> if (internalName in own) own[internalName] else read(internalName).also { own[internalName] = it } }
    }

    private fun classBytesReader(
        lookup: (internalName: String) -> ByteArray?,
        outOfScopeLookup: ((internalName: String) -> ByteArray?)?,
        includePackages: List<String>,
        excludePackages: List<String>,
    ): (String) -> ByteArray? =
        { internalName ->
            try {
                val reader =
                    if (outOfScopeLookup != null && !TypeMatchPolicy.isIncludedInternal(internalName, includePackages, excludePackages)) {
                        outOfScopeLookup
                    } else {
                        lookup
                    }
                reader(internalName)
            } catch (_: Exception) {
                null
            }
        }

    private fun readOnce(lookup: (internalName: String) -> ByteArray?): (String) -> ByteArray? {
        val read = HashMap<String, ByteArray?>()
        return { internalName ->
            if (internalName in read) {
                read[internalName]
            } else {
                val bytes =
                    try {
                        lookup(internalName)
                    } catch (_: Exception) {
                        null
                    }
                read[internalName] = bytes
                bytes
            }
        }
    }

    private class MetadataKindReader(
        delegate: AnnotationVisitor?,
        private val onKind: (Int) -> Unit,
    ) : AnnotationVisitor(Opcodes.ASM9, delegate) {
        override fun visit(
            name: String?,
            value: Any?,
        ) {
            if (name == "k" && value is Int) onKind(value)
            super.visit(name, value)
        }
    }

    private val PASS_THROUGH_FORWARDERS =
        setOf(
            GeneratedBy.JVM_OVERLOADS,
            GeneratedBy.MULTIFILE_FACADE,
            GeneratedBy.DEFAULT_IMPLS,
            GeneratedBy.STATIC_FORWARDER,
            GeneratedBy.TRAIT_FORWARDER,
            GeneratedBy.CLASS_DELEGATION,
        )

    private fun analyzeGuards(
        sites: List<BranchSite>,
        instructionsByMethod: Map<Pair<String, String>, () -> MethodInstructions>,
        sourceFile: String?,
        smap: KotlinSmap,
        includePackages: List<String>,
        excludePackages: List<String>,
        isThrowable: (internalName: String) -> Boolean,
    ): Map<Pair<String, String>, MethodGuards> {
        if (sites.none { it.dropReason == null }) return emptyMap()
        val firstBranchIndexes = KeptBranchSite.firstBranchIndexes(sites)
        val positionsByMethod = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        sites.forEachIndexed { position, site ->
            positionsByMethod.getOrPut(site.methodName to site.methodDescriptor) { mutableListOf() } += position
        }
        val ownFile = sourceFile ?: ""

        fun sourceLineOf(outputLine: Int): SourceLine? {
            val origin = smap.originOf(outputLine) ?: return SourceLine(ownFile, outputLine)
            if (!TypeMatchPolicy.isIncluded(origin.originClassName, includePackages, excludePackages)) return null
            return SourceLine(origin.sourceFile, origin.inputLine)
        }

        val result = mutableMapOf<Pair<String, String>, MethodGuards>()
        for ((methodKey, positions) in positionsByMethod) {
            if (positions.all { sites[it].dropReason != null }) continue
            val instructions = instructionsByMethod[methodKey]?.invoke() ?: continue
            val methodSites = positions.map { sites[it] }
            val methodFirstIndexes = IntArray(positions.size) { firstBranchIndexes[positions[it]] }
            GuardAnalysis
                .analyze(instructions, methodSites, methodFirstIndexes, ::sourceLineOf, isThrowable)
                ?.let { result[methodKey] = it }
        }
        return result
    }

    private fun findLambdaBodies(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
        isScalaClass: Boolean,
    ): Set<Pair<String, String>> {
        val pending =
            ArrayDeque(
                rawCandidatesByMethod.values
                    .flatten()
                    .filter { it.kind == CallEdgeKind.CREATES && it.owner == internalClassName }
                    .map { it.name to it.descriptor },
            )
        if (isScalaClass) pending += methodAccess.keys.filter { TypeMatchPolicy.isScala3LiftedLambdaName(it.first) }
        val named = mutableSetOf<Pair<String, String>>()
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (!named.add(key)) continue
            val access = methodAccess[key] ?: continue
            val isPassThrough = key !in eligibleMethodKeys && access and BODYLESS_FLAGS == 0
            if (!isPassThrough) continue
            for (candidate in rawCandidatesByMethod[key].orEmpty()) {
                if (candidate.owner == internalClassName) pending += candidate.name to candidate.descriptor
            }
        }
        return named.filterTo(mutableSetOf()) { it in eligibleMethodKeys && TypeMatchPolicy.isLambdaBodyName(it.first) }
    }

    private fun attachConditionFingerprints(
        sites: MutableList<BranchSite>,
        fingerprintsByMethod: Map<Pair<String, String>, ConditionFingerprinter.MethodResult>?,
        droppedOrdinalsByMethod: MutableMap<Pair<String, String>, MutableSet<Int>>,
        throwingDefaultOrdinalsByMethod: MutableMap<Pair<String, String>, MutableSet<Int>>,
        unprobedOutcomesByMethod: MutableMap<Pair<String, String>, MutableMap<Int, Int>>,
    ) {
        if (fingerprintsByMethod == null) return
        val siteIndicesByMethod = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        sites.forEachIndexed { index, site ->
            siteIndicesByMethod.getOrPut(site.methodName to site.methodDescriptor) { mutableListOf() } += index
        }
        for ((methodKey, siteIndices) in siteIndicesByMethod) {
            val result = fingerprintsByMethod[methodKey] ?: continue
            if (result.fingerprints.size != siteIndices.size) continue
            siteIndices.forEachIndexed { ordinal, siteListIndex ->
                val site = sites[siteListIndex]
                sites[siteListIndex] =
                    site.copy(
                        conditionFingerprint = result.fingerprints[ordinal],
                        caseKeys = result.caseKeys[ordinal],
                        condition = if (site.dropReason == null) conditionOf(result, ordinal) else emptyList(),
                        stringHashCodeSwitch = ordinal in result.stringHashCodeSwitches,
                    )
            }
            for ((ordinal, offset) in result.unreadCollisionOutcomes) {
                val position = siteIndices[ordinal]
                sites[position] = sites[position].copy(unreadOutcome = offset)
            }
            for (lowered in result.loweredSwitches) {
                val ordinals = lowered.loweringOrdinals + listOfNotNull(lowered.rebuiltOrdinal) + lowered.caseConditions.keys
                if (ordinals.any { sites[siteIndices[it]].dropReason != null }) continue
                for (ordinal in lowered.loweringOrdinals) {
                    val position = siteIndices[ordinal]
                    sites[position] = sites[position].copy(dropReason = BranchDropReason.SWITCH_LOWERING, condition = emptyList())
                    droppedOrdinalsByMethod.getOrPut(methodKey) { mutableSetOf() } += ordinal
                }
                for ((ordinal, condition) in lowered.caseConditions) {
                    val position = siteIndices[ordinal]
                    sites[position] = sites[position].copy(condition = condition)
                }
                for ((ordinal, offset) in lowered.collisionOutcomes) {
                    val position = siteIndices[ordinal]
                    sites[position] = sites[position].copy(unprobedOutcome = offset)
                    unprobedOutcomesByMethod.getOrPut(methodKey) { mutableMapOf() }[ordinal] = offset
                }
                val rebuilt = lowered.rebuiltOrdinal ?: continue
                val position = siteIndices[rebuilt]
                sites[position] =
                    sites[position].copy(
                        conditionFingerprint = lowered.fingerprint,
                        condition = lowered.condition,
                        caseLabels = lowered.caseLabels,
                        throwingDefault = lowered.throwingDefault,
                    )
                if (lowered.throwingDefault) throwingDefaultOrdinalsByMethod.getOrPut(methodKey) { mutableSetOf() } += rebuilt
            }
        }
    }

    private fun enumTest(
        ownInternalName: String,
        ownAccess: Int,
        lookup: (internalName: String) -> ByteArray?,
    ): (String) -> Boolean {
        val answers = HashMap<String, Boolean>()
        return { internalName ->
            answers.getOrPut(internalName) {
                val access =
                    if (internalName == ownInternalName) {
                        ownAccess
                    } else {
                        try {
                            lookup(internalName)?.let { ClassReader(it).access }
                        } catch (_: Exception) {
                            null
                        }
                    }
                access != null && access and Opcodes.ACC_ENUM != 0
            }
        }
    }

    private fun throwableTest(
        ownInternalName: String,
        ownSuperName: String?,
        lookup: (internalName: String) -> ByteArray?,
    ): (String) -> Boolean {
        val answers = HashMap<String, Boolean>()

        // The empty name stands for a class the lookup could not read.
        fun superNameOf(internalName: String): String? {
            if (internalName == ownInternalName) return ownSuperName
            return try {
                val bytes = lookup(internalName) ?: return UNREADABLE
                ClassReader(bytes).superName
            } catch (_: Exception) {
                UNREADABLE
            }
        }

        fun resolve(internalName: String): Boolean {
            var current = internalName
            val seen = HashSet<String>()
            while (seen.add(current)) {
                answers[current]?.let { return it }
                if (current == THROWABLE_INTERNAL_NAME) return true
                if (current == OBJECT_INTERNAL_NAME) return false
                val superName = superNameOf(current) ?: return false
                if (superName == UNREADABLE) return current.endsWith("Exception") || current.endsWith("Error")
                current = superName
            }
            return false
        }

        return { internalName -> answers.getOrPut(internalName) { resolve(internalName) } }
    }

    private fun conditionOf(
        result: ConditionFingerprinter.MethodResult,
        ordinal: Int,
    ): List<ConditionPart> =
        try {
            result.conditionOf(ordinal)
        } catch (_: Exception) {
            emptyList()
        }

    private class PlacedReferences(
        val byMethod: Map<Pair<String, String>, List<String>>,
        val onClass: List<String>,
    )

    private fun placeReferences(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        entryPoints: Set<Pair<String, String>>,
        resolvedCalls: ResolvedCalls,
        rawReferencesByMethod: Map<Pair<String, String>, Set<String>>,
        rawClassReferences: Set<String>,
        rekindedGetters: Set<Pair<String, String>>,
        includePackages: List<String>,
        excludePackages: List<String>,
    ): PlacedReferences {
        fun outOfScope(rawNames: Collection<String>): List<String> {
            if (rawNames.isEmpty()) return emptyList()
            val names = LinkedHashSet<String>()
            for (raw in rawNames) {
                if (raw == internalClassName || TypeMatchPolicy.isIncludedInternal(raw, includePackages, excludePackages)) continue
                names += raw.replace('/', '.')
            }
            return names.toList()
        }

        fun isProbed(key: Pair<String, String>): Boolean =
            key in entryPoints && key !in rekindedGetters && (methodAccess[key] ?: 0) and BODYLESS_FLAGS == 0

        val onClass = LinkedHashSet(rawClassReferences)
        for (key in methodAccess.keys) {
            if (!isProbed(key) && key !in resolvedCalls.reachedPassThroughs) onClass += rawReferencesByMethod[key].orEmpty()
        }
        for ((key, gathered) in resolvedCalls.referencesByMethod) {
            if (!isProbed(key)) onClass += gathered
        }
        val byMethod =
            resolvedCalls.referencesByMethod
                .filterKeys(::isProbed)
                .mapValues { (_, rawNames) -> outOfScope(rawNames) }
        return PlacedReferences(byMethod, outOfScope(onClass))
    }

    private fun recordSignatureReferences(
        references: MutableSet<String>,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ) {
        val collector = ReferenceCollector(references)
        collector.descriptor(descriptor)
        collector.signature(signature)
        exceptions?.forEach(collector::internalName)
    }

    private fun classLevelMemberReferences(
        collector: ReferenceCollector,
        descriptor: String,
        signature: String?,
    ): FieldVisitor {
        collector.descriptor(descriptor)
        collector.signature(signature)
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)
        }
    }

    private fun recordComponentReferences(
        collector: ReferenceCollector,
        descriptor: String,
        signature: String?,
    ): RecordComponentVisitor {
        collector.descriptor(descriptor)
        collector.signature(signature)
        return object : RecordComponentVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)
        }
    }

    private fun resolveCallEdges(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
        lookup: (String) -> ByteArray?,
        includePackages: List<String>,
        excludePackages: List<String>,
        tableCache: CrossClassTableCache? = null,
        rawReferencesByMethod: Map<Pair<String, String>, Set<String>> = emptyMap(),
        handlerInterfaces: Set<String> = emptySet(),
        guardsByMethod: Map<Pair<String, String>, MethodGuards> = emptyMap(),
        isOwnClassBodyClass: Boolean = false,
        forwarderKeys: Set<Pair<String, String>> = emptySet(),
        classAccess: Int,
        superInternalName: String?,
        enhancement: JpaEnhancement = JpaEnhancement.NONE,
    ): ResolvedCalls {
        val crossClassMethodTables = mutableMapOf<String, MethodTable?>()

        fun readTable(ownerInternalName: String): MethodTable? {
            val bytes =
                try {
                    lookup(ownerInternalName)
                } catch (_: Exception) {
                    null
                } ?: return null
            return try {
                readMethodTable(bytes, lookup)
            } catch (_: Exception) {
                null
            }
        }

        fun methodTableFor(ownerInternalName: String): MethodTable? {
            if (ownerInternalName in crossClassMethodTables) return crossClassMethodTables[ownerInternalName]
            val table =
                if (tableCache != null) {
                    tableCache.getOrRead(ownerInternalName) { readTable(ownerInternalName) }
                } else {
                    readTable(ownerInternalName)
                }
            crossClassMethodTables[ownerInternalName] = table
            return table
        }

        val dottedClassName = internalClassName.replace('/', '.')

        fun reportedName(
            dottedOwner: String,
            name: String,
            descriptor: String,
        ): String =
            if (dottedOwner == dottedClassName) {
                enhancement.reportedName(name, descriptor)
            } else {
                methodTableFor(dottedOwner.replace('.', '/'))?.enhancement?.reportedName(name, descriptor) ?: name
            }

        val reachedPassThroughs = mutableSetOf<Pair<String, String>>()
        val referencesByMethod = mutableMapOf<Pair<String, String>, Set<String>>()
        val reachedUnprobedBodyClasses = LinkedHashSet<String>()

        fun inheritedDeclaration(
            start: String?,
            name: String,
            descriptor: String,
        ): Pair<String, MethodTable>? {
            var current = start
            repeat(MAX_INHERITED_DECLARATION_HOPS) {
                val candidate = current ?: return null
                if (!TypeMatchPolicy.isIncluded(candidate.replace('/', '.'), includePackages, excludePackages)) return null
                val table = methodTableFor(candidate) ?: return null
                if ((name to descriptor) in table.methodAccess) return candidate to table
                current = table.superInternalName
            }
            return null
        }

        fun isBodyClass(ownerInternalName: String): Boolean =
            if (ownerInternalName == internalClassName) {
                isOwnClassBodyClass
            } else {
                TypeMatchPolicy.isIncluded(ownerInternalName.replace('/', '.'), includePackages, excludePackages) &&
                    methodTableFor(ownerInternalName)?.hasEnclosingMethod == true
            }

        fun walk(
            candidates: List<RawCandidate>,
            ownReferences: MutableSet<String>,
            passThroughs: MutableSet<Pair<String, String>>,
            passThroughForwarders: Boolean,
            guardOf: (RawCandidate) -> Int?,
        ): Set<CallEdge> {
            val edges = LinkedHashSet<CallEdge>()
            val visited = mutableSetOf<VisitKey>()
            var guard: Int? = null
            var references = ownReferences

            fun edge(
                owner: String,
                name: String,
                descriptor: String,
                virtual: Boolean,
                kind: CallEdgeKind,
                capturedCount: Int,
                implementedInterface: String?,
            ): CallEdge {
                val captured = if (capturedCount == 0) 0 else capturedCount.coerceAtMost(parseParameterDescriptors(descriptor).size)
                return CallEdge(
                    owner.interned(),
                    reportedName(owner, name, descriptor).interned(),
                    descriptor.interned(),
                    virtual,
                    kind,
                    captured,
                    guard,
                    implementedInterface?.replace('/', '.')?.interned(),
                )
            }

            fun visit(
                owner: String,
                name: String,
                descriptor: String,
                virtualRaw: Boolean,
                kind: CallEdgeKind,
                capturedCount: Int,
                implementedInterface: String?,
            ) {
                if (!visited.add(VisitKey(owner, name, descriptor, kind, capturedCount, guard, implementedInterface))) return

                fun visitInside(candidate: RawCandidate) {
                    if (candidate.kind == CallEdgeKind.CREATES) {
                        visit(
                            candidate.owner,
                            candidate.name,
                            candidate.descriptor,
                            candidate.virtualRaw,
                            CallEdgeKind.CREATES,
                            candidate.capturedCount,
                            candidate.functionalInterface,
                        )
                    } else {
                        visit(
                            candidate.owner,
                            candidate.name,
                            candidate.descriptor,
                            candidate.virtualRaw,
                            kind,
                            capturedCount,
                            implementedInterface,
                        )
                    }
                }

                fun passThroughForwarder(forwarderCandidates: List<RawCandidate>) {
                    val outer = references
                    references = LinkedHashSet()
                    forwarderCandidates.forEach(::visitInside)
                    references = outer
                }

                fun passThroughInherited(start: String?): Boolean {
                    if (name == "<init>" || name == "<clinit>") return false
                    val (declaringOwner, declaringTable) = inheritedDeclaration(start, name, descriptor) ?: return false
                    val declaringAccess = declaringTable.methodAccess.getValue(name to descriptor)
                    if (declaringAccess and BODYLESS_FLAGS != 0) return false
                    val declaringCandidates = declaringTable.rawCandidatesByMethod[name to descriptor].orEmpty()
                    when {
                        passThroughForwarders && (name to descriptor) in declaringTable.forwarderKeys -> {
                            passThroughForwarder(declaringCandidates)
                        }

                        declaringAccess and Opcodes.ACC_BRIDGE != 0 -> {
                            references += declaringTable.rawReferencesByMethod[name to descriptor].orEmpty()
                            declaringCandidates.forEach(::visitInside)
                        }

                        else -> {
                            return false
                        }
                    }
                    visit(declaringOwner, "<clinit>", "()V", false, kind, 0, null)
                    return true
                }

                if (owner == internalClassName) {
                    if (name == "<clinit>") return
                    val access = methodAccess[name to descriptor]
                    if (access == null) passThroughInherited(superInternalName)
                    val nonVirtual = access != null && access and NON_VIRTUAL_FLAGS != 0
                    val virtual = virtualRaw && !nonVirtual
                    val declaredWithBody = access != null && access and BODYLESS_FLAGS == 0
                    if (passThroughForwarders && declaredWithBody && (name to descriptor) in forwarderKeys) {
                        passThroughForwarder(rawCandidatesByMethod[name to descriptor].orEmpty())
                        if (virtual && classAccess and Opcodes.ACC_FINAL == 0) {
                            edges += edge(dottedClassName, name, descriptor, true, kind, capturedCount, implementedInterface)
                        }
                    } else if ((name to descriptor) in eligibleMethodKeys || !declaredWithBody) {
                        edges += edge(dottedClassName, name, descriptor, virtual, kind, capturedCount, implementedInterface)
                    } else {
                        passThroughs += name to descriptor
                        references += rawReferencesByMethod[name to descriptor].orEmpty()
                        rawCandidatesByMethod[name to descriptor].orEmpty().forEach(::visitInside)
                        val ownBridge = access != null && access and Opcodes.ACC_BRIDGE != 0
                        if (virtual && ownBridge && classAccess and Opcodes.ACC_FINAL == 0) {
                            edges += edge(dottedClassName, name, descriptor, true, kind, capturedCount, implementedInterface)
                        }
                    }
                    return
                }

                val dottedOwner = owner.replace('/', '.')
                if (!TypeMatchPolicy.isIncluded(dottedOwner, includePackages, excludePackages)) return

                if (isDefaultShaped(name, descriptor)) {
                    val defaultTable = methodTableFor(owner)
                    val accessorCandidates = defaultTable?.rawCandidatesByMethod?.get(name to descriptor).orEmpty()
                    if (defaultTable != null && name == "<init>" && isConstructorAccessor(owner, descriptor, accessorCandidates)) {
                        references += defaultTable.rawReferencesByMethod[name to descriptor].orEmpty()
                        accessorCandidates.forEach(::visitInside)
                        return
                    }
                    val target = defaultTable?.let { resolveCrossClassDefaultTarget(owner, name, descriptor, it) }
                    if (target != null) {
                        references += defaultTable.rawReferencesByMethod[name to descriptor].orEmpty()
                        if (passThroughForwarders && (target.name to target.descriptor) in defaultTable.forwarderKeys) {
                            visit(owner, target.name, target.descriptor, target.virtual, kind, capturedCount, implementedInterface)
                        } else {
                            edges +=
                                edge(dottedOwner, target.name, target.descriptor, target.virtual, kind, capturedCount, implementedInterface)
                        }
                        return
                    }
                }

                val table = methodTableFor(owner)
                val access = table?.methodAccess?.get(name to descriptor)
                if (table != null && access == null) passThroughInherited(table.superInternalName)
                if (table == null || access == null) {
                    if (!TypeMatchPolicy.isUndeclaredEnhancementName(name)) {
                        edges += edge(dottedOwner, name, descriptor, virtualRaw, kind, capturedCount, implementedInterface)
                    }
                    return
                }

                val declaredWithBody = access and BODYLESS_FLAGS == 0
                val isConstructorOrInitializer = name == "<init>" || name == "<clinit>"
                if (table.isUnprobedBodyClass) {
                    references += table.rawReferencesByMethod[name to descriptor].orEmpty()
                    table.rawCandidatesByMethod[name to descriptor].orEmpty().forEach(::visitInside)
                    if (isConstructorOrInitializer) {
                        reachedUnprobedBodyClasses += owner
                        for ((bodyKey, bodyAccess) in table.methodAccess) {
                            val (bodyName, bodyDescriptor) = bodyKey
                            if (bodyName == "<init>" || bodyName == "<clinit>" || bodyAccess and BODYLESS_FLAGS != 0) continue
                            visit(owner, bodyName, bodyDescriptor, bodyAccess and NON_VIRTUAL_FLAGS == 0, CallEdgeKind.CREATES, 0, null)
                        }
                    }
                    return
                }
                val overridable = virtualRaw && access and NON_VIRTUAL_FLAGS == 0 && table.classAccess and Opcodes.ACC_FINAL == 0
                if (declaredWithBody && !isConstructorOrInitializer &&
                    wouldNotBeProbedByMethodTier(access, name, descriptor, table)
                ) {
                    references += table.rawReferencesByMethod[name to descriptor].orEmpty()
                    table.rawCandidatesByMethod[name to descriptor].orEmpty().forEach(::visitInside)
                    visit(owner, "<clinit>", "()V", false, kind, 0, null)
                    if (overridable && access and Opcodes.ACC_BRIDGE != 0) {
                        edges += edge(dottedOwner, name, descriptor, true, kind, capturedCount, implementedInterface)
                    }
                    return
                }

                if (virtualRaw && access and Opcodes.ACC_ABSTRACT != 0 && table.classAccess and Opcodes.ACC_INTERFACE != 0) {
                    val implsOwner = owner + DEFAULT_IMPLS_SUFFIX
                    val implsDescriptor = "(L$owner;" + descriptor.substring(1)
                    val implsAccess = methodTableFor(implsOwner)?.methodAccess?.get(name to implsDescriptor)
                    if (implsAccess != null && implsAccess and Opcodes.ACC_STATIC != 0 && implsAccess and BODYLESS_FLAGS == 0) {
                        edges += edge(implsOwner.replace('/', '.'), name, implsDescriptor, false, kind, capturedCount, implementedInterface)
                    }
                }

                val isForwarder = passThroughForwarders && declaredWithBody && (name to descriptor) in table.forwarderKeys
                if (isForwarder) {
                    passThroughForwarder(table.rawCandidatesByMethod[name to descriptor].orEmpty())
                    visit(owner, "<clinit>", "()V", false, kind, 0, null)
                    if (overridable) edges += edge(dottedOwner, name, descriptor, true, kind, capturedCount, implementedInterface)
                } else {
                    val nonVirtual = access and NON_VIRTUAL_FLAGS != 0
                    edges += edge(dottedOwner, name, descriptor, virtualRaw && !nonVirtual, kind, capturedCount, implementedInterface)
                }

                if (isConstructorOrInitializer && table.hasEnclosingMethod) {
                    for ((bodyKey, bodyAccess) in table.methodAccess) {
                        val (bodyName, bodyDescriptor) = bodyKey
                        if (bodyName == "<init>" || bodyName == "<clinit>") continue
                        if (bodyAccess and BODYLESS_FLAGS != 0) continue
                        if (wouldNotBeProbedByMethodTier(bodyAccess, bodyName, bodyDescriptor, table)) continue
                        val bodyNonVirtual = bodyAccess and NON_VIRTUAL_FLAGS != 0
                        edges +=
                            CallEdge(
                                dottedOwner.interned(),
                                table.enhancement.reportedName(bodyName, bodyDescriptor).interned(),
                                bodyDescriptor.interned(),
                                !bodyNonVirtual,
                                CallEdgeKind.CREATES,
                                guard = guard,
                            )
                    }
                }
            }

            for (candidate in candidates) {
                guard = guardOf(candidate)
                visit(
                    candidate.owner,
                    candidate.name,
                    candidate.descriptor,
                    candidate.virtualRaw,
                    candidate.kind,
                    candidate.capturedCount,
                    candidate.functionalInterface,
                )
            }
            return edges
        }

        fun resolveOne(methodKey: Pair<String, String>): List<CallEdge> {
            val references = LinkedHashSet<String>(rawReferencesByMethod[methodKey].orEmpty())
            referencesByMethod[methodKey] = references
            val guards = guardsByMethod[methodKey]
            val guardOf: (RawCandidate) -> Int? =
                if (guards == null) {
                    { null }
                } else {
                    { candidate ->
                        val createsBody = candidate.name == "<init>" && candidate.newOrdinal >= 0 && isBodyClass(candidate.owner)
                        guards.guardAt(if (createsBody) candidate.newOrdinal else candidate.ordinal)
                    }
                }
            val passThroughForwarders = methodKey !in forwarderKeys
            val edges = walk(rawCandidatesByMethod[methodKey].orEmpty(), references, reachedPassThroughs, passThroughForwarders, guardOf)
            val (selfName, selfDescriptor) = methodKey
            val selfReportedName = enhancement.reportedName(selfName, selfDescriptor)
            return edges.filterNot {
                it.className == dottedClassName && it.methodName == selfReportedName &&
                    it.methodDescriptor == selfDescriptor
            }
        }

        val edgesByMethod = eligibleMethodKeys.associateWith(::resolveOne)
        val handlerForwarders =
            if (handlerInterfaces.isEmpty()) {
                emptyList()
            } else {
                findHandlerForwarders(
                    internalClassName,
                    methodAccess,
                    rawCandidatesByMethod,
                    eligibleMethodKeys,
                    reachedUnprobedBodyClasses,
                    handlerInterfaces,
                    ::methodTableFor,
                ) { candidates -> walk(candidates, LinkedHashSet(), mutableSetOf(), passThroughForwarders = true) { null } }
            }
        return ResolvedCalls(edgesByMethod, referencesByMethod, reachedPassThroughs, handlerForwarders)
    }

    private fun findHandlerForwarders(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
        reachedUnprobedBodyClasses: Set<String>,
        handlerInterfaces: Set<String>,
        methodTableFor: (String) -> MethodTable?,
        walk: (List<RawCandidate>) -> Set<CallEdge>,
    ): List<HandlerForwarder> {
        val dottedClassName = internalClassName.replace('/', '.')

        fun isHandlerInterface(internalName: String?): Boolean = internalName != null && internalName.replace('/', '.') in handlerInterfaces

        fun hasNoBody(edge: CallEdge): Boolean {
            val key = edge.methodName to edge.methodDescriptor
            val access =
                if (edge.className == dottedClassName) {
                    methodAccess[key]
                } else {
                    methodTableFor(edge.className.replace('.', '/'))?.methodAccess?.get(key)
                }
            return access != null && access and BODYLESS_FLAGS != 0
        }

        fun forwarder(
            ownerInternalName: String,
            key: Pair<String, String>,
            candidates: List<RawCandidate>,
        ): HandlerForwarder? {
            val owner = ownerInternalName.replace('/', '.')
            val (name, descriptor) = key
            val targets =
                walk(candidates)
                    .asSequence()
                    .filter { it.kind == CallEdgeKind.CALL && it.methodName != "<clinit>" }
                    .filterNot { it.className == owner && it.methodName == name && it.methodDescriptor == descriptor }
                    .distinctBy { Triple(it.className, it.methodName, it.methodDescriptor) }
                    .toList()
            val target = targets.singleOrNull() ?: return null
            if (hasNoBody(target)) return null
            return HandlerForwarder(owner, name, descriptor, target.className, target.methodName, target.methodDescriptor)
        }

        val forwarders = mutableListOf<HandlerForwarder>()
        val implementations =
            rawCandidatesByMethod.values
                .asSequence()
                .flatten()
                .filter { it.kind == CallEdgeKind.CREATES && it.owner == internalClassName && isHandlerInterface(it.functionalInterface) }
                .map { it.name to it.descriptor }
                .distinct()
        for (key in implementations) {
            val access = methodAccess[key] ?: continue
            if (key in eligibleMethodKeys || access and BODYLESS_FLAGS != 0) continue
            forwarder(internalClassName, key, rawCandidatesByMethod[key].orEmpty())?.let { forwarders += it }
        }
        // Iterates a snapshot, since the walk below can add to this set.
        for (bodyClass in reachedUnprobedBodyClasses.toList()) {
            val table = methodTableFor(bodyClass) ?: continue
            if (table.interfaceInternalNames.none(::isHandlerInterface)) continue
            for ((key, access) in table.methodAccess) {
                if (key.first == "<init>" || key.first == "<clinit>" || access and BODYLESS_FLAGS != 0) continue
                forwarder(bodyClass, key, table.rawCandidatesByMethod[key].orEmpty())?.let { forwarders += it }
            }
        }
        return forwarders
    }

    private data class VisitKey(
        val owner: String,
        val name: String,
        val descriptor: String,
        val kind: CallEdgeKind,
        val capturedCount: Int,
        val guard: Int?,
        val implementedInterface: String?,
    )

    private class ResolvedCalls(
        val edgesByMethod: Map<Pair<String, String>, List<CallEdge>>,
        val referencesByMethod: Map<Pair<String, String>, Set<String>>,
        val reachedPassThroughs: Set<Pair<String, String>>,
        val handlerForwarders: List<HandlerForwarder>,
    )

    private fun resolveCrossClassDefaultTarget(
        ownerInternalName: String,
        defaultName: String,
        defaultDescriptor: String,
        table: MethodTable,
    ): CrossClassDefaultTarget? {
        val isConstructor = defaultName == "<init>"
        val targetName = if (isConstructor) "<init>" else defaultName.removeSuffix("\$default")
        val defaultParams = parseParameterDescriptors(defaultDescriptor)
        val maskIntCount = resolveMaskIntCount(defaultParams.size - 1)
        val maskStartParamIndex = defaultParams.size - 1 - maskIntCount
        if (maskStartParamIndex < 0) return null
        val valueParams = defaultParams.subList(0, maskStartParamIndex)
        val defaultReturn = returnTypeOf(defaultDescriptor)
        val ownerDescriptor = "L$ownerInternalName;"

        val matches =
            table.methodAccess.entries.filter { (key, access) ->
                val (candidateName, candidateDescriptor) = key
                if (candidateName != targetName || candidateDescriptor == defaultDescriptor) return@filter false
                val candidateIsStatic = access and Opcodes.ACC_STATIC != 0
                val candidateParams = parseParameterDescriptors(candidateDescriptor)
                if (isConstructor) {
                    returnTypeOf(candidateDescriptor) == "V" && candidateParams == valueParams
                } else if (returnTypeOf(candidateDescriptor) != defaultReturn) {
                    false
                } else if (candidateIsStatic) {
                    candidateParams == valueParams
                } else {
                    valueParams.size == candidateParams.size + 1 &&
                        valueParams[0] == ownerDescriptor &&
                        valueParams.drop(1) == candidateParams
                }
            }

        if (matches.size != 1) return null
        val (targetKey, targetAccess) = matches.single()
        val nonVirtual = targetAccess and NON_VIRTUAL_FLAGS != 0
        return CrossClassDefaultTarget(targetKey.first, targetKey.second, virtual = !isConstructor && !nonVirtual)
    }

    private const val NON_VIRTUAL_FLAGS = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL

    private const val MAX_INHERITED_DECLARATION_HOPS = 16

    private const val BODYLESS_FLAGS = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE

    // These rules must match TypeMatchPolicy.methodMatcher, or this resolves a method the method tier
    // probes as a pass-through.
    private fun wouldNotBeProbedByMethodTier(
        access: Int,
        name: String,
        descriptor: String,
        table: MethodTable,
    ): Boolean {
        if (access and Opcodes.ACC_BRIDGE != 0) return true
        if (TypeMatchPolicy.isEnhancementMethod(name) || table.enhancement.isUnprobed(name, descriptor)) return true
        if (TypeMatchPolicy.isSuspendLambdaEntry(name, access and Opcodes.ACC_STATIC != 0) {
                table.superInternalName?.replace('/', '.')
            }
        ) {
            return true
        }
        if (access and Opcodes.ACC_SYNTHETIC != 0) return !TypeMatchPolicy.isProbedLambdaBody(name, table.isScalaClass)
        return false
    }

    private val scalaGetterPattern = Regex("^(.+)\\\$default\\\$(\\d+)$")

    private const val CONSTRUCTOR_GETTER_TARGET_NAME = "\$lessinit\$greater"

    private sealed interface RecentInsn {
        data object None : RecentInsn

        data class GetField(
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : RecentInsn

        data class ALoad(
            val varIndex: Int,
        ) : RecentInsn

        data class InstanceOf(
            val type: String,
        ) : RecentInsn

        data class Ldc(
            val value: Any?,
        ) : RecentInsn

        data object Iand : RecentInsn

        data object Dup : RecentInsn

        data class Invoke(
            val name: String,
            val descriptor: String,
        ) : RecentInsn

        data object SuspendedMarkerCall : RecentInsn

        data object Other : RecentInsn
    }

    private object CoroutineShapes {
        fun isSuspendShaped(
            name: String,
            descriptor: String,
            ownerSuperInternalName: String?,
        ): Boolean {
            val lastParameter = parseParameterDescriptors(descriptor).lastOrNull()
            if (lastParameter != null && lastParameter.endsWith("coroutines/Continuation;")) return true
            if (name != "invokeSuspend" || descriptor != "(Ljava/lang/Object;)Ljava/lang/Object;") return false
            val dottedSuper = ownerSuperInternalName?.replace('/', '.') ?: return false
            return TypeMatchPolicy.SUSPEND_LAMBDA_SUPERCLASS_SUFFIXES.any { dottedSuper.endsWith(it) }
        }

        fun isLabelSwitch(
            mostRecent: RecentInsn,
            ownerInternalName: String,
            methodName: String,
        ): Boolean {
            val getField = mostRecent as? RecentInsn.GetField ?: return false
            return isLabelRead(getField, ownerInternalName, methodName)
        }

        private fun isContinuationOf(
            className: String,
            ownerInternalName: String,
        ): Boolean {
            val interfaceName = ownerInternalName.removeSuffix(DEFAULT_IMPLS_SUFFIX)
            val nestedUnder =
                when {
                    className.startsWith("$ownerInternalName\$") -> ownerInternalName
                    interfaceName != ownerInternalName && className.startsWith("$interfaceName\$") -> interfaceName
                    else -> return false
                }
            return CONTINUATION_NAME_TAIL.matches(className.substring(nestedUnder.length + 1))
        }

        fun isSuspendedCompare(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
            suspendedMarkerSlots: Set<Int>,
        ): Boolean =
            isTrackedSuspendedLoad(mostRecent, suspendedMarkerSlots) || isTrackedSuspendedLoad(secondMostRecent, suspendedMarkerSlots)

        fun isSuspendedStackCompare(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
        ): Boolean = mostRecent == RecentInsn.SuspendedMarkerCall && secondMostRecent == RecentInsn.Dup

        private fun isTrackedSuspendedLoad(
            insn: RecentInsn,
            suspendedMarkerSlots: Set<Int>,
        ): Boolean = insn is RecentInsn.ALoad && insn.varIndex in suspendedMarkerSlots

        fun isContinuationInstanceOfCheck(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
            ownerInternalName: String,
            continuationSlot: Int,
        ): Boolean {
            val instanceOf = mostRecent as? RecentInsn.InstanceOf ?: return false
            val load = secondMostRecent as? RecentInsn.ALoad ?: return false
            if (load.varIndex != continuationSlot) return false
            return isContinuationOf(instanceOf.type, ownerInternalName)
        }

        fun isLabelReentryCheck(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
            thirdMostRecent: RecentInsn,
            ownerInternalName: String,
        ): Boolean {
            if (mostRecent !is RecentInsn.Iand) return false
            val ldc = secondMostRecent as? RecentInsn.Ldc ?: return false
            if (ldc.value != Int.MIN_VALUE) return false
            val getField = thirdMostRecent as? RecentInsn.GetField ?: return false
            if (getField.name != "label" || getField.descriptor != "I") return false
            return isContinuationOf(getField.owner, ownerInternalName)
        }

        fun isFedByMachinery(
            window: List<RecentInsn>,
            ownerInternalName: String,
            methodName: String,
            suspendedMarkerSlots: Set<Int>,
        ): Boolean =
            window.withIndex().any { (index, insn) ->
                (insn == RecentInsn.SuspendedMarkerCall && window.getOrNull(index + 1) == RecentInsn.Dup) ||
                    isTrackedSuspendedLoad(insn, suspendedMarkerSlots) ||
                    (insn is RecentInsn.GetField && isLabelRead(insn, ownerInternalName, methodName))
            }

        private fun isLabelRead(
            getField: RecentInsn.GetField,
            ownerInternalName: String,
            methodName: String,
        ): Boolean {
            if (getField.name != "label" || getField.descriptor != "I") return false
            return if (methodName == "invokeSuspend") {
                getField.owner == ownerInternalName
            } else {
                isContinuationOf(getField.owner, ownerInternalName)
            }
        }

        private const val DEFAULT_IMPLS_SUFFIX = "\$DefaultImpls"

        private val CONTINUATION_NAME_TAIL = Regex("""[^$]+(\$[^$]+)*\$\d+""")
    }

    private class DefaultSiteAwareMethodVisitor(
        private val name: String,
        private val descriptor: String,
        private val isStatic: Boolean,
        private val eligible: Boolean,
        private val weavedBranches: Boolean,
        private val clearChecks: Set<Int>,
        private val defaultShaped: Boolean,
        private val ownerInternalName: String,
        private val ownerSuperInternalName: String?,
        private val localNamesForMethod: MutableMap<Int, String>,
        private val sites: MutableList<BranchSite>,
        private val firstLines: MutableMap<Pair<String, String>, Int>,
        private val inlineMethods: MutableSet<Pair<String, String>>,
        private val onSiteIndexUsed: () -> Unit,
        private val nextSiteIndex: () -> Int,
        private val onDefaultCandidate: (DefaultCandidate) -> Unit,
        candidatesForMethod: MutableList<RawCandidate>,
        referencesForMethod: MutableSet<String>,
        private val smap: () -> KotlinSmap,
        private val includePackages: List<String>,
        private val excludePackages: List<String>,
        private val onSiteDropped: (ordinal: Int) -> Unit,
        private val onLineNumberSeen: () -> Unit,
        instructionOrdinal: () -> Int,
    ) : CallCandidateMethodVisitor(ownerInternalName, candidatesForMethod, referencesForMethod, instructionOrdinal) {
        private var currentLine = -1
        private var lineSeen = false
        private var lastLabel: Label? = null
        private val inlineMarkerName = "\$i\$f\$$name"
        private var nextMethodOrdinal = 0

        private val maskLocalIndex: Int
        private val secondaryMaskRange: IntRange

        private var phase = 0
        private var pendingConstant = 0
        private var optionalBits = 0
        private var higherMaskTested = false

        private val fillLines = mutableMapOf<Int, Int>()

        private var fillStartsNext = -1

        private var fillLabel: Label? = null
        private var fillLabelBit = -1

        private val suspendShaped = CoroutineShapes.isSuspendShaped(name, descriptor, ownerSuperInternalName)

        private val lastParameterSlot = lastParameterLocalIndex(descriptor, isStatic)

        private var recentInsn1: RecentInsn = RecentInsn.None
        private var recentInsn2: RecentInsn = RecentInsn.None
        private var recentInsn3: RecentInsn = RecentInsn.None
        private var recentInsn4: RecentInsn = RecentInsn.None

        private val suspendedMarkerSlots = mutableSetOf<Int>()

        private var pendingSuspendedMarkerCall = false

        private fun pushInsn(insn: RecentInsn) {
            if (fillStartsNext >= 0) {
                fillLines.putIfAbsent(fillStartsNext, currentLine)
                fillStartsNext = -1
            }
            recentInsn4 = recentInsn3
            recentInsn3 = recentInsn2
            recentInsn2 = recentInsn1
            recentInsn1 = insn
        }

        init {
            if (defaultShaped) {
                val (localIndex, maskIntCount) = maskLocalInfo(name, descriptor)
                maskLocalIndex = localIndex
                secondaryMaskRange = (localIndex + 1) until (localIndex + maskIntCount)
            } else {
                maskLocalIndex = -1
                secondaryMaskRange = IntRange.EMPTY
            }
        }

        override fun visitLabel(label: Label) {
            lastLabel = label
            if (label === fillLabel) {
                fillStartsNext = fillLabelBit
                fillLabel = null
            }
        }

        override fun visitLineNumber(
            line: Int,
            start: Label,
        ) {
            currentLine = line
            lineSeen = true
            onLineNumberSeen()
            if (eligible) firstLines.putIfAbsent(name to descriptor, line)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            var testedBit = -1
            if (defaultShaped) {
                // A coverage agent registered ahead of this one (JaCoCo) inverts every conditional jump,
                // so the mask test arrives as IFNE as well as IFEQ.
                if (phase == 3 && (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE)) {
                    optionalBits = optionalBits or pendingConstant
                    testedBit = Integer.numberOfTrailingZeros(pendingConstant)
                }
                resetMaskPhase()
            }
            if (eligible && ConditionalJump.isTracked(opcode)) {
                val machinery = suspendShaped && isCoroutineMachineryJump(opcode)
                recordSite(
                    coroutineMachinery = machinery,
                    unreadMachinery = suspendShaped && !machinery && isFedByMachinery(),
                    jpaWeaving = !machinery && (isWeavedJump(opcode) || nextMethodOrdinal in clearChecks),
                )
            }
            pendingSuspendedMarkerCall = false
            pushInsn(RecentInsn.Other)
            if (testedBit >= 0 && opcode == Opcodes.IFEQ) {
                fillStartsNext = testedBit
            } else if (testedBit >= 0) {
                fillLabel = label
                fillLabelBit = testedBit
            }
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            if (defaultShaped) resetMaskPhase()
            pendingSuspendedMarkerCall = false
            if (eligible) {
                val machinery = suspendShaped && CoroutineShapes.isLabelSwitch(recentInsn1, ownerInternalName, name)
                recordSite(
                    switchOutcomeCount(dflt, labels),
                    isSwitch = true,
                    coroutineMachinery = machinery,
                    unreadMachinery = suspendShaped && !machinery && isFedByMachinery(),
                )
            }
            pushInsn(RecentInsn.Other)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            if (defaultShaped) resetMaskPhase()
            pendingSuspendedMarkerCall = false
            if (eligible) {
                recordSite(
                    switchOutcomeCount(dflt, labels),
                    isSwitch = true,
                    unreadMachinery = suspendShaped && isFedByMachinery(),
                )
            }
            pushInsn(RecentInsn.Other)
        }

        private fun isCoroutineMachineryJump(opcode: Int): Boolean =
            when (opcode) {
                Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                    CoroutineShapes.isSuspendedCompare(recentInsn1, recentInsn2, suspendedMarkerSlots) ||
                        CoroutineShapes.isSuspendedStackCompare(recentInsn1, recentInsn2)
                }

                Opcodes.IFEQ, Opcodes.IFNE -> {
                    CoroutineShapes.isContinuationInstanceOfCheck(recentInsn1, recentInsn2, ownerInternalName, lastParameterSlot) ||
                        CoroutineShapes.isLabelReentryCheck(recentInsn1, recentInsn2, recentInsn3, ownerInternalName)
                }

                else -> {
                    false
                }
            }

        private fun isWeavedJump(opcode: Int): Boolean {
            val oneOperand = opcode in Opcodes.IFEQ..Opcodes.IFLE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
            return weavedBranches && !lineSeen && oneOperand &&
                JpaEnhancement.readsWeavedState(wovenOperand(recentInsn1), wovenOperand(recentInsn2))
        }

        private fun wovenOperand(insn: RecentInsn): JpaEnhancement.WovenOperand =
            when (insn) {
                is RecentInsn.GetField -> JpaEnhancement.WovenOperand.FieldRead(insn.name)
                is RecentInsn.Invoke -> JpaEnhancement.WovenOperand.Call(insn.name, insn.descriptor)
                else -> JpaEnhancement.WovenOperand.Other
            }

        private fun isFedByMachinery(): Boolean =
            CoroutineShapes.isFedByMachinery(
                listOf(recentInsn1, recentInsn2, recentInsn3, recentInsn4),
                ownerInternalName,
                name,
                suspendedMarkerSlots,
            )

        private fun recordSite(
            outcomeCount: Int = 2,
            isSwitch: Boolean = false,
            coroutineMachinery: Boolean = false,
            unreadMachinery: Boolean = false,
            jpaWeaving: Boolean = false,
        ) {
            val ordinal = nextMethodOrdinal++
            if (jpaWeaving) {
                onSiteDropped(ordinal)
                sites +=
                    BranchSite(
                        name,
                        descriptor,
                        currentLine,
                        nextSiteIndex(),
                        outcomeCount,
                        dropReason = BranchDropReason.JPA_WEAVING,
                        isSwitch = isSwitch,
                    )
                onSiteIndexUsed()
                return
            }
            if (coroutineMachinery) {
                onSiteDropped(ordinal)
                sites +=
                    BranchSite(
                        name,
                        descriptor,
                        currentLine,
                        nextSiteIndex(),
                        outcomeCount,
                        dropReason = BranchDropReason.COROUTINE_MACHINERY,
                        isSwitch = isSwitch,
                    )
                onSiteIndexUsed()
                return
            }
            val origin = smap().originOf(currentLine)
            val site =
                when {
                    origin == null -> {
                        BranchSite(name, descriptor, currentLine, nextSiteIndex(), outcomeCount, isSwitch = isSwitch)
                    }

                    TypeMatchPolicy.isIncluded(origin.originClassName, includePackages, excludePackages) -> {
                        BranchSite(
                            name,
                            descriptor,
                            origin.inputLine,
                            nextSiteIndex(),
                            outcomeCount,
                            inlinedFromClassName = origin.originClassName,
                            isSwitch = isSwitch,
                        )
                    }

                    else -> {
                        onSiteDropped(ordinal)
                        BranchSite(
                            name,
                            descriptor,
                            currentLine,
                            nextSiteIndex(),
                            outcomeCount,
                            dropReason = BranchDropReason.INLINED_OUT_OF_SCOPE,
                            inlinedFromClassName = origin.originClassName,
                            isSwitch = isSwitch,
                        )
                    }
                }
            sites += if (unreadMachinery && site.dropReason == null) site.copy(unreadShape = UnreadShape.COROUTINE_MACHINERY) else site
            onSiteIndexUsed()
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            when (opcode) {
                Opcodes.ALOAD -> {
                    pushInsn(RecentInsn.ALoad(varIndex))
                }

                Opcodes.ASTORE -> {
                    if (pendingSuspendedMarkerCall && suspendedMarkerSlots.isEmpty()) suspendedMarkerSlots += varIndex
                    pendingSuspendedMarkerCall = false
                    pushInsn(RecentInsn.Other)
                }

                else -> {
                    pushInsn(RecentInsn.Other)
                }
            }
            if (!defaultShaped) return
            if (opcode == Opcodes.ILOAD && varIndex == maskLocalIndex) {
                phase = 1
                pendingConstant = 0
                return
            }
            if (opcode == Opcodes.ILOAD && varIndex in secondaryMaskRange) higherMaskTested = true
            resetMaskPhase()
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            pushInsn(RecentInsn.Other)
            if (!defaultShaped) return
            if (phase == 1 && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && isSingleBit(operand)) {
                pendingConstant = operand
                phase = 2
            } else {
                resetMaskPhase()
            }
        }

        override fun visitLdcInsn(value: Any?) {
            super.visitLdcInsn(value)
            pushInsn(RecentInsn.Ldc(value))
            if (!defaultShaped) return
            if (phase == 1 && value is Int && isSingleBit(value)) {
                pendingConstant = value
                phase = 2
            } else {
                resetMaskPhase()
            }
        }

        override fun visitInsn(opcode: Int) {
            pushInsn(
                when (opcode) {
                    Opcodes.IAND -> RecentInsn.Iand
                    Opcodes.DUP -> RecentInsn.Dup
                    else -> RecentInsn.Other
                },
            )
            if (!defaultShaped) return
            when {
                phase == 1 && opcode in Opcodes.ICONST_0..Opcodes.ICONST_5 -> {
                    val value = opcode - Opcodes.ICONST_0
                    if (isSingleBit(value)) {
                        pendingConstant = value
                        phase = 2
                    } else {
                        resetMaskPhase()
                    }
                }

                phase == 2 && opcode == Opcodes.IAND -> {
                    phase = 3
                }

                else -> {
                    resetMaskPhase()
                }
            }
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            fieldName: String,
            fieldDescriptor: String,
        ) {
            pushInsn(if (opcode == Opcodes.GETFIELD) RecentInsn.GetField(owner, fieldName, fieldDescriptor) else RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            methodName: String,
            methodDescriptor: String,
            isInterface: Boolean,
        ) {
            val isCoroutineSuspendedCall =
                opcode == Opcodes.INVOKESTATIC && methodName == "getCOROUTINE_SUSPENDED" && owner.endsWith("/IntrinsicsKt")
            pendingSuspendedMarkerCall = isCoroutineSuspendedCall
            pushInsn(if (isCoroutineSuspendedCall) RecentInsn.SuspendedMarkerCall else RecentInsn.Invoke(methodName, methodDescriptor))
            if (defaultShaped) resetMaskPhase()
            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            super.visitTypeInsn(opcode, type)
            pushInsn(if (opcode == Opcodes.INSTANCEOF) RecentInsn.InstanceOf(type) else RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            pushInsn(RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitInvokeDynamicInsn(
            invokedName: String,
            invokedDescriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            pendingSuspendedMarkerCall = false
            pushInsn(RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
            super.visitInvokeDynamicInsn(invokedName, invokedDescriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitMultiANewArrayInsn(
            arrayDescriptor: String,
            numDimensions: Int,
        ) {
            super.visitMultiANewArrayInsn(arrayDescriptor, numDimensions)
            pushInsn(RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
        }

        private fun resetMaskPhase() {
            phase = 0
            pendingConstant = 0
        }

        override fun visitLocalVariable(
            localName: String,
            localDescriptor: String,
            signature: String?,
            start: Label,
            end: Label,
            index: Int,
        ) {
            localNamesForMethod.putIfAbsent(index, localName)
            if (eligible && localName == inlineMarkerName && end === lastLabel) inlineMethods += name to descriptor
        }

        override fun visitEnd() {
            if (defaultShaped && optionalBits != 0) {
                onDefaultCandidate(DefaultCandidate(name, descriptor, optionalBits, higherMaskTested, fillLines.toMap()))
            }
        }
    }

    private fun isSingleBit(value: Int): Boolean = value != 0 && (value and (value - 1)) == 0

    private fun parseParameterDescriptors(descriptor: String): List<String> {
        val params = descriptor.substring(descriptor.indexOf('(') + 1, descriptor.lastIndexOf(')'))
        val result = mutableListOf<String>()
        var i = 0
        while (i < params.length) {
            val start = i
            while (params[i] == '[') i++
            i = if (params[i] == 'L') params.indexOf(';', i) + 1 else i + 1
            result += params.substring(start, i)
        }
        return result
    }

    private fun returnTypeOf(descriptor: String): String = descriptor.substring(descriptor.lastIndexOf(')') + 1)

    private val dataClassComponentPattern = Regex("^component(\\d+)(?:-[A-Za-z0-9_-]{7})?$")

    private val dataClassCopyPattern = Regex("^copy(?:-[A-Za-z0-9_-]{7})?$")

    private fun computeGeneratedBy(
        classBytes: ByteArray,
        internalClassName: String,
        superInternalName: String?,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
        kotlinKind: KotlinKind,
        isScalaClass: Boolean,
        isKotlinClass: Boolean,
        interfaceInternalNames: List<String>,
        lookup: (internalName: String) -> ByteArray?,
        includeSerialization: Boolean = true,
        scalaLookup: (internalName: String) -> ByteArray? = lookup,
        scalaReleaseOf: ((internalName: String) -> String?)? = { null },
    ): GeneratedMarks {
        val result = mutableMapOf<Pair<String, String>, GeneratedBy>()

        if (internalClassName.endsWith(DEFAULT_IMPLS_SUFFIX)) {
            val interfaceInternalName = internalClassName.removeSuffix(DEFAULT_IMPLS_SUFFIX)
            val isValueClass = valueClassTest(lookup)
            for (key in defaultImplsForwarders(classBytes, interfaceInternalName, isValueClass, lookup)) {
                result[key] = GeneratedBy.DEFAULT_IMPLS
            }
            return GeneratedMarks(result)
        }

        if (superInternalName == "java/lang/Enum") {
            val arrayDescriptor = "()[L$internalClassName;"
            val valueOfDescriptor = "(Ljava/lang/String;)L$internalClassName;"
            for (key in methodAccess.keys) {
                val (name, descriptor) = key
                when {
                    name == "values" && descriptor == arrayDescriptor -> result[key] = GeneratedBy.ENUM
                    name == "valueOf" && descriptor == valueOfDescriptor -> result[key] = GeneratedBy.ENUM
                    name == "getEntries" && isEnumEntriesAccessor(descriptor, methodAccess.getValue(key)) -> result[key] = GeneratedBy.ENUM
                }
            }
        }

        if (superInternalName == "java/lang/Record") {
            for (key in javacRecordMembers(classBytes)) result[key] = GeneratedBy.RECORD
        }

        markDataClassMembers(internalClassName, methodAccess, methodsWithLineNumbers, result)
        if (isKotlinClass && EQUALS_KEY !in result) {
            for (key in dataObjectMembers(classBytes, internalClassName, methodAccess, methodsWithLineNumbers)) {
                result.putIfAbsent(key, GeneratedBy.DATA_CLASS)
            }
        }

        val valueClass =
            valueClassMarks(classBytes, internalClassName, methodAccess, methodsWithLineNumbers, interfaceInternalNames, lookup)
        for (key in valueClass.marked) result.putIfAbsent(key, GeneratedBy.VALUE_CLASS)

        if (kotlinKind == KotlinKind.MULTIFILE_CLASS_FACADE) {
            for (key in multifileFacadeForwarders(classBytes, internalClassName)) result.putIfAbsent(key, GeneratedBy.MULTIFILE_FACADE)
        }

        if (methodAccess.keys.any { (name, descriptor) -> isDefaultShaped(name, descriptor) }) {
            for (key in jvmOverloadsForwarders(classBytes, internalClassName)) result.putIfAbsent(key, GeneratedBy.JVM_OVERLOADS)
        }

        var stubsWithoutTarget: Set<Pair<String, String>> = emptySet()
        if (isKotlinClass && interfaceInternalNames.isNotEmpty()) {
            val isValueClass = valueClassTest(lookup)
            val stubs =
                defaultImplsStubs(
                    classBytes,
                    interfaceInternalNames,
                    isValueClass,
                    ownIsValueClass = isValueClassShape(internalClassName, methodAccess),
                    lookup = lookup,
                )
            for (key in stubs.marked) result.putIfAbsent(key, GeneratedBy.DEFAULT_IMPLS)
            stubsWithoutTarget = stubs.withoutTarget
        }

        if (isKotlinClass) {
            val forwarders =
                kotlinProxies(classBytes, internalClassName, methodAccess, methodsWithLineNumbers, interfaceInternalNames, kotlinKind)
            for ((key, generatedBy) in forwarders) {
                result.putIfAbsent(key, generatedBy)
            }
        }

        var serializationUnread: Map<Pair<String, String>, UnreadShape> = emptyMap()
        if (includeSerialization && isKotlinClass) {
            val serialization =
                KotlinxSerializationMarks.of(classBytes, internalClassName, superInternalName, interfaceInternalNames, methodAccess, lookup)
            for ((key, generatedBy) in serialization.generated) result.putIfAbsent(key, generatedBy)
            serializationUnread = serialization.unread.filterKeys { it !in result }
        }

        if (isScalaClass) {
            if (scalaReleaseOf == null) {
                for ((key, generatedBy) in ScalaGeneratedMethods.of(classBytes, scalaLookup)) result.putIfAbsent(key, generatedBy)
                val forwarders = ScalaGeneratedMethods.forwarders(classBytes, lookup)
                for ((key, generatedBy) in forwarders.generated) result.putIfAbsent(key, generatedBy)
                return GeneratedMarks(result, scalaPassThrough = forwarders.passThrough)
            }
            val scala = ScalaGeneratedMethods.analyse(classBytes, lookup, scalaReleaseOf)
            for ((key, generatedBy) in scala.generated) result.putIfAbsent(key, generatedBy)
            val unread = scala.unread.filterKeys { it !in result }
            return GeneratedMarks(
                result,
                unread,
                scala.unreadRelease.takeIf { unread.isNotEmpty() },
                scala.cause.takeIf { unread.isNotEmpty() },
                scalaPassThrough = scala.passThrough,
            )
        }
        if (kotlinKind == KotlinKind.MULTIFILE_CLASS_FACADE) {
            val unread =
                methodAccess
                    .filter { (key, access) ->
                        key !in result &&
                            key.first != "<init>" &&
                            key.first != "<clinit>" &&
                            access and (BODYLESS_FLAGS or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0
                    }.keys
                    .associateWith { UnreadShape.MULTIFILE_FACADE }
            return GeneratedMarks(result, unread, cause = UnreadCause.UNREAD_STRUCTURE.takeIf { unread.isNotEmpty() })
        }
        return GeneratedMarks(
            result,
            serializationUnread,
            cause = UnreadCause.PLUGIN_FILLED.takeIf { serializationUnread.isNotEmpty() },
            valueClassForwarders = valueClass.forwarders,
            stubsWithoutTarget = stubsWithoutTarget,
        )
    }

    private class GeneratedMarks(
        val generated: Map<Pair<String, String>, GeneratedBy>,
        val unread: Map<Pair<String, String>, UnreadShape> = emptyMap(),
        val unreadRelease: String? = null,
        val cause: UnreadCause? = null,
        private val valueClassForwarders: Set<Pair<String, String>> = emptySet(),
        private val scalaPassThrough: Set<Pair<String, String>> = emptySet(),
        private val stubsWithoutTarget: Set<Pair<String, String>> = emptySet(),
    ) {
        fun passThroughKeys(): Set<Pair<String, String>> =
            generated.filterValues { it in PASS_THROUGH_FORWARDERS }.keys + valueClassForwarders + scalaPassThrough - stubsWithoutTarget
    }

    private const val ACTIVITY_INTERFACE_DESCRIPTOR = "L${CallbackAnnotations.ACTIVITY_INTERFACE};"

    private const val DEFAULT_IMPLS_SUFFIX = "\$DefaultImpls"

    private val SMALL_INT_PUSHES = (Opcodes.ICONST_M1..Opcodes.ICONST_5).map { "insn:$it" }.toSet()
    private val TO_STRING_KEY = "toString" to "()Ljava/lang/String;"
    private val HASH_CODE_KEY = "hashCode" to "()I"
    private val EQUALS_KEY = "equals" to "(Ljava/lang/Object;)Z"

    private const val ENUM_ENTRIES_DESCRIPTOR_SUFFIX = "/enums/EnumEntries;"

    private const val INTRINSICS_SUFFIX = "/jvm/internal/Intrinsics"

    private const val BOX_IMPL = "box-impl"
    private const val UNBOX_IMPL = "unbox-impl"
    private const val OBJECT_METHODS_INTERNAL_NAME = "java/lang/runtime/ObjectMethods"
    private const val THROWABLE_INTERNAL_NAME = "java/lang/Throwable"
    private const val OBJECT_INTERNAL_NAME = "java/lang/Object"
    private const val UNREADABLE = ""

    private val parameterNullCheckNames = setOf("checkNotNullParameter", "checkParameterIsNotNull")

    private fun jvmOverloadsForwarders(
        classBytes: ByteArray,
        internalClassName: String,
    ): Set<Pair<String, String>> {
        val forwarders = mutableSetOf<Pair<String, String>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and (BODYLESS_FLAGS or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
                    if (name == "<clinit>" || isDefaultShaped(name, descriptor)) return null
                    val isStatic = access and Opcodes.ACC_STATIC != 0
                    return OverloadForwarderVisitor(internalClassName, name, descriptor, isStatic) { forwarders += name to descriptor }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return forwarders
    }

    private sealed interface Pushed {
        data class Load(
            val opcode: Int,
            val slot: Int,
        ) : Pushed

        data class IntConstant(
            val value: Int,
        ) : Pushed

        data class ZeroConstant(
            val opcode: Int,
        ) : Pushed

        data object StringConstant : Pushed
    }

    private class OverloadForwarderVisitor(
        private val internalClassName: String,
        private val name: String,
        descriptor: String,
        private val isStatic: Boolean,
        private val onForwarder: () -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val ownParameters = parseParameterDescriptors(descriptor)
        private val ownSlots =
            ownParameters
                .runningFold(if (isStatic) 0 else 1) { slot, type -> slot + slotWidth(type) }
                .dropLast(1)
        private val pushed = mutableListOf<Pushed>()
        private var twinDescriptor: String? = null
        private var returned = false
        private var broken = false

        private fun reject() {
            broken = true
        }

        private fun push(value: Pushed) {
            if (twinDescriptor != null) reject() else pushed += value
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            if (opcode in Opcodes.ILOAD..Opcodes.ALOAD) push(Pushed.Load(opcode, varIndex)) else reject()
        }

        override fun visitInsn(opcode: Int) {
            when {
                twinDescriptor != null && !returned && opcode in Opcodes.IRETURN..Opcodes.RETURN -> {
                    returned = true
                }

                opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> {
                    push(Pushed.IntConstant(opcode - Opcodes.ICONST_0))
                }

                opcode == Opcodes.ACONST_NULL || opcode == Opcodes.LCONST_0 || opcode == Opcodes.FCONST_0 || opcode == Opcodes.DCONST_0 -> {
                    push(Pushed.ZeroConstant(opcode))
                }

                else -> {
                    reject()
                }
            }
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) push(Pushed.IntConstant(operand)) else reject()
        }

        override fun visitLdcInsn(value: Any?) {
            when (value) {
                is Int -> push(Pushed.IntConstant(value))
                is String -> push(Pushed.StringConstant)
                else -> reject()
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            if (twinDescriptor != null) return reject()
            when {
                opcode == Opcodes.INVOKESTATIC && owner.endsWith(INTRINSICS_SUFFIX) && name in parameterNullCheckNames -> {
                    val checked = pushed.getOrNull(0) as? Pushed.Load
                    val isOwnReferenceParameter = checked != null && checked.opcode == Opcodes.ALOAD && checked.slot in ownSlots
                    if (pushed.size != 2 || !isOwnReferenceParameter || pushed[1] != Pushed.StringConstant) return reject()
                    pushed.clear()
                }

                owner != internalClassName -> {
                    reject()
                }

                this.name == "<init>" && opcode == Opcodes.INVOKESPECIAL && name == "<init>" && isDefaultShaped(name, descriptor) -> {
                    twinDescriptor = descriptor
                }

                this.name != "<init>" && opcode == Opcodes.INVOKESTATIC && name == this.name + "\$default" -> {
                    twinDescriptor = descriptor
                }

                else -> {
                    reject()
                }
            }
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) = reject()

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) = reject()

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) = reject()

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = reject()

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = reject()

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = reject()

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = reject()

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) = reject()

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) = reject()

        override fun visitEnd() {
            val twin = twinDescriptor
            if (!broken && twin != null && returned && argumentsMatch(twin)) onForwarder()
        }

        private fun argumentsMatch(twin: String): Boolean {
            val twinParameters = parseParameterDescriptors(twin)
            val values = twinParameters.toMutableList()
            if (values.removeLastOrNull() == null) return false
            if (name != "<init>" && !isStatic && values.removeFirstOrNull() != "L$internalClassName;") return false
            val maskCount = resolveMaskIntCount(values.size)
            if (maskCount > values.size) return false
            repeat(maskCount) { values.removeAt(values.lastIndex) }

            var next = 0
            if (!isStatic) {
                if (pushed.getOrNull(next++) != Pushed.Load(Opcodes.ALOAD, 0)) return false
            }
            if (pushed.size != next + values.size + maskCount + 1) return false

            var ownLoaded = 0
            val omitted = IntArray(maskCount)
            for ((index, type) in values.withIndex()) {
                val value = pushed[next++]
                val ownType = ownParameters.getOrNull(ownLoaded)
                when {
                    ownType == type && value == Pushed.Load(loadOpcodeFor(type), ownSlots[ownLoaded]) -> {
                        ownLoaded++
                    }

                    value == zeroValueOf(type) -> {
                        omitted[index / Int.SIZE_BITS] =
                            omitted[index / Int.SIZE_BITS] or (1 shl (index % Int.SIZE_BITS))
                    }

                    else -> {
                        return false
                    }
                }
            }
            if (ownLoaded != ownParameters.size || omitted.all { it == 0 }) return false
            for (word in omitted) {
                if (pushed[next++] != Pushed.IntConstant(word)) return false
            }
            return pushed[next] == Pushed.ZeroConstant(Opcodes.ACONST_NULL)
        }

        private fun zeroValueOf(type: String): Pushed =
            when (type[0]) {
                'J' -> Pushed.ZeroConstant(Opcodes.LCONST_0)
                'F' -> Pushed.ZeroConstant(Opcodes.FCONST_0)
                'D' -> Pushed.ZeroConstant(Opcodes.DCONST_0)
                'L', '[' -> Pushed.ZeroConstant(Opcodes.ACONST_NULL)
                else -> Pushed.IntConstant(0)
            }
    }

    private fun defaultImplsForwarders(
        classBytes: ByteArray,
        interfaceInternalName: String,
        isValueClass: (String) -> Boolean,
        lookup: (internalName: String) -> ByteArray?,
    ): Set<Pair<String, String>> {
        val interfaceBytes by lazy {
            try {
                lookup(interfaceInternalName)
            } catch (_: Exception) {
                null
            }
        }
        val interfaceScope by lazy { interfaceBytes?.let { scopeOf(it, lookup) } }
        val declaredByInterface by lazy { interfaceBytes?.let { declaredMethods(it) } }

        fun callsOwnInterfacesDefaultImpls(
            owner: String,
            calledDescriptor: String,
        ): Boolean {
            if (!owner.endsWith(DEFAULT_IMPLS_SUFFIX) || owner == interfaceInternalName + DEFAULT_IMPLS_SUFFIX) return false
            val first = Type.getArgumentTypes(calledDescriptor).firstOrNull() ?: return false
            return first.sort == Type.OBJECT && first.internalName == owner.removeSuffix(DEFAULT_IMPLS_SUFFIX)
        }

        val candidates = mutableListOf<ForwarderCandidate>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and BODYLESS_FLAGS != 0) return null
                    val isStatic = access and Opcodes.ACC_STATIC != 0
                    val ownParameters = Type.getArgumentTypes(descriptor)
                    val ownReturn = Type.getReturnType(descriptor)
                    return ForwarderShapeVisitor(
                        argumentLoads(descriptor, isStatic = isStatic),
                        allowNullChecks = true,
                        isForwardingCall = { opcode, owner, calledName, calledDescriptor ->
                            opcode == Opcodes.INVOKESTATIC &&
                                (
                                    owner == interfaceInternalName ||
                                        (
                                            isStatic && namesTheCall(name, calledName) &&
                                                callsOwnInterfacesDefaultImpls(owner, calledDescriptor)
                                        )
                                )
                        },
                        returnCast = ownReturn.takeIf { it.sort in REFERENCE_SORTS }?.internalName,
                        allowBoxing = true,
                        allowReceiverCast = true,
                        isValueClass = isValueClass,
                        allowDroppedResult = true,
                        allowSuspendTail = descriptor.endsWith(CONTINUATION_RETURNS_OBJECT),
                        allowArgumentCasts = true,
                    ) { match ->
                        val forwards =
                            if (match.calledOwner == interfaceInternalName) {
                                !match.cast &&
                                    match.boxed.isEmpty() &&
                                    match.argumentCasts.isEmpty() &&
                                    match.receiverCast == null &&
                                    !match.dropped &&
                                    match.returnUnboxed == null &&
                                    (
                                        match.returnBoxed == null ||
                                            returnMatches(ownReturn, Type.getReturnType(match.calledDescriptor), match)
                                    )
                            } else {
                                val superInterface = match.calledOwner.removeSuffix(DEFAULT_IMPLS_SUFFIX)
                                val declared = declaredByInterface
                                val ownMember =
                                    name to "(" + ownParameters.drop(1).joinToString("") { it.descriptor } + ")" + ownReturn.descriptor
                                declared != null && ownMember !in declared &&
                                    (match.receiverCast == null || match.receiverCast == superInterface) &&
                                    (!match.suspendTail || match.receiverCast != null) &&
                                    forwardsThroughErasure(ownParameters, ownReturn, match, offset = 0) &&
                                    forwardsAsTyped(
                                        ownParameters,
                                        ownReturn,
                                        match,
                                        offset = 0,
                                        interfaceScope,
                                        superInterface,
                                        lookup,
                                        true,
                                    )
                            }
                        if (forwards) candidates += ForwarderCandidate(name to descriptor, match)
                    }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return withoutBoxingTwins(candidates)
    }

    private fun defaultImplsStubs(
        classBytes: ByteArray,
        interfaceInternalNames: List<String>,
        isValueClass: (String) -> Boolean,
        ownIsValueClass: Boolean,
        lookup: (internalName: String) -> ByteArray?,
    ): DefaultImplsStubs {
        val candidates = mutableListOf<ForwarderCandidate>()
        val scope by lazy { scopeOf(classBytes, lookup) }
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val excluded = BODYLESS_FLAGS or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_FINAL
                    if (access and excluded != 0) return null
                    if (name.startsWith("<")) return null
                    val stubParameters = Type.getArgumentTypes(descriptor)
                    val stubReturn = Type.getReturnType(descriptor)
                    return ForwarderShapeVisitor(
                        listOf(Opcodes.ALOAD to 0) + argumentLoads(descriptor, isStatic = false),
                        allowNullChecks = false,
                        isForwardingCall = { opcode, owner, calledName, calledDescriptor ->
                            val interfaceName = owner.removeSuffix(DEFAULT_IMPLS_SUFFIX)
                            val calledParameters = Type.getArgumentTypes(calledDescriptor)
                            opcode == Opcodes.INVOKESTATIC &&
                                owner.endsWith(DEFAULT_IMPLS_SUFFIX) &&
                                interfaceName in interfaceInternalNames &&
                                (namesTheCall(name, calledName) || mangledAlike(name, calledName)) &&
                                calledParameters.size == stubParameters.size + 1 &&
                                calledParameters[0].sort == Type.OBJECT &&
                                calledParameters[0].internalName == interfaceName
                        },
                        returnCast = stubReturn.takeIf { it.sort in REFERENCE_SORTS }?.internalName,
                        allowBoxing = true,
                        allowReceiverCast = ownIsValueClass,
                        isValueClass = isValueClass,
                        allowDroppedResult = true,
                        allowArgumentCasts = true,
                    ) { match ->
                        val interfaceName = match.calledOwner.removeSuffix(DEFAULT_IMPLS_SUFFIX)
                        val ownGeneric = signature?.let { ownErasures(it, scope.bounds) }
                        val shaped =
                            (match.receiverCast == null || match.receiverCast == interfaceName) &&
                                forwardsThroughErasure(stubParameters, stubReturn, match, offset = 1)
                        val typed =
                            shaped &&
                                namesTheCall(name, match.calledName) &&
                                forwardsAsTyped(
                                    stubParameters,
                                    stubReturn,
                                    match,
                                    offset = 1,
                                    scope,
                                    interfaceName,
                                    lookup,
                                    false,
                                    ownGeneric,
                                )
                        if (typed) {
                            candidates += ForwarderCandidate(name to descriptor, match)
                        } else if (shaped && declaresNoMethod(match.calledOwner, match.calledName, match.calledDescriptor, lookup)) {
                            candidates += ForwarderCandidate(name to descriptor, match, withoutTarget = true)
                        }
                    }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        if (candidates.isEmpty()) return DefaultImplsStubs(emptySet(), emptySet())
        val firstLines = firstLineEntries(classBytes)
        val headerLine = firstLines.values.minOrNull()
        val onHeader = candidates.filter { candidate -> firstLines[candidate.key].let { it == null || it == headerLine } }
        val marked = withoutBoxingTwins(onHeader)
        return DefaultImplsStubs(marked, onHeader.filter { it.withoutTarget && it.key in marked }.mapTo(mutableSetOf()) { it.key })
    }

    private class DefaultImplsStubs(
        val marked: Set<Pair<String, String>>,
        val withoutTarget: Set<Pair<String, String>>,
    )

    private fun declaresNoMethod(
        owner: String,
        name: String,
        descriptor: String,
        lookup: (internalName: String) -> ByteArray?,
    ): Boolean {
        val bytes =
            try {
                lookup(owner)
            } catch (_: Exception) {
                null
            } ?: return false
        return declaredMethods(bytes)?.contains(name to descriptor) == false
    }

    private const val AJ_SYNTHETIC_ATTRIBUTE = "org.aspectj.weaver.AjSynthetic"

    private fun firstLineEntries(classBytes: ByteArray): Map<Pair<String, String>, Int> {
        val firstLines = mutableMapOf<Pair<String, String>, Int>()
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name == "<clinit>" || name.startsWith("ajc$")) {
                        null
                    } else {
                        object : MethodVisitor(Opcodes.ASM9) {
                            private var woven = false
                            private var firstLine: Int? = null

                            override fun visitAttribute(attribute: Attribute) {
                                if (attribute.type == AJ_SYNTHETIC_ATTRIBUTE) woven = true
                            }

                            override fun visitLineNumber(
                                line: Int,
                                start: Label,
                            ) {
                                if (firstLine == null) firstLine = line
                            }

                            override fun visitEnd() {
                                val line = firstLine
                                if (!woven && line != null) firstLines[name to descriptor] = line
                            }
                        }
                    }
            },
            ClassReader.SKIP_FRAMES,
        )
        return firstLines
    }

    private class ForwarderMatch(
        val calledOwner: String,
        val calledDescriptor: String,
        val cast: Boolean,
        val boxed: Map<Int, String>,
        val receiverCast: String?,
        val returnBoxed: String?,
        val returnUnboxed: String? = null,
        val dropped: Boolean = false,
        val suspendTail: Boolean = false,
        val boxedInto: Map<Int, String> = emptyMap(),
        val unboxedFrom: String? = null,
        val calledName: String = "",
        val argumentCasts: Map<Int, String> = emptyMap(),
    )

    private fun boxedPrimitive(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
    ): String? {
        if (opcode != Opcodes.INVOKESTATIC) return null
        val primitive =
            when {
                name == "valueOf" -> BOX_TYPES.entries.singleOrNull { it.value == owner }?.key
                owner.endsWith(COROUTINE_BOXING_SUFFIX) -> COROUTINE_BOXING_NAMES.entries.singleOrNull { it.value == name }?.key
                else -> null
            } ?: return null
        return primitive.takeIf { descriptor == "($it)L${BOX_TYPES.getValue(it)};" }
    }

    private fun valueClassBoxedType(
        isValueClass: (String) -> Boolean,
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
    ): String? {
        if (opcode != Opcodes.INVOKESTATIC || name != VALUE_CLASS_BOX) return null
        val parameters = parseParameterDescriptors(descriptor)
        if (parameters.size != 1 || returnTypeOf(descriptor) != "L$owner;" || !isValueClass(owner)) return null
        return parameters.single()
    }

    private const val COROUTINE_BOXING_SUFFIX = "/coroutines/jvm/internal/Boxing"

    private val COROUTINE_BOXING_NAMES =
        mapOf(
            "Z" to "boxBoolean",
            "B" to "boxByte",
            "C" to "boxChar",
            "S" to "boxShort",
            "I" to "boxInt",
            "J" to "boxLong",
            "F" to "boxFloat",
            "D" to "boxDouble",
        )

    private val BOX_TYPES =
        mapOf(
            "Z" to "java/lang/Boolean",
            "B" to "java/lang/Byte",
            "C" to "java/lang/Character",
            "S" to "java/lang/Short",
            "I" to "java/lang/Integer",
            "J" to "java/lang/Long",
            "F" to "java/lang/Float",
            "D" to "java/lang/Double",
        )

    private fun forwardsThroughErasure(
        ownParameters: Array<Type>,
        ownReturn: Type,
        match: ForwarderMatch,
        offset: Int,
    ): Boolean {
        if (match.returnBoxed != null) return false
        val called = Type.getMethodType(match.calledDescriptor)
        val calledParameters = called.argumentTypes
        if (calledParameters.size != ownParameters.size + offset) return false
        val parametersMatch =
            ownParameters.indices.all { index ->
                val ownParameter = ownParameters[index]
                val calledParameter = calledParameters[index + offset]
                val boxedPrimitive = match.boxed[index + offset]
                if (boxedPrimitive != null) {
                    ownParameter.descriptor == boxedPrimitive && calledParameter.sort == Type.OBJECT
                } else {
                    sameOrBothReferences(ownParameter, calledParameter)
                }
            }
        return parametersMatch && returnMatches(ownReturn, called.returnType, match)
    }

    private fun returnMatches(
        ownReturn: Type,
        calledReturn: Type,
        match: ForwarderMatch,
    ): Boolean {
        if (match.dropped) {
            return ownReturn.sort == Type.VOID && calledReturn.descriptor == OBJECT_DESCRIPTOR && !match.cast && match.returnUnboxed == null
        }
        val unboxed = match.returnUnboxed
        if (unboxed != null) {
            return !match.cast && calledReturn.descriptor == OBJECT_DESCRIPTOR && ownReturn.descriptor == unboxed
        }
        if (match.suspendTail) {
            return !match.cast &&
                ownReturn.descriptor == OBJECT_DESCRIPTOR &&
                calledReturn.descriptor == OBJECT_DESCRIPTOR &&
                match.calledDescriptor.endsWith(CONTINUATION_RETURNS_OBJECT)
        }
        val boxedPrimitive = match.returnBoxed
        if (boxedPrimitive != null) {
            return !match.cast && calledReturn.descriptor == boxedPrimitive && ownReturn.sort == Type.OBJECT &&
                ownReturn.internalName == BOX_TYPES.getValue(boxedPrimitive)
        }
        return sameOrBothReferences(ownReturn, calledReturn) && match.cast == (calledReturn != ownReturn)
    }

    private fun sameOrBothReferences(
        a: Type,
        b: Type,
    ): Boolean = a == b || (a.sort in REFERENCE_SORTS && b.sort in REFERENCE_SORTS)

    private val REFERENCE_SORTS = setOf(Type.OBJECT, Type.ARRAY)

    private fun forwardsAsTyped(
        ownParameters: Array<Type>,
        ownReturn: Type,
        match: ForwarderMatch,
        offset: Int,
        scope: TypeScope?,
        interfaceName: String,
        lookup: (internalName: String) -> ByteArray?,
        firstIsReceiver: Boolean,
        ownGeneric: ExpectedTypes? = null,
    ): Boolean {
        val typeScope = scope ?: return false
        val arguments = typeScope.interfaceArguments[interfaceName] ?: emptyList()
        val key = Triple(match.calledOwner, match.calledName, match.calledDescriptor) to interfaceName
        val expected =
            typeScope.expected.getOrPut(key) { expectedTypes(match, arguments, typeScope.bounds, lookup) } ?: return false
        return matchesExpected(ownParameters, ownReturn, match, offset, expected, firstIsReceiver, ownGeneric)
    }

    private fun declaredMethods(bytes: ByteArray): Set<Pair<String, String>>? {
        val methods = mutableSetOf<Pair<String, String>>()
        try {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? = null.also { methods += name to descriptor }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        } catch (_: Exception) {
            return null
        }
        return methods
    }

    private class ForwarderCandidate(
        val key: Pair<String, String>,
        val match: ForwarderMatch,
        val withoutTarget: Boolean = false,
    )

    private fun withoutBoxingTwins(candidates: List<ForwarderCandidate>): Set<Pair<String, String>> {
        fun call(candidate: ForwarderCandidate) =
            Triple(candidate.match.calledOwner, candidate.match.calledName, candidate.match.calledDescriptor)
        val unboxedCalls = candidates.filter { it.match.boxedInto.isEmpty() }.mapTo(mutableSetOf()) { call(it) }
        return candidates.filterNot { it.match.boxedInto.isNotEmpty() && call(it) in unboxedCalls }.mapTo(mutableSetOf()) { it.key }
    }

    private fun ownErasures(
        signature: String,
        bounds: Map<String, SignatureType>,
    ): ExpectedTypes? {
        val method = parseMethodSignature(signature) ?: return null
        val all = bounds + method.formals
        return ExpectedTypes(method.parameters.map { eraseIn(it, all) }, eraseIn(method.returnType, all))
    }

    private fun matchesExpected(
        ownParameters: Array<Type>,
        ownReturn: Type,
        match: ForwarderMatch,
        offset: Int,
        expected: ExpectedTypes,
        firstIsReceiver: Boolean = false,
        ownGeneric: ExpectedTypes? = null,
    ): Boolean {
        val calledParameters = Type.getArgumentTypes(match.calledDescriptor)
        val castsFit =
            match.argumentCasts.all { (position, castType) ->
                val called = calledParameters.getOrNull(position) ?: return@all false
                val own = ownParameters.getOrNull(position - offset) ?: return@all false
                position - 1 in expected.variableParameters &&
                    called.sort in REFERENCE_SORTS &&
                    called.descriptor != OBJECT_DESCRIPTOR &&
                    Type.getObjectType(castType).descriptor == called.descriptor &&
                    own.descriptor != called.descriptor
            }
        if (!castsFit) return false
        val parametersMatch =
            ownParameters.indices.all { index ->
                if (firstIsReceiver && index == 0) return@all true
                val wanted = expected.parameters.getOrNull(index + offset - 1) ?: return@all false
                val boxedInto = match.boxedInto[index + offset]
                when {
                    boxedInto != null -> wanted == "L$boxedInto;"
                    ownParameters[index].descriptor == wanted -> true
                    else -> ownGeneric?.parameters?.getOrNull(index) == wanted
                }
            }
        if (!parametersMatch) return false
        val unboxedFrom = match.unboxedFrom
        return when {
            match.suspendTail -> true
            match.dropped -> unitDescriptorShape.matches(expected.returnType)
            unboxedFrom != null -> expected.returnType == "L$unboxedFrom;"
            else -> ownReturn.descriptor == expected.returnType || ownGeneric?.returnType == expected.returnType
        }
    }

    private val unitDescriptorShape = Regex("^L[^/;]+/Unit;$")

    private class ExpectedTypes(
        val parameters: List<String>,
        val returnType: String,
        val variableParameters: Set<Int> = emptySet(),
    )

    private fun expectedTypes(
        match: ForwarderMatch,
        typeArguments: List<SignatureType>,
        bounds: Map<String, SignatureType>,
        lookup: (internalName: String) -> ByteArray?,
    ): ExpectedTypes? {
        val bytes =
            try {
                lookup(match.calledOwner)
            } catch (_: Exception) {
                null
            } ?: return null
        var found = false
        var genericSignature: String? = null
        try {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (name == match.calledName && descriptor == match.calledDescriptor) {
                            found = true
                            genericSignature = signature
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        } catch (_: Exception) {
            return null
        }
        if (!found) return null
        val method = genericSignature?.let { parseMethodSignature(it) }
        if (method == null) {
            val called = Type.getMethodType(match.calledDescriptor)
            return ExpectedTypes(called.argumentTypes.drop(1).map { it.descriptor }, called.returnType.descriptor)
        }
        val receiver = method.parameters.firstOrNull() ?: return null
        val positions =
            receiver.arguments
                .withIndex()
                .mapNotNull { (index, argument) -> argument.variable?.let { it to index } }
                .toMap()

        fun erase(
            type: SignatureType,
            depth: Int = 0,
        ): String =
            type.erasure { variable ->
                val position = positions[variable]
                val bound = method.formals[variable]
                when {
                    depth > MAX_TYPE_VARIABLE_DEPTH -> OBJECT_DESCRIPTOR
                    position != null -> typeArguments.getOrNull(position)?.let { eraseIn(it, bounds) } ?: OBJECT_DESCRIPTOR
                    bound != null -> erase(bound, depth + 1)
                    else -> OBJECT_DESCRIPTOR
                }
            }
        val parameters = method.parameters.drop(1)
        val variables = parameters.indices.filterTo(mutableSetOf()) { parameters[it].variable != null }
        return ExpectedTypes(parameters.map { erase(it) }, erase(method.returnType), variables)
    }

    private fun eraseIn(
        type: SignatureType,
        bounds: Map<String, SignatureType>,
        depth: Int = 0,
    ): String =
        type.erasure { variable ->
            val bound = bounds[variable]
            if (bound == null || depth > MAX_TYPE_VARIABLE_DEPTH) OBJECT_DESCRIPTOR else eraseIn(bound, bounds, depth + 1)
        }

    private const val MAX_TYPE_VARIABLE_DEPTH = 8

    private const val MAX_ENCLOSING_SCOPES = 8

    private class TypeScope(
        val interfaceArguments: Map<String, List<SignatureType>>,
        val bounds: Map<String, SignatureType>,
    ) {
        val expected = HashMap<Pair<Triple<String, String, String>, String>, ExpectedTypes?>()
    }

    private fun scopeOf(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
    ): TypeScope {
        val bounds = LinkedHashMap<String, SignatureType>()
        var interfaceArguments: Map<String, List<SignatureType>> = emptyMap()
        var bytes: ByteArray? = classBytes
        var level = 0
        while (bytes != null && level <= MAX_ENCLOSING_SCOPES) {
            val shape = readScopeShape(bytes) ?: break
            val classSignature = shape.signature?.let { parseClassSignature(it) }
            if (level == 0) interfaceArguments = classSignature?.interfaceArguments ?: emptyMap()
            classSignature?.formals?.forEach { (name, bound) -> bounds.putIfAbsent(name, bound) }
            val enclosing = shape.enclosingOwner ?: shape.outer
            val enclosingBytes =
                enclosing?.let {
                    try {
                        lookup(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            val methodName = shape.enclosingMethodName
            val methodDescriptor = shape.enclosingMethodDescriptor
            if (enclosingBytes != null && methodName != null && methodDescriptor != null) {
                readMethodSignature(enclosingBytes, methodName, methodDescriptor)
                    ?.let { parseMethodSignature(it) }
                    ?.formals
                    ?.forEach { (name, bound) -> bounds.putIfAbsent(name, bound) }
            }
            bytes = enclosingBytes
            level++
        }
        return TypeScope(interfaceArguments, bounds)
    }

    private class ScopeShape(
        val signature: String?,
        val enclosingOwner: String?,
        val enclosingMethodName: String?,
        val enclosingMethodDescriptor: String?,
        val outer: String?,
    )

    private fun readScopeShape(bytes: ByteArray): ScopeShape? {
        var self = ""
        var signature: String? = null
        var enclosingOwner: String? = null
        var enclosingMethodName: String? = null
        var enclosingMethodDescriptor: String? = null
        var outer: String? = null
        try {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        classSignature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        self = name
                        signature = classSignature
                    }

                    override fun visitOuterClass(
                        owner: String,
                        name: String?,
                        descriptor: String?,
                    ) {
                        enclosingOwner = owner
                        enclosingMethodName = name
                        enclosingMethodDescriptor = descriptor
                    }

                    override fun visitInnerClass(
                        name: String,
                        outerName: String?,
                        innerName: String?,
                        access: Int,
                    ) {
                        if (name == self && outerName != null && access and Opcodes.ACC_STATIC == 0) outer = outerName
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        } catch (_: Exception) {
            return null
        }
        return ScopeShape(signature, enclosingOwner, enclosingMethodName, enclosingMethodDescriptor, outer)
    }

    private fun readMethodSignature(
        bytes: ByteArray,
        name: String,
        descriptor: String,
    ): String? {
        var found: String? = null
        try {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        methodName: String,
                        methodDescriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (methodName == name && methodDescriptor == descriptor) found = signature
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        } catch (_: Exception) {
            return null
        }
        return found
    }

    private class ClassSignature(
        val formals: Map<String, SignatureType>,
        val interfaceArguments: Map<String, List<SignatureType>>,
    )

    private class MethodSignature(
        val formals: Map<String, SignatureType>,
        val parameters: List<SignatureType>,
        val returnType: SignatureType,
    )

    private open class FormalsVisitor : SignatureVisitor(Opcodes.ASM9) {
        val formals = LinkedHashMap<String, SignatureType>()
        private var current: String? = null

        override fun visitFormalTypeParameter(name: String) {
            current = name
        }

        override fun visitClassBound(): SignatureVisitor = bound()

        override fun visitInterfaceBound(): SignatureVisitor = bound()

        private fun bound(): SignatureVisitor {
            val name = current ?: return SignatureType()
            if (name in formals) return SignatureType()
            return SignatureType().also { formals[name] = it }
        }
    }

    private fun parseClassSignature(signature: String): ClassSignature? {
        val interfaces = mutableListOf<SignatureType>()
        val visitor =
            object : FormalsVisitor() {
                override fun visitSuperclass(): SignatureVisitor = SignatureType()

                override fun visitInterface(): SignatureVisitor = SignatureType().also { interfaces += it }
            }
        try {
            SignatureReader(signature).accept(visitor)
        } catch (_: Exception) {
            return null
        }
        val arguments = interfaces.mapNotNull { type -> type.className?.let { it to type.arguments.toList() } }.toMap()
        return ClassSignature(visitor.formals, arguments)
    }

    private fun parseMethodSignature(signature: String): MethodSignature? {
        val parameters = mutableListOf<SignatureType>()
        var returnType: SignatureType? = null
        val visitor =
            object : FormalsVisitor() {
                override fun visitParameterType(): SignatureVisitor = SignatureType().also { parameters += it }

                override fun visitReturnType(): SignatureVisitor = SignatureType().also { returnType = it }

                override fun visitExceptionType(): SignatureVisitor = SignatureType()
            }
        try {
            SignatureReader(signature).accept(visitor)
        } catch (_: Exception) {
            return null
        }
        return MethodSignature(visitor.formals, parameters, returnType ?: return null)
    }

    private class SignatureType : SignatureVisitor(Opcodes.ASM9) {
        private var base: Char? = null
        var className: String? = null
            private set
        var variable: String? = null
            private set
        private var element: SignatureType? = null
        val arguments = mutableListOf<SignatureType>()

        override fun visitBaseType(descriptor: Char) {
            base = descriptor
        }

        override fun visitTypeVariable(name: String) {
            variable = name
        }

        override fun visitArrayType(): SignatureVisitor = SignatureType().also { element = it }

        override fun visitClassType(name: String) {
            className = name
        }

        override fun visitInnerClassType(name: String) {
            className = className + "$" + name
            arguments.clear()
        }

        override fun visitTypeArgument() {
            arguments += SignatureType().also { it.visitClassType("java/lang/Object") }
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = SignatureType().also { arguments += it }

        fun erasure(variableErasure: (String) -> String): String {
            base?.let { return it.toString() }
            className?.let { return "L$it;" }
            element?.let { return "[" + it.erasure(variableErasure) }
            return variable?.let(variableErasure) ?: OBJECT_DESCRIPTOR
        }
    }

    private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"

    private const val CONTINUATION_RETURNS_OBJECT = "/coroutines/Continuation;)Ljava/lang/Object;"

    private const val COROUTINE_INTRINSICS_SUFFIX = "/coroutines/intrinsics/IntrinsicsKt"

    private const val UNIT_SUFFIX = "/Unit"

    private fun javacRecordMembers(classBytes: ByteArray): Set<Pair<String, String>> {
        val members = mutableSetOf<Pair<String, String>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val returnOpcode =
                        when {
                            name == "equals" && descriptor == "(Ljava/lang/Object;)Z" -> Opcodes.IRETURN
                            name == "hashCode" && descriptor == "()I" -> Opcodes.IRETURN
                            name == "toString" && descriptor == "()Ljava/lang/String;" -> Opcodes.ARETURN
                            else -> return null
                        }
                    val expected =
                        buildList {
                            add(RecordStep.Load(0))
                            if (name == "equals") add(RecordStep.Load(1))
                            add(RecordStep.ObjectMethodsIndy)
                            add(RecordStep.Return(returnOpcode))
                        }
                    return RecordBodyVisitor(expected) { members += name to descriptor }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return members
    }

    private sealed interface RecordStep {
        data class Load(
            val slot: Int,
        ) : RecordStep

        data object ObjectMethodsIndy : RecordStep

        data class Return(
            val opcode: Int,
        ) : RecordStep

        data object Other : RecordStep
    }

    private class RecordBodyVisitor(
        private val expected: List<RecordStep>,
        private val onMatch: () -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val steps = mutableListOf<RecordStep>()

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            steps += if (opcode == Opcodes.ALOAD) RecordStep.Load(varIndex) else RecordStep.Other
        }

        override fun visitInsn(opcode: Int) {
            steps += if (opcode in Opcodes.IRETURN..Opcodes.RETURN) RecordStep.Return(opcode) else RecordStep.Other
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            val isObjectMethods =
                bootstrapMethodHandle.owner == OBJECT_METHODS_INTERNAL_NAME && bootstrapMethodHandle.name == "bootstrap"
            steps += if (isObjectMethods) RecordStep.ObjectMethodsIndy else RecordStep.Other
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            steps += RecordStep.Other
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            steps += RecordStep.Other
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            steps += RecordStep.Other
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            steps += RecordStep.Other
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            steps += RecordStep.Other
        }

        override fun visitLdcInsn(value: Any?) {
            steps += RecordStep.Other
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            steps += RecordStep.Other
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            steps += RecordStep.Other
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            steps += RecordStep.Other
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            steps += RecordStep.Other
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            steps += RecordStep.Other
        }

        override fun visitEnd() {
            if (steps == expected) onMatch()
        }
    }

    private fun multifileFacadeForwarders(
        classBytes: ByteArray,
        internalClassName: String,
    ): Set<Pair<String, String>> {
        val ownPackage = internalClassName.substringBeforeLast('/', "")
        val forwarders = mutableSetOf<Pair<String, String>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and (BODYLESS_FLAGS or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
                    if (access and Opcodes.ACC_STATIC == 0 || name == "<clinit>") return null
                    return ForwarderShapeVisitor(
                        argumentLoads(descriptor, isStatic = true),
                        allowNullChecks = true,
                        isForwardingCall = { opcode, owner, calledName, calledDescriptor ->
                            opcode == Opcodes.INVOKESTATIC &&
                                owner != internalClassName &&
                                owner.substringBeforeLast('/', "") == ownPackage &&
                                calledName == name &&
                                calledDescriptor == descriptor
                        },
                    ) { forwarders += name to descriptor }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return forwarders
    }

    private fun argumentLoads(
        descriptor: String,
        isStatic: Boolean,
    ): List<Pair<Int, Int>> {
        val loads = mutableListOf<Pair<Int, Int>>()
        var slot = if (isStatic) 0 else 1
        for (type in parseParameterDescriptors(descriptor)) {
            loads += loadOpcodeFor(type) to slot
            slot += slotWidth(type)
        }
        return loads
    }

    private fun loadOpcodeFor(type: String): Int =
        when (type[0]) {
            'J' -> Opcodes.LLOAD
            'F' -> Opcodes.FLOAD
            'D' -> Opcodes.DLOAD
            'L', '[' -> Opcodes.ALOAD
            else -> Opcodes.ILOAD
        }

    private class ForwarderShapeVisitor(
        private val expectedLoads: List<Pair<Int, Int>>,
        private val allowNullChecks: Boolean,
        private val isForwardingCall: (opcode: Int, owner: String, name: String, descriptor: String) -> Boolean,
        private val returnCast: String? = null,
        private val allowBoxing: Boolean = false,
        private val allowReceiverCast: Boolean = false,
        private val isValueClass: (String) -> Boolean = { false },
        private val allowDroppedResult: Boolean = false,
        private val allowSuspendTail: Boolean = false,
        private val allowArgumentCasts: Boolean = false,
        private val onForwarder: (ForwarderMatch) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private var cast = false
        private val argumentCasts = mutableMapOf<Int, String>()
        private var castable = -1
        private var guardStage = 0
        private var guardIsResult = false
        private var guardPosition = -1
        private var guardNull: Label? = null
        private var guardEnd: Label? = null
        private var pendingUnbox: String? = null
        private var returnUnboxed: String? = null
        private var dropped = false
        private var tailStage = 0
        private var tailLabel: Label? = null
        private var receiverCast: String? = null
        private var returnBoxed: String? = null
        private var calledOwner = ""
        private var calledName = ""
        private var calledDescriptor = ""
        private val boxed = mutableMapOf<Int, String>()
        private val boxedInto = mutableMapOf<Int, String>()
        private var unboxedFrom: String? = null
        private val referenceSlots = expectedLoads.filter { it.first == Opcodes.ALOAD }.mapTo(mutableSetOf()) { it.second }
        private val pushed = mutableListOf<Pushed>()
        private var invoked = false
        private var returned = false
        private var broken = false

        private fun reject() {
            broken = true
        }

        private fun takeCastable(): Int = castable.also { castable = -1 }

        private fun guardCall(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            if (guardStage != 2) return reject()
            if (guardIsResult) {
                val unboxType = pendingUnbox
                val reference = descriptor.startsWith("()L") || descriptor.startsWith("()[")
                val unboxes =
                    unboxType != null && opcode == Opcodes.INVOKEVIRTUAL && owner == unboxType && name == VALUE_CLASS_UNBOX && reference
                if (!unboxes) return reject()
                returnUnboxed = descriptor.substring(2)
                unboxedFrom = unboxType
                pendingUnbox = null
            } else {
                val underlying = valueClassBoxedType(isValueClass, opcode, owner, name, descriptor)
                val load = pushed.lastOrNull() as? Pushed.Load
                val atGuard = load != null && load.opcode == Opcodes.ALOAD && pushed.size - 1 == guardPosition && guardPosition !in boxed
                if (underlying == null || !(underlying.startsWith("L") || underlying.startsWith("[")) || !atGuard) return reject()
                boxed[guardPosition] = underlying
                boxedInto[guardPosition] = owner
            }
            guardStage = 3
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            castable = -1
            if (guardStage != 0 || invoked || opcode !in Opcodes.ILOAD..Opcodes.ALOAD) return reject()
            pushed += Pushed.Load(opcode, varIndex)
            if (opcode == Opcodes.ALOAD) castable = pushed.size - 1
        }

        override fun visitLdcInsn(value: Any?) {
            castable = -1
            if (guardStage != 0 || invoked || !allowNullChecks || value !is String) return reject()
            pushed += Pushed.StringConstant
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            castable = -1
            if (guardStage != 0) return guardCall(opcode, owner, name, descriptor)
            if (tailStage != 0) {
                val checksSuspended =
                    tailStage == 1 &&
                        opcode == Opcodes.INVOKESTATIC &&
                        owner.endsWith(COROUTINE_INTRINSICS_SUFFIX) &&
                        name == "getCOROUTINE_SUSPENDED" &&
                        descriptor == "()$OBJECT_DESCRIPTOR"
                if (!checksSuspended) return reject()
                tailStage = 2
                return
            }
            if (invoked) {
                val unboxType = pendingUnbox
                if (unboxType != null) {
                    val unboxes =
                        opcode == Opcodes.INVOKEVIRTUAL && owner == unboxType && name == VALUE_CLASS_UNBOX && descriptor.startsWith("()")
                    if (!unboxes) return reject()
                    returnUnboxed = descriptor.substring(2)
                    unboxedFrom = unboxType
                    pendingUnbox = null
                    return
                }
                val primitive =
                    if (allowBoxing && !returned && !cast && !dropped &&
                        returnBoxed == null && returnUnboxed == null
                    ) {
                        boxedPrimitive(opcode, owner, name, descriptor)
                    } else {
                        null
                    }
                if (primitive == null) return reject()
                returnBoxed = primitive
                return
            }
            if (allowNullChecks && opcode == Opcodes.INVOKESTATIC && owner.endsWith(INTRINSICS_SUFFIX) && name in parameterNullCheckNames) {
                val checked = pushed.getOrNull(0) as? Pushed.Load
                val checksOwnReference = checked != null && checked.opcode == Opcodes.ALOAD && checked.slot in referenceSlots
                if (pushed.size != 2 || !checksOwnReference || pushed[1] != Pushed.StringConstant) return reject()
                pushed.clear()
                return
            }
            val boxing =
                if (allowBoxing) {
                    boxedPrimitive(opcode, owner, name, descriptor) ?: valueClassBoxedType(isValueClass, opcode, owner, name, descriptor)
                } else {
                    null
                }
            if (boxing != null) {
                val load = pushed.lastOrNull() as? Pushed.Load
                if (load == null || load.opcode != loadOpcodeFor(boxing)) return reject()
                if (boxed.put(pushed.size - 1, boxing) != null) return reject()
                boxedInto[pushed.size - 1] = if (name == VALUE_CLASS_BOX) owner else BOX_TYPES.getValue(boxing)
                if (name != VALUE_CLASS_BOX) castable = pushed.size - 1
                return
            }
            if (!isForwardingCall(opcode, owner, name, descriptor)) return reject()
            if (pushed != expectedLoads.map { (loadOpcode, slot) -> Pushed.Load(loadOpcode, slot) }) return reject()
            calledOwner = owner
            calledName = name
            calledDescriptor = descriptor
            invoked = true
        }

        override fun visitInsn(opcode: Int) {
            val castFrom = takeCastable()
            if (guardStage != 0) {
                when {
                    guardStage == 5 && opcode == Opcodes.POP -> guardStage = 6
                    guardStage == 6 && opcode == Opcodes.ACONST_NULL -> guardStage = 7
                    else -> reject()
                }
                return
            }
            if (allowBoxing && opcode == Opcodes.DUP && guardStage == 0) {
                if (!invoked && castFrom >= 1) {
                    guardStage = 1
                    guardIsResult = false
                    guardPosition = castFrom
                    return
                }
                if (invoked && !returned && pendingUnbox != null && tailStage == 0) {
                    guardStage = 1
                    guardIsResult = true
                    return
                }
            }
            if (tailStage != 0) {
                when {
                    tailStage == 3 && opcode == Opcodes.ARETURN -> {
                        tailStage = 4
                    }

                    tailStage == 5 && opcode == Opcodes.POP -> {
                        tailStage = 6
                    }

                    tailStage == 7 && opcode == Opcodes.ARETURN -> {
                        tailStage = 8
                        returned = true
                    }

                    else -> {
                        reject()
                    }
                }
                return
            }
            val plain = !returned && !cast && !dropped && returnBoxed == null && returnUnboxed == null && pendingUnbox == null
            if (invoked && plain && allowDroppedResult && opcode == Opcodes.POP) {
                dropped = true
                return
            }
            if (invoked && plain && allowSuspendTail && opcode == Opcodes.DUP) {
                tailStage = 1
                return
            }
            if (!invoked || returned || opcode !in Opcodes.IRETURN..Opcodes.RETURN || pendingUnbox != null) return reject()
            if (dropped && opcode != Opcodes.RETURN) return reject()
            returned = true
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = reject()

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            val castFrom = takeCastable()
            if (tailStage != 0 || guardStage != 0) return reject()
            if (allowReceiverCast && opcode == Opcodes.CHECKCAST && !invoked && pushed.size == 1 && receiverCast == null) {
                receiverCast = type
                return
            }
            if (allowArgumentCasts && opcode == Opcodes.CHECKCAST && !invoked && castFrom >= 1 && castFrom !in argumentCasts) {
                argumentCasts[castFrom] = type
                return
            }
            val castsToValueClass =
                allowBoxing && opcode == Opcodes.CHECKCAST && invoked && !returned && !cast && !dropped && returnBoxed == null &&
                    returnUnboxed == null && pendingUnbox == null && type != returnCast && isValueClass(type)
            if (castsToValueClass) {
                pendingUnbox = type
                return
            }
            if (opcode != Opcodes.CHECKCAST || type != returnCast || !invoked || returned || cast) return reject()
            cast = true
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            castable = -1
            val readsUnit =
                tailStage == 6 &&
                    opcode == Opcodes.GETSTATIC &&
                    owner.endsWith(UNIT_SUFFIX) &&
                    name == "INSTANCE" &&
                    descriptor == "L$owner;"
            if (!readsUnit) return reject()
            tailStage = 7
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) = reject()

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            castable = -1
            if (guardStage == 1 && opcode == Opcodes.IFNULL) {
                guardNull = label
                guardStage = 2
                return
            }
            if (guardStage == 3 && opcode == Opcodes.GOTO) {
                guardEnd = label
                guardStage = 4
                return
            }
            if (guardStage != 0 || tailStage != 2 || opcode != Opcodes.IF_ACMPNE) return reject()
            tailLabel = label
            tailStage = 3
        }

        override fun visitLabel(label: Label) {
            if (guardStage == 4 && label == guardNull) guardStage = 5
            if (guardStage == 7 && label == guardEnd) guardStage = 0
            if (tailStage == 4 && label == tailLabel) tailStage = 5
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = reject()

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = reject()

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = reject()

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) = reject()

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) = reject()

        override fun visitEnd() {
            if (!broken && invoked && guardStage == 0 &&
                returned && pendingUnbox == null && (tailStage == 0 || tailStage == 8)
            ) {
                onForwarder(
                    ForwarderMatch(
                        calledOwner,
                        calledDescriptor,
                        cast,
                        boxed,
                        receiverCast,
                        returnBoxed,
                        returnUnboxed,
                        dropped,
                        tailStage == 8,
                        boxedInto,
                        unboxedFrom,
                        calledName,
                        argumentCasts,
                    ),
                )
            }
        }
    }

    private fun isEnumEntriesAccessor(
        descriptor: String,
        access: Int,
    ): Boolean =
        access and Opcodes.ACC_STATIC != 0 && descriptor.startsWith("()L") && descriptor.endsWith(ENUM_ENTRIES_DESCRIPTOR_SUFFIX)

    private fun markDataClassMembers(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
        result: MutableMap<Pair<String, String>, GeneratedBy>,
    ) {
        val components =
            methodAccess.keys
                .mapNotNull { key ->
                    val (name, descriptor) = key
                    val match = dataClassComponentPattern.matchEntire(name) ?: return@mapNotNull null
                    if (parseParameterDescriptors(descriptor).isNotEmpty()) return@mapNotNull null
                    val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    index to (key to returnTypeOf(descriptor))
                }.toMap()
        if (components.isEmpty()) return
        val componentCount = components.keys.max()
        if ((1..componentCount).any { it !in components }) return
        val componentTypes = (1..componentCount).map { components.getValue(it).second }

        val hasMatchingConstructor =
            methodAccess.keys.any { (name, descriptor) ->
                name == "<init>" && parseParameterDescriptors(descriptor) == componentTypes
            }
        if (!hasMatchingConstructor) return

        val ownerDescriptor = "L$internalClassName;"
        val copyKey =
            methodAccess.keys.firstOrNull { (name, descriptor) ->
                dataClassCopyPattern.matches(name) &&
                    returnTypeOf(descriptor) == ownerDescriptor &&
                    parseParameterDescriptors(descriptor) == componentTypes
            } ?: return

        val objectMethodKeys =
            listOf("equals" to "(Ljava/lang/Object;)Z", "hashCode" to "()I", "toString" to "()Ljava/lang/String;")
        if (objectMethodKeys.any { it !in methodAccess }) return

        val members = (1..componentCount).map { components.getValue(it).first } + copyKey + objectMethodKeys
        for (key in members) {
            if (key !in methodsWithLineNumbers) result[key] = GeneratedBy.DATA_CLASS
        }
    }

    private fun dataObjectMembers(
        classBytes: ByteArray,
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
    ): Set<Pair<String, String>> {
        val keys = listOf(TO_STRING_KEY, HASH_CODE_KEY, EQUALS_KEY)
        val plain = Opcodes.ACC_PUBLIC
        val excluded = Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE or BODYLESS_FLAGS
        val shaped =
            keys.all { key ->
                val access = methodAccess[key] ?: return@all false
                access and plain != 0 && access and excluded == 0 && key !in methodsWithLineNumbers
            }
        if (!shaped) return emptySet()

        var hasInstance = false
        val bodies = mutableMapOf<Pair<String, String>, List<String>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    if (name == "INSTANCE" && access and Opcodes.ACC_STATIC != 0 && descriptor == "L$internalClassName;") hasInstance = true
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val key = name to descriptor
                    return if (key in keys) ExactBodyVisitor(onBody = { bodies[key] = it }) else null
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        if (!hasInstance) return emptySet()

        val simpleName = internalClassName.substringAfterLast('/').substringAfterLast('$')
        val toStringOk = bodies[TO_STRING_KEY] == listOf("ldc:$simpleName", "insn:${Opcodes.ARETURN}")
        val hashCodeBody = bodies[HASH_CODE_KEY]
        val hashCodeOk =
            hashCodeBody != null &&
                hashCodeBody.size == 2 &&
                (hashCodeBody[0] == "int" || hashCodeBody[0] in SMALL_INT_PUSHES) &&
                hashCodeBody[1] == "insn:${Opcodes.IRETURN}"
        val equalsOk =
            bodies[EQUALS_KEY] ==
                listOf(
                    "var:${Opcodes.ALOAD}:0",
                    "var:${Opcodes.ALOAD}:1",
                    "jump:${Opcodes.IF_ACMPNE}:5",
                    "insn:${Opcodes.ICONST_1}",
                    "insn:${Opcodes.IRETURN}",
                    "var:${Opcodes.ALOAD}:1",
                    "type:${Opcodes.INSTANCEOF}:$internalClassName",
                    "jump:${Opcodes.IFNE}:10",
                    "insn:${Opcodes.ICONST_0}",
                    "insn:${Opcodes.IRETURN}",
                    "var:${Opcodes.ALOAD}:1",
                    "type:${Opcodes.CHECKCAST}:$internalClassName",
                    "insn:${Opcodes.POP}",
                    "insn:${Opcodes.ICONST_1}",
                    "insn:${Opcodes.IRETURN}",
                )
        return if (toStringOk && hashCodeOk && equalsOk) keys.toSet() else emptySet()
    }

    private fun kotlinProxies(
        classBytes: ByteArray,
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
        interfaceInternalNames: List<String>,
        kotlinKind: KotlinKind,
    ): Map<Pair<String, String>, GeneratedBy> {
        val delegates = interfaceInternalNames.isNotEmpty()
        val companions = kotlinKind == KotlinKind.KOTLIN_CLASS
        val unwanted = Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE or BODYLESS_FLAGS
        val candidates =
            methodAccess
                .filter { (key, access) ->
                    key.first != "<init>" &&
                        key.first != "<clinit>" &&
                        access and Opcodes.ACC_PUBLIC != 0 &&
                        access and unwanted == 0 &&
                        if (access and Opcodes.ACC_STATIC != 0) companions else delegates && key !in methodsWithLineNumbers
                }.mapValues { (_, access) -> access and Opcodes.ACC_STATIC != 0 }
        if (candidates.isEmpty()) return emptyMap()

        val fields = mutableMapOf<String, Pair<String, Int>>()
        val bodies = mutableMapOf<Pair<String, String>, List<String>>()
        val lines = mutableMapOf<Pair<String, String>, List<Int>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    fields[name] = descriptor to access
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val key = name to descriptor
                    return if (key in candidates) ExactBodyVisitor({ lines[key] = it }) { bodies[key] = it } else null
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)

        val hasLineNumbers = methodsWithLineNumbers.any { it.first == "<init>" }
        val result = mutableMapOf<Pair<String, String>, GeneratedBy>()
        for ((key, body) in bodies) {
            val isStatic = candidates.getValue(key)
            val matches =
                if (isStatic) {
                    lines[key].orEmpty().all { it == body.size - 1 } && isCompanionProxy(internalClassName, key, body, fields)
                } else {
                    isDelegationForwarder(internalClassName, key, body, fields, interfaceInternalNames, hasLineNumbers)
                }
            if (matches) result[key] = if (isStatic) GeneratedBy.STATIC_FORWARDER else GeneratedBy.CLASS_DELEGATION
        }
        return result
    }

    private val DELEGATE_FIELD = Regex("\\\$\\\$delegate_\\d+")

    private fun isDelegationForwarder(
        internalClassName: String,
        key: Pair<String, String>,
        body: List<String>,
        fields: Map<String, Pair<String, Int>>,
        interfaceInternalNames: List<String>,
        hasLineNumbers: Boolean,
    ): Boolean {
        val (name, descriptor) = key
        val parameters = parseParameterDescriptors(descriptor)
        val slots = IntArray(parameters.size)
        var slot = 1
        parameters.forEachIndexed { index, type ->
            slots[index] = slot
            slot += slotWidth(type)
        }

        var position = 0
        var lastChecked = -1
        while (
            body.getOrNull(position)?.startsWith("var:${Opcodes.ALOAD}:") == true &&
            body.getOrNull(position + 1)?.startsWith("ldc:") == true
        ) {
            val checkedSlot = body[position].substringAfterLast(':').toInt()
            val index = slots.indexOf(checkedSlot)
            if (index <= lastChecked || parameters[index][0] != 'L' && parameters[index][0] != '[') return false
            if (!isParameterNullCheck(body.getOrNull(position + 2))) return false
            lastChecked = index
            position += 3
        }

        if (body.getOrNull(position) != "var:${Opcodes.ALOAD}:0") return false
        val fieldToken = body.getOrNull(position + 1)?.removePrefix("field:${Opcodes.GETFIELD}:$internalClassName.") ?: return false
        if (fieldToken == body[position + 1]) return false
        val fieldName = fieldToken.substringBefore(':')
        val fieldDescriptor = fieldToken.substringAfter(':')
        val (declaredDescriptor, access) = fields[fieldName] ?: return false
        val delegateAccess = Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL
        if (declaredDescriptor != fieldDescriptor) return false
        if (access and delegateAccess != delegateAccess || access and Opcodes.ACC_STATIC != 0) return false
        if (!fieldDescriptor.startsWith("L") || !fieldDescriptor.endsWith(";")) return false
        val delegateType = fieldDescriptor.substring(1, fieldDescriptor.length - 1)
        if (!DELEGATE_FIELD.matches(fieldName) && !(hasLineNumbers && delegateType in interfaceInternalNames)) return false

        position += 2
        parameters.forEachIndexed { index, type ->
            if (body.getOrNull(position++) != "var:${loadOpcodeFor(type)}:${slots[index]}") return false
        }
        val call = body.getOrNull(position++) ?: return false
        val callTarget = "$delegateType.$name$descriptor"
        if (call != "call:${Opcodes.INVOKEINTERFACE}:$callTarget" && call != "call:${Opcodes.INVOKEVIRTUAL}:$callTarget") return false
        return body.getOrNull(position++) == "insn:${returnOpcodeFor(returnTypeOf(descriptor))}" && position == body.size
    }

    private fun isCompanionProxy(
        internalClassName: String,
        key: Pair<String, String>,
        body: List<String>,
        fields: Map<String, Pair<String, Int>>,
    ): Boolean {
        val (name, descriptor) = key
        val fieldToken = body.firstOrNull()?.removePrefix("field:${Opcodes.GETSTATIC}:$internalClassName.") ?: return false
        if (fieldToken == body[0]) return false
        val fieldName = fieldToken.substringBefore(':')
        val companion = "$internalClassName\$$fieldName"
        val (declaredDescriptor, access) = fields[fieldName] ?: return false
        val staticFinal = Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
        if (fieldToken.substringAfter(':') != "L$companion;" || declaredDescriptor != "L$companion;") return false
        if (access and staticFinal != staticFinal) return false

        var position = 1
        var slot = 0
        for (type in parseParameterDescriptors(descriptor)) {
            if (body.getOrNull(position++) != "var:${loadOpcodeFor(type)}:$slot") return false
            slot += slotWidth(type)
        }
        if (body.getOrNull(position++) != "call:${Opcodes.INVOKEVIRTUAL}:$companion.$name$descriptor") return false
        return body.getOrNull(position++) == "insn:${returnOpcodeFor(returnTypeOf(descriptor))}" && position == body.size
    }

    private fun isParameterNullCheck(token: String?): Boolean {
        val prefix = "call:${Opcodes.INVOKESTATIC}:"
        val target = token?.removePrefix(prefix)?.takeIf { it != token } ?: return false
        val owner = target.substringBefore('.')
        return owner.endsWith(INTRINSICS_SUFFIX) &&
            parameterNullCheckNames.any { target == "$owner.$it(Ljava/lang/Object;Ljava/lang/String;)V" }
    }

    private fun returnOpcodeFor(type: String): Int =
        when (type[0]) {
            'V' -> Opcodes.RETURN
            'J' -> Opcodes.LRETURN
            'F' -> Opcodes.FRETURN
            'D' -> Opcodes.DRETURN
            'L', '[' -> Opcodes.ARETURN
            else -> Opcodes.IRETURN
        }

    private class ExactBodyVisitor(
        private val onLines: (List<Int>) -> Unit = {},
        private val onBody: (List<String>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val tokens = mutableListOf<String>()
        private val labelIndex = mutableMapOf<Label, Int>()
        private val targets = mutableMapOf<Int, Label>()
        private val lineStarts = mutableListOf<Int>()

        override fun visitLineNumber(
            line: Int,
            start: Label,
        ) {
            lineStarts += labelIndex[start] ?: tokens.size
        }

        override fun visitLabel(label: Label) {
            labelIndex[label] = tokens.size
        }

        override fun visitInsn(opcode: Int) {
            tokens += "insn:$opcode"
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            tokens += if (opcode == Opcodes.NEWARRAY) "other" else "int"
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            tokens += "var:$opcode:$varIndex"
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            tokens += "type:$opcode:$type"
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            tokens += "field:$opcode:$owner.$name:$descriptor"
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            tokens += "call:$opcode:$owner.$name$descriptor"
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            targets[tokens.size] = label
            tokens += "jump:$opcode:"
        }

        override fun visitLdcInsn(value: Any?) {
            tokens += if (value is String) "ldc:$value" else if (value is Int) "int" else "other"
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            tokens += "other"
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            tokens += "other"
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            tokens += "other"
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            tokens += "other"
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            tokens += "other"
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            tokens += "other"
        }

        override fun visitEnd() {
            for ((position, label) in targets) {
                tokens[position] += labelIndex[label]?.toString() ?: "end"
            }
            onLines(lineStarts)
            onBody(tokens)
        }
    }

    private fun isMangleHash(text: String): Boolean =
        text.length == MANGLE_HASH_LENGTH && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun isMangledForm(
        name: String,
        plain: String,
    ): Boolean =
        name.length == plain.length + 1 + MANGLE_HASH_LENGTH &&
            name.startsWith(plain) &&
            name[plain.length] == '-' &&
            isMangleHash(name.substring(plain.length + 1))

    private fun namesTheCall(
        name: String,
        calledName: String,
    ): Boolean = name == calledName || isMangledForm(name, calledName)

    private fun mangledAlike(
        name: String,
        calledName: String,
    ): Boolean = isMangled(name) && isMangled(calledName) && name.dropLast(MANGLE_HASH_LENGTH) == calledName.dropLast(MANGLE_HASH_LENGTH)

    private fun isMangled(name: String): Boolean =
        name.length > MANGLE_HASH_LENGTH + 1 && name[name.length - MANGLE_HASH_LENGTH - 1] == '-' &&
            isMangleHash(name.substring(name.length - MANGLE_HASH_LENGTH))

    private const val MANGLE_HASH_LENGTH = 7
    private const val VALUE_CLASS_BOX = "box-impl"
    private const val VALUE_CLASS_UNBOX = "unbox-impl"
    private const val IMPL_SUFFIX = "-impl"
    private const val VALUE_CLASS_CONSTRUCTOR = "constructor-impl"
    private val VALUE_CLASS_STATICS = setOf("equals-impl", "equals-impl0", "hashCode-impl", "toString-impl")
    private val VALUE_CLASS_OBJECT_STATICS = setOf("equals-impl", "hashCode-impl", "toString-impl")

    private fun isValueClassShape(
        internalName: String,
        methodAccess: Map<Pair<String, String>, Int>,
    ): Boolean {
        val synthetic = Opcodes.ACC_SYNTHETIC
        val boxes =
            methodAccess.any { (key, access) ->
                key.first == VALUE_CLASS_BOX &&
                    access and (synthetic or Opcodes.ACC_STATIC) == (synthetic or Opcodes.ACC_STATIC) &&
                    returnTypeOf(key.second) == "L$internalName;"
            }
        val unboxes =
            methodAccess.any { (key, access) ->
                key.first == VALUE_CLASS_UNBOX && access and (synthetic or Opcodes.ACC_STATIC) == synthetic && key.second.startsWith("()")
            }
        return boxes && unboxes
    }

    private fun valueClassTest(lookup: (internalName: String) -> ByteArray?): (String) -> Boolean {
        val known = HashMap<String, Boolean>()
        return { internalName ->
            known.getOrPut(internalName) {
                val bytes =
                    try {
                        lookup(internalName)
                    } catch (_: Exception) {
                        null
                    }
                bytes != null &&
                    try {
                        val access = LinkedHashMap<Pair<String, String>, Int>()
                        ClassReader(bytes).accept(
                            object : ClassVisitor(Opcodes.ASM9) {
                                override fun visitMethod(
                                    methodAccess: Int,
                                    name: String,
                                    descriptor: String,
                                    signature: String?,
                                    exceptions: Array<out String>?,
                                ): MethodVisitor? = null.also { access[name to descriptor] = methodAccess }
                            },
                            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                        )
                        isValueClassShape(internalName, access)
                    } catch (_: Exception) {
                        false
                    }
            }
        }
    }

    private class ValueClassMarks(
        val marked: Set<Pair<String, String>>,
        val forwarders: Set<Pair<String, String>>,
    )

    private fun valueClassMarks(
        classBytes: ByteArray,
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
        interfaceInternalNames: List<String>,
        lookup: (internalName: String) -> ByteArray?,
    ): ValueClassMarks {
        val none = ValueClassMarks(emptySet(), emptySet())
        if (!isValueClassShape(internalClassName, methodAccess)) return none
        val marked =
            methodAccess
                .filter { (key, access) ->
                    key.first in VALUE_CLASS_STATICS && access and Opcodes.ACC_STATIC != 0 && key !in methodsWithLineNumbers
                }.keys
                .toMutableSet()

        val fields = mutableListOf<Pair<String, String>>()
        val bodies = mutableListOf<Triple<String, String, List<String>>>()
        val staticBodies = mutableListOf<Triple<String, String, List<BodyOp>>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? = null.also { if (access and Opcodes.ACC_STATIC == 0) fields += name to descriptor }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and (BODYLESS_FLAGS or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
                    if (name.startsWith("<")) return null
                    if (access and Opcodes.ACC_STATIC != 0) {
                        val named = name.endsWith(IMPL_SUFFIX) || isMangled(name)
                        val inherited =
                            named && access and Opcodes.ACC_FINAL == 0 && name !in VALUE_CLASS_STATICS && name != VALUE_CLASS_CONSTRUCTOR
                        return if (inherited) BodyRecorder { staticBodies += Triple(name, descriptor, it) } else null
                    }
                    return InstructionTokenVisitor { bodies += Triple(name, descriptor, it) }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val field = fields.singleOrNull() ?: return ValueClassMarks(marked, emptySet())

        val forwarders = mutableSetOf<Pair<String, String>>()
        for ((name, descriptor, tokens) in bodies) {
            val target = if (isMangled(name)) name else name + IMPL_SUFFIX
            val parameters = parseParameterDescriptors(descriptor)
            val returnType = returnTypeOf(descriptor)
            val staticDescriptor = "(" + field.second + parameters.joinToString("") + ")" + returnType
            var slot = 1
            val expected =
                buildList {
                    add("load:${Opcodes.ALOAD}:0")
                    add("field:${Opcodes.GETFIELD}:$internalClassName.${field.first}:${field.second}")
                    for (type in parameters) {
                        add("load:${loadOpcodeFor(type)}:$slot")
                        slot += slotWidth(type)
                    }
                    add("call:${Opcodes.INVOKESTATIC}:$internalClassName.$target$staticDescriptor")
                    add("return:${Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)}")
                }
            val staticKey = target to staticDescriptor
            val forwardsToAdopter = target in VALUE_CLASS_OBJECT_STATICS && staticKey !in marked
            if (tokens == expected && !forwardsToAdopter) forwarders += name to descriptor
        }

        val ownStaticCall = "call:${Opcodes.INVOKESTATIC}:$internalClassName."
        val calledStatics =
            bodies
                .flatMap { (_, _, tokens) -> tokens }
                .filter { it.startsWith(ownStaticCall) }
                .mapTo(mutableSetOf()) { it.removePrefix(ownStaticCall) }
        val isValueClass = valueClassTest(lookup)
        val scope by lazy { scopeOf(classBytes, lookup) }
        for ((name, descriptor, ops) in staticBodies) {
            if (name + descriptor in calledStatics) continue
            val accepted =
                forwardsToInheritedDefault(
                    internalClassName,
                    field.second,
                    name,
                    descriptor,
                    ops,
                    isValueClass,
                ) { member, calledDescriptor ->
                    inheritedMethodTypes(scope, interfaceInternalNames, member, calledDescriptor, lookup)
                }
            if (accepted) forwarders += name to descriptor
        }
        return ValueClassMarks(marked + forwarders, forwarders)
    }

    private sealed interface BodyOp {
        data class Load(
            val opcode: Int,
            val slot: Int,
        ) : BodyOp

        data class StringConstant(
            val value: Any?,
        ) : BodyOp

        data class Call(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : BodyOp

        data class TypeOp(
            val opcode: Int,
            val type: String,
        ) : BodyOp

        data class Jump(
            val opcode: Int,
            val target: Label,
        ) : BodyOp

        data class Mark(
            val label: Label,
        ) : BodyOp

        data class Simple(
            val opcode: Int,
        ) : BodyOp

        data object Other : BodyOp
    }

    private class BodyRecorder(
        private val onBody: (List<BodyOp>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val ops = mutableListOf<BodyOp>()

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            ops += if (opcode in Opcodes.ILOAD..Opcodes.ALOAD) BodyOp.Load(opcode, varIndex) else BodyOp.Other
        }

        override fun visitLdcInsn(value: Any?) {
            ops += BodyOp.StringConstant(value)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            ops += BodyOp.Call(opcode, owner, name, descriptor)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            ops += BodyOp.TypeOp(opcode, type)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            ops += BodyOp.Jump(opcode, label)
        }

        override fun visitLabel(label: Label) {
            ops += BodyOp.Mark(label)
        }

        override fun visitInsn(opcode: Int) {
            ops += BodyOp.Simple(opcode)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            ops += BodyOp.Other
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            ops += BodyOp.Other
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            ops += BodyOp.Other
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            ops += BodyOp.Other
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            ops += BodyOp.Other
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            ops += BodyOp.Other
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            ops += BodyOp.Other
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            ops += BodyOp.Other
        }

        override fun visitEnd() {
            onBody(ops)
        }
    }

    private sealed interface Conversion {
        data object None : Conversion

        data class ValueOf(
            val primitive: String,
        ) : Conversion

        data class BoxImpl(
            val valueClass: String,
            val underlying: String,
        ) : Conversion

        data class Cast(
            val type: String,
        ) : Conversion

        data class Unbox(
            val valueClass: String,
            val underlying: String,
        ) : Conversion
    }

    private fun forwardsToInheritedDefault(
        internalClassName: String,
        underlying: String,
        name: String,
        descriptor: String,
        ops: List<BodyOp>,
        isValueClass: (String) -> Boolean,
        typesOf: (member: String, calledDescriptor: String) -> ExpectedTypes?,
    ): Boolean {
        val parameters = parseParameterDescriptors(descriptor)
        if (parameters.firstOrNull() != underlying) return false
        val slots = IntArray(parameters.size)
        var nextSlot = 0
        parameters.forEachIndexed { index, type ->
            slots[index] = nextSlot
            nextSlot += slotWidth(type)
        }
        val ownReturn = returnTypeOf(descriptor)
        var at = 0

        fun isReference(type: String) = type.startsWith("L") || type.startsWith("[")

        var lastChecked = 0
        while (ops.getOrNull(at) is BodyOp.Load && ops.getOrNull(at + 1) is BodyOp.StringConstant) {
            val load = ops[at] as BodyOp.Load
            val constant = ops[at + 1] as BodyOp.StringConstant
            val call = ops.getOrNull(at + 2) as? BodyOp.Call ?: return false
            val index = slots.indexOf(load.slot)
            val checks =
                load.opcode == Opcodes.ALOAD && index > lastChecked && isReference(parameters[index]) && constant.value is String &&
                    call.opcode == Opcodes.INVOKESTATIC && call.owner.endsWith(INTRINSICS_SUFFIX) &&
                    call.name in parameterNullCheckNames && call.descriptor == NULL_CHECK_DESCRIPTOR
            if (!checks) return false
            lastChecked = index
            at += 3
        }

        if (ops.getOrNull(at++) != BodyOp.Load(loadOpcodeFor(underlying), 0)) return false
        val selfBox = BodyOp.Call(Opcodes.INVOKESTATIC, internalClassName, VALUE_CLASS_BOX, "($underlying)L$internalClassName;")
        if (ops.getOrNull(at++) != selfBox) return false

        fun guarded(
            start: Int,
            isCall: (BodyOp.Call) -> Boolean,
        ): Boolean {
            val jump = ops.getOrNull(start + 1) as? BodyOp.Jump ?: return false
            val goto = ops.getOrNull(start + 3) as? BodyOp.Jump ?: return false
            val call = ops.getOrNull(start + 2) as? BodyOp.Call ?: return false
            return ops.getOrNull(start) == BodyOp.Simple(Opcodes.DUP) &&
                jump.opcode == Opcodes.IFNULL && isCall(call) && goto.opcode == Opcodes.GOTO &&
                ops.getOrNull(start + 4) == BodyOp.Mark(jump.target) &&
                ops.getOrNull(start + 5) == BodyOp.Simple(Opcodes.POP) &&
                ops.getOrNull(start + 6) == BodyOp.Simple(Opcodes.ACONST_NULL) &&
                ops.getOrNull(start + 7) == BodyOp.Mark(goto.target)
        }

        val conversions = mutableListOf<Conversion>()
        for (index in 1 until parameters.size) {
            val type = parameters[index]
            if (ops.getOrNull(at++) != BodyOp.Load(loadOpcodeFor(type), slots[index])) return false
            val next = ops.getOrNull(at)
            val call = next as? BodyOp.Call
            val primitive = call?.let { boxedPrimitive(it.opcode, it.owner, it.name, it.descriptor) }
            val boxedType = call?.let { valueClassBoxedType(isValueClass, it.opcode, it.owner, it.name, it.descriptor) }
            when {
                primitive != null && call.name == "valueOf" && primitive == type -> {
                    conversions += Conversion.ValueOf(primitive)
                    at += 1
                }

                boxedType != null && boxedType == type -> {
                    conversions += Conversion.BoxImpl(call.owner, boxedType)
                    at += 1
                }

                next == BodyOp.Simple(Opcodes.DUP) && isReference(type) -> {
                    var owner: String? = null
                    val shaped =
                        guarded(at) {
                            owner = it.owner
                            valueClassBoxedType(isValueClass, it.opcode, it.owner, it.name, it.descriptor) == type
                        }
                    val valueClass = owner
                    if (!shaped || valueClass == null) return false
                    conversions += Conversion.BoxImpl(valueClass, type)
                    at += 8
                }

                next is BodyOp.TypeOp && next.opcode == Opcodes.CHECKCAST -> {
                    conversions += Conversion.Cast(next.type)
                    at += 1
                }

                else -> {
                    conversions += Conversion.None
                }
            }
        }

        val invoke = ops.getOrNull(at++) as? BodyOp.Call ?: return false
        if (invoke.opcode != Opcodes.INVOKEVIRTUAL || invoke.owner != internalClassName) return false
        val member = invoke.name
        val namesStatic = name == member + IMPL_SUFFIX || (name == member && isMangled(member)) || isMangledForm(name, member)
        if (!namesStatic) return false
        val calledParameters = parseParameterDescriptors(invoke.descriptor)
        if (calledParameters.size != parameters.size - 1) return false

        val result: Conversion
        val next = ops.getOrNull(at)
        if (next is BodyOp.TypeOp && next.opcode == Opcodes.CHECKCAST) {
            val after = ops.getOrNull(at + 1)
            val unboxCall = after as? BodyOp.Call
            val plainUnbox =
                unboxCall != null && unboxCall.opcode == Opcodes.INVOKEVIRTUAL && unboxCall.owner == next.type &&
                    unboxCall.name == VALUE_CLASS_UNBOX && unboxCall.descriptor.startsWith("()")
            when {
                after is BodyOp.Simple && after.opcode in Opcodes.IRETURN..Opcodes.RETURN -> {
                    result = Conversion.Cast(next.type)
                    at += 1
                }

                plainUnbox && isValueClass(next.type) -> {
                    result = Conversion.Unbox(next.type, unboxCall.descriptor.substring(2))
                    at += 2
                }

                after == BodyOp.Simple(Opcodes.DUP) -> {
                    var unboxed: String? = null
                    val shaped =
                        guarded(at + 1) {
                            unboxed = it.descriptor.substring(2).takeIf { _ -> it.descriptor.startsWith("()") }
                            it.opcode == Opcodes.INVOKEVIRTUAL && it.owner == next.type && it.name == VALUE_CLASS_UNBOX
                        }
                    val underlyingResult = unboxed
                    if (!shaped || underlyingResult == null || !isReference(underlyingResult) || !isValueClass(next.type)) return false
                    result = Conversion.Unbox(next.type, underlyingResult)
                    at += 9
                }

                else -> {
                    return false
                }
            }
        } else {
            result = Conversion.None
        }
        val last = ops.getOrNull(at++) as? BodyOp.Simple ?: return false
        if (last.opcode != Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN) || at != ops.size) return false

        val expected = typesOf(member, invoke.descriptor) ?: return false
        if (expected.parameters.size != calledParameters.size) return false
        val calledReturn = returnTypeOf(invoke.descriptor)
        val parametersFit =
            calledParameters.indices.all { index ->
                val own = parameters[index + 1]
                val called = calledParameters[index]
                val wanted = expected.parameters[index]
                when (val conversion = conversions[index]) {
                    is Conversion.None -> {
                        own == wanted && (own == called || called == OBJECT_DESCRIPTOR)
                    }

                    is Conversion.ValueOf -> {
                        wanted == "L${BOX_TYPES.getValue(conversion.primitive)};" && wanted != called && isReference(called)
                    }

                    is Conversion.BoxImpl -> {
                        wanted == "L${conversion.valueClass};" && wanted != called && isReference(called)
                    }

                    is Conversion.Cast -> {
                        own == wanted && own != called && called != OBJECT_DESCRIPTOR &&
                            Type.getObjectType(conversion.type).descriptor == called
                    }

                    is Conversion.Unbox -> {
                        false
                    }
                }
            }
        if (!parametersFit) return false
        return when (result) {
            is Conversion.None -> {
                ownReturn == calledReturn && ownReturn == expected.returnType
            }

            is Conversion.Cast -> {
                Type.getObjectType(result.type).descriptor == ownReturn && ownReturn == expected.returnType && ownReturn != calledReturn
            }

            is Conversion.Unbox -> {
                expected.returnType == "L${result.valueClass};" && expected.returnType != calledReturn && result.underlying == ownReturn
            }

            else -> {
                false
            }
        }
    }

    private const val NULL_CHECK_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)V"

    private class InterfaceShape(
        val signature: String?,
        val interfaces: List<String>,
        val found: Boolean,
        val methodSignature: String?,
    )

    private fun readInterfaceShape(
        bytes: ByteArray,
        name: String,
        descriptor: String,
    ): InterfaceShape? {
        var signature: String? = null
        var interfaces: List<String> = emptyList()
        var found = false
        var methodSignature: String? = null
        try {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        className: String,
                        classSignature: String?,
                        superName: String?,
                        implemented: Array<out String>?,
                    ) {
                        signature = classSignature
                        interfaces = implemented?.toList() ?: emptyList()
                    }

                    override fun visitMethod(
                        access: Int,
                        methodName: String,
                        methodDescriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (methodName == name && methodDescriptor == descriptor && access and Opcodes.ACC_STATIC == 0) {
                            found = true
                            methodSignature = signature
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        } catch (_: Exception) {
            return null
        }
        return InterfaceShape(signature, interfaces, found, methodSignature)
    }

    private fun inheritedMethodTypes(
        scope: TypeScope,
        interfaces: List<String>,
        name: String,
        descriptor: String,
        lookup: (internalName: String) -> ByteArray?,
    ): ExpectedTypes? {
        fun erase(
            type: SignatureType,
            variables: Map<String, String>,
            own: Map<String, SignatureType>,
            depth: Int = 0,
        ): String =
            type.erasure { variable ->
                variables[variable]
                    ?: own[variable]?.takeIf { depth <= MAX_TYPE_VARIABLE_DEPTH }?.let { erase(it, variables, own, depth + 1) }
                    ?: OBJECT_DESCRIPTOR
            }

        fun search(
            interfaceName: String,
            arguments: List<String>,
            depth: Int,
        ): ExpectedTypes? {
            if (depth > MAX_ENCLOSING_SCOPES) return null
            val bytes =
                try {
                    lookup(interfaceName)
                } catch (_: Exception) {
                    null
                } ?: return null
            val shape = readInterfaceShape(bytes, name, descriptor) ?: return null
            val classSignature = shape.signature?.let { parseClassSignature(it) }
            val formals = classSignature?.formals?.keys?.toList() ?: emptyList()
            if (formals.size != arguments.size) return null
            val variables = formals.zip(arguments).toMap()
            if (shape.found) {
                val method = shape.methodSignature?.let { parseMethodSignature(it) }
                if (method == null) {
                    val called = Type.getMethodType(descriptor)
                    return ExpectedTypes(called.argumentTypes.map { it.descriptor }, called.returnType.descriptor)
                }
                return ExpectedTypes(
                    method.parameters.map { erase(it, variables, method.formals) },
                    erase(method.returnType, variables, method.formals),
                )
            }
            for (superName in shape.interfaces) {
                val superArguments =
                    classSignature?.interfaceArguments?.get(superName)?.map { erase(it, variables, emptyMap()) } ?: emptyList()
                search(superName, superArguments, depth + 1)?.let { return it }
            }
            return null
        }
        for (interfaceName in interfaces) {
            val arguments = scope.interfaceArguments[interfaceName]?.map { eraseIn(it, scope.bounds) } ?: emptyList()
            search(interfaceName, arguments, 0)?.let { return it }
        }
        return null
    }

    private class InstructionTokenVisitor(
        private val onBody: (List<String>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val tokens = mutableListOf<String>()

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            tokens += if (opcode in Opcodes.ILOAD..Opcodes.ALOAD) "load:$opcode:$varIndex" else "other"
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            tokens += "field:$opcode:$owner.$name:$descriptor"
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            tokens += "call:$opcode:$owner.$name$descriptor"
        }

        override fun visitInsn(opcode: Int) {
            tokens += if (opcode in Opcodes.IRETURN..Opcodes.RETURN) "return:$opcode" else "other"
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            tokens += "other"
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            tokens += "other"
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            tokens += "other"
        }

        override fun visitLdcInsn(value: Any?) {
            tokens += "other"
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            tokens += "other"
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            tokens += "other"
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            tokens += "other"
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            tokens += "other"
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            tokens += "other"
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            tokens += "other"
        }

        override fun visitEnd() {
            onBody(tokens)
        }
    }

    private fun slotWidth(type: String): Int = if (type == "J" || type == "D") 2 else 1

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    private fun resolveMaskIntCount(paramsExcludingTrailing: Int): Int {
        var maskIntCount = 1
        while (maskIntCount <= paramsExcludingTrailing) {
            val valueParams = paramsExcludingTrailing - maskIntCount
            if (valueParams >= 0 && maskIntCount == ceilDiv(maxOf(valueParams, 1), 32)) return maskIntCount
            maskIntCount++
        }
        return 1
    }

    private fun maskLocalInfo(
        name: String,
        descriptor: String,
    ): Pair<Int, Int> {
        val params = parseParameterDescriptors(descriptor)
        val paramsExcludingTrailing = params.size - 1
        val maskIntCount = resolveMaskIntCount(paramsExcludingTrailing)
        val maskStartParamIndex = paramsExcludingTrailing - maskIntCount
        val startSlot = if (name == "<init>") 1 else 0
        var slot = startSlot
        for (i in 0 until maskStartParamIndex) slot += slotWidth(params[i])
        return slot to maskIntCount
    }

    private fun lastParameterLocalIndex(
        descriptor: String,
        isStatic: Boolean,
    ): Int {
        val params = parseParameterDescriptors(descriptor)
        var slot = if (isStatic) 0 else 1
        for (i in 0 until params.size - 1) slot += slotWidth(params[i])
        return slot
    }

    private fun resolveDefaultSites(
        internalClassName: String,
        classAccess: Int,
        methodAccess: Map<Pair<String, String>, Int>,
        localNames: Map<Pair<String, String>, Map<Int, String>>,
        candidates: List<DefaultCandidate>,
    ): List<DefaultSite> {
        val classIsFinal = classAccess and Opcodes.ACC_FINAL != 0
        val ownerDescriptor = "L$internalClassName;"

        return candidates.mapNotNull { candidate ->
            val isConstructor = candidate.defaultName == "<init>"
            val targetName = if (isConstructor) "<init>" else candidate.defaultName.removeSuffix("\$default")
            val defaultParams = parseParameterDescriptors(candidate.defaultDescriptor)
            val maskIntCount = resolveMaskIntCount(defaultParams.size - 1)
            val maskStartParamIndex = defaultParams.size - 1 - maskIntCount
            val valueParams = defaultParams.subList(0, maskStartParamIndex)
            val defaultReturn = returnTypeOf(candidate.defaultDescriptor)

            val matches =
                methodAccess.entries.filter { (key, access) ->
                    val (candidateName, candidateDescriptor) = key
                    if (candidateName != targetName || candidateDescriptor == candidate.defaultDescriptor) return@filter false
                    val candidateIsStatic = access and Opcodes.ACC_STATIC != 0
                    val candidateParams = parseParameterDescriptors(candidateDescriptor)
                    if (isConstructor) {
                        returnTypeOf(candidateDescriptor) == "V" && candidateParams == valueParams
                    } else if (returnTypeOf(candidateDescriptor) != defaultReturn) {
                        false
                    } else if (candidateIsStatic) {
                        candidateParams == valueParams
                    } else {
                        valueParams.size == candidateParams.size + 1 &&
                            valueParams[0] == ownerDescriptor &&
                            valueParams.drop(1) == candidateParams
                    }
                }

            if (matches.size != 1) return@mapNotNull null
            val (targetKey, targetAccess) = matches.single()
            val targetDescriptor = targetKey.second
            val targetIsStatic = targetAccess and Opcodes.ACC_STATIC != 0
            val targetIsPrivate = targetAccess and Opcodes.ACC_PRIVATE != 0
            val targetIsFinal = targetAccess and Opcodes.ACC_FINAL != 0
            val overridable = !isConstructor && !targetIsStatic && !targetIsPrivate && !targetIsFinal && !classIsFinal

            DefaultSite(
                defaultName = candidate.defaultName,
                defaultDescriptor = candidate.defaultDescriptor,
                targetName = targetName,
                targetDescriptor = targetDescriptor,
                optionalBits = candidate.optionalBits,
                higherMaskTested = candidate.higherMaskTested,
                overridable = overridable,
                maskParameterIndex = maskStartParamIndex,
                parameterNames = parameterNamesOf(candidate, targetDescriptor, targetIsStatic, localNames[targetKey] ?: emptyMap()),
                defaultLines = candidate.fillLines,
            )
        }
    }

    private fun parameterNamesOf(
        candidate: DefaultCandidate,
        targetDescriptor: String,
        targetIsStatic: Boolean,
        targetLocalNames: Map<Int, String>,
    ): Map<Int, String> {
        val targetParams = parseParameterDescriptors(targetDescriptor)
        val startSlot = if (targetIsStatic) 0 else 1
        val firstParameterName = targetLocalNames[startSlot]
        val skipFirst = targetParams.isNotEmpty() && firstParameterName != null && firstParameterName.startsWith("\$this\$")
        val remainingParams = if (skipFirst) targetParams.drop(1) else targetParams
        var slot = if (skipFirst) startSlot + slotWidth(targetParams[0]) else startSlot

        val names = mutableMapOf<Int, String>()
        for (bit in remainingParams.indices) {
            if ((candidate.optionalBits shr bit) and 1 == 1) names[bit] = targetLocalNames[slot] ?: ""
            slot += slotWidth(remainingParams[bit])
        }
        return names
    }

    private fun resolveScalaGetterSites(
        ownInternalClassName: String,
        classAccess: Int,
        methodAccess: Map<Pair<String, String>, Int>,
        localNames: Map<Pair<String, String>, Map<Int, String>>,
        firstLines: Map<Pair<String, String>, Int>,
        candidateNames: List<Pair<String, String>>,
        lookupCompanionBytes: (String) -> ByteArray?,
    ): List<ScalaGetterSite> {
        val ownNamespace = GetterTargetNamespace(methodAccess, localNames, classAccess, targetClassName = null)
        val companionNamespaces = mutableMapOf<String, GetterTargetNamespace?>()

        fun companionNamespace(companionInternalName: String): GetterTargetNamespace? {
            if (companionInternalName in companionNamespaces) return companionNamespaces[companionInternalName]
            val namespace = readCompanionNamespace(companionInternalName, lookupCompanionBytes)
            companionNamespaces[companionInternalName] = namespace
            return namespace
        }

        return candidateNames.mapNotNull { (getterName, getterDescriptor) ->
            val match = scalaGetterPattern.matchEntire(getterName) ?: return@mapNotNull null
            val rawTargetName = match.groupValues[1]
            val parameterIndex = match.groupValues[2].toInt() - 1
            val isConstructorGetter = rawTargetName == CONSTRUCTOR_GETTER_TARGET_NAME
            val targetName = if (isConstructorGetter) "<init>" else rawTargetName

            val namespace =
                if (isConstructorGetter && ownInternalClassName.endsWith("$")) {
                    companionNamespace(ownInternalClassName.removeSuffix("$")) ?: return@mapNotNull null
                } else {
                    ownNamespace
                }

            val getterParams = parseParameterDescriptors(getterDescriptor)
            val getterReturn = returnTypeOf(getterDescriptor)

            val matches =
                namespace.methodAccess.entries.filter { (key, _) ->
                    val (candidateName, candidateDescriptor) = key
                    if (candidateName != targetName || scalaGetterPattern.matches(candidateName)) return@filter false
                    val candidateParams = parseParameterDescriptors(candidateDescriptor)
                    if (candidateParams.size <= parameterIndex) return@filter false
                    val nthParameterMatches =
                        candidateParams[parameterIndex] == getterReturn || candidateParams[parameterIndex] == "Lscala/Function0;"
                    nthParameterMatches && candidateParams.take(getterParams.size) == getterParams
                }

            if (matches.size != 1) return@mapNotNull null
            val (targetKey, targetAccess) = matches.single()
            val targetDescriptor = targetKey.second
            val targetIsStatic = targetAccess and Opcodes.ACC_STATIC != 0
            val targetIsPrivate = targetAccess and Opcodes.ACC_PRIVATE != 0
            val targetIsFinal = targetAccess and Opcodes.ACC_FINAL != 0
            val namespaceClassIsFinal = namespace.classAccess and Opcodes.ACC_FINAL != 0
            val overridable = !isConstructorGetter && !targetIsStatic && !targetIsPrivate && !targetIsFinal && !namespaceClassIsFinal

            val targetParams = parseParameterDescriptors(targetDescriptor)
            var slot = if (targetIsStatic) 0 else 1
            for (i in 0 until parameterIndex) slot += slotWidth(targetParams[i])
            val parameterName = namespace.localNames[targetKey]?.get(slot) ?: ""
            val line = firstLines[getterName to getterDescriptor] ?: -1

            ScalaGetterSite(
                getterName = getterName,
                getterDescriptor = getterDescriptor,
                targetName = targetName,
                targetDescriptor = targetDescriptor,
                parameterIndex = parameterIndex,
                parameterName = parameterName,
                overridable = overridable,
                targetClassName = namespace.targetClassName,
                line = line,
            )
        }
    }

    private class GetterTargetNamespace(
        val methodAccess: Map<Pair<String, String>, Int>,
        val localNames: Map<Pair<String, String>, Map<Int, String>>,
        val classAccess: Int,
        val targetClassName: String?,
    )

    private fun readCompanionNamespace(
        companionInternalName: String,
        lookupCompanionBytes: (String) -> ByteArray?,
    ): GetterTargetNamespace? {
        val bytes =
            try {
                lookupCompanionBytes(companionInternalName)
            } catch (_: Exception) {
                null
            } ?: return null
        val table =
            try {
                readMethodTable(bytes, lookupCompanionBytes)
            } catch (_: Exception) {
                null
            } ?: return null
        return GetterTargetNamespace(
            table.methodAccess,
            table.localNames,
            table.classAccess,
            targetClassName = companionInternalName.replace('/', '.'),
        )
    }

    internal class MethodTable(
        val classAccess: Int,
        val methodAccess: Map<Pair<String, String>, Int>,
        val localNames: Map<Pair<String, String>, Map<Int, String>>,
        val firstLines: Map<Pair<String, String>, Int>,
        val rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>> = emptyMap(),
        val isScalaClass: Boolean = false,
        val hasEnclosingMethod: Boolean = false,
        val rawReferencesByMethod: Map<Pair<String, String>, Set<String>> = emptyMap(),
        val internalName: String = "",
        val superInternalName: String? = null,
        val interfaceInternalNames: List<String> = emptyList(),
        val kotlinKind: KotlinKind = KotlinKind.NONE,
        val forwarderKeys: Set<Pair<String, String>> = emptySet(),
        val enhancement: JpaEnhancement = JpaEnhancement.NONE,
    ) {
        val isUnprobedBodyClass: Boolean
            get() =
                hasEnclosingMethod &&
                    TypeMatchPolicy.isTurnedAwayByShape(
                        internalName.replace('/', '.'),
                        classAccess and Opcodes.ACC_SYNTHETIC != 0,
                        { superInternalName?.replace('/', '.') },
                        { kotlinKind },
                    )
    }

    private fun readMethodTable(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
    ): MethodTable {
        var classAccess = 0
        var internalName = ""
        var superInternalName: String? = null
        var interfaceInternalNames: List<String> = emptyList()
        var hasEnclosingMethod = false
        var isKotlinClass = false
        var isScalaClass = false
        var kotlinKind = KotlinKind.NONE
        val methodAccess = LinkedHashMap<Pair<String, String>, Int>()
        val localNames = LinkedHashMap<Pair<String, String>, Map<Int, String>>()
        val firstLines = LinkedHashMap<Pair<String, String>, Int>()
        val rawCandidatesByMethod = LinkedHashMap<Pair<String, String>, List<RawCandidate>>()
        val rawReferencesByMethod = LinkedHashMap<Pair<String, String>, Set<String>>()

        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    classAccess = access
                    internalName = name
                    superInternalName = superName
                    interfaceInternalNames = interfaces?.toList() ?: emptyList()
                }

                override fun visitOuterClass(
                    owner: String,
                    name: String?,
                    descriptor: String?,
                ) {
                    hasEnclosingMethod = true
                }

                override fun visitAttribute(attribute: Attribute) {
                    if (ScalaClassDetector.isScalaAttribute(attribute)) isScalaClass = true
                }

                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    if (!kotlinMetadataDescriptorShape.matches(descriptor)) return null
                    isKotlinClass = true
                    kotlinKind = KotlinKind.ofMetadataKind(null)
                    return MetadataKindReader(null) { kotlinKind = KotlinKind.ofMetadataKind(it) }
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val key = name.interned() to descriptor.interned()
                    methodAccess[key] = access
                    var localNamesForMethod: MutableMap<Int, String>? = null
                    val candidatesForMethod = mutableListOf<RawCandidate>()
                    val referencesForMethod = LinkedHashSet<String>()
                    recordSignatureReferences(referencesForMethod, descriptor, signature, exceptions)
                    return object : CallCandidateMethodVisitor(internalName, candidatesForMethod, referencesForMethod) {
                        override fun visitLineNumber(
                            line: Int,
                            start: Label,
                        ) {
                            firstLines.putIfAbsent(key, line)
                        }

                        override fun visitLocalVariable(
                            localName: String,
                            localDescriptor: String,
                            localSignature: String?,
                            start: Label,
                            end: Label,
                            index: Int,
                        ) {
                            val names = localNamesForMethod ?: mutableMapOf<Int, String>().also { localNamesForMethod = it }
                            names.putIfAbsent(index, localName.interned())
                        }

                        override fun visitEnd() {
                            localNames[key] = localNamesForMethod ?: emptyMap()
                            rawCandidatesByMethod[key] =
                                candidatesForMethod
                                    .map {
                                        it.copy(
                                            owner = it.owner.interned(),
                                            name = it.name.interned(),
                                            descriptor = it.descriptor.interned(),
                                            functionalInterface = it.functionalInterface.internedOrNull(),
                                        )
                                    }.rightSized()
                            rawReferencesByMethod[key] =
                                when (referencesForMethod.size) {
                                    0 -> emptySet()
                                    1 -> setOf(referencesForMethod.first().interned())
                                    else -> referencesForMethod.mapTo(LinkedHashSet(referencesForMethod.size * 2)) { it.interned() }
                                }
                        }
                    }
                }
            }

        val reader = ClassReader(classBytes)
        reader.accept(classVisitor, ClassReader.SKIP_FRAMES)
        val forwarderKeys =
            computeGeneratedBy(
                classBytes,
                internalName,
                superInternalName,
                methodAccess,
                firstLines.keys,
                kotlinKind,
                isScalaClass,
                isKotlinClass,
                interfaceInternalNames,
                lookup = lookup,
                scalaReleaseOf = null,
                scalaLookup = { null },
                includeSerialization = false,
            ).passThroughKeys()
        return MethodTable(
            classAccess,
            methodAccess,
            localNames,
            firstLines,
            rawCandidatesByMethod,
            isScalaClass,
            hasEnclosingMethod,
            rawReferencesByMethod,
            internalName.interned(),
            superInternalName.internedOrNull(),
            interfaceInternalNames.internedAll(),
            kotlinKind,
            forwarderKeys.mapTo(LinkedHashSet()) { it.first.interned() to it.second.interned() },
            JpaEnhancement.of(reader),
        )
    }
}
