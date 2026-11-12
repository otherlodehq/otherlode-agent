package dev.otherlode.export

/**
 * What one probe counts. [METHOD] counts entries to a method, [BRANCH] one outcome of a
 * conditional or a switch, and [OPTIONAL_ARGUMENT] calls that leave out one optional parameter's
 * argument.
 *
 * [INITIALIZER_RETURNED] and [INITIALIZER_THREW] are slots of a `<clinit>` the class file declares:
 * the first counts before each normal return of the initialiser, the second in a catch-all handler
 * around its body that rethrows what it caught. Both carry that `<clinit>`'s method name and
 * descriptor. When a class sends both slots, a [METHOD] probe that counted with neither slot counted
 * means the initialiser has not finished. A class that sends no slots says nothing about it.
 */
enum class ProbeKind { METHOD, BRANCH, OPTIONAL_ARGUMENT, INITIALIZER_RETURNED, INITIALIZER_THREW }

/**
 * What compiled a method into existence rather than the adopter writing its body, from bytecode
 * shape alone. It is set on a [ProbeKind.METHOD] probe and a [DeclaredMethod], on a
 * [ProbeKind.BRANCH] probe as the mark of the method it sits in, and on a
 * [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's mark.
 *
 * - [ENUM]: an enum's `values`, `valueOf` and `getEntries`, and a Scala 3 enum's plumbing.
 * - [DATA_CLASS]: a data class's `componentN` and `copy`, and the `equals`, `hashCode` and
 *   `toString` of a data class or data object that the adopter did not override.
 * - [DEFAULT_IMPLS]: a `$DefaultImpls` method that only forwards to the interface's default method
 *   or to another interface's `$DefaultImpls`, or a class's stub that only forwards to `$DefaultImpls`.
 * - [RECORD]: a Java record's `equals`, `hashCode` and `toString`.
 * - [JVM_OVERLOADS]: an overload `@JvmOverloads` adds, which only forwards to its `$default` twin.
 * - [MULTIFILE_FACADE]: a multi-file facade function that only forwards to a part class.
 * - [CLASS_DELEGATION]: a forwarder for Kotlin interface delegation.
 * - [STATIC_FORWARDER]: a `@JvmStatic` companion function's static proxy, or a Scala object method's
 *   static forwarder.
 * - [KOTLINX_SERIALIZATION]: what the kotlinx.serialization compiler plugin generates.
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
 * What kind of class kotlinc says a class is: the `k` element of its `kotlin.Metadata`, the one int
 * the agent reads from that annotation. The entries are in `k` order, so an entry's ordinal is its
 * `k`. A consumer names a [FILE_FACADE] or a [MULTIFILE_CLASS_PART] by its source file, not its JVM
 * name.
 */
enum class KotlinKind {
    /** The class has no `kotlin.Metadata`, such as a Java or Scala class, or its `k` is outside 1 to 5. */
    NONE,

    /** A class, interface, object, enum or annotation class, or a companion. */
    KOTLIN_CLASS,

    /** The class kotlinc makes for one source file's top-level functions and properties. */
    FILE_FACADE,

    /** A class kotlinc makes that has no Kotlin declaration of its own, such as a lambda class or a `$DefaultImpls` class. */
    SYNTHETIC_CLASS,

    /** The class `@file:JvmMultifileClass` makes. It holds only forwarders to its parts. */
    MULTIFILE_CLASS_FACADE,

    /** One file's part of a multi-file facade, which holds that file's code. */
    MULTIFILE_CLASS_PART,
    ;

