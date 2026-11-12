package dev.otherlode.testkit

import dev.otherlode.export.DependencyDiscoverySource as WireDependencyDiscoverySource
import dev.otherlode.export.DisabledEndpointModuleKind as WireDisabledEndpointModuleKind
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
 * argument. [INITIALIZER_RETURNED] counts normal returns from a class's static initialiser, and
 * [INITIALIZER_THREW] throws out of it. No [ProbeRef] the collector returns has either of these two
 * kinds. A minor release can add values, so a `when` over this enum needs an `else` branch.
 */
public enum class ProbeKind { METHOD, BRANCH, OPTIONAL_ARGUMENT, INITIALIZER_RETURNED, INITIALIZER_THREW }

/**
 * The compiler feature that wrote a method's body in place of the adopter. A minor release can add
 * values, so a `when` over this enum needs an `else` branch.
 */
public enum class GeneratedBy {
    /** A method the compiler writes for an enum, such as `values` or `valueOf`. */
    ENUM,

    /** A member the compiler writes for a Kotlin data class or data object, such as `copy` or `componentN`. */
    DATA_CLASS,

    /**
     * A forwarder in a Kotlin interface's `$DefaultImpls` class, or a class's stub that forwards to
     * `$DefaultImpls`.
     */
    DEFAULT_IMPLS,

    /** A Java record's `equals`, `hashCode` or `toString`. */
    RECORD,

    /** An overload that `@JvmOverloads` adds. */
    JVM_OVERLOADS,

    /** A function of a Kotlin multi-file facade that forwards to the same function on a part class. */
    MULTIFILE_FACADE,

    /** A Scala case class's plumbing, or its companion's. */
    CASE_CLASS,

    /** A static forwarder that scalac or kotlinc adds for a method of an object or a companion. */
    STATIC_FORWARDER,

    /** A serialization method scalac writes for a Scala object, such as `writeReplace`. */
    SCALA_OBJECT,

    /** A method the compiler writes for a Kotlin or Scala value class, such as `equals-impl` or a boxed forwarder. */
    VALUE_CLASS,

    /** Code the kotlinx.serialization compiler plugin generates, such as a `$serializer` class's members. */
    KOTLINX_SERIALIZATION,

    /** A forwarder scalac writes for a trait's concrete method, in a class or in the trait. */
    TRAIT_FORWARDER,

    /** A method kotlinc writes for interface delegation that forwards to the delegate's method. */
    CLASS_DELEGATION,
}

/**
 * The kind of routine code a branch outcome leads to. [ProbeRef.routine] is null for an outcome that
 * is not routine. A minor release can add values, so a `when` over this enum needs an `else` branch.
 */
public enum class RoutineKind {
    /**
     * The null side of a null check whose path does trivial stack and local work, such as loading a
     * constant, before it rejoins the other side or returns.
     */
    NULL_DEFAULT,

    /** A path that does nothing but build an exception and throw it. */
    THROW_ONLY,

    /** An outcome of a site inside the exception-path copy of a `finally` body. */
    FINALLY_COPY,
}

/**
 * The family of compiler output an unread shape belongs to. [ProbeRef.unreadShape] is null for a
 * probe that is not an unread shape. A minor release can add values, so a `when` over this enum
 * needs an `else` branch.
 */
public enum class UnreadShape {
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

    /**
     * A Scala value class's boxed method, or its companion's `hashCode$extension` or `equals$extension`, with an
     * unread body.
     */
    VALUE_CLASS,

    /** A Scala trait forwarder, in a class or in the trait, with an unread body. */
    TRAIT_FORWARDER,
}

/**
 * Why code outside scope may call a method. A minor release can add values, so a `when` over this
 * enum needs an `else` branch.
 */
public enum class OutsideCallerKind {
    /** The method overrides or implements a method that an out-of-scope type declares. */
    OVERRIDES_METHOD,

    /** A callback annotation marks the method, for example on the method itself or on one of its parameters. */
    CALLBACK_ANNOTATION,
}

/**
 * How an endpoint was discovered: [REGISTRATION] when its route was registered, [DISPATCH] when a request
 * reached it before any registration did. A minor release can add values, so a `when` over this enum needs
 * an `else` branch.
 */
public enum class EndpointDiscoverySource { REGISTRATION, DISPATCH }

/**
 * How a dependency was discovered. A minor release can add values, so a `when` over this enum needs
 * an `else` branch.
 */
public enum class DependencyDiscoverySource {
    /** Listed from the startup classpath, including jars nested in an executable Spring Boot jar or war. */
    STARTUP_CLASSPATH,

    /** Seen when a class from it loaded. */
    LOAD,
}

/** A class matched by `includePackages` that was not instrumented, with the [reason] given for it. */
@ConsistentCopyVisibility
public data class SkippedClass internal constructor(
    val className: String,
    val reason: String,
)

/**
 * An endpoint module that is switched off, with the [reason] given and the [kind] of cause. [kind] is
 * null when the manifest names no kind.
 */
@ConsistentCopyVisibility
public data class DisabledEndpointModule internal constructor(
    val module: String,
    val reason: String,
    val kind: DisabledEndpointModuleKind?,
)

/**
 * What switched an endpoint module off. A minor release can add values, so a `when` over this enum
 * needs an `else` branch.
 */
public enum class DisabledEndpointModuleKind {
    /**
     * A sign of a framework release the module was not built for, such as a `LinkageError`. A
     * `LinkageError` from the servlet module's route walk reads [ROUTE_WALK_FAILED].
     */
    LINKAGE_ERROR,

    /**
     * A throw that is not a [LINKAGE_ERROR], from the module's advice or from its work before a manifest
     * is built, or a request the advice could not read.
     */
    ADVICE_FAILED,

