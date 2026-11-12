package dev.otherlode.export

/**
 * What one probe counts. [METHOD] counts entries to a method, [BRANCH] one outcome of a
 * conditional or a switch, and [OPTIONAL_ARGUMENT] calls that leave out one optional parameter's
 * argument. [INITIALIZER_RETURNED] counts normal returns of a class's `<clinit>`, and
 * [INITIALIZER_THREW] counts throws out of its body. Both carry that `<clinit>`'s method name and
 * descriptor.
 */
enum class ProbeKind { METHOD, BRANCH, OPTIONAL_ARGUMENT, INITIALIZER_RETURNED, INITIALIZER_THREW }

/**
 * The compiler feature that generated a method, read from bytecode shape. [NONE] means no generated
 * shape. On a [ProbeKind.BRANCH] probe it is the mark of the method the branch sits in, and on a
 * [ProbeKind.OPTIONAL_ARGUMENT] probe the mark of its target.
 *
 * - [ENUM]: enum plumbing, such as `values`, `valueOf` and `getEntries`.
 * - [DATA_CLASS]: a data class's or data object's generated members, such as `componentN` and `copy`.
 * - [DEFAULT_IMPLS]: a `$DefaultImpls` method whose body does nothing but forward to an interface's default
 *   method or to another `$DefaultImpls`, or a class's stub that does nothing but forward to `$DefaultImpls`.
 * - [RECORD]: a Java record's generated `equals`, `hashCode` and `toString`.
 * - [JVM_OVERLOADS]: an overload `@JvmOverloads` adds, which forwards to its `$default` twin.
 * - [MULTIFILE_FACADE]: a multi-file facade function that forwards to a part class.
 * - [CLASS_DELEGATION]: a forwarder for Kotlin interface delegation.
 * - [STATIC_FORWARDER]: a `@JvmStatic` companion function's static proxy, or a Scala object method's
 *   static forwarder.
 * - [KOTLINX_SERIALIZATION]: code the kotlinx.serialization compiler plugin generates.
 * - [VALUE_CLASS]: a Kotlin or Scala value class's boxed forwarders and generated `-impl` or
 *   `$extension` methods.
 * - [CASE_CLASS], [SCALA_OBJECT], [TRAIT_FORWARDER]: a Scala case class's plumbing, an object's
 *   `writeReplace` or, from Scala 2, its `readResolve`, and a trait's mixed-in forwarders.
 */
enum class GeneratedBy {
    NONE,
    ENUM,
    DATA_CLASS,
    DEFAULT_IMPLS,
    RECORD,
    JVM_OVERLOADS,
    MULTIFILE_FACADE,
    CASE_CLASS,
    STATIC_FORWARDER,
    SCALA_OBJECT,
    VALUE_CLASS,
    KOTLINX_SERIALIZATION,
    TRAIT_FORWARDER,
    CLASS_DELEGATION,
}

/**
 * What kind of class kotlinc says a class is: the `k` element of its `kotlin.Metadata`. An entry's
 * ordinal is its `k`.
 */
enum class KotlinKind {
    /** The class has no `kotlin.Metadata`, or its `k` is outside 1 to 5. */
    NONE,

    /** A class, interface, object, enum or annotation class, or a companion. */
    KOTLIN_CLASS,

    /** The class kotlinc makes for one source file's top-level functions and properties. */
    FILE_FACADE,

    /**
     * A class kotlinc makes that has no Kotlin declaration of its own, such as a lambda class or a
     * `$DefaultImpls` class.
     */
    SYNTHETIC_CLASS,

    /** The facade class `@file:JvmMultifileClass` makes, which holds forwarders to its parts. */
    MULTIFILE_CLASS_FACADE,

    /** One file's part of a multi-file facade, which holds that file's code. */
    MULTIFILE_CLASS_PART,
    ;

    companion object {
        /**
         * The kind for a `kotlin.Metadata` whose `k` element is [k]. A null [k] gives [KOTLIN_CLASS],
         * the default `k`. A value outside 1 to 5 gives [NONE].
         */
        fun ofMetadataKind(k: Int?): KotlinKind =
            when (k) {
                null -> KOTLIN_CLASS
                in 1..5 -> entries[k]
                else -> NONE
            }
    }
}