    companion object {
        /**
         * The kind for a `kotlin.Metadata` whose `k` element is [k]. A null [k] is [KOTLIN_CLASS], since
         * `k` defaults to 1. A value outside 1 to 5 is [NONE].
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
 * The family of compiler output a probe's code belongs to when the agent could not read its body.
 * Its body matches no shape the agent has read for that compiler. It is a statement about the agent,
 * not about who wrote the code.
 */
enum class UnreadShape {
    /** Not an unread shape. */
    NONE,

    /** A Scala case class's plumbing, or its companion's, whose body the agent has not read. */
    CASE_CLASS,

    /** A static method with a `$` twin of the same name and descriptor, whose body is unread. */
    STATIC_FORWARDER,

    /** A Scala object's `writeReplace` or `readResolve` whose body the agent has not read. */
    SCALA_OBJECT,

    /** Scala 3 enum plumbing whose body the agent has not read. */
    SCALA_ENUM,

    /** A method of a Kotlin multi-file facade that is not a recognised forwarder. */
    MULTIFILE_FACADE,

    /** A jump or switch in a suspend-shaped method that matches no coroutine shape the agent reads. */
    COROUTINE_MACHINERY,

    /** The collision side of a bucket in a string switch's `hashCode` lowering the agent cannot read. */
    STRING_SWITCH,

    /** A `@Serializer(forClass = ...)` object's `serialize`, `deserialize` and `getDescriptor`, which the plugin may have filled in. */
    KOTLINX_SERIALIZER_OBJECT,
    VALUE_CLASS,
    TRAIT_FORWARDER,
}

/**
 * Who sent a payload. Every payload kind carries one, and one process stamps the same value on all of
 * them apart from [manifestConfirmed].
 *
 * [runId] is a random id the agent's `forNewRun` makes once per process. A new process always gets
 * a new one, even when [serviceInstanceId] is pinned to a name that survives a restart.
 * [serviceNamespace] is OpenTelemetry's `service.namespace`, null when unspecified. [testRun] marks
 * a JVM that runs the adopter's tests. [agentVersion] is the jar manifest's `Implementation-Version`,
 * empty when unknown. A collector sets [fieldsStripped] when it removed a field its bindings did not
 * know; the agent never does. [flushIntervalSeconds] is the time between flushes, from 1 to 86400,
 * and 0 means unknown. [manifestConfirmed] is false until the collector confirms a manifest of this
 * run that held at least one class, then true on every later payload and never false again.
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
    /** Holds the agent's `forNewRun`, which builds one from its configuration. */
    companion object
}

/**
 * [hitsTotal] is cumulative from process start, not the count since the last flush. [firstSeenAt]
 * is stamped by the first flush that observed a non-zero count, so its precision is one flush
 * interval.
 */
data class ProbeDelta(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val firstSeenAt: Long,
    val hitsTotal: Long,
)

/**
 * [finalFlush] is set only on the empty batch that ends the final flush of a graceful exit. It is
 * sent once every other send of that flush has finished or run out of time, and a send that ran out
 * of time can still deliver after it.
 * [dependencyDeltas] holds entries for dependencies whose loaded-class total changed since it was
 * last delivered, at most one per dependency. A flush can spread them over several batches.
 * [payloadSequence], [countsPendingSince] and [starting] are the run's delivery
 * state when the batch was stamped, as [ProbeManifest] documents them: 0 and false until stamped.
 *
 * [flushComplete] is set on at most one batch of a flush, and only when the flush read its counts
 * and every earlier batch of the flush was confirmed. That batch is the last one that carries the
 * flush's counts, or an empty batch after them when hits were pending at the flush's start or the
 * flush is the final one. A heartbeat that carries a change of the pending state made after those
 * batches were stamped can follow it, without the mark.
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
 * [inline] marks a probe in a Kotlin inline function, or a branch inside one. A Kotlin caller copies
 * the body into its call site, so a zero hit total is not evidence the code never ran.
 *
 * [parameterIndex], [parameterName] and [overridable] are set only for an
 * [ProbeKind.OPTIONAL_ARGUMENT] probe. So is [targetClassName], and only when the target lives in
 * another class, as a Scala constructor getter on a companion module class does.
 *
 * The following are set only for a [ProbeKind.METHOD] probe. [lambdaBody], [static], [branchSites],
 * [parameterNames], [genericSignature] and [extensionReceiver] are false or empty for the type
 * initializer's probe:
 * - [calls]: the in-scope call edges read from the method's own bytecode. A pass-through's callees
 *   are attributed to whatever probed method referenced it.
 * - [referencedClasses]: the dotted out-of-scope classes the method's bytecode references, which is
 *   wider than a call edge. Classes from the bootstrap or platform loader and from a classpath
 *   directory are never listed. A name's [ExternalClass] entry may arrive on a later manifest, and a
 *   name that belongs to no dependency, such as one from an agent jar, never gets one.
 * - [lambdaBody]: an `invokedynamic` in the method's own class names it as the `LambdaMetafactory`
 *   implementation, directly or through a pass-through in that class, under a name a compiler gives a
 *   body the source never named. A body Scala 3 lifted out of a nested class is marked by its
 *   expanded name alone. See the agent's `TypeMatchPolicy.isLambdaBodyName`.
 * - [branchSites]: the kept branch sites, in [BranchSite.siteIndex] order.
 * - [static]: the method has `ACC_STATIC`.
 * - [parameterNames]: one name per descriptor parameter, empty when the class file names none or
 *   only some. [genericSignature]: the `Signature` attribute as written, or empty.
 *   [extensionReceiver]: the first name starts with `$this$` or is `$receiver`.
 * - [outsideCaller]: see [OutsideCaller]. Null when the method has none.
 *
 * [inlinedFromClassName] is the dotted origin class of a [ProbeKind.BRANCH] probe in a kept inlined
 * copy whose origin class is in scope, and null otherwise. [branchKey] and [siteIndex] are set only for a [ProbeKind.BRANCH]
 * probe: [branchKey] is an opaque lowercase hex token naming the outcome across builds, null when the
 * agent cannot name it safely, and [siteIndex] names the site listed in the method's [branchSites].
 *
 * [generatedBy] and [unreadShape] are exclusive: building a location with both set fails. See
 * [GeneratedBy] and [UnreadShape].
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
 * Why code outside scope may call a method. A method has at most one:
 * an annotation wins over an override, and the first annotation in class-file order wins.
 * [typeName] is dotted, and what it names depends on [kind].
 */
data class OutsideCaller(
    val kind: OutsideCallerKind,
    val typeName: String,
)

/** The reason an [OutsideCaller] gives. The wire's zero value reads as no outside caller. */
enum class OutsideCallerKind {
    /**
     * The method overrides or implements a method that an out-of-scope type declares. The type name
     * is the first declaring type found in this order: the superclass and its supertypes, then each
     * interface in declaration order with its supertypes.
     */
    OVERRIDES_METHOD,

