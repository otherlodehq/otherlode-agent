---
title: Dependencies
description: How the agent reports which of your service's libraries are unloaded, unreferenced, or unreached without putting a probe in library code.
order: 60
---

The agent reports on libraries without instrumenting them. It sends three observations: which jars are on the classpath, how many of each jar's classes the JVM loaded, and which library classes your own code names. Otherlode, and the testkit in a test, turn those into a status for each dependency. A status is an observation that tells you where to look. None of them says a library can be removed.

## What counts as a dependency

A dependency is one jar on the classpath that is not your own code. These jars are not dependencies:

- A jar that holds any class your [include rules](configuration) admit. It is your own code, even when it also bundles other libraries. When such a jar also holds classes outside your include rules, the agent logs one `INFO` line naming the jar and the count of those classes.
- A classpath directory, and `BOOT-INF/classes` or `WEB-INF/classes` inside a fat jar.
- A jar on the classpath whose manifest has `Premain-Class` or `Launcher-Agent-Class`, such as the Otherlode agent, an OpenTelemetry agent or a profiler. The agent also skips the jar it was loaded from, and any jar that holds a class in its own package. The agent judges a jar nested inside a fat jar, or one an unpacked Spring Boot application's own class loader reads (its library folders, its classpath index, and the jars their `Class-Path` names), by the include rules alone. So a library such as `byte-buddy-agent` that carries `Premain-Class` stays a dependency there.
- A Spring Boot fat jar or executable WAR itself. Its manifest has `Spring-Boot-Classpath-Index` or `Spring-Boot-Lib`. The jars nested inside it are the dependencies.

So a service shipped as one shaded jar that holds your code reports no dependencies.

### Identity

The agent identifies a jar by `groupId:artifactId`, in this order:

1. Each `META-INF/maven/<group>/<artifact>/pom.properties` that has an `artifactId`. The version comes from the same file.
2. The file name, when no `pom.properties` names an artifact. It is the name of the file at the path the agent reads, which for a `java.class.path` entry is the file its symbolic links point to. The agent strips a `.jar` or `.zip` extension, then splits at the first `-` after which the rest looks like a version: digits with a dot, or digits followed by `-SNAPSHOT`, then any qualifiers. `guava-33.0.0-jre.jar` is `guava` at `33.0.0-jre`. A bare number is not a version, so `endpoints-ktor-2.jar` is all artifact name. A file-name identity has an empty group, and the jar's manifest `Implementation-Version` fills in a version the file name lacks.

The agent never uses the manifest's `Implementation-Title`. The version is not part of the identity, so an upgrade does not make a dependency look new.

A jar with several `pom.properties` files, as a shaded jar has, is one dependency that carries every identity. Two jars with the same set of identities, or one jar loaded by two class loaders, are also one dependency. The first one found keeps its location and class count.

### Location

Each dependency carries a location for display:

- For an entry of `java.class.path`, the entry's real path, with every symbolic link in it followed.
- For a jar a manifest `Class-Path` names, the path the manifest gives, resolved against the folder of the jar that names it.
- For a jar in an unpacked Spring Boot application, its path under the application's folder.
- For a nested jar, the entry path, such as `BOOT-INF/lib/jackson-databind-2.17.0.jar`.
- For any other jar on disk, such as one discovered at load, its absolute path.

A jar reached at two paths shows the first one in search order. The agent writes a leading home folder (the `user.home` system property) as `~`, so a jar in the Maven cache shows as `~/.m2/repository/...` and the path does not name the person running the agent. Only a whole leading folder matches: `/home/al` does not rewrite `/home/alice/x.jar`. When the path to the home folder goes through a symbolic link, the agent also writes `~` for a path under the home folder's real path, with every link followed.

## Where the agent looks

The agent lists the startup classpath once per process, off the application's main thread. The first flush or the [static baseline](classes#never-loaded) scan starts the listing, whichever comes first.

| Source | What the agent reads |
|---|---|
| `java.class.path` | Each entry, read through its real path. The agent reads a file as a jar, whatever its extension. A directory is not a dependency itself. |
| Manifest `Class-Path` | Each `file:` entry a jar names, searched right after the jar that names it. An entry ending in `/` is a directory, never a dependency. The agent skips a jar whose `Class-Path` has a token that is not a valid URL, and logs a warning. On Java 17 it keeps such a jar, and logs no warning, when the jar also holds `META-INF/INDEX.LIST`. Either way, it reads none of the jars that `Class-Path` names. |
| Unpacked Spring Boot application on the classpath | Every file under `BOOT-INF/lib/`, or `WEB-INF/lib/` and `WEB-INF/lib-provided/`, plus every jar its classpath index names. Each is a dependency unless it holds your own classes, even a jar whose manifest names an agent. The agent searches them after every `java.class.path` entry. |
| Spring Boot executable jar | Every file under `BOOT-INF/lib/`, each read from its own central directory inside the fat jar, with nothing extracted to disk. |
| Executable WAR | Every file under `WEB-INF/lib/` and `WEB-INF/lib-provided/`. |

