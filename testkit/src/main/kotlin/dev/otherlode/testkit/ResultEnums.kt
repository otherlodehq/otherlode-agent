package dev.otherlode.testkit

import dev.otherlode.export.DependencyDiscoverySource as WireDependencyDiscoverySource
import dev.otherlode.export.EndpointDiscoverySource as WireEndpointDiscoverySource
import dev.otherlode.export.GeneratedBy as WireGeneratedBy
import dev.otherlode.export.OutsideCaller as WireOutsideCaller
import dev.otherlode.export.OutsideCallerKind as WireOutsideCallerKind
import dev.otherlode.export.ProbeKind as WireProbeKind
import dev.otherlode.export.RoutineKind as WireRoutineKind
import dev.otherlode.export.UnreadShape as WireUnreadShape

/**
 * What one probe counts. [METHOD] counts entries to a method, [BRANCH] one outcome of a
 * conditional or a switch, and [OPTIONAL_ARGUMENT] calls that leave out one optional parameter's
 * argument. Values may be added in a minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class ProbeKind { METHOD, BRANCH, OPTIONAL_ARGUMENT }

/**
 * What compiled a method into existence rather than the adopter writing its body, read from
 * bytecode shape alone. A [ProbeRef] whose method is not generated holds null instead of a value of
 * this enum. Values may be added in a minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class GeneratedBy {
    /** An enum's `values`, `valueOf` or `getEntries`. */
    ENUM,

    /** A data class's `componentN` and `copy`, and whichever of `equals`, `hashCode` and `toString` the adopter did not write. */
    DATA_CLASS,

    /** A `$DefaultImpls` method that only forwards to the interface's own default method. */
    DEFAULT_IMPLS,

    /** A Java record's `equals`, `hashCode` or `toString`. */
    RECORD,

    /** An overload `@JvmOverloads` adds, whose body only forwards to its own class's `$default` twin. */
    JVM_OVERLOADS,

    /** A function of a Kotlin multi-file facade whose body only forwards to the same function on a part class. */
    MULTIFILE_FACADE,

    /** A Scala case class's plumbing, or its companion's. */
    CASE_CLASS,

    /** A static forwarder scalac adds for an object's method. */
    STATIC_FORWARDER,

    /** A Scala object's `writeReplace`. */
    SCALA_OBJECT,
}

/**
 * Why a kept branch outcome is real but not worth a person's time when it never runs. A [ProbeRef]
 * whose outcome is not routine holds null instead of a value of this enum. Values may be added in a
 * minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class RoutineKind {
    /** The null side of a null check whose path calls nothing before it rejoins the other side or leaves the method. */
    NULL_DEFAULT,

    /** The path only builds an exception and throws it. */
    THROW_ONLY,

    /** An outcome of a site inside the exception-path copy of a `finally` body. */
    FINALLY_COPY,
}

/**
 * The family of compiler output a probe's code belongs to when the agent could not read its body. A
 * [ProbeRef] that is not an unread shape holds null instead of a value of this enum. Values may be
 * added in a minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class UnreadShape {
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
}

/**
 * Why code outside scope may call a method. Values may be added in a minor release, so a `when`
 * over this enum needs an `else` branch.
 */
public enum class OutsideCallerKind {
    /** The method overrides or implements a method that an out-of-scope type declares. */
    OVERRIDES_METHOD,

    /** The method, or one of its parameters, carries an annotation a framework calls through. */
    CALLBACK_ANNOTATION,
}

/**
 * How the agent learned of an endpoint: the framework declared it at registration, or a request
 * matched it at dispatch before any registration had. Values may be added in a minor release, so a
 * `when` over this enum needs an `else` branch.
 */
public enum class EndpointDiscoverySource { REGISTRATION, DISPATCH }

/**
 * How the agent learned of a dependency. Values may be added in a minor release, so a `when` over
 * this enum needs an `else` branch.
 */
public enum class DependencyDiscoverySource {
    /** Listed from the startup classpath, including every jar under a fat jar's `BOOT-INF/lib`, `WEB-INF/lib` or `lib-provided`. */
    STARTUP_CLASSPATH,