/**
 * The family of compiler output a probe's code has the outline of when its body matches no read
 * shape for that compiler.
 */
enum class UnreadShape {
    NONE,

    /** A Scala case class's plumbing, or its companion's, with an unread body. */
    CASE_CLASS,

    /** A static method with a `$` twin of the same name and descriptor, with an unread body. */
    STATIC_FORWARDER,

    /** A Scala object's `writeReplace` or `readResolve` with an unread body. */
    SCALA_OBJECT,

    /** Scala 3 enum plumbing with an unread body. */
    SCALA_ENUM,

    /** A method of a Kotlin multi-file facade that is not a recognised forwarder. */
    MULTIFILE_FACADE,

    /** A jump or switch in a suspend-shaped method that matches no read coroutine shape. */
    COROUTINE_MACHINERY,

    /** The collision side of a bucket in a string switch's `hashCode` lowering, with an unread shape. */
    STRING_SWITCH,

    /** A `@Serializer(forClass = ...)` object's `serialize`, `deserialize` or `getDescriptor`. */
    KOTLINX_SERIALIZER_OBJECT,
    VALUE_CLASS,
    TRAIT_FORWARDER,
}

/**
 * Who sent a payload. [runId] identifies one process. [serviceNamespace] is OpenTelemetry's
 * `service.namespace`, null when unspecified. [testRun] marks a JVM that runs the adopter's tests.
 * [agentVersion] is the jar manifest's `Implementation-Version`, empty when unknown.
 * [fieldsStripped] is true when a field the payload carried was removed in transit.
 * [flushIntervalSeconds] is the time between flushes, 0 when unknown. [manifestConfirmed] is true
 * once a manifest of this run that held at least one class was confirmed.
 */
data class ResourceAttributes(
    val serviceName: String,
    val serviceVersion: String?,
    val serviceInstanceId: String,
    val environment: String?,
    val runId: String,
    val serviceNamespace: String? = null,
    val testRun: Boolean = false,
    val agentVersion: String = "",
    val fieldsStripped: Boolean = false,
    val flushIntervalSeconds: Int = 0,
    val manifestConfirmed: Boolean = false,
) {
    companion object
}

/**
 * [hitsTotal] is cumulative from process start, not the count since the last flush. [firstSeenAt]
 * is the time of the first flush that saw a non-zero count, so it is precise to one flush interval.
 */
data class ProbeDelta(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val firstSeenAt: Long,
    val hitsTotal: Long,
)

/**
 * One batch of a run's counts.
 *
 * [finalFlush] marks the empty batch that ends the final flush of a graceful exit. Other batches of
 * that flush can arrive after it. [dependencyDeltas] holds dependencies whose loaded-class total
 * changed since it was last delivered. [payloadSequence], [countsPendingSince] and [starting] mean
 * what they mean on [ProbeManifest].
 *
 * [flushComplete] marks the batch that ends a flush's counts, sent once the earlier batches of that
 * flush were confirmed. A flush can have no such batch.
 */
data class DeltaBatch(
    val resource: ResourceAttributes,
    val deltas: List<ProbeDelta>,
    val endpointDeltas: List<EndpointDelta> = emptyList(),
    val finalFlush: Boolean = false,
    val dependencyDeltas: List<DependencyDelta> = emptyList(),
    val payloadSequence: Long = 0,
    val countsPendingSince: Long = 0,
    val flushComplete: Boolean = false,
    val starting: Boolean = false,
)