    /**
     * The method carries an annotation from the agent's list of callback annotations, directly or
     * through meta-annotations, or takes one from a method it overrides, or one of its parameters
     * carries `@Observes` or `@ObservesAsync`. The type name is the annotation as written where the
     * agent found it, so a composed annotation is named, not the one it carries. A method of an
     * interface annotated `io.temporal.activity.ActivityInterface`, or one that implements such a
     * method or holds its `$DefaultImpls` body, also has this kind, with that annotation as the type
     * name.
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

/**
 * Why a kept branch outcome is real but not worth a person's time when it never runs.
 */
enum class RoutineKind {
    /** Not routine: the outcome is judged like any other. */
    NONE,

    /**
     * The null side of a null check whose path calls nothing before it rejoins the other side or
     * leaves the method. It only yields null, a constant or a local, or returns early.
     */
    NULL_DEFAULT,

    /** The path only builds an exception and throws it. */
    THROW_ONLY,

    /**
     * An outcome of a site inside the exception-path copy of a `finally` body: a catch-any handler
     * that stores what it caught and ends by throwing it again.
     */
    FINALLY_COPY,
}

/**
 * A run of consecutive source lines in one file, both ends inclusive. [sourceFile] is a file name
 * as the class's `SourceFile` attribute or its SMAP names it, never a path, and empty when the
 * class has no `SourceFile`.
 */
data class LineRange(
    val sourceFile: String,
    val firstLine: Int,
    val lastLine: Int,
)

/**
 * One outcome of a [BranchSite]. [branchIndex] is the value the outcome's BRANCH probe carries.
 * [caseKey] is set only for [BranchRole.CASE]: the case key as the switch instruction names it, null
 * when the agent could not read it.
 *
 * [guardedLines] are the lines that run only through this outcome: its edge dominates every
 * instruction the method places on them. [partlyGuardedLines] are the lines where it dominates some
 * of those instructions and not all. A line in an in-scope
 * inlined copy is named at its origin, and a line copied from out-of-scope code is left out.
 *
 * [caseLabel] is set only for a [BranchRole.CASE] of a switch read back from a string or enum
 * lowering or from a `SwitchBootstraps` pattern switch: the label the source names, as one
 * [ConditionPartKind.CODE] part for an enum constant, class pattern, integer or `null`, or one
 * [ConditionPartKind.STRING_LITERAL] part for a string.
 * Such a case has no [caseKey].
 *
 * [routine] and [unreadShape] are exclusive. [routine] is [RoutineKind.NONE] when the outcome is not
 * routine.
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
 * [siteIndex] is the site's ordinal within its class in bytecode order across every method, dropped
 * sites counted. It names the site within one build only. [siteKey] is an opaque lowercase hex token
 * naming it across builds, made the way a branch key is without the outcome, and null exactly when
 * the site's branch keys are null. [line] is the line the site's BRANCH probes carry.
 *
 * [outcomes] lists a conditional's [BranchRole.TAKEN] then [BranchRole.FALL_THROUGH], or a switch's
 * [BranchRole.CASE] outcomes in instruction order then [BranchRole.DEFAULT]. A switch read back
 * from a lowering lists no default when its default only throws an exception the compiler added,
 * and a case check of a string switch lowering lists one outcome when only a hash collision reaches
 * the other.
 *
 * [guard] is the [BranchOutcome.branchIndex] of the innermost kept outcome that dominates this site,
 * or null when nothing in the method stands between its entry and the site.
 *
 * [condition] is the expression the site tests, in the class's source language, as the fall-through
 * side reads it. A switch's condition is its subject. It is empty when the agent could not write it.
 *
 * [stringHashCodeSwitch] is true for a switch on `String.hashCode()`, left as a plain switch. Each
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
    /**
     * The entries this site adds to a chunk: one for the site, one per outcome, one per line range in
     * either of an outcome's lists, one per case label part and one per condition part.
     */
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

