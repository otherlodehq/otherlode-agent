---
status: accepted
---

# The testkit's public API is a compatibility surface, owned by the testkit

Decided on 2026-10-04 in a grilling session with Luke, as `STATUS.md`'s pre-release checklist item 4.

Publishing `otherlode-testkit` freezes everything public in it. As built, it froze more than anyone had chosen: its result types named the wire module's Kotlin models (`ProbeKind`, `GeneratedBy`, `RoutineKind`, `UnreadShape`, `KotlinKind`, `CallEdge`, `BranchSite`, `SkippedClass` and others) through an `implementation` dependency, so an adopter would have compiled against classes they were never given, with protobuf-java 3.25.5 on their test classpath beside whatever protobuf the application uses. Every result type was a public data class that had gained fields one at a time, each a binary break. And the testkit judged findings instance by instance where the server merges every in-scope instance, so the two could disagree about the same data.

## The design

- **Java and Kotlin callers.** `@JvmOverloads` on every function with default arguments, `@JvmStatic` on `start`, and a Java source set that compiles against the API in CI.
- **The testkit owns its types.** Every type in a public signature lives in `dev.otherlode.testkit`. The wire module and protobuf-java are shaded and relocated inside the testkit jar, so its only dependency is kotlin-stdlib, and the jar carries the NOTICE and licence files the agent jar does. Enums mirror the wire's values one for one, and a test checks the mapping is complete. "None of these" is `null`, never a `NONE` value, as `UnreachedCluster.rootFinding` already did. `SkippedClass` and `DisabledEndpointModule` keep their name and reason and drop their timestamps.
- **Result types only grow.** Data classes keep `==`, destructuring and readable assertion output, but their constructors are `internal` with `@ConsistentCopyVisibility`, so `copy` is internal too, and a field is only ever added at the end. Adopters read results; they never build them. `ProbeRef` stays one flat type for every probe kind, with the KDoc saying which fields apply to which kind.
- **Enums grow in minor releases.** Each enum's KDoc and the README say so: write an `else` branch.
- **Findings merge across instances, as the server's do.** `neverHit()`, the class findings, `unreachedClusters()`, `neverSupplied()` and `alwaysSupplied()` judge the merged hits of every instance by name, and branch rows group by `branch_key` when one is set. `ProbeRef.serviceInstanceId` and `OptionalParameterRef.serviceInstanceId` go.
- **A small surface, promoted on demand.** `callEdges`, `kotlinKind`, `unreportedClasses`, `endedCleanly`, `instancesEndedCleanly`, `agentVersion` and `UnreachedCluster.rootSite` are internal. Only the testkit's own tests used them. An internal declaration can be made public in a minor release; a public one can only be removed in a major.
- **Enforced by the build.** Kotlin's `explicitApi()` on the module, and a checked-in ABI dump (JetBrains' binary-compatibility-validator) that fails CI on an unreviewed change.
- **American spelling in identifiers**: `neverInitialized`, `ClassFinding.NEVER_INITIALIZED`, as the JDK and `RouteTemplateNormalizer.normalize` spell it. Prose keeps British spelling. The server's JSON field and route that carry the word are renamed in the same change.
- **System properties follow ADR 0016's form**: `otherlode.testkit.port` and `otherlode.testkit.startup.timeout.seconds`. No agent option begins with `testkit` or `collector`, so the agent's derived names never collide with the testkit's or the collector's own (`OTHERLODE_COLLECTOR_*`).
- **The agent and the testkit are one version.** The codec rejects an enum value it does not know, so an agent jar newer than the testkit used to fail every query with a decode error. The testkit compares each payload's `agent_version` with its own and rejects a mismatch with a message naming both versions and the fix. An empty version, an agent run from classes, skips the check.

## Considered options

- **Publishing `otherlode-wire` and making it an `api` dependency.** Rejected: the wire's Kotlin models would become a second compatibility surface, and adopters would still get protobuf-java.
- **Plain classes with hand-written `equals`, or sealed `MethodRef`/`BranchRef`/`OmissionRef` types.** Rejected: the first loses readable assertions for nothing internal constructors do not already give, and the second meets the exhaustiveness problem below the day a fourth probe kind arrives.
- **Open classes with named constants in place of enums**, as Ktor's `HttpMethod`. Rejected: no `entries`, no Java `switch`, and a value class variant has mangled names from Java. Adding an enum value breaks only a `when` with no `else`, and test code is always recompiled against the new testkit, so the break is a compile error at upgrade.
- **Lenient decoding to an `UNKNOWN` value.** Rejected: the testkit would then have to judge a probe whose origin it cannot read, and either answer can put a wrong row in `neverHit()`.
- **Judging per instance and documenting the difference.** Rejected: the testkit's value is a local preview of what the server will say, and dropping a field later costs a major while adding a per-instance view does not.

## Consequences

- A test that starts several agent JVMs against one `start()` collector gets the server's answer, not one answer per JVM.
- An adopter who bumps the agent jar must bump the testkit with it. The failure says so.
- The ABI dump makes every public change a reviewed diff, including the ones `0.x` releases may still make (ADR 0057).