/**
 * One probe and the code it sits in.
 *
 * [inline] marks a probe in a Kotlin inline function, or a branch inside one. Its hit total misses
 * the copies inlined into callers.
 *
 * [parameterIndex], [parameterName], [overridable] and [targetClassName] describe a
 * [ProbeKind.OPTIONAL_ARGUMENT] probe. [targetClassName] is null when the target is in the probe's
 * own class.
 *
 * These describe a [ProbeKind.METHOD] probe. [lambdaBody], [static], [branchSites],
 * [parameterNames], [genericSignature] and [extensionReceiver] are false or empty for the type
 * initializer's probe:
 * - [calls]: the in-scope call edges from the method's bytecode, with a pass-through's callees in
 *   place of the pass-through.
 * - [referencedClasses]: the dotted out-of-scope classes the method's bytecode references, and those
 *   of each pass-through it reaches. Leaves out bootstrap and platform classes and classes from a
 *   classpath directory. A listed name can have no [ExternalClass] entry.
 * - [lambdaBody]: the method is a lambda body under a name a compiler gives such a body, such as the
 *   implementation method a `LambdaMetafactory` `invokedynamic` in its own class names.
 * - [branchSites]: the kept branch sites, in [BranchSite.siteIndex] order.
 * - [static]: the method has `ACC_STATIC`.
 * - [parameterNames]: one name per descriptor parameter, empty when the class file names none or
 *   names some but not all. [genericSignature]: the `Signature` attribute as written, or empty.
 *   [extensionReceiver]: the first name starts with `$this$` or is `$receiver`.
 * - [outsideCaller]: see [OutsideCaller]. Null when the method has none.
 *
 * These describe a [ProbeKind.BRANCH] probe. [inlinedFromClassName] is the dotted origin class of a
 * probe in a kept inlined copy whose origin class is in scope, and null otherwise. [branchKey] is an
 * opaque lowercase hex token that names the outcome across builds, null when it cannot be named safely.
 * [siteIndex] names the site in the method's [branchSites].
 *
 * The constructor throws when [generatedBy] and [unreadShape] are both set.
 */
data class ProbeLocation(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int?,
    val inline: Boolean = false,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val calls: List<CallEdge> = emptyList(),
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
    val referencedClasses: List<String> = emptyList(),
    val branchKey: String? = null,
    val lambdaBody: Boolean = false,
    val branchSites: List<BranchSite> = emptyList(),
    val siteIndex: Int? = null,
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
    val unreadShape: UnreadShape = UnreadShape.NONE,
    val outsideCaller: OutsideCaller? = null,
) {
    init {
        require(generatedBy == GeneratedBy.NONE || unreadShape == UnreadShape.NONE) {
            "a probe is generated or an unread shape, not both: $generatedBy and $unreadShape"
        }
    }
}

/**
 * Why code outside scope may call a method. A method with a callback annotation that also overrides an
 * out-of-scope method gets [OutsideCallerKind.CALLBACK_ANNOTATION]. [typeName] is dotted, and what it
 * names depends on [kind].
 */
data class OutsideCaller(
    val kind: OutsideCallerKind,
    val typeName: String,
)

/** The reason an [OutsideCaller] gives. */
enum class OutsideCallerKind {
    /**
     * The method overrides or implements a method that an out-of-scope type declares. The type name
     * is one such declaring type.
     */
    OVERRIDES_METHOD,

    /**
     * A callback annotation marks the method, such as one on the method, on a method it overrides, on
     * one of its parameters or on its interface. The type name is that annotation as written where it
     * was found.
     */
    CALLBACK_ANNOTATION,
}

/** What one outcome of a [BranchSite] is within its site. */
enum class BranchRole {
    /** A conditional's taken jump. */
    TAKEN,

    /** A conditional's fall-through: the jump's own test was false. */
    FALL_THROUGH,

    /** One case entry of a switch. */
    CASE,

    /** A switch's default, which a value with no case entry of its own also reaches. */
    DEFAULT,
}

/** The kind of routine path a kept branch outcome leads to. */
enum class RoutineKind {
    NONE,

    /**
     * The null side of a null check whose path, until it rejoins the other side or returns, does nothing
     * but yield null, a constant or a local.
     */
    NULL_DEFAULT,

    /** The path does nothing but build an exception and throw it. */
    THROW_ONLY,

    /**
     * An outcome of a site inside the exception-path copy of a `finally` body: a catch-any handler
     * that stores what it caught and ends by throwing it again.
     */
    FINALLY_COPY,
}

/**
 * A run of consecutive source lines in one file, both ends inclusive. [sourceFile] is a file name
 * as the class's `SourceFile` attribute or its SMAP names it, not a path, and empty when the class
 * has no `SourceFile`.
 */
data class LineRange(
    val sourceFile: String,
    val firstLine: Int,
    val lastLine: Int,
)

