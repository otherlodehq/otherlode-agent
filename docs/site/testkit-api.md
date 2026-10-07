---
title: Testkit API
description: Every public type, query, wait, exception and system property of the testkit, with the class-name format and the compatibility promise.
order: 90
---

This page lists the testkit's public API. For setup and the pattern of an assertion, see [test your app against the agent](testkit). Everything lives in the package `dev.otherlode.testkit`, except the JUnit 5 extension in `dev.otherlode.testkit.junit5`.

The examples use Kotlin property syntax. From Java, a property is a getter: `collector.exportUrl` is `collector.getExportUrl()`, and `ProbeRef.line` is `getLine()`. A function with default arguments has an overload for each shorter argument list, so Java callers can leave the trailing arguments out.

## Class and method names

Every class name in this API is the dotted binary name the agent reports.

| Source | Name to use |
|---|---|
| A class | `com.acme.OrderService` |
| A nested class | `com.acme.Outer$Inner` |
| Top-level Kotlin functions in `Orders.kt` | `com.acme.OrdersKt` |
| Top-level Kotlin functions in a file with `@file:JvmName("Billing")` | `com.acme.Billing` |
| A function in a `@file:JvmMultifileClass` file | the part class, `com.acme.Billing__OrderTotalsKt` |

A multi-file facade, `com.acme.Billing` in the last row, holds only forwarders that call the part class, and the agent marks them `MULTIFILE_FACADE`. Query the part class to count calls.

A method name is the name in the source. A constructor is `<init>` and a static initialiser is `<clinit>`. A method descriptor is the JVM descriptor, such as `(Ljava/lang/String;I)V`. Where a query takes a descriptor, `null` matches every overload and a value selects one.

## OtherlodeTestCollector

`OtherlodeTestCollector` is an HTTP server that accepts the agent's payloads. It implements `AutoCloseable`, so use it in a `use` block or a try-with-resources statement.

The agent under test must run with `includePackages` set and `exportUrl` pointing at the collector. Without `includePackages` the agent disables itself and never sends anything.

### Start and stop

| Member | Description |
|---|---|
| `OtherlodeTestCollector.start(port: Int = 0)` | Starts a collector bound to `localhost`. Port `0` picks a free port. The server threads are daemon threads, so a collector never keeps the JVM alive. Throws an `IOException` if the port cannot be bound. The method does not declare it, so Java code cannot name `IOException` in a `catch` clause around the call. |
| `exportUrl: String` | The base URL for the agent's `exportUrl` option, such as `http://localhost:54321`. |
| `close()` | Stops the server. A wait that is running, or begins later, throws `IllegalStateException`. |

The collector serves the agent's three payload types: delta batches, probe manifests, and static baselines. It keeps what the agent reports for each instance, and each query merges instances by name, as described below.

### Waits

