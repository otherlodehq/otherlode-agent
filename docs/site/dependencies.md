---
title: Dependencies
description: How the agent reports which of your service's libraries are unloaded, unreferenced, or unreached without putting a probe in library code.
order: 60
---

The agent reports on libraries without instrumenting them. It combines three observations: which jars are on the classpath, which of their classes the JVM loaded, and which library classes your own code names. A collector turns those into a status for each dependency. The agent never decides that a library can be removed, and neither does a status. Each status is an observation that tells you where to look.

## What counts as a dependency

A dependency is one jar on the classpath that is not your own code. These jars are not dependencies:

- A jar that holds any class your [include rules](configuration) admit. It is your own code, even when it also bundles other libraries. When such a jar also holds classes outside your include rules, the agent logs one INFO line naming the jar and the count of those classes.
- A classpath directory, and `BOOT-INF/classes` or `WEB-INF/classes` inside a fat jar.
- A jar on the classpath whose manifest has `Premain-Class` or `Launcher-Agent-Class`, so the Otherlode agent, an OpenTelemetry agent, and a profiler never read as unused. The agent also skips the jar it was loaded from, and any jar that holds a class in its own package. A jar nested inside a fat jar is judged by the include rules alone, so a library such as `byte-buddy-agent` that carries `Premain-Class` stays a dependency there.
- A Spring Boot fat jar or executable WAR itself. Its manifest has `Spring-Boot-Classpath-Index` or `Spring-Boot-Lib`. The jars nested inside it are the dependencies.

A service shipped as one shaded jar that holds your code therefore reports no dependencies.

### Identity

The agent identifies a jar by `groupId:artifactId`, in this order:

1. Each `META-INF/maven/<group>/<artifact>/pom.properties` that has an `artifactId`. The version comes from the same file.
2. The file name, when no `pom.properties` names an artifact. The agent strips a `.jar` or `.zip` extension, then splits at the first `-` after which the rest looks like a version: digits with a dot, or digits followed by `-SNAPSHOT`, then any qualifiers. `guava-33.0.0-jre.jar` is `guava` at `33.0.0-jre`. A bare number is not a version, so `endpoints-ktor-2.jar` is all artifact name. A file-name identity has an empty group, and the jar's manifest `Implementation-Version` fills in a version the file name lacks.

The manifest's `Implementation-Title` is never used. Several jars can share one title.

The version is an attribute and is not part of the identity, so an upgrade does not make a dependency look new.

A jar with several `pom.properties` files, which is what a shaded jar has, is one dependency that carries every identity. The agent reports it as one unit because it cannot be removed in halves. Two jars with the same set of identities, or one jar loaded by two class loaders, are also one dependency. The first one found keeps its location and class count.

### Location

Each dependency carries a location for display. For a jar on disk it is the jar's path, and for a nested jar it is the entry path, such as `BOOT-INF/lib/jackson-databind-2.17.0.jar`. A leading home folder (the `user.home` system property) is written as `~`, so a jar in the Maven cache shows as `~/.m2/repository/...` and the path does not name the person running the agent. Only a whole leading folder matches: `/home/al` does not rewrite `/home/alice/x.jar`.

## Where the agent looks

The agent lists the startup classpath once per process, off the application's main thread. The first flush or the [static baseline](classes) scan starts the listing, whichever comes first.

| Source | What the agent reads |
|---|---|
| `java.class.path` | Each entry that is a file. It reads any non-directory as a jar, whatever its extension, as the JDK's system loader does. |
| Manifest `Class-Path` | Each `file:` entry a jar names, searched right after the jar that names it. |
| Spring Boot executable jar | Every file under `BOOT-INF/lib/`, streamed out of the fat jar with nothing extracted to disk. |
| Executable WAR | Every file under `WEB-INF/lib/` and `WEB-INF/lib-provided/`. |

The agent does not read Spring Boot's classpath index. A packaged launch ignores it, and it can leave out a jar that still runs.

A jar the agent cannot read is skipped with one WARNING, and the listing continues. If the whole listing fails, the agent logs a WARNING, reports no dependencies from that instance, and the collector reads the instance as having none listed, not as having a partial list.