/**
 * One outcome of a [BranchSite]. [branchIndex] is the value the outcome's BRANCH probe carries.
 * [caseKey] is a [BranchRole.CASE]'s key as the switch instruction names it. It is null for other roles
 * and for a case whose key is not known.
 *
 * [guardedLines] are the lines whose instructions this outcome's edge dominates, and
 * [partlyGuardedLines] the lines where it dominates some of them. A line in an in-scope inlined copy
 * is named at its origin, and a line copied from out-of-scope code is left out.
 *
 * [caseLabel] is the label the source names for a [BranchRole.CASE] of a switch read back from a
 * lowering, such as an enum constant, a class pattern or a string, as one [ConditionPart]. Such a
 * case has no [caseKey].
 *
 * The constructor throws when [routine] and [unreadShape] are both set.
 */
data class BranchOutcome(
    val branchIndex: Int,
    val role: BranchRole,
    val caseKey: Int? = null,
    val guardedLines: List<LineRange> = emptyList(),
    val partlyGuardedLines: List<LineRange> = emptyList(),
    val caseLabel: List<ConditionPart> = emptyList(),
    val routine: RoutineKind = RoutineKind.NONE,
    val unreadShape: UnreadShape = UnreadShape.NONE,
) {
    init {
        require(routine == RoutineKind.NONE || unreadShape == UnreadShape.NONE) {
            "an outcome is routine or an unread shape, not both: $routine and $unreadShape"
        }
    }
}

/**
 * One kept conditional jump or switch in a method, with its outcomes.
 *
 * [siteIndex] is the site's ordinal within its class in bytecode order, dropped sites counted. It
 * names the site within one build. [siteKey] is an opaque lowercase hex token that names it across
 * builds, null exactly when the site's branch keys are null. [line] is the line the site's BRANCH probes carry.
 *
 * [outcomes] lists a conditional's [BranchRole.TAKEN] then [BranchRole.FALL_THROUGH], or a switch's
 * [BranchRole.CASE] outcomes in instruction order then [BranchRole.DEFAULT]. A switch read back from
 * a lowering can leave out an outcome. A case check of a string switch lowering lists one outcome when
 * only a hash collision reaches the other.
 *
 * [guard] is the [BranchOutcome.branchIndex] of the innermost kept outcome that dominates this site,
 * or null when none does.
 *
 * [condition] is the expression the site tests, in the class's source language, as the fall-through
 * side reads it. A switch's condition is its subject. It is empty when it could not be written.
 *
 * [stringHashCodeSwitch] is true for a switch on `String.hashCode()` left as a plain switch. Each
 * [BranchOutcome.caseKey] of it is the hash code of a string.
 */
data class BranchSite(
    val siteIndex: Int,
    val siteKey: String?,
    val line: Int,
    val outcomes: List<BranchOutcome>,
    val guard: Int? = null,
    val condition: List<ConditionPart> = emptyList(),
    val stringHashCodeSwitch: Boolean = false,
) {
    /** The number of entries this site adds to a chunk. */
    val chunkWeight: Int
        get() = 1 + condition.size + outcomes.sumOf { 1 + it.guardedLines.size + it.partlyGuardedLines.size + it.caseLabel.size }
}

/** What one [ConditionPart] holds. */
enum class ConditionPartKind {
    /** Source text, such as `discounted > ` or `System.getenv(`. */
    CODE,

    /**
     * A string constant from the class's bytecode. The text is its value, not quoted and not escaped.
     */
    STRING_LITERAL,

    /** One sub-expression that could not be written in source terms. The text is empty. */
    PLACEHOLDER,
}

/** One part of a [BranchSite.condition]. Parts render one after the other with nothing between them. */
data class ConditionPart(
    val kind: ConditionPartKind,
    val text: String = "",
)

/**
 * A loaded class the include rules admit that was not probed, not skipped and not found to have
 * nothing to probe. It carries no reason. [firstSeenUnreportedAt] is when a sweep first found it.
 */
data class UnreportedClass(
    val className: String,
    val firstSeenUnreportedAt: Long,
)

/**
 * A class that was woven and withheld for good because the JVM never defined it. A class the
 * verifier rejected is a [ClassFailedToInitialize] instead. [withheldAt] is in milliseconds since
 * the epoch.
 */
data class FailedClass(
    val className: String,
    val withheldAt: Long,
)

/**
 * Why a class the JVM defined can never run in this JVM: its static initializer threw, or the
 * verifier rejected it.
 */