A wait blocks the calling thread until its condition holds. Each takes a `java.time.Duration` and throws `TimeoutException` when the duration elapses first. Each also throws `IllegalStateException` at once when the collector has rejected a payload (see [rejected payloads](#rejected-payloads)) or when the collector closes during the wait.

| Wait | Returns when |
|---|---|
| `awaitNextFlush(timeout)` | A delta batch has arrived that was received after the call began. An empty batch counts, because the agent sends one on every flush as a liveness signal. |
| `awaitSettled(timeout)` | Two delta batches, from any instance, have arrived after the call began. One batch can miss the hits of the action that just finished, and a probe's manifest entry can arrive after the batch that carries its count. Two batches span a full flush. |
| `awaitProbe(className, methodName, timeout)` | Some manifest has named a probe in that class for that method. Use it when the test needs the manifest entry itself. |
| `awaitEndpoint(verb, routeTemplate, timeout)` | Some manifest has named that endpoint. Verb and route template are normalised as in [endpoint queries](#endpoint-queries). |
| `awaitDependency(groupId, artifactId, timeout)` | Some manifest has listed the dependency. After it returns, `dependency` does not throw `UnknownDependencyException`. |
| `awaitDependenciesListed(timeout)` | At least one instance has been heard from, and every instance heard from has finished listing its dependencies. An agent whose listing failed never finishes, so the wait times out for it. |

### Method queries

`wasHit` and `hitCount` look at method probes only. Both throw `UnknownProbeException` for a class or method the collector has no probe for, rather than answering `false` or `0`. The exception section below lists the reasons.

| Query | Returns |
|---|---|
| `wasHit(className, methodName, methodDescriptor = null): Boolean` | `true` if any instance reported a count above zero for a matching method. |
| `hitCount(className, methodName, methodDescriptor = null): Long` | The sum, over every matching method and every instance, of the highest count that instance delivered. A null descriptor sums every overload. |
| `omissionCount(className, methodName, parameterIndex: Int, methodDescriptor = null): Long` | The number of calls that left out the optional parameter at that zero-based index, summed the same way. |
| `omissionCount(className, methodName, parameterName: String, methodDescriptor = null): Long` | The same, selecting the parameter by name. A name is available when the class was compiled with debug information. |

For `omissionCount`, `className` is the class that declares the function. For a Scala constructor default, that is the constructor's own class, not the companion object's class.

### Findings

A finding method takes no arguments and returns a list. It judges the merged hits of every instance heard from, by name. A probe, class, or call edge that one instance reports is the same one in another instance, whatever number each instance gave it. A branch outcome is the same outcome by its branch key when it has one, and by its branch index when it has none. A finding names no instance. The server merges instances the same way, so a finding here previews the server's.

| Query | Returns |
|---|---|
| `neverHit(): List<ProbeRef>` | Every method or branch outcome that no instance hit and that is worth a person's attention. See the exclusions after this table. |
| `neverHitRoutineOutcomes(): List<ProbeRef>` | The branch outcomes `neverHit` leaves out because they are routine, each with its `routine` kind. |
| `neverHitUnreadShapes(): List<ProbeRef>` | The probes `neverHit` leaves out because their code has the outline of compiler output the agent cannot read, each with its `unreadShape` family. |
| `neverInitialized(): List<ClassFindingRef>` | Classes some instance loaded whose static initialiser ran in no instance. A class with no static initialiser is never listed. |
| `neverInstantiated(): List<ClassFindingRef>` | Classes some instance loaded, that are not never initialised, with a constructor and an instance method, and none of whose constructors ran. A class with only static methods and an interface are never listed. |
| `neverLoaded(): List<String>` | Class names a complete static baseline declared that no instance ever loaded. Throws `IllegalStateException` until a complete baseline has arrived, which needs the agent option `staticBaselineEnabled=true`. |
| `failedToLoad(): List<String>` | Class names the agent wove that the JVM never defined in any instance. Each is a deployment problem to fix and never dead code. |
| `skippedClasses(): List<SkippedClass>` | Classes the include rules matched that the agent could not instrument, each with a reason. Sorted by class name. |
| `unreachedClusters(): List<UnreachedCluster>` | Every unreached cluster in the call graph, largest first. See [call graph](call-graph) for the rules. |
| `neverSupplied(): List<OptionalParameterRef>` | Optional parameters that every call left out, so the parameter can go. Never listed for an overridable function. |
| `alwaysSupplied(): List<OptionalParameterRef>` | Optional parameters that no call left out while the function was called, so the default value is dead. |

`neverHit` leaves these out, and each has its own query or none at all:

- A method inside a Kotlin inline function, and a branch inside one. A Kotlin caller copies the body, so a zero count proves nothing.
- A generated method, such as an enum's `values` or a data class's `copy`.
- Optional-argument probes, which `neverSupplied` and `alwaysSupplied` cover.
- A `<clinit>`, which is a class state. A constructor appears only as an unused overload, when another constructor of its class ran.
- A method that can run only through a class that `neverInitialized` or `neverInstantiated` lists, and a lambda body whose every creator is such a method or a never-hit method listed.
- A branch inside code that never ran. It folds into the row for that code.
- A routine outcome and an unread shape, listed by the two queries above.

`neverHit` rows sort by class name, method name, line, then branch index. The class and cluster queries sort by class name, and `skippedClasses` by class name.

### Endpoint queries

`wasCalled`, `callCount` and `awaitEndpoint` normalise `verb` and `routeTemplate` before lookup, so `wasCalled("get", "/checkout/")` finds the endpoint the agent reports as `GET /checkout`. An endpoint is identified by that normalised pair alone. Several instances that serve one endpoint merge into one.

| Query | Returns |
|---|---|
| `wasCalled(verb, routeTemplate): Boolean` | `true` if the summed call count is above zero. |
| `callCount(verb, routeTemplate): Long` | The count summed over instances, each instance's highest delivered total. |
| `neverCalled(): List<EndpointRef>` | Every endpoint with a summed count of zero, sorted by route template, then verb. |
| `endpoints(): List<EndpointRef>` | Every endpoint any instance reported, sorted the same way. |
| `disabledEndpointModules(): List<DisabledEndpointModule>` | The endpoint modules that switched themselves off, such as on a framework version the agent does not support. Sorted by module name. |

`wasCalled` and `callCount` throw `UnknownEndpointException` for an endpoint no manifest has named.

### Dependency queries

Dependency queries apply the dependency rules within the one test JVM. See [dependencies](dependencies) for what the statuses mean. Where the data cannot answer yet, a query throws `IllegalStateException` instead of returning an empty list, because an empty list would read as "none".

| Query | Returns |
|---|---|
| `dependency(groupId: String?, artifactId: String): DependencyStatus` | The status of one dependency, merged across instances. A null or empty `groupId` matches a dependency identified by its file name, which has no group. A shaded jar matches any identity it carries. |
| `unloadedDependencies(): List<DependencyStatus>` | Dependencies listed from a startup classpath with no class loaded from them. |
| `unreferencedDependencies(): List<DependencyStatus>` | Loaded dependencies that nothing in your code references. |
| `unreachedDependencies(): List<DependencyStatus>` | Dependencies referenced only from methods never hit or classes never loaded. |
| `failedToLoadDependencies(): List<DependencyStatus>` | Loaded dependencies with no live reference that a class which failed to load references. The status asks for a review and does not say the dependency can go. |
| `absentReferences(): List<AbsentReference>` | Referenced classes that no class loader could find, such as code guarded by a check for an optional library. |

The five list queries sort by `DependencyStatus.identityKey`, except `absentReferences`, which sorts by class name. They throw `IllegalStateException` in these cases:

- No instance has been heard from yet, or an instance has not finished listing its dependencies. Call `awaitDependenciesListed` first.
- For `unreferencedDependencies`, `unreachedDependencies` and `failedToLoadDependencies`: no instance records references, which needs `includePackages`, or a loaded dependency cannot be split into unreferenced and unreached because an instance sent no complete static baseline (`staticBaselineEnabled=true`).
- For `absentReferences`: no instance records references.

`dependency` throws `IllegalStateException` when more than one dependency carries the identity and none has it alone. The message names each candidate's `identityKey`. It does not wait for the listing to finish. Call `awaitDependency`, and `awaitDependenciesListed` when a shaded jar may carry the identity. The hits and reference sites that separate used from unreached arrive like any probe data, so call `awaitSettled` before asking.

### Rejected payloads

`rejectedPayloads(): List<String>` lists the reason for each payload the collector answered with HTTP 400, oldest first. The collector keeps nothing from a rejected payload. The agent does not resend a payload after a 400.

Once the list is not empty, every other query and wait throws `IllegalStateException` that lists the reasons. Only `rejectedPayloads` and `close` keep working. A test fails even if it never reads the list.

A payload is rejected when:

- Its run id is empty.
- A second run id arrives under an instance id the collector already heard from. The collector keys on the instance id alone and accepts the first run id.
- It carries an agent version that differs from the testkit's version. The reason names both versions. An empty version on either side skips the check.
- A collector between the agent and the testkit stripped fields it did not know.
- It does not decode, which happens when the agent is newer than the testkit or something other than the agent posted to the port.
- Applying it failed.
- The collector serves one test JVM, as the extension's does, and the payload comes from a second instance. The reason names `maxParallelForks` above 1 and a child JVM that has the agent.

## OtherlodeExtension

`dev.otherlode.testkit.junit5.OtherlodeExtension` is a JUnit 5 extension. JUnit 5 is `compileOnly` for the testkit, so the extension needs `junit-jupiter-api` on your test classpath, and nothing else in the testkit does.

Register it with `@ExtendWith(OtherlodeExtension::class)`. It starts one collector per test JVM, keeps it for the life of the JVM, and injects it into any test or lifecycle method parameter of exactly the type `OtherlodeTestCollector`. The agent must already be on the test JVM through the `-javaagent` flag, because the extension never attaches it.

| Member | Description |
|---|---|
| `beforeAll` | On the first test class in the JVM, waits for the agent's first delta batch with `awaitNextFlush`. Throws `IllegalStateException` if none arrives within the startup timeout. The message gives the `-javaagent` flag to add. |
| `OtherlodeExtension.collector()` | A static accessor for the collector the extension started. Throws `IllegalStateException` until a test class with the extension has run. |

The extension's collector accepts payloads from one agent instance only. It is not stopped when JUnit closes its store, so the agent's shutdown flush still reaches it.

The extension throws `IllegalStateException` when the collector cannot bind its port, when a system property below is malformed, and when the first delta batch does not arrive in time.

## System properties

The testkit reads two system properties, both in the test JVM and both read by the extension. A collector you start with `start` ignores them.

| Property | Default | Meaning |
|---|---|---|
| `otherlode.testkit.port` | `4319` | The port the extension's collector binds. 4319 is the agent's default export port, so a `-javaagent` flag with no `exportUrl` works. A value that is not an integer throws `IllegalStateException`. A blank value counts as unset. |
| `otherlode.testkit.startup.timeout.seconds` | `15` | How long `beforeAll` waits for the first delta batch. A value that is not a positive integer throws `IllegalStateException`. A blank value counts as unset. |

The agent's default flush interval is 60 seconds, longer than the default timeout. Set the agent's `flushIntervalSeconds` to 1 in a test.

## Exceptions

| Exception | Thrown by | Meaning |
|---|---|---|
| `UnknownProbeException` | `wasHit`, `hitCount`, `omissionCount` | The collector has no probe for the class or method named. |
| `UnknownEndpointException` | `wasCalled`, `callCount` | No manifest has named the endpoint. |
| `UnknownDependencyException` | `dependency` | No manifest has listed the dependency. |
| `IllegalStateException` | Queries, waits, the extension | The collector cannot give a trustworthy answer. See below. |
| `java.util.concurrent.TimeoutException` | Every `await` method | The condition did not hold within the timeout. |

These three exception classes extend `RuntimeException`. Each exists so that "no idea" never looks like "confirmed never hit".

### UnknownProbeException

The message names the first reason below that applies.

1. The class matched `includePackages` but could not be instrumented. The message gives the skip reason, the same text `skippedClasses` lists.
2. The class loaded, but no transformer saw it, so it has no probes. The JVM hands a class loaded from inside another class's transformation to no transformer.
3. A complete static baseline declared the class and no instance ever loaded it.
4. The class is instrumented, but has no probe for that method name or descriptor. For `omissionCount`, it has no omission probe for that method and parameter.
5. No manifest or baseline ever mentioned the class: it is outside `includePackages`, misspelled, or not loaded yet.

Cases 3 and 5 can clear on their own as the application runs. Wait for the class with `awaitProbe` before asking.

### UnknownEndpointException

1. At least one endpoint module reported itself disabled. The message lists every disabled module and its reason, because the endpoint may belong to one of them.
2. No manifest from any instance named the endpoint.

### UnknownDependencyException

The message names the dependency and every dependency the collector knows. When no manifest has listed any dependency yet, it says so and suggests `awaitDependency`.

### IllegalStateException

| Cause | Where |
|---|---|
| The collector rejected a payload. | Every query and wait. |
| The collector closed during a wait. | Every wait. |
| No complete static baseline has arrived. | `neverLoaded` |
| Dependency listing is incomplete, references are not recorded, or no complete baseline exists. | The dependency list queries, `absentReferences` |
| Several dependencies carry the identity. | `dependency` |
| The extension could not start or the agent's first batch did not arrive. | `OtherlodeExtension` |

## Result types

Every result type is a data class, so `equals`, `hashCode`, `toString` and destructuring work. Constructors and `copy` are internal. Test code reads these types and never builds them.

Where a field below is null, the fact does not apply. No enum has a `NONE` value.

### ProbeRef

One probe, merged across instances. A single flat type serves every probe kind, so some fields apply to some kinds only.

| Field | Type | Meaning |
|---|---|---|
| `className` | `String` | The class that holds the probe. |
| `methodName` | `String` | The method that holds the probe. |
| `methodDescriptor` | `String` | The method's JVM descriptor. |
| `line` | `Int` | The source line. `-1` for a member of a cluster that exists only in the static baseline, and for a class compiled without line numbers. |
| `kind` | `ProbeKind` | What the probe counts. |
| `branchIndex` | `Int?` | The outcome's ordinal within its class for one build. Null for a method probe. When copies of the class disagree, the lowest. |
| `branchKey` | `String?` | A lowercase hex token that names the outcome across builds and instances. Null for a method probe, and for a branch outcome the agent could not name safely. |
| `inline` | `Boolean` | `true` for a Kotlin inline function or a branch inside one. |
| `inlinedFromClassName` | `String?` | For a branch copied from an inline function, the class that function came from. |
| `generatedBy` | `GeneratedBy?` | What generated the method. For a branch, the mark of the method it sits in. For an optional-argument probe, the mark of its target. |
| `routine` | `RoutineKind?` | Why a branch outcome is not worth a person's time. Only on branch probes. |
| `unreadShape` | `UnreadShape?` | The family of compiler output whose body the agent could not read. Exclusive with `routine`. |
| `outsideCaller` | `OutsideCaller?` | Why code outside your scope may call the method. Only on method probes. |
| `parameterIndex` | `Int?` | The optional parameter's zero-based index. Only on optional-argument probes. |
| `parameterName` | `String?` | The parameter's name, when known. Only on optional-argument probes. |
| `overridable` | `Boolean` | `true` when the function an optional-argument probe belongs to can be overridden. |
| `targetClassName` | `String?` | For an optional-argument probe, the class of the function when it differs from the class that holds the probe. |
| `neverLoaded` | `Boolean` | `true` for a cluster member that exists only because a complete static baseline declared it. |

Where instances disagree about a row, the collector applies the server's choice. The newest instance's line wins. A row is inline when any copy is. `generatedBy` and `inlinedFromClassName` take the greatest value. A row is routine or an unread shape when any copy is.

### OutsideCaller

| Field | Type | Meaning |
|---|---|---|
| `kind` | `OutsideCallerKind` | Why outside code may call the method. |
| `typeName` | `String` | For `OVERRIDES_METHOD`, the out-of-scope type that declares the overridden method. For `CALLBACK_ANNOTATION`, the annotation as written on the method. |

### ClassFindingRef

Returned by `neverInitialized` and `neverInstantiated`.

| Field | Type | Meaning |
|---|---|---|
| `className` | `String` | The class. |
| `finding` | `ClassFinding` | Which finding applies. |
| `methods` | `List<String>` | The names of its methods, one entry per name, sorted. Includes constructors as `<init>`, and inline and generated methods, but never `<clinit>`. |
| `instancesLoading` | `Int` | How many instances loaded the class. |

### UnreachedCluster

A root plus every never-hit method that can be reached from it only through the cluster.

| Member | Type | Meaning |
|---|---|---|
| `root` | `ProbeRef` | The root. A method probe for a method root. The branch probe of the outcome for an `UNTAKEN_OUTCOME` root. For a `CLASS_FINDING` root, a ref that names only the class: empty method name and descriptor, line `0`, and `neverLoaded` true for a never-loaded class. |
| `rootKind` | `RootKind` | Which of the five root shapes it has. |
| `rootFinding` | `ClassFinding?` | The class's finding for a `CLASS_FINDING` root. Null otherwise. |
| `reachedFrom` | `List<ProbeRef>` | For a `REACHED_FROM_HIT` root, and a `CLASS_FINDING` root that a method with hits calls, the methods with hits that call it. Empty otherwise. |
| `members` | `List<ProbeRef>` | Every method in the cluster except those in `wholeClasses`. |
| `wholeClasses` | `List<WholeClass>` | The classes the cluster holds whole, sorted by class name. |
| `neverLoadedClasses` | `Int` | The number of classes in the cluster that exist only because a static baseline declared them. |
| `membersTotal` | `Int` | The number of methods the cluster holds. |
| `methods` | `List<ProbeRef>` | All of those methods, sorted as `neverHit` sorts. |

Neither `members` nor `wholeClasses` lists a `<clinit>`.

### WholeClass

| Field | Type | Meaning |
|---|---|---|
| `className` | `String` | The class. |
| `finding` | `ClassFinding?` | The class's finding, when it has one. |
| `methods` | `List<ProbeRef>` | Its methods in the cluster, sorted as `neverHit` sorts. |

### OptionalParameterRef

Returned by `neverSupplied` and `alwaysSupplied`. It names the function the parameter belongs to, not the synthetic method that holds the probe.

| Field | Type | Meaning |
|---|---|---|
| `className` | `String` | The class of the function. |
| `methodName` | `String` | The function's name. |
| `methodDescriptor` | `String` | The function's descriptor. |
| `parameterIndex` | `Int` | The parameter's zero-based index. |
| `parameterName` | `String` | The parameter's name, or empty when unknown. |
| `line` | `Int` | The highest line among the omission probes, or `-1`. |
| `targetClassName` | `String?` | The raw target class the manifests reported, set only when the target crosses a class boundary. |

### EndpointRef

| Field | Type | Meaning |
|---|---|---|
| `verb` | `String` | The normalised verb, uppercase, or `*` when the endpoint is not limited to one. |
| `routeTemplate` | `String` | The normalised route template. |
| `verbatimTemplate` | `String` | The framework's own spelling. |
| `framework` | `String` | The framework or module that reported it. |
| `discoverySource` | `EndpointDiscoverySource` | How the agent learned of it. |
| `handlerClass` | `String?` | The handler's class, once a framework hook has joined one. |
| `handlerMethod` | `String?` | The handler's method, where the framework exposes one. |
| `handlerDescriptor` | `String?` | The handler method's descriptor. |

### DisabledEndpointModule and SkippedClass

| Type | Fields |
|---|---|
| `DisabledEndpointModule` | `module: String`, `reason: String`, `kind: DisabledEndpointModuleKind` |
| `SkippedClass` | `className: String`, `reason: String` |

### DependencyStatus

| Field | Type | Meaning |
|---|---|---|
| `identityKey` | `String` | The identities of the dependency joined with commas, such as `com.fasterxml.jackson.core:jackson-databind`. An identity read from a file name has no group and reads `:commons-lang3`. |
| `status` | `DependencyUsage` | What the dependency rules say. |
| `identities` | `List<DependencyIdentityRef>` | Every `groupId:artifactId` the dependency carries. A shaded jar carries several. |
| `loadedClassesTotal` | `Long` | The largest count of distinct loaded classes any one instance reported. |
| `classCount` | `Int?` | The jar's class count, when the listing knew it. |
| `discoverySources` | `Set<DependencyDiscoverySource>` | How instances learned of it. |
| `sites` | `List<DependencyReferenceSite>` | Every place in your code that references it, live or not. Empty for `UNREFERENCED`. |

### DependencyIdentityRef, DependencyReferenceSite, AbsentReference

| Type | Field | Type | Meaning |
|---|---|---|---|
| `DependencyIdentityRef` | `groupId` | `String` | Empty for an identity read from a file name. |
| | `artifactId` | `String` | The artifact id. |
| | `versions` | `Set<String>` | Every non-empty version any instance listed. |
| `DependencyReferenceSite` | `className` | `String` | The class that references the dependency. |
| | `methodName` | `String?` | The method, or null for a reference at class level. |
| | `neverLoaded` | `Boolean` | `true` when the static baseline declared the site and its class never loaded. |
| | `failedToLoad` | `Boolean` | `true` when the site's class failed to load in some instance and loaded in none. |
| `AbsentReference` | `className` | `String` | The referenced class no loader could find. |
| | `sites` | `List<DependencyReferenceSite>` | Every site that references it, sorted. |

`DependencyReferenceSite.toString()` reads `Class#method`, `Class (class level)`, and appends `(never loaded)` or `(failed to load)` where those flags are set.

## Enums

Each enum lists its values in declaration order.

### ProbeKind

What one probe counts.

| Value | Meaning |
|---|---|
| `METHOD` | Entries to a method. |
| `BRANCH` | One outcome of a conditional or a switch. |
| `OPTIONAL_ARGUMENT` | Calls that leave out one optional parameter's argument. |

### GeneratedBy

What produced a method, read from the bytecode alone.

| Value | Meaning |
|---|---|
| `ENUM` | An enum's `values`, `valueOf` or `getEntries`. |
| `DATA_CLASS` | A Kotlin data class's `componentN` and `copy`, and whichever of `equals`, `hashCode` and `toString` you did not write. |
| `DEFAULT_IMPLS` | A `$DefaultImpls` method that only forwards to the interface's own default method. |
| `RECORD` | A Java record's `equals`, `hashCode` or `toString`. |
| `JVM_OVERLOADS` | An overload that `@JvmOverloads` adds, whose body only forwards to its `$default` twin. |
| `MULTIFILE_FACADE` | A function of a Kotlin multi-file facade whose body only forwards to the same function on a part class. |
| `CASE_CLASS` | A Scala case class's plumbing, or its companion's. |
| `STATIC_FORWARDER` | A static forwarder that scalac adds for an object's method. |
| `SCALA_OBJECT` | A Scala object's `writeReplace`. |

### RoutineKind

Why a kept branch outcome is real but not worth a person's time when it never runs.

| Value | Meaning |
|---|---|
| `NULL_DEFAULT` | The null side of a null check whose path calls nothing before it rejoins the other side or leaves the method. |
| `THROW_ONLY` | A path that only builds an exception and throws it. |
| `FINALLY_COPY` | An outcome inside the exception-path copy of a `finally` body. |

### UnreadShape

The family of compiler output a probe's code belongs to when the agent could not read its body.

| Value | Meaning |
|---|---|
| `CASE_CLASS` | A Scala case class's plumbing, or its companion's, whose body the agent has not read. |
| `STATIC_FORWARDER` | A static method with a `$` twin of the same name and descriptor, whose body is unread. |
| `SCALA_OBJECT` | A Scala object's `writeReplace` or `readResolve`, whose body the agent has not read. |
| `SCALA_ENUM` | Scala 3 enum plumbing whose body the agent has not read. |
| `MULTIFILE_FACADE` | A method of a Kotlin multi-file facade that is not a recognised forwarder. |
| `COROUTINE_MACHINERY` | A jump or switch in a suspend-shaped method that matches no coroutine shape the agent reads. |
| `STRING_SWITCH` | The collision side of a bucket in a string switch's `hashCode` lowering that the agent cannot read. |

### OutsideCallerKind

Why code outside your scope may call a method.

| Value | Meaning |
|---|---|
| `OVERRIDES_METHOD` | The method overrides or implements a method that an out-of-scope type declares. |
| `CALLBACK_ANNOTATION` | The method, or one of its parameters, carries an annotation that a framework calls through. |

### RootKind

The shape of an unreached cluster's root. The shapes call for different fixes.

| Value | Meaning |
|---|---|
| `REACHED_FROM_HIT` | A method with at least one in-scope caller that has hits. Often an override that a virtual call never reached, or a call an exception cut short. |
| `UNCALLED` | A method with no in-scope caller. |
| `CALLED_FROM_OUTSIDE_SCOPE` | A method with no in-scope caller and an outside caller, named by `ProbeRef.outsideCaller`. Weaker evidence for deletion than `UNCALLED`. |
| `UNTAKEN_OUTCOME` | A branch outcome never taken, in a method with hits. Deleting that side of the branch removes the cluster. |
| `CLASS_FINDING` | A class that holds a class finding, named by `rootFinding`. Listed only when the cluster holds more than the methods the finding already covers. |

### ClassFinding

A finding about a whole class. A class holds at most one, the first that applies in this order.

| Value | Meaning |
|---|---|
| `NEVER_LOADED` | A complete static baseline declared the class and no instance ever loaded it. See `neverLoaded`. |
| `NEVER_INITIALIZED` | The class loaded and its static initialiser never ran. |
| `NEVER_INSTANTIATED` | The class loaded, has instance methods, and none of its constructors ran. |

### EndpointDiscoverySource

| Value | Meaning |
|---|---|
| `REGISTRATION` | The framework declared the endpoint when it registered it. |
| `DISPATCH` | A request matched the endpoint before any registration was seen. |

### DependencyDiscoverySource

| Value | Meaning |
|---|---|
| `STARTUP_CLASSPATH` | Listed from the startup classpath, which includes the jars under a fat jar's `BOOT-INF/lib`, `WEB-INF/lib` and `lib-provided`. |
| `LOAD` | Seen only because a class from it loaded. Such a dependency never reads as unloaded. |

### DisabledEndpointModuleKind

What switched an endpoint module off.

| Value | Meaning |
|---|---|
| `LINKAGE_ERROR` | A `LinkageError`, wherever it was caught: the framework release differs from the one the module was built for. |
| `ADVICE_FAILED` | Any other throw from the module's advice. |
| `TRANSFORM_FAILED` | The module's own transform threw, or a framework class it hooks failed to weave. |
| `ROUTE_WALK_FAILED` | Walking a framework's route objects threw. |
| `HOOK_UNMATCHED` | A hook matched no method on the framework class it hooks: a framework release renamed or changed the method. |

### DependencyUsage

What the dependency rules say about one dependency.

| Value | Meaning |
|---|---|
| `UNLOADED` | Listed from a startup classpath, and no instance loaded a class from it. |
| `UNREFERENCED` | A class from it loaded, and a complete baseline shows nothing in your code references it. |
| `UNREACHED` | Referenced only from methods never hit or classes never loaded, per a complete baseline. |
| `NO_LIVE_REFERENCE` | Loaded, with no live reference, from an instance that sent no complete baseline. It is unreferenced or unreached, and the two cannot be told apart. |
| `FAILED_TO_LOAD` | Loaded, with no live reference, and referenced from a class that failed to load. A prompt to review, not a claim that it can go. |
| `USED` | Referenced from a method with hits, or at class level by a class that loaded. |
| `LOADED` | Loaded, and no instance that lists it records references, so nothing further is claimed. |
| `RESOURCES_ONLY` | The listing counted no class in it, as with native libraries, web assets and message bundles. Nothing is claimed. |

## Compatibility

The testkit and the agent share one version. The collector rejects a payload from an agent of another version, and the rejection names both versions. Use the testkit built from the agent's version.

Result types and enums only grow in a minor release:

- A data class gains fields at the end, so destructuring positions stay valid.
- An enum gains values.
- A method that returns a list can return rows it did not return before, when the agent learns to report a new shape.

A `when` over any enum in this API needs an `else` branch. The enums are `ProbeKind`, `GeneratedBy`, `RoutineKind`, `UnreadShape`, `OutsideCallerKind`, `RootKind`, `ClassFinding`, `EndpointDiscoverySource`, `DisabledEndpointModuleKind`, `DependencyDiscoverySource` and `DependencyUsage`. A new value breaks a `when` without `else` at compile time, when you upgrade the testkit.

Because `GeneratedBy`, `RoutineKind` and `UnreadShape` have no "none" value, the matching `ProbeRef` field is null when the probe has no mark.

The testkit's jar bundles its own protobuf and wire classes under `dev.otherlode.testkit.shaded`. No type in this API comes from them.