### Dependencies discovered at load

Some jars are on no startup list. An application server can open a WAR's `WEB-INF/lib` after the agent starts, Spring Boot can unpack a library to the temporary directory, and a plugin loader can open a jar at runtime. When the agent counts loaded classes, it reads and judges each unknown jar with the same rules as the listing. A new dependency is registered with the discovery source `load`.

A jar discovered at load can never read as unloaded, because a class from it already loaded. A jar that is on no listing and never loads a class stays invisible.

## Statuses

A collector derives one status per dependency from three observations, checked in this order. The first condition that holds decides the status.

| Status | Meaning |
|---|---|
| Resources only | Every instance's listing counted no class in the jar, as with native libraries, web assets, or message bundles. Loading says nothing about use, so the collector claims nothing else. |
| Unloaded | Some instance listed the jar from its startup classpath, and no instance loaded a class from it. |
| Loaded | A class loaded, and no instance that lists the jar records references, so nothing further is claimed. |
| Used | Your code holds a live reference to the jar. |
| Failed to load | No live reference, and your code references the jar from a class that failed to load. |
| No live reference | The jar has no live reference, and the collector cannot tell unreferenced from unreached. |
| Unreferenced | A class from the jar loaded, and nothing in your code references the jar. |
| Unreached | Your code references the jar, but only from methods that were never hit or classes that never loaded. |

Because the agent refuses to start without include rules, every running agent records references. "Loaded" appears only when no instance that lists the jar sent references.

Unreferenced is often a library that another library needs, or one reached only through a service lookup, such as a JDBC driver or a logging backend. Read the status next to the loaded-class count. It does not say the dependency can go.

Direct and transitive dependencies look the same, because the build file is not visible at run time.

### Counting loads

On every flush the agent counts the distinct classes loaded from each dependency. It matches a class to its jar by the jar's identity, not by the string form of the code-source URL. That string differs between Spring Boot versions and for unpacked libraries. The count never falls, even if a class unloads. The agent also stamps when a dependency's count first rose above zero, with the precision of one flush.

The agent skips arrays, primitives, hidden classes such as lambda classes, and classes with no code source, which are the JDK's own.

### What counts as a reference

A reference is any mention of a class in your bytecode that the running JVM can need:

- a call, a field access, or a construction
- a cast, an `instanceof` test, or a catch type
- a class literal
- a type in a method or field descriptor, or in a generic signature
- a supertype
- a runtime-visible annotation on a class, method, field, or parameter, including the types its values name

A dependency used only through an annotation such as `@JsonProperty`, or only as a base class, is still referenced.

These do not count:

- A class-retention annotation. The JVM never resolves its type, so the jar can leave the runtime classpath with nothing changing. kotlinc puts `@NotNull` and `@Nullable` from `org.jetbrains:annotations` on nearly every Kotlin class, so counting them would mark that jar as used on every Kotlin service.
- A reference to a JDK class, or to any class a `java` package names that nothing provides.
- A reference to a class that sits in a classpath directory. It is your own code.
- A reference to a class inside your include rules.

The agent records references per method, and per class for references outside any method it probes, such as the class header and its fields. It attributes a reference in a generated forwarder to the method that calls the forwarder. A lambda body or a body class keeps its own references. The agent finds where a referenced class lives by asking the referencing class's own loader for the class file as a resource. That reads the file and never loads the class.

### Live references

A reference is live when either holds:

- A method with hits holds it, on any instance.
- A class holds it at class level (an annotation, a supertype, a field type) and that class loaded.

An inline method's own references never count, because its callers carry copies of the body. Read [methods and branches](methods-and-branches) for inline methods.

A reference from a method that was never hit, or from a class that never loaded, is not live. The collector can see a reference in a class that never loaded only through the static baseline.

### Absent references

A reference to a class no loader can find is an absent reference. Typical causes are code guarded by a check for an optional library, or a loader that throws when asked for the class. The agent reports it with every site that holds it and maps it to no dependency.

### When the collector can judge each level

