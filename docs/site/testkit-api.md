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
| `OtherlodeTestCollector.start(port: Int = 0)` | Starts a collector bound to `localhost`. Port `0` picks a free port. The server threads are daemon threads, so a collector never keeps the JVM alive. Throws an `IOException` if it cannot bind the port. The method does not declare it, so Java code cannot name `IOException` in a `catch` clause around the call. |
| `exportUrl: String` | The base URL for the agent's `exportUrl` option, such as `http://localhost:54321`. |
| `close()` | Stops the server. A wait that is running, or begins later, throws `IllegalStateException` unless its condition already holds. |

The collector serves the agent's four payload types: delta batches, probe manifests, static baselines and class listings. It keeps what the agent reports for each instance, and each query merges instances by name, as described below. It keeps nothing from a class listing, but checks it as it checks every other payload. [Rejected payloads](#rejected-payloads) lists the checks.

### Waits

A wait blocks the calling thread until its condition holds. Each takes a `java.time.Duration` and throws `TimeoutException` when the duration elapses first. Each throws `IllegalStateException` when the collector has rejected a payload (see [rejected payloads](#rejected-payloads)), or when the collector is closed and the condition does not hold.

| Wait | Returns when |
|---|---|
| `awaitNextFlush(timeout)` | A delta batch has arrived since the call began. An empty batch counts, because the agent sends one on every flush as a liveness signal. |
| `awaitSettled(timeout)` | Every instance heard from has completed two flushes since the call began, or has ended with a complete shutdown flush. A flush is complete when its last delta batch carries `flush_complete`. The first complete flush can have read its counts before your last action, so the second one holds every hit made before the call. An instance whose latest payload says `starting` has not settled, unless it has ended with a complete shutdown flush. The agent keeps marking its payloads `starting` until the application has loaded its classes, so give the timeout room for that. An instance whose latest payload says its counts are pending has not settled either, even after it ended. At the timeout the message names the instance and why it has not settled. With no instance heard from yet, it keeps waiting. An instance that went silent, or whose shutdown flush was not complete, never settles, and the timeout names it. A probe's manifest entry can still arrive after its count, so wait for it with `awaitProbe`. |
| `awaitProbe(className, methodName, timeout)` | Some manifest has named a probe in that class for that method. Use it when the test needs the manifest entry itself. |
| `awaitEndpoint(verb, routeTemplate, timeout)` | Some manifest has named that endpoint. The wait normalises the verb and the route template as [endpoint queries](#endpoint-queries) describes. |
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

A finding method takes no arguments and returns a list. It judges the merged hits of every instance heard from, by name. A probe, class, or call edge that one instance reports is the same one in another instance, whatever number each instance gave it. A branch outcome is the same outcome by its branch key when it has one, and by its branch index when it has none. A finding names no instance.

| Query | Returns |
|---|---|
| `neverHit(): List<ProbeRef>` | Every method or branch outcome that no instance hit and that is worth a person's attention. See the exclusions after this table. |
| `neverHitRoutineOutcomes(): List<ProbeRef>` | The branch outcomes `neverHit` leaves out because they are routine, each with its `routine` kind. |
| `neverHitUnreadShapes(): List<ProbeRef>` | The probes `neverHit` leaves out because their code has the outline of compiler output the agent cannot read, each with its `unreadShape` family. |
| `neverInitialized(): List<ClassFindingRef>` | Classes some instance loaded whose static initialiser ran in no instance. The query never lists a class with no static initialiser. |
| `neverInstantiated(): List<ClassFindingRef>` | Classes some instance loaded, that `neverInitialized()` does not list, and none of whose constructors or instance methods ran, generated and inline instance methods included. A listed class has a constructor and an instance method that are neither inline, generated nor an unread shape. The query never lists a class with only static methods, or an interface. |
| `neverLoaded(): List<String>` | Class names a complete static baseline declared that no instance ever loaded. Lists a class only when at least one complete scan declares a method of it that is not inline, generated or an unread shape. Leaves out a class in `failedToLoad()`. Leaves out the scan of an instance that has stopped and either sent no delta batch or stopped while its latest payload said `starting`. Throws `IllegalStateException` until a complete baseline has arrived, which needs the agent option `staticBaselineEnabled=true`, and while a live instance says it is starting, has sent no delta batch or has pending counts. A baseline is complete when the collector holds every chunk of one scan. A chunk whose chunk count differs from the one its scan began with does not count, and neither does a chunk it already holds. For each instance the collector reads its latest complete scan, and it joins the classes, methods and call edges of every instance's latest scan, so the answer does not depend on which instance's scan arrived first. |
| `failedToLoad(): List<String>` | Class names the agent wove that the JVM never defined in any instance. Each is a deployment problem to fix and never dead code. |
| `failedToInitialize(): List<String>` | Class names that failed to initialise, sorted. See [Classes that failed to initialise](#classes-that-failed-to-initialise). Each is a deployment problem to fix and never dead code. |
| `skippedClasses(): List<SkippedClass>` | Classes the include rules matched that the agent could not instrument, each with a reason. Sorted by class name. |
| `unreachedClusters(): List<UnreachedCluster>` | Every unreached cluster in the call graph, largest first. See [call graph](call-graph) for the rules. |
| `neverSupplied(): List<OptionalParameterRef>` | Optional parameters that every call left out, so the parameter can go. Never listed for an overridable function. |
| `alwaysSupplied(): List<OptionalParameterRef>` | Optional parameters that no call left out while the function was called, so the default value is dead. |

`neverHit` leaves these out, and each has its own query or none at all:

- A method inside a Kotlin inline function, and a branch inside one. A Kotlin caller copies the body, so a zero count proves nothing.
- A generated method, such as an enum's `values` or a data class's `copy`.
- Optional-argument probes, which `neverSupplied` and `alwaysSupplied` cover.
- A `<clinit>`, which is a class state. A constructor appears only as an unused overload, when another constructor of its class ran.
- A method that can run only through a class that `neverInitialized` or `neverInstantiated` lists, and a lambda body whose every creator is such a method or a never-hit method listed. A lambda body folds into its own class's finding only when every chain of creators ends in a method that finding covers. Otherwise it stays a method of its own in the call graph. Lambda bodies that only create each other fold nowhere and are `neverHit` rows.
- A method of a class that failed to initialise, and of its subtypes. See below.
- A method of a class that some instance reported as skipped, even where another instance probed it. A skipped class's hits may never arrive, so a zero count says nothing about it.
- A branch inside code that never ran. It folds into the row for that code.
- A routine outcome and an unread shape, listed by the two queries above.

#### Skipped classes

A class that any instance lists in `skippedClasses()` supports no claim that rests on missing hits, even where another instance probed it, and even where one JVM holds a probed copy and a skipped copy of the class. `neverHit`, `neverHitRoutineOutcomes`, `neverHitUnreadShapes`, `neverInitialized`, `neverInstantiated`, `neverSupplied`, `alwaysSupplied` and `unreachedClusters` leave out the class. `neverSupplied` and `alwaysSupplied` also leave out a parameter whose function belongs to a skipped class. A method of the class that has hits stays hit, but an untaken outcome of that method still blocks the call it guards. A never-hit method of the class is neither a root nor a member of a cluster, and it still counts as a caller, so a method that only it calls is not a root. So does a generated method of the class that has no hits.

A method of a loaded class that a complete static baseline declares, and that has no method probe, is a caller the testkit cannot judge. That covers a method of a skipped class, of an unreported class, or of a loaded class with no probe for it. Such a caller is neither hit nor never hit, and a method it calls is not a root and joins no cluster through it.

`neverHit` rows sort by class name, method name, descriptor, kind, line, then branch index. `neverInitialized`, `neverInstantiated` and `skippedClasses` sort by class name. `unreachedClusters` sorts by `membersTotal`, largest first, then by the root's class name, method name and descriptor. Where roots share a method, a method root comes before an outcome root. Outcome roots without a branch key come first, by branch index, then those with one, by branch key. A cluster's `reachedFrom`, `members` and `methods` sort by class name, method name, then descriptor.

#### Classes that failed to initialise

A class the JVM defined can still never run in that JVM, because its static initialiser threw or the JVM's verifier rejected it. The agent names such a class in the manifest with the reason. `failedToInitialize()` lists a class that no instance initialised and that meets one of two conditions. Some instance named it, or some instance loaded it and it fails with a class that instance named. A class fails with one that instance named when its superclasses or interfaces, followed through the class records that instance sent, reach:

- A class the verifier rejected, as a superclass or an interface.
- A class whose static initialiser threw, as a superclass.
- An interface whose static initialiser threw and that declares a default or private instance method.

A class is initialised in an instance when its static initialiser's returned slot counted, if that instance sent the class's returned and threw slots. The entry probe and every method the initialiser calls count before it throws, so they are no evidence. When the instance sent no slots for the class, because the class declares no static initialiser, any hit on a probe of the class means it initialised.

`failedToInitialize()` does not list a name that no instance loaded, such as a library class the verifier rejected, but it lists that class's loaded subtypes. It does not list a class that failed to initialise in one instance and initialised in another.

`neverHit`, `neverHitRoutineOutcomes`, `neverHitUnreadShapes`, `neverInitialized`, `neverInstantiated` and `unreachedClusters` leave out a listed class. `neverCalled` leaves out an endpoint when, in every instance that reports it, that instance has a probe of the handler class and the class failed to initialise there, is listed, or has its initialiser still running there. `alwaysSupplied` leaves out a parameter when each of its omission probes is in such a class. A method of such a class that has hits is not a hit caller, so a never-hit method of another class that only it calls is in no cluster. That covers the static initialiser of a class whose initialiser threw, which has hits. A generated or unread-shape method of the class that has hits still counts as a hit caller. A never-hit method of the class, an untaken outcome of one of its hit methods and a generated method of it that has no hits each still count as a caller that blocks.

All of these queries also leave out a class whose static initialiser is still running, and `failedToInitialize()` does not list it. Its initialiser is still running when, in every instance that loaded it, the initialiser's entry probe counted, the instance sent its returned and threw slots, and neither slot counted. Unlike a class that failed to initialise, a method of such a class that has hits still counts as a hit caller. A dependency that a method of such a class with no hits references reads `WITHOUT_EVIDENCE` until the initialiser returns or throws, unless a live site, or a class that failed to load or initialise, references it. So does a dependency referenced from a method that a complete baseline declares in such a class, and one referenced from a method that a complete baseline declares in a skipped class.

#### Queries that refuse

A zero count says nothing about an instance that is still starting or has sent no delta batch yet, and a class the verifier rejects fires no probe, so while the agent could not see every rejection, a zero count says nothing either. `neverHit`, `neverHitRoutineOutcomes`, `neverHitUnreadShapes`, `neverInitialized`, `neverInstantiated`, `neverCalled`, `alwaysSupplied`, `unreachedClusters`, `unreachedDependencies`, `failedToLoadDependencies` and `failedToInitializeDependencies` throw `IllegalStateException` while any instance heard from is in one of these states. So does `dependency` when the status it would return is `UNREACHED`, `NO_LIVE_REFERENCE`, `FAILED_TO_LOAD` or `FAILED_TO_INITIALIZE` and the dependency has at least one reference site:

- The instance's agent could not install its hook on `VerifyError`. That agent logged a `WARNING` that starts `otherlode: classes the JVM's verifier rejects will not be named on this JVM`.
- The instance counted `VerifyError` messages in no format the agent reads.
- The instance's latest payload says `starting`, and the instance is live. An instance is live until it ends with a final flush or goes silent for three of its flush intervals, or for an hour when it reported no flush interval. Call `awaitSettled` first.
- The instance's latest payload says its counts are pending, even after its JVM has exited.
- The instance has sent a manifest but no delta batch, even after its JVM has exited. These queries do not refuse for an instance heard from only through a baseline or a class listing, since it has no counts. `unreachedClusters` still refuses for one known only from a baseline when a class would read never loaded.

The message names each such instance and why:

```text
this query rests on zero counts, and the zero counts of these instances are not evidence that code never ran (a class the verifier rejects fires no probe, and an instance that is starting, has sent no delta batch or has pending counts has not reported all its counts):
  i-1: its payloads say it is still starting
  i-2: the agent's hook on VerifyError was not installed
  i-3: 2 VerifyError messages in no read format
  i-4: no delta batch has arrived
  i-5: its counts are pending
```

A query that reads no zero count keeps answering: `wasHit`, `hitCount`, `omissionCount`, `failedToLoad`, `failedToInitialize`, `skippedClasses`, `neverSupplied`, `wasCalled`, `callCount`, `endpoints`, `disabledEndpointModules`, `unreferencedDependencies`, and `dependency` and `absentReferences` when they do not refuse as above or below.

`neverLoaded`, `unreachedClusters` when a class would read never loaded, and a dependency query whose answer would hold a site with `neverLoaded` set, claim that a class never loaded. They throw while a live instance is starting, has sent no delta batch or has pending counts, since that instance may still load the class.

`unloadedDependencies`, and `dependency` when it would return `UNLOADED`, claim that no class of a dependency loaded. They throw while an instance that listed the dependency has sent no delta batch or has pending counts, even after its JVM has exited, or is starting and live. `unloadedDependencies` throws only when it would list a dependency.

The message names each such instance and why.

### Endpoint queries

`wasCalled`, `callCount` and `awaitEndpoint` normalise `verb` and `routeTemplate` before lookup, so `wasCalled("get", "/checkout/")` finds the endpoint the agent reports as `GET /checkout`. That normalised pair alone identifies a path endpoint. A regex endpoint and a servlet pattern endpoint are each a separate endpoint, even when the template reads the same. `EndpointRef.isRegexTemplate` and `EndpointRef.isServletPatternTemplate` mark them. These queries address path endpoints only, so a regex or servlet pattern endpoint never answers one written for a path. `endpoints()` and `neverCalled()` list them. Several instances that serve one endpoint merge into one.

| Query | Returns |
|---|---|
| `wasCalled(verb, routeTemplate): Boolean` | `true` if the summed call count is above zero. |
| `callCount(verb, routeTemplate): Long` | The count summed over instances, each instance's highest delivered total. |
| `neverCalled(): List<EndpointRef>` | Every endpoint with a summed count of zero, sorted by verb, then route template, then path, regex and servlet pattern in that order. Leaves out an endpoint whose handler class, in every instance that reports it and has a probe of the class, failed to initialise, is listed in `failedToInitialize()`, or has a static initialiser still running, and one whose endpoint module any instance switched off. Also leaves out an endpoint that every report found only at dispatch. Returns nothing while any instance switched a module off by failure and reported no endpoint of that module; a module switched off by configuration or for not counting routes is not a failure. Refuses while an instance's zero counts are not evidence. |
| `endpoints(): List<EndpointRef>` | Every endpoint any instance reported, sorted the same way. |
| `disabledEndpointModules(): List<DisabledEndpointModule>` | The endpoint modules an instance reported as switched off, each with its kind: those that switched themselves off, such as on a framework version the agent does not support, and those the agent's configuration kept off. Sorted by module name. A module's reason and kind come from the newest instance that reported it. |

`wasCalled` and `callCount` throw `UnknownEndpointException` for an endpoint no manifest has named.

### Dependency queries

Dependency queries apply the dependency rules within the one test JVM. See [dependencies](dependencies) for what the statuses mean. Where the data cannot answer yet, a query throws `IllegalStateException` instead of returning an empty list.

| Query | Returns |
|---|---|
| `dependency(groupId: String?, artifactId: String): DependencyStatus` | The status of one dependency, merged across instances. A null or empty `groupId` matches a dependency identified by its file name, which has no group. A shaded jar matches any identity it carries. |
| `unloadedDependencies(): List<DependencyStatus>` | Dependencies listed from a startup classpath with no class loaded from them. Throws `IllegalStateException` instead of listing one while an instance that listed it has sent no delta batch, has pending counts, or is live and starting, or, when a site of one would read never loaded, while a live instance is starting, has sent no delta batch or has pending counts. |
| `unreferencedDependencies(): List<DependencyStatus>` | Loaded dependencies that nothing in your code references. |
| `unreachedDependencies(): List<DependencyStatus>` | Dependencies referenced only from methods never hit or classes never loaded. |
| `failedToLoadDependencies(): List<DependencyStatus>` | Loaded dependencies with no live reference that a class which failed to load references. The status asks for a review and does not say the dependency can go. |
| `failedToInitializeDependencies(): List<DependencyStatus>` | Loaded dependencies with no live reference that a class which failed to initialise references, and that no class which failed to load references. A class that failed to initialise is loaded, so its references count as live only through a hit on a method. The status asks for a review and does not say the dependency can go. |
| `absentReferences(): List<AbsentReference>` | Referenced classes that no class loader could find, such as code guarded by a check for an optional library. |

The six list queries sort by `DependencyStatus.identityKey`, except `absentReferences`, which sorts by class name. They throw `IllegalStateException` in these cases:

- No instance has been heard from yet, or an instance has not finished listing its dependencies. Call `awaitDependenciesListed` first.
- For `unreferencedDependencies`, `unreachedDependencies`, `failedToLoadDependencies` and `failedToInitializeDependencies`: no instance records references, which needs `includePackages`, or some dependency cannot be split into unreferenced and unreached. A dependency cannot be split when it reads `LOADED`, because no instance that lists it records references, or `NO_LIVE_REFERENCE`, because an instance that lists it and records references sent no complete static baseline (`staticBaselineEnabled=true`). The message names each such dependency.
- For `absentReferences`: no instance records references.
- For `unreachedDependencies`, `failedToLoadDependencies` and `failedToInitializeDependencies`, and for `dependency` in the cases [Queries that refuse](#queries-that-refuse) names: an instance's zero counts are not evidence.

`dependency` throws `IllegalStateException` when more than one dependency carries the identity and none has it alone. The message names each candidate's `identityKey`. It does not wait for the listing to finish. Call `awaitDependency`, and `awaitDependenciesListed` when a shaded jar may carry the identity. The hits and reference sites that separate used from unreached arrive like any probe data, so call `awaitSettled` before asking.

### Rejected payloads

`rejectedPayloads(): List<String>` lists the reason for each payload the collector answered with HTTP 400, oldest first.

Once the list is not empty, every other query and wait throws `IllegalStateException`. The message starts `this collector rejected` and lists the reasons. Only `rejectedPayloads` and `close` keep working, so a test that calls any other query or wait fails even if it never reads the list.

The collector applies the same resource and run checks to all four payload types, class listings included. It rejects a payload when:

- Its run ID is empty.
- A second run ID arrives under an instance ID the collector already heard from. The collector keys on the instance ID alone and accepts the first run ID.
- It carries an agent version that differs from the testkit's version. The reason names both versions. An empty version on either side skips the check.
- A collector between the agent and the testkit stripped fields it did not know.
- Its resource has a value over 1024 bytes or a NUL byte, an empty service name or instance ID, or a service name or namespace that is `.` or `..` once leading and trailing spaces are trimmed.
- A timestamp is out of range, or a probe manifest has a class that failed to initialise with no reason or a dependency with no identities.
- A static baseline or class listing has no scan or listing time, or a chunk count below 1 or a chunk index outside its chunk count.
- It does not decode, which happens when the agent is newer than the testkit or something other than the agent posted to the port.
- Applying it failed. The reason carries the exception.
- The collector serves one test JVM, as the extension's does, and the payload comes from a second instance. The reason names `maxParallelForks` above 1 and a child JVM that has the agent.

## OtherlodeExtension

`dev.otherlode.testkit.junit5.OtherlodeExtension` is a JUnit 5 extension. JUnit 5 is `compileOnly` for the testkit, so the extension needs `junit-jupiter-api` on your test classpath, and nothing else in the testkit does.

Register it with `@ExtendWith(OtherlodeExtension::class)`. It starts one collector per test JVM, keeps it for the life of the JVM, and injects it into any test or lifecycle method parameter of exactly the type `OtherlodeTestCollector`. The agent must already be on the test JVM through the `-javaagent` flag, because the extension never attaches it.

| Member | Description |
|---|---|
| `beforeAll` | Waits with `awaitNextFlush` for a delta batch that arrives after it begins. Throws `IllegalStateException` if none arrives within the startup timeout, with a message that gives the `-javaagent` flag to add. Once one wait has succeeded in the JVM, later test classes skip it. |
| `OtherlodeExtension.collector()` | A static accessor for the collector the extension started. Throws `IllegalStateException` until the extension has started a collector, and keeps throwing if binding its port failed. |

The extension's collector accepts payloads from one agent instance only. The collector keeps running when JUnit closes its store, so the agent's shutdown flush still reaches it.

The extension throws `IllegalStateException` when the collector cannot bind its port, when a system property below is malformed, when the collector has rejected a payload before or during `beforeAll`'s wait, when `beforeAll` gets no delta batch in time, and when `collector()` is called before the extension has started a collector.

## System properties

The extension reads two system properties from the test JVM. A collector you start with `start` ignores them.

| Property | Default | Meaning |
|---|---|---|
| `otherlode.testkit.port` | `4319` | The port the extension's collector binds. 4319 is the agent's default export port, so a `-javaagent` flag with no `exportUrl` works. A value that is not an integer throws `IllegalStateException`. A blank value counts as unset. |
| `otherlode.testkit.startup.timeout.seconds` | `15` | How long `beforeAll` waits for a delta batch. A value that is not a positive integer throws `IllegalStateException`. A blank value counts as unset. |

The agent's default flush interval is 60 seconds, longer than the default timeout. Set the agent's `flushIntervalSeconds` to 1 in a test.

## Exceptions

| Exception | Thrown by | Meaning |
|---|---|---|
| `UnknownProbeException` | `wasHit`, `hitCount`, `omissionCount` | The collector has no probe for the class or method named. |
| `UnknownEndpointException` | `wasCalled`, `callCount` | No manifest has named the endpoint. |
| `UnknownDependencyException` | `dependency` | No manifest has listed the dependency. |
| `IllegalStateException` | Queries, waits, the extension | The collector cannot give a trustworthy answer. See below. |
| `java.util.concurrent.TimeoutException` | Every `await` method | The condition did not hold within the timeout. |

`UnknownProbeException`, `UnknownEndpointException` and `UnknownDependencyException` extend `RuntimeException`.

### UnknownProbeException

The message names the first reason below that applies.

1. The class matched `includePackages` but the agent could not instrument it. The message gives the skip reason, the same text `skippedClasses` lists.
2. The class loaded, but no transformer saw it, so it has no probes. The JVM hands a class loaded from inside another class's transformation to no transformer.
3. A complete static baseline declared the class and no instance ever loaded it.
4. The class is instrumented, but has no probe for that method name or descriptor. For `omissionCount`, it has no omission probe for that method and parameter.
5. No manifest or baseline ever mentioned the class. It is outside `includePackages`, its name is misspelled, or it has not loaded yet.

Cases 3 and 5 can clear on their own as the application runs. Wait for the class with `awaitProbe` before asking.

### UnknownEndpointException

1. At least one endpoint module is disabled. The message lists every disabled module and its reason, because the endpoint may belong to one of them.
2. No path endpoint has that template, but a regex or servlet pattern endpoint does. The message names which kind does and points to `endpoints()` and `neverCalled()`.
3. No manifest from any instance named the endpoint.

### UnknownDependencyException

The message names the dependency and every dependency the collector knows. When no manifest has listed any dependency yet, it says so and suggests `awaitDependency`.

### IllegalStateException

| Cause | Where |
|---|---|
| The collector rejected a payload. | Every query and wait. |
| The collector is closed and the condition does not hold. | Every wait. |
| No complete static baseline has arrived, or a live instance says it is starting, has sent no delta batch or has pending counts. | `neverLoaded` |
| A live instance says it is starting, has sent no delta batch or has pending counts, and a class would read never loaded. | `unreachedClusters` |
| A live instance says it is starting, has sent no delta batch or has pending counts, and a site would read never loaded. | `dependency`, the dependency list queries, `absentReferences` |
| An instance that listed the dependency has sent no delta batch, has pending counts, or is starting and live. | `unloadedDependencies`, and `dependency` when it would return `UNLOADED` |
| An instance's hook on `VerifyError` was not installed, it counted a `VerifyError` message in no read format, it says it is starting, it has sent no delta batch, or its counts are pending. | The queries listed in [Queries that refuse](#queries-that-refuse) |
| Dependency listing is incomplete, references are not recorded, or a dependency cannot be split into unreferenced and unreached. | The dependency list queries, `absentReferences` |
| Several dependencies carry the identity. | `dependency` |
| The extension could not start its collector, a system property is malformed, the collector rejected a payload before or during `beforeAll`'s wait, `beforeAll` got no delta batch in time, or `collector()` was called before the extension started one. | `OtherlodeExtension` |

## Result types

Every result type is a data class, so `equals`, `hashCode`, `toString` and destructuring work. Constructors and `copy` are internal. Test code reads these types and never builds them.

Where a field below is null, the fact does not apply. No enum has a `NONE` value.

### ProbeRef

One method probe or branch outcome, merged across instances. Some fields apply to one kind only.

| Field | Type | Meaning |
|---|---|---|
| `className` | `String` | The class that holds the probe. |
| `methodName` | `String` | The method that holds the probe. |
| `methodDescriptor` | `String` | The method's JVM descriptor. |
| `line` | `Int` | The source line. `-1` for a member of a cluster that exists only in the static baseline, and for a class compiled without line numbers. |
| `kind` | `ProbeKind` | What the probe counts. |
| `branchIndex` | `Int?` | The outcome's ordinal within its class for one build. Null for a method probe. When copies of the class disagree, it is the lowest. |
| `branchKey` | `String?` | A lowercase hex token that names the outcome across builds and instances. Null for a method probe, and for a branch outcome the agent could not name safely. |
| `inline` | `Boolean` | Always `false`, since no query returns a probe of a Kotlin inline function. |
| `inlinedFromClassName` | `String?` | For a branch copied from an inline function, the class that function came from. |
| `generatedBy` | `GeneratedBy?` | Always null, also for a generated method that `UnreachedCluster.reachedFrom` lists. |
| `routine` | `RoutineKind?` | Why a branch outcome is not worth a person's time. Only on branch probes. |
| `unreadShape` | `UnreadShape?` | The family of compiler output whose body the agent could not read. Exclusive with `routine`. |
| `outsideCaller` | `OutsideCaller?` | Why code outside your scope may call the method. Only on method probes. |
| `parameterIndex` | `Int?` | Always null. |
| `parameterName` | `String?` | Always null. |
| `overridable` | `Boolean` | Always `false`. |
| `targetClassName` | `String?` | Always null. |
| `neverLoaded` | `Boolean` | `true` for a cluster member that exists only because a complete static baseline declared it, and for the class root of a never-loaded class. |

No query returns an optional-argument probe. `neverSupplied` and `alwaysSupplied` report optional parameters as `OptionalParameterRef`.

Where instances disagree about a row, the newest instance wins for `line`, and the newest instance that has one wins for `outsideCaller`. The newest instance is the one the collector first heard from last. `inlinedFromClassName` takes the greatest value, and a row is routine or an unread shape when any copy is.

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
| `methods` | `List<ProbeRef>` | All of those methods, sorted by class name, method name, then descriptor. |

Neither `members` nor `wholeClasses` lists a `<clinit>`.

### WholeClass

| Field | Type | Meaning |
|---|---|---|
| `className` | `String` | The class. |
| `finding` | `ClassFinding?` | The class's finding, when it has one. |
| `methods` | `List<ProbeRef>` | Its methods in the cluster, sorted by class name, method name, then descriptor. |

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

One endpoint, merged across instances. Every field except `verb`, `routeTemplate`, `isRegexTemplate` and `isServletPatternTemplate` comes from the last manifest entry the collector received for the endpoint, from any instance.

| Field | Type | Meaning |
|---|---|---|
| `verb` | `String` | The normalised verb, uppercase, or `*` when the endpoint is not limited to one. |
| `routeTemplate` | `String` | The normalised route template. |
| `verbatimTemplate` | `String` | The framework's own spelling. |
| `framework` | `String` | The framework or module that reported it. |
| `discoverySource` | `EndpointDiscoverySource` | How the agent learned of it. |
| `handlerClass` | `String?` | The handler's class, or null when the entry names none. |
| `handlerMethod` | `String?` | The handler's method, where the framework exposes one. |
| `handlerDescriptor` | `String?` | The handler method's descriptor. |
| `isRegexTemplate` | `Boolean` | `true` when the framework matches the route by a regular expression. `routeTemplate` and `verbatimTemplate` are then that expression exactly as written, and the endpoint is not the path endpoint whose template reads the same. |
| `isServletPatternTemplate` | `Boolean` | `true` when the template is a servlet mapping no path can express: an extension mapping such as `*.do`, the default mapping `/`, or an exact mapping the naming rules would change or that holds a star or a brace, such as `/orders/`. `routeTemplate` and `verbatimTemplate` are then that pattern exactly as written, and the endpoint is neither the path endpoint nor the regex endpoint whose template reads the same. |

### DisabledEndpointModule and SkippedClass

| Type | Fields |
|---|---|
| `DisabledEndpointModule` | `module: String`, `reason: String`, `kind: DisabledEndpointModuleKind?` |
| `SkippedClass` | `className: String`, `reason: String` |

`kind` is null when the agent gave none, such as for a module that dropped endpoint records before the agent finished installing.

### DependencyStatus

| Field | Type | Meaning |
|---|---|---|
| `identityKey` | `String` | The identities of the dependency joined with commas, such as `com.fasterxml.jackson.core:jackson-databind`. An identity read from a file name has no group and reads `:commons-lang3`. The testkit escapes a `\`, `:` or `,` inside a group or artifact id with a backslash. |
| `status` | `DependencyUsage` | What the dependency rules say. |
| `identities` | `List<DependencyIdentityRef>` | Every `groupId:artifactId` the dependency carries. A shaded jar carries several. |
| `loadedClassesTotal` | `Long` | The largest count of distinct loaded classes any one instance reported. |
| `classCount` | `Int?` | The largest class count a listing gave, or null when none gave one. |
| `discoverySources` | `Set<DependencyDiscoverySource>` | How instances learned of it. |
| `sites` | `List<DependencyReferenceSite>` | Every place in your code that references it, live or not. Empty for `UNREFERENCED`. |

### DependencyIdentityRef, DependencyReferenceSite, AbsentReference

| Type | Field | Type | Meaning |
|---|---|---|---|
| `DependencyIdentityRef` | `groupId` | `String` | Empty for an identity read from a file name. |
| | `artifactId` | `String` | The artifact id. |
| | `versions` | `Set<String>` | Every non-empty version any instance listed, sorted. |
| `DependencyReferenceSite` | `className` | `String` | The class that references the dependency. |
| | `methodName` | `String?` | The method, or null for a reference at class level. |
| | `neverLoaded` | `Boolean` | `true` when the site comes from a static baseline and no instance loaded its class, and `failedToLoad` is `false`. |
| | `failedToLoad` | `Boolean` | `true` when the site's class failed to load in some instance and loaded in none. |
| | `failedToInitialize` | `Boolean` | `true` when the site's class failed to initialise and no reference of the site is live. |
| `AbsentReference` | `className` | `String` | The referenced class no loader could find. |
| | `sites` | `List<DependencyReferenceSite>` | Every site that references it, sorted. |

`DependencyReferenceSite.toString()` reads `Class#method` or `Class (class level)`. It appends one of `(failed to load)`, `(failed to initialise)` or `(never loaded)`, for the first of those flags that is set, in that order.

## Enums

Each enum lists its values in declaration order.

### ProbeKind

What one probe counts.

| Value | Meaning |
|---|---|
| `METHOD` | Entries to a method. |
| `BRANCH` | One outcome of a conditional or a switch. |
| `OPTIONAL_ARGUMENT` | Calls that leave out one optional parameter's argument. No query returns one. |
| `INITIALIZER_RETURNED` | Normal returns of a static initialiser the class file declares. A slot of that initialiser, not a probe, so no query returns it and no finding lists it. |
| `INITIALIZER_THREW` | Exceptions that left a static initialiser the class file declares. A slot of that initialiser, not a probe, so no query returns it and no finding lists it. |

### GeneratedBy

What produced a method, read from the bytecode alone.

| Value | Meaning |
|---|---|
| `ENUM` | An enum's `values`, `valueOf` or `getEntries`. |
| `DATA_CLASS` | A Kotlin data class's `componentN` and `copy`, and whichever of `equals`, `hashCode` and `toString` you did not write. |
| `DEFAULT_IMPLS` | A `$DefaultImpls` method that only forwards to the interface's own default method, and a class's stub that only forwards to `$DefaultImpls`. |
| `RECORD` | A Java record's `equals`, `hashCode` or `toString`. |
| `JVM_OVERLOADS` | An overload that `@JvmOverloads` adds, whose body only forwards to its `$default` twin. |
| `MULTIFILE_FACADE` | A function of a Kotlin multi-file facade whose body only forwards to the same function on a part class. |
| `CASE_CLASS` | A Scala case class's plumbing, or its companion's. |
| `STATIC_FORWARDER` | A static forwarder that scalac adds for an object's method, or the static method that kotlinc adds for a `@JvmStatic` companion function. |
| `SCALA_OBJECT` | A Scala object's `writeReplace`. |
| `VALUE_CLASS` | A Kotlin value class's `equals-impl`, `equals-impl0`, `hashCode-impl` and `toString-impl` that the compiler wrote, the boxed methods that only forward to a static, and the static whose body only calls an interface default the class inherits. A Scala value class's boxed methods that only forward to the companion's `$extension` method, and the companion's `hashCode$extension` and `equals$extension`. |
| `KOTLINX_SERIALIZATION` | Code the kotlinx.serialization compiler plugin generates. |
| `TRAIT_FORWARDER` | A Scala class's method that only calls a trait's static `m$`, and the trait's `m$` that only calls its default method. |
| `CLASS_DELEGATION` | A Kotlin class's method that kotlinc writes for interface delegation, whose body only forwards to the delegate's method of the same name. |

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
| `KOTLINX_SERIALIZER_OBJECT` | A `@Serializer(forClass = ...)` object's `serialize`, `deserialize` and `getDescriptor`, which the plugin may have filled in. |
| `VALUE_CLASS` | A Scala value class's boxed method, or its companion's `hashCode$extension` or `equals$extension`, whose body the agent has not read. |
| `TRAIT_FORWARDER` | A Scala class's method with a trait's static `m$` twin, or a trait's static `m$`, whose body the agent has not read. |

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
| `NEVER_LOADED` | A complete static baseline declared the class and no instance ever loaded it. Like `neverLoaded()`, this leaves out a class in `failedToLoad()`. |
| `NEVER_INITIALIZED` | The class loaded and its static initialiser never ran. |
| `NEVER_INSTANTIATED` | The class loaded, has instance methods, and none of its constructors or instance methods ran. |

### EndpointDiscoverySource

| Value | Meaning |
|---|---|
| `REGISTRATION` | The framework declared the endpoint when it registered it. |
| `DISPATCH` | A request matched the endpoint before any registration was seen. |

### DependencyDiscoverySource

| Value | Meaning |
|---|---|
| `STARTUP_CLASSPATH` | Listed from the startup classpath, which includes the jars under a fat jar's or an unpacked Spring Boot application's `BOOT-INF/lib`, `WEB-INF/lib` and `lib-provided`. |
| `LOAD` | Seen only because a class from it loaded. Such a dependency never reads as unloaded. |

### DisabledEndpointModuleKind

What switched an endpoint module off.

| Value | Meaning |
|---|---|
| `LINKAGE_ERROR` | A `LinkageError`, wherever it was caught. The framework release differs from the one the module was built for. |
| `ADVICE_FAILED` | Any other throw from the module's advice. |
| `TRANSFORM_FAILED` | The module's own transform threw, or a framework class it hooks failed to weave. |
| `ROUTE_WALK_FAILED` | Walking a framework's route objects threw. |
| `HOOK_UNMATCHED` | A hook matched no method on the framework class it hooks, because a framework release renamed or changed the method. |
| `CONFIGURED_OFF` | The agent's configuration kept the module from installing, because `disabledEndpointModules` named it or `endpointsEnabled` is false. |
| `ROUTES_NOT_COUNTED` | The runtime constructed no dispatch point the module counts at within one flush interval of building a router, so the module declares no routes. Micronaut only. |

### DependencyUsage

What the dependency rules say about one dependency.

| Value | Meaning |
|---|---|
| `UNLOADED` | Listed from a startup classpath, and no instance loaded a class from it. |
| `UNREFERENCED` | A class from it loaded, and a complete baseline shows nothing in your code references it. |
| `UNREACHED` | Referenced only from methods no instance hit or classes no instance loaded, per a complete baseline. |
| `NO_LIVE_REFERENCE` | Loaded, with no live reference, from an instance that sent no complete baseline. It is unreferenced or unreached, and the two cannot be told apart. |
| `FAILED_TO_LOAD` | Loaded, with no live reference, and referenced from a class that failed to load. A prompt to review, not a claim that it can go. |
| `USED` | Referenced from a method that any instance hit, or at class level by a class that any instance loaded and that did not fail to initialise. |
| `LOADED` | Loaded, and nothing further is claimed, because no instance that lists it records references. |
| `RESOURCES_ONLY` | The listing counted no class in it, as with native libraries, web assets and message bundles. Nothing is claimed. |
| `FAILED_TO_INITIALIZE` | Loaded, with no live reference, and referenced from a class that failed to initialise and from none that failed to load. A prompt to review, not a claim that it can go. A dependency referenced from both kinds of failed class reads as `FAILED_TO_LOAD`. |
| `WITHOUT_EVIDENCE` | Loaded, with no live reference, and referenced from a method with no hits whose class gives no evidence: its static initialiser is still running or failed in every instance that knows the method, or the class is skipped. Its zero counts prove nothing. The split queries leave it out and keep answering. |

## Compatibility

The testkit and the agent share one version. The collector rejects a payload from an agent of another version, and the rejection names both versions. Use the testkit built from the agent's version.

Result types and enums only grow in a minor release:

- A data class gains fields at the end, so destructuring positions stay valid.
- An enum gains values.
- A method that returns a list can return rows it did not return before, when the agent learns to report a new shape.

A `when` over any enum in this API needs an `else` branch. The enums are `ProbeKind`, `GeneratedBy`, `RoutineKind`, `UnreadShape`, `OutsideCallerKind`, `RootKind`, `ClassFinding`, `EndpointDiscoverySource`, `DisabledEndpointModuleKind`, `DependencyDiscoverySource` and `DependencyUsage`. A new value breaks a `when` without `else` at compile time, when you upgrade the testkit.

`ProbeRef.routine` and `ProbeRef.unreadShape` are null when the probe has no mark, since `RoutineKind` and `UnreadShape` have no "none" value.

The testkit's jar bundles its own protobuf and wire classes under `dev.otherlode.testkit.shaded`. No type in this API comes from them.