The agent reads Spring Boot's classpath index only for an unpacked application. For a packaged jar it lists every file under the library folders, whatever the index says.

The agent skips a jar it cannot read, logs one `WARNING`, and continues the listing. If the whole listing fails, the agent logs `the startup dependency listing failed; no dependencies will be reported` as a `WARNING`. It reports no dependencies from that instance and never sets the "dependencies listed" flag.

### Dependencies discovered at load

Some jars are on no startup list. An application server can open a WAR's `WEB-INF/lib` after the agent starts, Spring Boot can unpack a library to the temporary directory, and a plugin loader can open a jar at runtime. When the agent counts loaded classes, it reads and judges each unknown jar with the same rules as the listing, and registers a new dependency with the discovery source `load`.

A jar discovered at load can never read as unloaded, because a class from it already loaded. A jar that is on no listing and never loads a class stays invisible.

## Statuses

The testkit derives one status per dependency from the instances it heard from. It checks the statuses in the order below, and the first condition that holds decides.

| Status | Meaning |
|---|---|
| Resources only | Every instance's listing counted no class in the jar, and no instance loaded a class from it, as with native libraries, web assets, or message bundles. |
| Unloaded | Some instance listed the jar from its startup classpath, and no instance loaded a class from it. |
| Loaded | A class loaded, and the testkit claims nothing further, because no instance that lists the jar records references. |
| Used | Your code holds a live reference to the jar. |
| Failed to load | No live reference, and your code references the jar from a class that a complete baseline declares and that failed to load. |
| Failed to initialise | No live reference, no reference from a class that failed to load, and your code references the jar from a class that failed to initialise. |
| Without evidence | No live reference, and a method with no hits in a class whose static initialiser is still running references the jar, so its zero counts prove nothing. |
| No live reference | The jar has no live reference, and some instance that lists it sent no complete baseline, so the testkit cannot tell unreferenced from unreached. |
| Unreferenced | A class from the jar loaded, and nothing in your code references the jar. |
| Unreached | Your code references the jar, but only from methods that were never hit or classes that never loaded. |

