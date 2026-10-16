package dev.otherlode.testkit

import dev.otherlode.export.ClassFailedToInitialize
import dev.otherlode.export.ClassLocation
import dev.otherlode.export.ClassReferences
import dev.otherlode.export.DependencyLocation
import dev.otherlode.export.DisabledEndpointModule
import dev.otherlode.export.EndpointLocation
import dev.otherlode.export.ExternalClass
import dev.otherlode.export.FailedClass
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.SkippedClass
import dev.otherlode.export.UnreportedClass
import dev.otherlode.instrumentation.VerifierRejectionWatch

/**
 * A [ProbeManifest] from an agent whose hook on `VerifyError` is installed, which is what an agent
 * of the testkit's version sends. A query that rests on zero counts refuses a manifest without it.
 */
@Suppress("LongParameterList")
fun watchedManifest(
    resource: ResourceAttributes,
    probes: List<ProbeLocation>,
    skippedClasses: List<SkippedClass> = emptyList(),
    endpoints: List<EndpointLocation> = emptyList(),
    disabledEndpointModules: List<DisabledEndpointModule> = emptyList(),
    classLocations: List<ClassLocation> = emptyList(),
    unreportedClasses: List<UnreportedClass> = emptyList(),
    dependencies: List<DependencyLocation> = emptyList(),
    classReferences: List<ClassReferences> = emptyList(),
    externalClasses: List<ExternalClass> = emptyList(),
    referencesRecorded: Boolean = false,
    dependenciesListed: Boolean = false,
    failedClasses: List<FailedClass> = emptyList(),
    payloadSequence: Long = 0,
    countsPendingSince: Long = 0,
    classesFailedToInitialize: List<ClassFailedToInitialize> = emptyList(),
    unplacedVerificationFailures: Long = 0,
    verifierRejectionsWatched: Boolean = true,
) = ProbeManifest(
    resource,
    probes,
    skippedClasses,
    endpoints,
    disabledEndpointModules,
    classLocations,
    unreportedClasses,
    dependencies,
    classReferences,
    externalClasses,
    referencesRecorded,
    dependenciesListed,
    failedClasses,
    payloadSequence,
    countsPendingSince,
    classesFailedToInitialize,
    unplacedVerificationFailures,
    verifierRejectionsWatched,
)

/** The verifier watch of an agent whose hook is installed and which has read no `VerifyError`. */
fun watchedVerifier() = VerifierRejectionWatch(watched = true, drainMessages = { emptyArray() }, takeOverflow = { 0L })