enum class InitializationFailure { STATIC_INITIALIZER_THREW, VERIFIER_REJECTED }

/**
 * A class the JVM defined that can never run in this JVM. [observedAt] is when the failure was first
 * seen, in milliseconds since the epoch.
 */
data class ClassFailedToInitialize(
    val className: String,
    val reason: InitializationFailure,
    val observedAt: Long,
)

/**
 * A class the include rules matched that was not instrumented, or whose manifest entries alone exceed the
 * manifest's size bounds. It has no classId and no probes.
 */
data class SkippedClass(
    val className: String,
    val reason: String,
    val skippedAt: Long,
)

/** What a [CallEdge] records. */
enum class CallEdgeKind {
    /** The caller runs the callee, such as through an invoke instruction or a `new`. */
    CALL,

    /**
     * The caller hands the callee to something else to run, such as a `LambdaMetafactory`
     * implementation method or a method of a body class the caller creates. The constructor call of
     * that body class is a [CALL] edge.
     */
    CREATES,
}

/**
 * One caller method's static reference to one callee method, read from the caller's bytecode. One
 * callee reached under two guards, or created for two interfaces, gives two edges.
 *
 * [className], [methodName] and [methodDescriptor] name the callee as the caller's bytecode names
 * it. A virtual call to an abstract method of an in-scope interface also gives a non-virtual edge to the
 * matching `$DefaultImpls` body, when one exists. [virtual] is true for an `invokevirtual` or
 * `invokeinterface` call, unless its in-scope target is private, static or final. On a
 * [CallEdgeKind.CREATES] edge it comes from the implementation handle's kind or from the body class
 * method's access. [kind] says whether the caller runs the callee or hands it off.
 *
 * [capturedCount] is set on a [CallEdgeKind.CREATES] edge from an `invokedynamic`: how many of the
 * target descriptor's leading parameters take values captured at the call site, not counting a
 * bound receiver. It is 0 on other edges.
 *
 * [guard] is the [BranchOutcome.branchIndex] of the innermost kept outcome that dominates the
 * instruction recording this edge, or null when none does.
 *
 * [implementedInterface] is set on a [CallEdgeKind.CREATES] edge from an `invokedynamic`: the dotted
 * name of the interface the call site returns, such as `java.lang.Runnable`. It is null on other
 * edges.
 */
data class CallEdge(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val virtual: Boolean,
    val kind: CallEdgeKind = CallEdgeKind.CALL,
    val capturedCount: Int = 0,
    val guard: Int? = null,
    val implementedInterface: String? = null,
)

/**
 * What a loaded class's own header says about it.
 *
 * [superClassName] is null for `java.lang.Object` and when the class's bytecode could not be read;
 * [interfaceNames] is then empty. An interface's [superClassName] is `java.lang.Object`.
 * [sourceFile] is the class file's `SourceFile` attribute as written, null when absent.
 * [sourceName] is the name the source gave a [BodyKind.LOCAL_CLASS], such as `Local` for
 * `Foo$1Local`, and null for other kinds. [kotlinKind] is [KotlinKind.NONE] when the class carries no
 * `kotlin.Metadata`.
 */
data class ClassLocation(
    val classId: Int,
    val superClassName: String?,
    val interfaceNames: List<String>,
    val sourceFile: String? = null,
    val bodyKind: BodyKind = BodyKind.NONE,
    val sourceName: String? = null,
    val kotlinKind: KotlinKind = KotlinKind.NONE,
)

/**
 * What kind of body class a class is, read from its own class file. A body class carries a body its
 * creator hands to someone else, and has an `EnclosingMethod` attribute.
 */
enum class BodyKind {
    /** The class has no `EnclosingMethod` attribute, so it is not a body class. */
    NONE,

    /**
     * An anonymous class, such as javac's `new Runnable() { ... }`: its own `InnerClasses` entry has no name and
     * it carries no `kotlin.Metadata`, or it has no `InnerClasses` entry for itself.
     */
    ANONYMOUS_CLASS,

    /** A Kotlin object expression, such as `object : Runnable { ... }`. */
    OBJECT_EXPRESSION,

    /** A class declared inside a method. Its source name is [ClassLocation.sourceName]. */
    LOCAL_CLASS,