Otherlode reports these statuses too, and two more that claim nothing: stale and counts behind. [Unused dependencies](/docs/server/findings#unused-dependencies) has Otherlode's rules.

The agent does not start without `includePackages`, so every running agent records references. A dependency reads loaded only when no instance that lists it sent references.

Unreferenced is often a library that another library needs, or one reached only through a service lookup, such as a JDBC driver or a logging backend. Read the status next to the loaded-class count. Direct and transitive dependencies look the same, because the build file is not visible at run time.

### Counting loads

On every flush the agent counts the distinct classes loaded from each dependency. It matches a class to its jar by the jar's identity, not by the string form of the code-source URL. The count never falls, even if a class unloads. The agent also stamps when a dependency's count first rose above zero, with the precision of one flush.

The agent skips arrays, primitives, hidden classes such as lambda classes, and classes with no code source.

### What counts as a reference

A reference is any mention of a class in your bytecode that the running JVM can need:

- a call, a field access, or a construction
- a cast, an `instanceof` test, or a catch type
- a class literal
- a type in a method or field descriptor, or in a generic signature
- a supertype
- a runtime-visible annotation on a class, method, field, or parameter, including the types its values name

A dependency your code uses only through an annotation such as `@JsonProperty`, or only as a base class, still counts as referenced.

These do not count:

- A class-retention annotation, since the JVM never resolves its type. So the `@NotNull` and `@Nullable` from `org.jetbrains:annotations` that kotlinc adds do not make that jar used.
- A reference to a JDK class, or to any class in a `java` package that nothing provides.
- A reference to a class that sits in a classpath directory. It is your own code.
- A reference to a class inside your include rules.

The agent records references per method, and per class for references outside any method it probes, such as the class header and its fields. It attributes a reference in a generated forwarder to the method that calls the forwarder. A lambda body or a body class keeps its own references. The agent finds where a referenced class lives by asking the referencing class's own loader for the class file as a resource, which reads the file and never loads the class.

### Live references

A reference is live when either holds:

- A method with hits holds it, on any instance.
- A class holds it at class level (an annotation, a supertype, a field type), that class loaded, and it did not fail to initialise.

An inline method's own references never count, because its callers carry copies of the body. Read [methods and branches](methods-and-branches) for inline methods.

A reference from a method that was never hit, or from a class that never loaded, is not live. A reference in a class that never loaded is visible only through the static baseline.

### Absent references

A reference to a class no loader can find is an absent reference. Typical causes are code guarded by a check for an optional library, or a loader that throws when asked for the class. The agent reports it with every site that holds it and maps it to no dependency.

### What each status needs

| Status | Needs |
|---|---|
| Resources only, unloaded, loaded | The startup listing and the loaded-class counts. |
| Used, failed to initialise, no live reference | The recorded references, which every running agent sends. |
| Failed to load | A complete static baseline from an instance whose scan declares the class that failed to load. |
| Unreferenced, unreached | A complete [static baseline](classes#enable-the-baseline) from every instance that lists the dependency. Set `staticBaselineEnabled=true` to send it. |

Without a complete baseline, the testkit cannot see a reference held in a class that never loaded, so a dependency that is unreached would read as unreferenced. The status is "no live reference" instead, which is true either way. A baseline is complete only when every chunk of one scan has arrived.

Failed to load asks for review and claims no removal. The class may fail because of the very classpath that jar is on. Read [classes](classes#failed-to-load) for how the agent names a class that failed to load.

### A Kotlin service always uses kotlin-stdlib

Every Kotlin class carries the runtime-visible `kotlin.Metadata` annotation, so kotlin-stdlib always reads as used on a Kotlin service. A Java service that pulls kotlin-stdlib in is judged on its own use.

## When a dependency is listed

The agent sends a dependency's entry only after the receiver confirmed the sends of the first flush that counted its loaded classes, even when that count is zero and no count goes out for it. On the manifest, the agent holds back the mapping from a referenced class to that dependency under the same condition. An absent reference names no dependency, so nothing holds it back. A static baseline chunk carries the mappings of the names its classes reference, held back or not.

The manifest of each instance also carries a flag that means "dependencies listed". The agent sets it once the startup listing has finished and the agent has delivered every startup dependency and every reference mapping it recorded before the listing ended. Before that point, an empty list of dependencies or absent references means "not listed yet", not "none".

The entry usually arrives in the same flush as the first count, and at the earliest on the flush after the listing ends.

## Query dependencies in tests

[The testkit](testkit) applies the statuses above inside one test JVM. See [the testkit API](testkit-api) for the signatures.

| Query | Returns |
|---|---|
| `dependency(groupId, artifactId)` | The status of one dependency. Pass `null` as the group for a file-name identity. |
| `unloadedDependencies()` | Every dependency with status unloaded. |
| `unreferencedDependencies()` | Every dependency with status unreferenced. |
| `unreachedDependencies()` | Every dependency with status unreached, with the sites that reference it. |
| `failedToLoadDependencies()` | Every dependency with status failed to load. |
| `failedToInitializeDependencies()` | Every dependency with status failed to initialise. |
| `absentReferences()` | Every absent reference, with its sites. |
| `awaitDependency(groupId, artifactId, timeout)` | Waits until a manifest lists the dependency. |
| `awaitDependenciesListed(timeout)` | Waits until the testkit has heard from at least one instance and every instance it heard from has sent the "dependencies listed" flag. |

The result of a query is a `DependencyStatus` with a `DependencyUsage` value. `DependencyUsage` has the values `UNLOADED`, `UNREFERENCED`, `UNREACHED`, `NO_LIVE_REFERENCE`, `FAILED_TO_LOAD`, `USED`, `LOADED`, `RESOURCES_ONLY`, `FAILED_TO_INITIALIZE`, and `WITHOUT_EVIDENCE`. A later release can add values, so a `when` over it needs an `else` branch. Each referencing site prints as `Class#method`, with `(class level)`, `(never loaded)`, `(failed to load)`, or `(failed to initialise)` where they apply.

These rules apply to the queries:

- `dependency` throws `UnknownDependencyException` when no manifest listed the dependency. The message names the identities the collector knows.
- When several dependencies carry the identity you asked for, as when a plain jar and a shaded jar both bundle it, `dependency` returns the one whose only identity it is. It throws `IllegalStateException` when none of them has it as its only identity, and the message names each candidate.
- The five list queries and `absentReferences()` throw `IllegalStateException` until the testkit has heard from at least one instance and every instance it heard from has sent the "dependencies listed" flag. Call `awaitDependenciesListed` first.
- `unreachedDependencies()`, `failedToLoadDependencies()` and `failedToInitializeDependencies()` throw `IllegalStateException` while an instance's zero counts are not evidence, as `neverHit` does. `dependency` throws then too, but only when the status it would return is unreached, no live reference, failed to load or failed to initialise, and the dependency has referencing sites.
- `unreferencedDependencies()`, `unreachedDependencies()`, `failedToLoadDependencies()` and `failedToInitializeDependencies()` also throw `IllegalStateException` while any dependency reads no live reference or loaded, or no instance recorded references. A dependency reads no live reference only while some instance that lists it has sent no complete baseline, so run every instance with `staticBaselineEnabled=true`. `absentReferences()` throws when no instance recorded references.
- Hits and reference sites that separate used from unreached arrive like any probe data. Wait for them with `awaitSettled`.