    /** One sub-expression the agent could not write in source terms. The text is empty. */
    PLACEHOLDER,
}

/** One part of a [BranchSite.condition]. Parts render one after the other with nothing between them. */
data class ConditionPart(
    val kind: ConditionPartKind,
    val text: String = "",
)

/**
 * A class the include rules admit that the JVM loaded and the agent neither probed, skipped nor
 * found nothing to probe in. The sweep finds it, so it carries no reason. [firstSeenUnreportedAt] is
 * when a sweep first found it.
 */
data class UnreportedClass(
    val className: String,
    val firstSeenUnreportedAt: Long,
)

/**
 * A class the agent wove and withheld for good because the JVM never defined it. The agent cannot
 * see why: a definition failure is thrown after its transformer returns. A verifier rejection
 * happens after the class is defined, so it is never here; see [ClassFailedToInitialize].
 * [withheldAt] is in milliseconds since the epoch.
 */
data class FailedClass(
    val className: String,
    val withheldAt: Long,
)

/**
 * Why a class the JVM defined can never run in this JVM. [STATIC_INITIALIZER_THREW] is read from
 * the class's own [ProbeKind.INITIALIZER_THREW] slot. [VERIFIER_REJECTED] is read from a
 * `VerifyError` whose message named the class in a format the agent has read.
 */
enum class InitializationFailure { STATIC_INITIALIZER_THREW, VERIFIER_REJECTED }

/**
 * A class the JVM defined that can never run in this JVM, because its verification or its static
 * initializer failed. Sent once per class and reason. [observedAt] is when the agent first saw the
 * evidence, in milliseconds since the epoch.
 */
data class ClassFailedToInitialize(
    val className: String,
    val reason: InitializationFailure,
    val observedAt: Long,
)

/**
 * A class the agent matched but could not instrument, or whose manifest entries alone exceed the
 * ingest limits. No payload gives it a classId or any probes.
 */
data class SkippedClass(
    val className: String,
    val reason: String,
    val skippedAt: Long,
)

/**
 * What a [CallEdge] records.
 */
enum class CallEdgeKind {
    /** The caller runs the callee: an invoke instruction, a `new`, or a static field use that runs the owner's initializer. */
    CALL,