    /**
     * A Kotlin lambda compiled to a class, or a suspend lambda: its superclass is one of the Kotlin
     * runtime's lambda base classes.
     */
    LAMBDA_CLASS,
}

/**
 * What a run loaded and probed. A `classId` names a class within one run, and another run can use it
 * for a different class.
 *
 * Each list but [endpoints] holds entries that no earlier confirmed manifest of the run carried.
 *
 * [referencesRecorded] is true when the run records class references. [dependenciesListed] is true
 * once the startup dependency listing, and the reference mappings recorded before it ended, went out
 * on confirmed manifests. Until then an empty dependency list or no absent references does not mean
 * none.
 *
 * [payloadSequence] numbers a run's delta batches and manifests from 1, and is 0 until stamped. The
 * payload with the highest number holds the run's latest delivery state. [countsPendingSince], in
 * milliseconds since the epoch, is the start of the flush in which the run's unconfirmed hits or
 * manifest content began, or 0 when it has none.
 *
 * [starting] is true while the application's class loading has not settled.
 * [verifierRejectionsWatched] is true when the run watches the JVM's `VerifyError`.
 * [frameworkMarkers] are frameworks whose routing loaded in this JVM. [classListingBlind] is true
 * when a class the include rules admit loaded that the run's class listing may not hold, such as one
 * from a code source the listing did not read. These four are false or empty in a payload from a sender
 * that predates them.
 */
data class ProbeManifest(
    val resource: ResourceAttributes,
    val probes: List<ProbeLocation>,
    val skippedClasses: List<SkippedClass> = emptyList(),
    val endpoints: List<EndpointLocation> = emptyList(),
    val disabledEndpointModules: List<DisabledEndpointModule> = emptyList(),
    val classLocations: List<ClassLocation> = emptyList(),
    val unreportedClasses: List<UnreportedClass> = emptyList(),
    val dependencies: List<DependencyLocation> = emptyList(),
    val classReferences: List<ClassReferences> = emptyList(),
    val externalClasses: List<ExternalClass> = emptyList(),
    val referencesRecorded: Boolean = false,
    val dependenciesListed: Boolean = false,
    val failedClasses: List<FailedClass> = emptyList(),
    val payloadSequence: Long = 0,
    val countsPendingSince: Long = 0,
    val classesFailedToInitialize: List<ClassFailedToInitialize> = emptyList(),
    val unplacedVerificationFailures: Long = 0,
    val verifierRejectionsWatched: Boolean = false,
    val frameworkMarkers: List<FrameworkMarker> = emptyList(),
    val classListingBlind: Boolean = false,
    val starting: Boolean = false,
)

/**
 * A method the static scan found. Its fields mean what the [ProbeLocation] fields of the same name
 * mean. The class's own `<clinit>` entry has the empty fields a `<clinit>` probe has, and no
 * [generatedBy] or [unreadShape]. The constructor throws when [generatedBy] and [unreadShape] are
 * both set.
 */
data class DeclaredMethod(
    val methodName: String,
    val methodDescriptor: String,
    val inline: Boolean = false,
    val calls: List<CallEdge> = emptyList(),
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
    val referencedClasses: List<String> = emptyList(),
    val lambdaBody: Boolean = false,
    val branchSites: List<BranchSite> = emptyList(),
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
    val unreadShape: UnreadShape = UnreadShape.NONE,
) {
    init {
        require(generatedBy == GeneratedBy.NONE || unreadShape == UnreadShape.NONE) {
            "a method is generated or an unread shape, not both: $generatedBy and $unreadShape"
        }
    }
}

/**
 * A class the static scan found. [superClassName], [interfaceNames], [sourceFile], [bodyKind],
 * [sourceName] and [kotlinKind] mean what they mean on [ClassLocation], and keep their defaults when
 * the class's bytes could not be read. [referencedClasses] holds the references outside any probed
 * method, such as annotations on the class, its supertypes and its field types.
 */
data class DeclaredClass(
    val className: String,
    val methods: List<DeclaredMethod>,
    val superClassName: String? = null,
    val interfaceNames: List<String> = emptyList(),
    val referencedClasses: List<String> = emptyList(),
    val sourceFile: String? = null,
    val bodyKind: BodyKind = BodyKind.NONE,
    val sourceName: String? = null,
    val kotlinKind: KotlinKind = KotlinKind.NONE,
)