    /** Seen only when a class from it loaded. Such a dependency can never read as unloaded. */
    LOAD,
}

/** A class the agent matched by `includePackages` but could not instrument, with the [reason] it gives. */
@ConsistentCopyVisibility
public data class SkippedClass internal constructor(
    val className: String,
    val reason: String,
)

/** An endpoint module that switched itself off, typically on a linkage failure against an unexpected framework version. */
@ConsistentCopyVisibility
public data class DisabledEndpointModule internal constructor(
    val module: String,
    val reason: String,
)

internal fun WireProbeKind.toTestkit(): ProbeKind =
    when (this) {
        WireProbeKind.METHOD -> ProbeKind.METHOD
        WireProbeKind.BRANCH -> ProbeKind.BRANCH
        WireProbeKind.OPTIONAL_ARGUMENT -> ProbeKind.OPTIONAL_ARGUMENT
    }

internal fun ProbeKind.toWire(): WireProbeKind =
    when (this) {
        ProbeKind.METHOD -> WireProbeKind.METHOD
        ProbeKind.BRANCH -> WireProbeKind.BRANCH
        ProbeKind.OPTIONAL_ARGUMENT -> WireProbeKind.OPTIONAL_ARGUMENT
    }

internal fun WireOutsideCallerKind.toTestkit(): OutsideCallerKind =
    when (this) {
        WireOutsideCallerKind.OVERRIDES_METHOD -> OutsideCallerKind.OVERRIDES_METHOD
        WireOutsideCallerKind.CALLBACK_ANNOTATION -> OutsideCallerKind.CALLBACK_ANNOTATION
    }

internal fun WireOutsideCaller?.toTestkit(): OutsideCaller? = this?.let { OutsideCaller(it.kind.toTestkit(), it.typeName) }

internal fun OutsideCallerKind.toWire(): WireOutsideCallerKind =
    when (this) {
        OutsideCallerKind.OVERRIDES_METHOD -> WireOutsideCallerKind.OVERRIDES_METHOD
        OutsideCallerKind.CALLBACK_ANNOTATION -> WireOutsideCallerKind.CALLBACK_ANNOTATION
    }

internal fun WireGeneratedBy.toTestkit(): GeneratedBy? =
    when (this) {
        WireGeneratedBy.NONE -> null
        WireGeneratedBy.ENUM -> GeneratedBy.ENUM
        WireGeneratedBy.DATA_CLASS -> GeneratedBy.DATA_CLASS
        WireGeneratedBy.DEFAULT_IMPLS -> GeneratedBy.DEFAULT_IMPLS
        WireGeneratedBy.RECORD -> GeneratedBy.RECORD
        WireGeneratedBy.JVM_OVERLOADS -> GeneratedBy.JVM_OVERLOADS
        WireGeneratedBy.MULTIFILE_FACADE -> GeneratedBy.MULTIFILE_FACADE
        WireGeneratedBy.CASE_CLASS -> GeneratedBy.CASE_CLASS
        WireGeneratedBy.STATIC_FORWARDER -> GeneratedBy.STATIC_FORWARDER
        WireGeneratedBy.SCALA_OBJECT -> GeneratedBy.SCALA_OBJECT
    }

internal fun GeneratedBy.toWire(): WireGeneratedBy =
    when (this) {
        GeneratedBy.ENUM -> WireGeneratedBy.ENUM
        GeneratedBy.DATA_CLASS -> WireGeneratedBy.DATA_CLASS
        GeneratedBy.DEFAULT_IMPLS -> WireGeneratedBy.DEFAULT_IMPLS
        GeneratedBy.RECORD -> WireGeneratedBy.RECORD
        GeneratedBy.JVM_OVERLOADS -> WireGeneratedBy.JVM_OVERLOADS
        GeneratedBy.MULTIFILE_FACADE -> WireGeneratedBy.MULTIFILE_FACADE
        GeneratedBy.CASE_CLASS -> WireGeneratedBy.CASE_CLASS
        GeneratedBy.STATIC_FORWARDER -> WireGeneratedBy.STATIC_FORWARDER
        GeneratedBy.SCALA_OBJECT -> WireGeneratedBy.SCALA_OBJECT
    }