    /**
     * The caller hands a body to someone else to run, so the body can only run after the caller
     * ran. It is the edge to the implementation method of a `LambdaMetafactory` `invokedynamic`,
     * whether a lambda body or a named method passed by reference. It is also the edge to each
     * probed method of a body class the caller creates with `new`, or whose static initializer a
     * static field use of the caller runs, such as `getstatic INSTANCE`. The constructor edge of that
     * `new`, and the initializer edge of that field use, stay [CALL] edges, since the caller runs them
     * itself.
     */
    CREATES,
}

/**
 * One caller method's static reference to one callee method, read from the caller's bytecode.
 *
 * [className], [methodName] and [methodDescriptor] name the callee as the caller's bytecode names
 * it, and the agent resolves no virtual call, with one exception: a virtual call to an abstract
 * method of an in-scope interface also gives a non-virtual edge to the matching static body of that
 * interface's `$DefaultImpls` class, when one exists. [virtual] is true for `invokevirtual` and
 * `invokeinterface`, false for `invokestatic` and `invokespecial`, except that a call to an in-scope
 * target its class declares private, static or final is non-virtual even when the instruction is
 * `invokevirtual` or `invokeinterface`. A [CallEdgeKind.CREATES] edge takes it from the
 * implementation handle's kind, or from a body class method's access.
 *
 * [kind] says whether the caller runs the callee or hands it off. An edge that takes the place of a
 * pass-through is [CallEdgeKind.CREATES] when either the edge to the pass-through or the
 * pass-through's own edge is.
 *
 * [capturedCount] is set only on a [CallEdgeKind.CREATES] edge from an `invokedynamic`: how many of
 * the target descriptor's leading parameters take values captured at the call site, read from its
 * `invokedType`. A receiver bound by a reference to an instance method is not counted. 0 on every
 * other edge.
 *
 * [guard] is the [BranchOutcome.branchIndex] of the innermost kept outcome that dominates the
 * instruction recording this edge, or null when nothing in the method stands between its entry and
 * that instruction. An edge that takes the place of a pass-through keeps the guard of the call to the
 * pass-through. Edges are distinct by every field, so one callee reached under two guards gives two
 * edges.
 *
 * [implementedInterface] is set only on a [CallEdgeKind.CREATES] edge from an `invokedynamic`: the
 * dotted name of the interface the call site returns, such as `java.lang.Runnable`. Null on a
 * [CallEdgeKind.CALL] edge and on a creation edge from a body class's `new` or static field use.
 * One body created for two interfaces gives two edges.
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
 * What a loaded class's own header says about it, sent once per class alongside its probes.
 *
 * [superClassName] is null for `java.lang.Object`, which this agent never instruments, and when the
 * agent could not read the class's bytecode; [interfaceNames] is then empty. An interface's
 * [superClassName] is `java.lang.Object`. [sourceFile] is the class file's `SourceFile` attribute
 * exactly as it appears, null when absent. [bodyKind] is
 * [BodyKind.NONE] for a class that is not a body class, and [sourceName] is the name the source gave
 * a [BodyKind.LOCAL_CLASS], such as `Local` for `Foo$1Local`, null for every other kind.
 * [kotlinKind] is [KotlinKind.NONE] when the class carries no `kotlin.Metadata`.
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
 * What kind of body class a class is, read from its own class file. A body class exists only to
 * carry a body its creator hands to someone else, and has an `EnclosingMethod` attribute. A Kotlin
 * function or property reference class has no kind here: kotlinc marks it synthetic, so no
 * [ClassLocation] or [DeclaredClass] describes it.
 */
enum class BodyKind {
    /** The class has no `EnclosingMethod` attribute, so it is not a body class. */
    NONE,