/**
 * A class file the scanner found but could not read. [className] comes from its path within the
 * classpath root, not from its bytecode.
 */
data class UnreadableClass(
    val className: String,
    val reason: String,
)

/**
 * An in-scope class with no concrete method to put a probe in, such as an interface with only
 * abstract methods or an annotation type.
 */
data class UnprobedClass(
    val className: String,
    val reason: String,
)

/**
 * An inventory of the in-scope classes on the classpath, read from their bytecode without loading
 * them.
 *
 * One scan can arrive as several chunks. Each chunk of a scan carries the same [resource] and
 * [scannedAt], and [chunkIndex] (0-based) and [chunkCount] say which part this is and how many to
 * expect. [externalClasses] maps class names this chunk's declared classes reference to their
 * dependency, or to absent, in the chunk of the first class that references each name. A referenced
 * name with no mapping has no entry. [includeRules] is null when the sender did not say.
 */
data class StaticBaseline(
    val resource: ResourceAttributes,
    val declaredClasses: List<DeclaredClass>,
    val unreadableClasses: List<UnreadableClass> = emptyList(),
    val unprobedClasses: List<UnprobedClass> = emptyList(),
    val scannedAt: Long,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
    val externalClasses: List<ExternalClass> = emptyList(),
    val includeRules: IncludeRules? = null,
)

/**
 * The include and exclude rules applied: dotted package or class name prefixes. A prefix covers a
 * name that equals it or continues it with `.` or `$`.
 */
data class IncludeRules(
    val includePackages: List<String>,
    val excludePackages: List<String> = emptyList(),
)

/**
 * The names of the classes a run's build holds under [includeRules], read from jar central
 * directories and class folders without reading a class file. [classNames] are dotted, distinct and
 * in ascending order.
 *
 * One listing can arrive as several chunks. Each chunk carries the same [resource], [includeRules]
 * and [listedAt], and [chunkIndex] (0-based) and [chunkCount] say which part this is and how many to
 * expect. A class missing from one chunk can be in another. Decoding a message with no rules gives
 * empty rules, which cover no class.
 */
data class ClassListing(
    val resource: ResourceAttributes,
    val classNames: List<String>,
    val includeRules: IncludeRules,
    val listedAt: Long,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
)

/**
 * How an endpoint was found: [REGISTRATION] when the framework registered it, [DISPATCH] when a
 * request matched it before any registration had.
 */
enum class EndpointDiscoverySource { REGISTRATION, DISPATCH }

/**
 * What an endpoint's templates are: [PATH] a normalised path, [REGEX] the regular expression a
 * framework matches by, or [SERVLET_PATTERN] a servlet extension or default mapping. The last two
 * are kept as written.
 */
enum class EndpointTemplateKind { PATH, REGEX, SERVLET_PATTERN }

/**
 * One endpoint the framework serves. [endpointId] names it within one run, and ([verb],
 * [routeTemplate], [templateKind]) names it across runs. For a [EndpointTemplateKind.REGEX] or
 * [EndpointTemplateKind.SERVLET_PATTERN] endpoint, [routeTemplate] and [verbatimTemplate] are the
 * same text.
 *
 * A record with the same [endpointId] can arrive again with changed handler fields or
 * [discoverySource]. [handlerClass] alone is set when the handler object's class is known but not its
 * method, and all three handler fields when the method is known.
 */
data class EndpointLocation(
    val endpointId: Int,
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String? = null,
    val handlerMethod: String? = null,
    val handlerDescriptor: String? = null,
    val templateKind: EndpointTemplateKind = EndpointTemplateKind.PATH,
)

/**
 * [hitsTotal] and [firstSeenAt] mean what [ProbeDelta.hitsTotal] and [ProbeDelta.firstSeenAt]
 * mean.
 */
data class EndpointDelta(
    val endpointId: Int,
    val firstSeenAt: Long,
    val hitsTotal: Long,
)

/** An endpoint module that is not instrumenting. [kind] and [reason] say why. */
data class DisabledEndpointModule(
    val module: String,
    val reason: String,
    val disabledAt: Long,
    val kind: DisabledEndpointModuleKind,
)