internal fun WireRoutineKind.toTestkit(): RoutineKind? =
    when (this) {
        WireRoutineKind.NONE -> null
        WireRoutineKind.NULL_DEFAULT -> RoutineKind.NULL_DEFAULT
        WireRoutineKind.THROW_ONLY -> RoutineKind.THROW_ONLY
        WireRoutineKind.FINALLY_COPY -> RoutineKind.FINALLY_COPY
    }

internal fun RoutineKind.toWire(): WireRoutineKind =
    when (this) {
        RoutineKind.NULL_DEFAULT -> WireRoutineKind.NULL_DEFAULT
        RoutineKind.THROW_ONLY -> WireRoutineKind.THROW_ONLY
        RoutineKind.FINALLY_COPY -> WireRoutineKind.FINALLY_COPY
    }

internal fun WireUnreadShape.toTestkit(): UnreadShape? =
    when (this) {
        WireUnreadShape.NONE -> null
        WireUnreadShape.CASE_CLASS -> UnreadShape.CASE_CLASS
        WireUnreadShape.STATIC_FORWARDER -> UnreadShape.STATIC_FORWARDER
        WireUnreadShape.SCALA_OBJECT -> UnreadShape.SCALA_OBJECT
        WireUnreadShape.SCALA_ENUM -> UnreadShape.SCALA_ENUM
        WireUnreadShape.MULTIFILE_FACADE -> UnreadShape.MULTIFILE_FACADE
        WireUnreadShape.COROUTINE_MACHINERY -> UnreadShape.COROUTINE_MACHINERY
        WireUnreadShape.STRING_SWITCH -> UnreadShape.STRING_SWITCH
    }

internal fun UnreadShape.toWire(): WireUnreadShape =
    when (this) {
        UnreadShape.CASE_CLASS -> WireUnreadShape.CASE_CLASS
        UnreadShape.STATIC_FORWARDER -> WireUnreadShape.STATIC_FORWARDER
        UnreadShape.SCALA_OBJECT -> WireUnreadShape.SCALA_OBJECT
        UnreadShape.SCALA_ENUM -> WireUnreadShape.SCALA_ENUM
        UnreadShape.MULTIFILE_FACADE -> WireUnreadShape.MULTIFILE_FACADE
        UnreadShape.COROUTINE_MACHINERY -> WireUnreadShape.COROUTINE_MACHINERY
        UnreadShape.STRING_SWITCH -> WireUnreadShape.STRING_SWITCH
    }

internal fun WireEndpointDiscoverySource.toTestkit(): EndpointDiscoverySource =
    when (this) {
        WireEndpointDiscoverySource.REGISTRATION -> EndpointDiscoverySource.REGISTRATION
        WireEndpointDiscoverySource.DISPATCH -> EndpointDiscoverySource.DISPATCH
    }

internal fun EndpointDiscoverySource.toWire(): WireEndpointDiscoverySource =
    when (this) {
        EndpointDiscoverySource.REGISTRATION -> WireEndpointDiscoverySource.REGISTRATION
        EndpointDiscoverySource.DISPATCH -> WireEndpointDiscoverySource.DISPATCH
    }

internal fun WireDependencyDiscoverySource.toTestkit(): DependencyDiscoverySource =
    when (this) {
        WireDependencyDiscoverySource.STARTUP_CLASSPATH -> DependencyDiscoverySource.STARTUP_CLASSPATH
        WireDependencyDiscoverySource.LOAD -> DependencyDiscoverySource.LOAD
    }

internal fun DependencyDiscoverySource.toWire(): WireDependencyDiscoverySource =
    when (this) {
        DependencyDiscoverySource.STARTUP_CLASSPATH -> WireDependencyDiscoverySource.STARTUP_CLASSPATH
        DependencyDiscoverySource.LOAD -> WireDependencyDiscoverySource.LOAD
    }