    /**
     * Installing the module's hooks into a framework class failed, for example because the module's own
     * `transform` threw.
     */
    TRANSFORM_FAILED,

    /** Walking a framework's route objects threw. */
    ROUTE_WALK_FAILED,

    /** A hook matched no method on the framework class it hooks. */
    HOOK_UNMATCHED,

    /** The adopter's configuration kept the module from installing. */
    CONFIGURED_OFF,

    /** The module has no dispatch point on this runtime to count its routes at. */
    ROUTES_NOT_COUNTED,
}

internal fun WireProbeKind.toTestkit(): ProbeKind =
    when (this) {
        WireProbeKind.METHOD -> ProbeKind.METHOD
        WireProbeKind.BRANCH -> ProbeKind.BRANCH
        WireProbeKind.OPTIONAL_ARGUMENT -> ProbeKind.OPTIONAL_ARGUMENT
        WireProbeKind.INITIALIZER_RETURNED -> ProbeKind.INITIALIZER_RETURNED
        WireProbeKind.INITIALIZER_THREW -> ProbeKind.INITIALIZER_THREW
    }

internal fun ProbeKind.toWire(): WireProbeKind =
    when (this) {
        ProbeKind.METHOD -> WireProbeKind.METHOD
        ProbeKind.BRANCH -> WireProbeKind.BRANCH
        ProbeKind.OPTIONAL_ARGUMENT -> WireProbeKind.OPTIONAL_ARGUMENT
        ProbeKind.INITIALIZER_RETURNED -> WireProbeKind.INITIALIZER_RETURNED
        ProbeKind.INITIALIZER_THREW -> WireProbeKind.INITIALIZER_THREW
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
        WireGeneratedBy.VALUE_CLASS -> GeneratedBy.VALUE_CLASS
        WireGeneratedBy.KOTLINX_SERIALIZATION -> GeneratedBy.KOTLINX_SERIALIZATION
        WireGeneratedBy.TRAIT_FORWARDER -> GeneratedBy.TRAIT_FORWARDER
        WireGeneratedBy.CLASS_DELEGATION -> GeneratedBy.CLASS_DELEGATION
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
        GeneratedBy.VALUE_CLASS -> WireGeneratedBy.VALUE_CLASS
        GeneratedBy.KOTLINX_SERIALIZATION -> WireGeneratedBy.KOTLINX_SERIALIZATION
        GeneratedBy.TRAIT_FORWARDER -> WireGeneratedBy.TRAIT_FORWARDER
        GeneratedBy.CLASS_DELEGATION -> WireGeneratedBy.CLASS_DELEGATION
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
        WireUnreadShape.KOTLINX_SERIALIZER_OBJECT -> UnreadShape.KOTLINX_SERIALIZER_OBJECT
        WireUnreadShape.VALUE_CLASS -> UnreadShape.VALUE_CLASS
        WireUnreadShape.TRAIT_FORWARDER -> UnreadShape.TRAIT_FORWARDER
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
        UnreadShape.KOTLINX_SERIALIZER_OBJECT -> WireUnreadShape.KOTLINX_SERIALIZER_OBJECT
        UnreadShape.VALUE_CLASS -> WireUnreadShape.VALUE_CLASS
        UnreadShape.TRAIT_FORWARDER -> WireUnreadShape.TRAIT_FORWARDER
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

internal fun WireDisabledEndpointModuleKind.toTestkit(): DisabledEndpointModuleKind? =
    when (this) {
        WireDisabledEndpointModuleKind.UNSPECIFIED -> {
            null
        }

        WireDisabledEndpointModuleKind.LINKAGE_ERROR -> {
            DisabledEndpointModuleKind.LINKAGE_ERROR
        }

        WireDisabledEndpointModuleKind.ADVICE_FAILED -> {
            DisabledEndpointModuleKind.ADVICE_FAILED
        }

        WireDisabledEndpointModuleKind.TRANSFORM_FAILED -> {
            DisabledEndpointModuleKind.TRANSFORM_FAILED
        }

        WireDisabledEndpointModuleKind.ROUTE_WALK_FAILED -> {
            DisabledEndpointModuleKind.ROUTE_WALK_FAILED
        }

        WireDisabledEndpointModuleKind.HOOK_UNMATCHED -> {
            DisabledEndpointModuleKind.HOOK_UNMATCHED
        }

        WireDisabledEndpointModuleKind.CONFIGURED_OFF -> {
            DisabledEndpointModuleKind.CONFIGURED_OFF
        }

        WireDisabledEndpointModuleKind.ROUTES_NOT_COUNTED -> {
            DisabledEndpointModuleKind.ROUTES_NOT_COUNTED
        }
    }

internal fun DisabledEndpointModuleKind.toWire(): WireDisabledEndpointModuleKind =
    when (this) {
        DisabledEndpointModuleKind.LINKAGE_ERROR -> WireDisabledEndpointModuleKind.LINKAGE_ERROR
        DisabledEndpointModuleKind.ADVICE_FAILED -> WireDisabledEndpointModuleKind.ADVICE_FAILED
        DisabledEndpointModuleKind.TRANSFORM_FAILED -> WireDisabledEndpointModuleKind.TRANSFORM_FAILED
        DisabledEndpointModuleKind.ROUTE_WALK_FAILED -> WireDisabledEndpointModuleKind.ROUTE_WALK_FAILED
        DisabledEndpointModuleKind.HOOK_UNMATCHED -> WireDisabledEndpointModuleKind.HOOK_UNMATCHED
        DisabledEndpointModuleKind.CONFIGURED_OFF -> WireDisabledEndpointModuleKind.CONFIGURED_OFF
        DisabledEndpointModuleKind.ROUTES_NOT_COUNTED -> WireDisabledEndpointModuleKind.ROUTES_NOT_COUNTED
    }