| Level | Needs |
|---|---|
| Resources only, unloaded, loaded | The startup listing and the loaded-class counts. Nothing else. |
| Used, failed to load, no live reference | The instance's include rules are set (the agent always runs with them) and the recorded references. |
| Unreferenced, unreached | A complete [static baseline](classes) from every instance that lists the dependency. Set `staticBaselineEnabled=true` to send it. |

Without a complete baseline, a collector cannot see a reference held in a class that never loaded. It would call such a dependency unreferenced when it is unreached. So without the baseline it reports "no live reference", which is true either way.

A baseline is complete only when every chunk of one scan has arrived. A collector judges each dependency from the instances that list it. A collector merges instances by `groupId:artifactId` across versions and shows the versions it saw.

The failed to load status applies when a reference comes from a class that a complete baseline declares and some run named as failed to load, and no run loaded. It asks for review and claims no removal. The class may fail because of the very classpath that jar is on, and a dependency used only by a long-failing class may not be needed. Read [classes](classes) for how the agent names a class that failed to load.

### A Kotlin service always uses kotlin-stdlib

Every Kotlin class carries the runtime-visible `kotlin.Metadata` annotation, so kotlin-stdlib always reads as used on a Kotlin service. A Kotlin service cannot drop it. A Java service that pulls kotlin-stdlib in is judged on its own use.

## When a dependency is listed

The agent sends a dependency's entry only after a send that carried its first loaded-class count was confirmed. A collector that saw the entry first would read a used jar as unloaded. The agent holds back the mapping from a referenced class to that dependency under the same condition. An absent reference names no dependency, so nothing holds it back.

The manifest of each instance also carries a flag that means "dependencies listed". The agent sets it once the startup listing finished, every startup dependency has been delivered, and every reference mapping recorded before the listing ended has been delivered. Before that point, an empty list of dependencies or absent references means "not listed yet", not "none". A collector can then judge a dependency as soon as its entry arrives.

The entry usually arrives in the same flush as the first count, and at the earliest on the flush after the listing ends.

## Query dependencies in tests

[The testkit](testkit) applies the collector's rules inside one test JVM. See [the testkit API](testkit-api) for the signatures.

| Query | Returns |
|---|---|
| `dependency(groupId, artifactId)` | The status of one dependency. Pass `null` as the group for a file-name identity. |
| `unloadedDependencies()` | Every dependency with status unloaded. |
| `unreferencedDependencies()` | Every dependency with status unreferenced. |
| `unreachedDependencies()` | Every dependency with status unreached, with the sites that reference it. |
| `failedToLoadDependencies()` | Every dependency with status failed to load. |
| `absentReferences()` | Every absent reference, with its sites. |
| `awaitDependency(groupId, artifactId, timeout)` | Waits until a manifest lists the dependency. |
| `awaitDependenciesListed(timeout)` | Waits until every instance heard from has sent the "dependencies listed" flag. |

The result of a query is a `DependencyStatus` with a `DependencyUsage` value. `DependencyUsage` has the values `UNLOADED`, `UNREFERENCED`, `UNREACHED`, `NO_LIVE_REFERENCE`, `FAILED_TO_LOAD`, `USED`, `LOADED`, and `RESOURCES_ONLY`. A later release can add values, so a `when` over it needs an `else` branch. Each referencing site prints as `Class#method`, with `(class level)`, `(never loaded)`, or `(failed to load)` where they apply.

These rules apply to the queries:

- `dependency` throws `UnknownDependencyException` when no manifest listed the dependency. The message names the identities the collector knows.
- `dependency` throws `IllegalStateException` when several dependencies carry the identity you asked for, as when a plain jar and a shaded jar both bundle it. The message names each candidate.
- The four list queries and `absentReferences()` throw `IllegalStateException` until every instance heard from has sent the "dependencies listed" flag. Call `awaitDependenciesListed` first.
- `unreferencedDependencies()`, `unreachedDependencies()`, and `failedToLoadDependencies()` also throw `IllegalStateException` when a loaded dependency could not be split. That happens when no complete baseline arrived, so run with `staticBaselineEnabled=true`. `absentReferences()` throws when no instance recorded references.
- Hits and reference sites that separate used from unreached arrive like any probe data. Wait for them with `awaitSettled`.