    /**
     * An anonymous class, such as javac's `new Runnable() { ... }`: the class's own `InnerClasses`
     * entry has no name and it carries no `kotlin.Metadata`, or it has no entry for itself at all.
     */
    ANONYMOUS_CLASS,

    /** A Kotlin object expression (`object : Runnable { ... }`): its own `InnerClasses` entry has no name, and it carries `kotlin.Metadata`. */
    OBJECT_EXPRESSION,

    /** A class declared inside a method: its own `InnerClasses` entry has a name, which is [ClassLocation.sourceName]. */
    LOCAL_CLASS,

    /** A Kotlin lambda compiled to a class, or any suspend lambda: its superclass is one of the Kotlin runtime's lambda base classes. */
    LAMBDA_CLASS,
}

/**
 * [resource] is the one the process stamps on its delta batches and static baseline. A `class_id`
 * is assigned by each process's own registry, so the same `class_id` can mean a different class in
 * two instances of the same (service, version), or in two runs of one pinned instance.
 *
 * [dependencies], [classReferences], [externalClasses] and [failedClasses] are delivered
 * incrementally, each entry sent once per run.
 *
 * [referencesRecorded] is true when the instance records references at all, which it does only when
 * its include rules are set. [dependenciesListed] is true once every dependency from the startup
 * listing, and every reference mapping recorded before the listing ended, has gone out on a
 * confirmed manifest. Until then, an empty dependency list or no absent references means "not listed
 * yet", not "none".
 *
 * [payloadSequence] numbers the delta batches and manifests a run sends, from 1. The payload with
 * the highest number says the run's current delivery state, whatever order they arrive in. 0 until
 * stamped. [countsPendingSince], in milliseconds since the epoch, is 0 when the run has no
 * unconfirmed hits or manifest content, otherwise the start of the flush in which that unconfirmed
 * content began.
 *
 * [starting] is true on every payload the run sends before its application's class loading has
 * settled (agent ADR 0086 and ADR 0087), then false and never true again.
 *
 * [verifierRejectionsWatched] is true when the agent's hook on the JVM's `VerifyError` is installed.
 * A class the verifier rejected fires no probe, so a run any of whose manifests carries false is
 * unwatched.
 *
 * [frameworkMarkers] are the frameworks whose routing loaded in this JVM, each delivered once.
 *
 * [classListingBlind] is true on every manifest the run sends once a class the include rules admit
 * loaded from a code source the run's class listing did not read, or with no code source and a name
 * the listing lacks. A class whose name or code source the agent could not read also sets it, as do
 * more loads before the listing finished than the agent keeps. It then never goes back to false.
 *
 * [starting], [verifierRejectionsWatched], [frameworkMarkers] and [classListingBlind] are false or
 * empty from an agent that predates them.
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
 * No line field, unlike [ProbeLocation]: the static scan records no line. Every other field means
 * what the [ProbeLocation] field of the same name means, read from the same class. For the class's
 * own `<clinit>` entry, [generatedBy] and [unreadShape] are none, [lambdaBody] and [static] are
 * false, [branchSites] is empty, and [parameterNames], [genericSignature] and [extensionReceiver]
 * are empty or false. [generatedBy] and [unreadShape] are exclusive.
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
 * [superClassName] and [interfaceNames] mean what they mean on [ClassLocation]. [superClassName] is null
 * only when the class's bytes could not be read, and [interfaceNames] is then empty. [referencedClasses] holds the references outside any probed method, such as annotations on
 * the class, its supertypes, its field types and the signatures of methods without a probe.
 * Method-level references travel on [DeclaredMethod.referencedClasses]. [sourceFile], [bodyKind],
 * [sourceName] and [kotlinKind] mean what they mean on [ClassLocation], and are null,
 * [BodyKind.NONE], null and [KotlinKind.NONE] when the bytes could not be read.
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
 * A class file the scanner found but could not read. [className] is best-effort, derived from its
 * path within the classpath root rather than from parsed bytecode.
 */
data class UnreadableClass(
    val className: String,
    val reason: String,
)

/**
 * An in-scope class with no concrete method to put a probe in, such as an interface with only
 * abstract methods or an annotation type. A manifest never gives it probes, though it can list it as
 * a [SkippedClass] or an [UnreportedClass].
 */
data class UnprobedClass(
    val className: String,
    val reason: String,
)

/**
 * Sent at most once per process, and only when `staticBaselineEnabled` is set, independent of
 * [ProbeManifest]: an inventory of what exists on the classpath under `includePackages`, built by
 * reading bytecode directly rather than waiting for the JVM to load it. A scan that fails, or that
 * holds a class too large for one chunk, sends none.
 *
 * One scan may be delivered as several of these. Every chunk of a scan carries the same [resource]
 * and [scannedAt]; [chunkIndex] (0-based) and [chunkCount] say which part this is and how many to
 * expect. [externalClasses] maps the class names this chunk's declared classes reference to their
 * dependency, or to absent, in the chunk of the first class that references each name. A referenced
 * name the agent has not mapped has no entry. [includeRules] is null when the sender did not say.
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
 * The include and exclude rules the agent applied: dotted package or class name prefixes, as
 * configuration parsing leaves them. A prefix covers a name that equals it or continues it with `.`
 * or `$`. A class is in scope when some include prefix covers it and no exclude prefix does, and
 * its name does not start with `dev.otherlode.`.
 */
data class IncludeRules(
    val includePackages: List<String>,
    val excludePackages: List<String> = emptyList(),
)

/**
 * The names of the classes a run's build holds under [includeRules], sent at most once per process
 * (agent ADR 0070): none when `classListingEnabled` is off or the listing would be partial. They are
 * read from jar central directories and class folders without reading a class file. [classNames]
 * are dotted, distinct and in ascending order.
 *
 * One listing may be delivered as several of these. Every chunk carries the same [resource],
 * [includeRules] and [listedAt]; [chunkIndex] (0-based) and [chunkCount] say which part this is and
 * how many to expect. A class's absence can be read only once every chunk is held. Decoding a message
 * with no rules gives empty rules, which cover no class.
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
 * How the agent learned of an endpoint: the framework declared it at registration, or a request
 * matched it at dispatch before any registration had.
 */
enum class EndpointDiscoverySource { REGISTRATION, DISPATCH }

/**
 * What an endpoint's templates are: a path the agent normalised, the regular expression a
 * framework matches by, or a servlet extension or default mapping. The last two are kept exactly
 * as written.
 */
enum class EndpointTemplateKind { PATH, REGEX, SERVLET_PATTERN }

/**
 * One endpoint the framework serves. [endpointId] is per run, like `classId`; cross-run identity
 * is ([verb], [routeTemplate], [templateKind]). For a [EndpointTemplateKind.REGEX] or
 * [EndpointTemplateKind.SERVLET_PATTERN] endpoint, [routeTemplate] and [verbatimTemplate] are the
 * same text, never normalised.
 *
 * A record may be re-sent with the same [endpointId] when the handler fields are learned or change,
 * or when an endpoint first found at dispatch is registered, which turns [discoverySource] to
 * [EndpointDiscoverySource.REGISTRATION].
 * [handlerClass] alone is set when only the handler object's class is known; all three handler fields
 * are set when the framework hands over a method.
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

/**
 * An endpoint module that is not instrumenting: it switched itself off, typically on a linkage
 * failure against an unexpected framework version, or the configuration kept it from installing.
 */
data class DisabledEndpointModule(
    val module: String,
    val reason: String,
    val disabledAt: Long,
    val kind: DisabledEndpointModuleKind,
)

/**
 * Evidence that a framework's own routing loaded in this JVM: every class in [classes], one of the
 * marker's class sets, was defined when the loaded-class sweep ran at [seenAt], in milliseconds since
 * the epoch. [id] is the marker's id from the agent's fixed list, kebab-case and stable. [classes]
 * are fully qualified binary names.
 */
data class FrameworkMarker(
    val id: String,
    val classes: List<String>,
    val seenAt: Long,
)

/** What switched an endpoint module off. */
enum class DisabledEndpointModuleKind {
    /**
     * No kind: sent by an agent that predates the kind, for a module disabled because the endpoint
     * record buffer filled, and for a disable that fits none of the other kinds.
     */
    UNSPECIFIED,