/**
 * Evidence that a framework's own routing loaded in this JVM: each class in [classes] was defined
 * when the loaded-class sweep ran at [seenAt], in milliseconds since the epoch. [id] is the marker's
 * id, kebab-case and stable. [classes] are fully qualified binary names.
 */
data class FrameworkMarker(
    val id: String,
    val classes: List<String>,
    val seenAt: Long,
)

/** What switched an endpoint module off. */
enum class DisabledEndpointModuleKind {
    /** No kind given, or none of the other kinds fits. */
    UNSPECIFIED,

    /** A `LinkageError`, or a framework that lacks an API the module needs. */
    LINKAGE_ERROR,

    /** Another throw from the module's advice, or a framework value the advice cannot use. */
    ADVICE_FAILED,

    /** Hooking a framework class failed, such as when the module's matcher or `transform` threw. */
    TRANSFORM_FAILED,

    /** Walking a framework's route objects threw. */
    ROUTE_WALK_FAILED,

    /** A hook matched no method on the framework class it hooks. */
    HOOK_UNMATCHED,

    /** The adopter's configuration kept the module from installing. */
    CONFIGURED_OFF,

    /** The module has no dispatch point on this runtime to count its routes at, so it declares none. */
    ROUTES_NOT_COUNTED,
}

enum class DependencyDiscoverySource {
    /**
     * Listed from the startup classpath, such as a `java.class.path` entry, a jar a manifest
     * `Class-Path` reaches, or a library jar of a Spring Boot fat jar.
     */
    STARTUP_CLASSPATH,

    /** Seen when a class from it loaded, such as a jar in a WAR's `WEB-INF/lib`. */
    LOAD,
}

enum class DependencyIdentitySource {
    /** `META-INF/maven/<group>/<artifact>/pom.properties` inside the jar. */
    POM_PROPERTIES,

    /** Unused. Its wire number stays reserved. */
    JAR_MANIFEST,

    /**
     * The jar's filename with its version suffix stripped; no group. The version comes from the
     * filename or, when it carries none, from the manifest's `Implementation-Version`.
     */
    FILENAME,
}

/**
 * One library a dependency carries. [groupId] and [version] are null when unknown. The version is
 * an attribute beside the identity, not part of it.
 */
data class DependencyIdentity(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
)

/**
 * One dependency a run has seen: the jars outside the adopter's own code that carry one set of
 * `groupId:artifactId` pairs.
 *
 * [dependencyId] names it within one run, and the sorted set of `groupId:artifactId` pairs in
 * [identities] names it across runs. A shaded jar carries one identity per bundled library.
 * [identities] must not be empty.
 *
 * [location] is for display. [location], the versions in [identities] and [classCount] come from
 * the first such jar found. [classCount] is that jar's class count, or null when unknown.
 */
data class DependencyLocation(
    val dependencyId: Int,
    val identities: List<DependencyIdentity>,
    val identitySource: DependencyIdentitySource,
    val location: String,
    val discoverySource: DependencyDiscoverySource,
    val classCount: Int? = null,
) {
    init {
        require(identities.isNotEmpty()) { "dependency $dependencyId at $location has no identities" }
    }
}

/**
 * [loadedClassesTotal] counts distinct class names seen loaded from the dependency, cumulative from
 * process start. [firstLoadedAt] has the same precision as [ProbeDelta.firstSeenAt].
 */
data class DependencyDelta(
    val dependencyId: Int,
    val firstLoadedAt: Long,
    val loadedClassesTotal: Long,
)

/**
 * A loaded class's own references outside any probed method, such as annotations on the class, its
 * supertypes, its field types and the signatures of methods without a probe. They follow the listing
 * rule of [ProbeLocation.referencedClasses].
 */
data class ClassReferences(
    val classId: Int,
    val referencedClasses: List<String>,
)

/**
 * Where one referenced class name lives. [dependencyId] is set when the class maps to one of the
 * run's [DependencyLocation] records, and [absent] is true when no loader could find it. The
 * constructor throws unless exactly one of the two holds.
 */
data class ExternalClass(
    val className: String,
    val dependencyId: Int?,
    val absent: Boolean = false,
) {
    init {
        require((dependencyId == null) == absent) {
            "external class $className must be either mapped to a dependency or absent, not both or neither"
        }
    }
}