    /**
     * A `LinkageError`, or a framework that lacks an API the module needs: the framework release
     * differs from the one the module was built for.
     */
    LINKAGE_ERROR,

    /** Any other throw from the module's advice, or a framework value the advice cannot use. */
    ADVICE_FAILED,

    /**
     * The module's matcher or its own `transform` threw, a framework class it hooks failed to weave,
     * or the loader of that class cannot see the agent's bootstrap package.
     */
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

/** How the agent learned of a dependency. */
enum class DependencyDiscoverySource {
    /**
     * Listed from the startup classpath: each `java.class.path` entry, the jars a manifest
     * `Class-Path` reaches from one, and the library jars of a Spring Boot fat jar, executable war or
     * exploded application.
     */
    STARTUP_CLASSPATH,

    /**
     * Seen only when a class from it loaded, such as a jar in a WAR's `WEB-INF/lib` that the app
     * server opened after `premain`. Such a dependency can never read as unloaded.
     */
    LOAD,
}

/** Where a dependency's identity was read from, most to least reliable. */
enum class DependencyIdentitySource {
    /** `META-INF/maven/<group>/<artifact>/pom.properties` inside the jar. */
    POM_PROPERTIES,

    /**
     * Never produced. Its wire number stays reserved.
     */
    JAR_MANIFEST,

    /**
     * The jar's filename with its version suffix stripped; no group. The version comes from the
     * filename or, when it carries none, from the manifest's `Implementation-Version`.
     */
    FILENAME,
}

/**
 * One library a dependency carries. [groupId] is null when the identity source carries none, and
 * [version] is null when unknown. The version is an attribute beside the identity, not part of it.
 */
data class DependencyIdentity(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
)

/**
 * One dependency this run has seen: the jars that are not the adopter's own code and carry one set
 * of `groupId:artifactId` pairs.
 *
 * [dependencyId] is per run. Cross-run identity is the sorted set of `groupId:artifactId`
 * pairs in [identities], never [dependencyId]. A shaded jar that bundles several libraries carries
 * one identity per library.
 *
 * Sent once: a dependency discovered by load arrives on the flush after it was first seen.
 * [location] is for display only. [location], the versions in [identities] and [classCount] come
 * from the first such jar the agent found; [classCount] is that jar's total class count, when the
 * listing knows it.
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
 * [loadedClassesTotal] counts distinct class names ever seen loaded from the dependency,
 * cumulative from process start. [firstLoadedAt] has the same precision as
 * [ProbeDelta.firstSeenAt].
 */
data class DependencyDelta(
    val dependencyId: Int,
    val firstLoadedAt: Long,
    val loadedClassesTotal: Long,
)

/**
 * A loaded class's own references outside any probed method: annotations on the class, its
 * supertypes, its field types, the signatures of methods without a probe (abstract, native), and
 * anything else not held by a probed method. The same listing rule as
 * [ProbeLocation.referencedClasses] applies.
 */
data class ClassReferences(
    val classId: Int,
    val referencedClasses: List<String>,
)

/**
 * Where one referenced class name lives. Manifests send each name once per run, and the static
 * baseline chunk of the first declared class that references it carries the same mapping.
 * [dependencyId] refers to that run's [DependencyLocation] records, whichever payload carries the
 * mapping.
 * Exactly one of two things holds: the class maps to a dependency, so [dependencyId] is set, or no
 * loader could find it, so [absent] is true.
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
