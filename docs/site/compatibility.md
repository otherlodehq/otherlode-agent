---
title: Compatibility
description: The JDKs, class-file versions, compilers, frameworks and other agents the Otherlode agent works with, and what changes when it shares a JVM.
order: 110
---

## JDKs

The agent and the testkit are compiled against the JDK 17 API and emit class-file version 61, so they load on JDK 17 and later. An API added after JDK 17 is a compile error in the build, not a runtime failure in your JVM.

The build runs every module's tests on JDK 17, 21 and 25 (Temurin), each as its own CI job. The agent reads two fields of the JDK's lambda factory to name Java lambda handlers, so a JDK that renames them fails a test there. No other JDK version is tested.

The agent attaches with `-javaagent` only. The jar has no `agentmain` entry point, so you cannot attach it to a running JVM. See [Attach the agent](attach).

## Class-file versions

The agent weaves classes at any class-file version your JDK can define. A test generates classes at versions 45, 46, 47, 48, 49, 50, 51, 52, 55, 61, 65 and 69 (Java 1.1 to Java 25), weaves each, and checks that the woven class loads, initialises, runs and counts as its unwoven twin does. It also does this for every class of two real-world corpora. A version newer than the test JVM can define is skipped in that test.

Two things differ by version:

- **Version 55 and later.** Each probe reads its class's count array as a dynamic constant. The agent adds no field, method or static initialiser to the class.
- **Below version 55.** The agent adds a `public static final long[]` field, a static initialiser that fills it, and a private synthetic accessor that every probe calls. Reflection over such a class shows the extra members.

Classes below version 51 can contain `jsr` and `ret` subroutines. The agent inlines them before it adds probes.

A woven class fails to load only where the unwoven class fails too. The one exception is a class whose supertype is missing at any depth: the agent skips it and reports it. See [classes](classes).

## Compilers

The agent tells a compiler's generated code (a data class's `copy`, a case class's `productElement`, a suspend function's state machine) from yours by the exact body the compiler wrote. Those bodies change between compiler releases, so the marks are exact only for releases the agent was checked against. Each release's output is run through the real analyser.

| Language | Compiler releases checked |
|---|---|
| Kotlin | kotlinc 1.9.25, 2.1.21, 2.2.21 and 2.4.20. They stand for 1.9 to 2.4. 2.1.21 stands for the releases that default to `-jvm-default=disable`. |
| Java | javac 17, 21 and 25 |
| Scala 2 | scalac 2.12.20, 2.13.15, 2.13.16 and 2.13.18. A Scala 2 class does not name its compiler, so the rules are version-blind: they accept every body shape found in a survey of 2.12.18 to 2.12.21 and 2.13.14 to 2.13.18, and the tested releases stand for those shapes. |
| Scala 3 | Each release from 3.3.3 to 3.9.0, one at a time. A Scala 3 class names the release that wrote it. The releases the agent reads are listed in `scala3-read-releases.txt` inside the agent jar. |

Code from any other compiler, or from a release that changed a shape, is probed and counted like any other code. If its outline looks like compiler output but its body is not one the agent has read, the agent reports it as an unread shape. An unread shape is never called dead code and never part of an unreached cluster, and the collector lists it apart. See [methods and branches](methods-and-branches).

On the first flush that finds an unread shape, the agent logs one WARNING that counts them by family and names each Scala 3 release it has not read. A scheduled CI job compiles the fixtures with the newest release of each compiler every week. A changed shape fails that build before it reaches a report.

## Frameworks

The agent counts HTTP endpoints for Spring MVC and Spring functional routes, Ktor 2 and 3, JAX-RS (`javax` and `jakarta`) and the JDK `HttpServer`. The versions of each are on [endpoints](endpoints).

## Other agents in the same JVM

The JVM calls every class transformer that is not retransformation-capable before every one that is, whatever order the `-javaagent` flags list. Both of the agent's transformers are retransformation-capable. So the agent always runs after JaCoCo's agent, AspectJ's weaver and Spring's load-time weaver, and it reads each class's shape from the class file on disk instead of from the bytes it receives. Your coverage report and the agent's report do not depend on the flag order.

| Other agent | What changes |
|---|---|
| JaCoCo, listed before or after | Nothing. JaCoCo identifies each class by the checksum of its class file, and that still matches, because the agent weaves after JaCoCo. The agent's manifest equals a run without JaCoCo. Gradle's `jacoco` plugin can stay applied to the test task that carries the agent. |
| AspectJ's weaver, Spring's load-time weaver | The agent sees the weaver's output as the bytes it receives. A method whose conditional jumps the weaver added, removed or reordered keeps its entry probe and gets no branch probes. The agent logs one INFO line per class. The method's branches are left out of the report instead of reported at zero. |
| OpenTelemetry's agent | OpenTelemetry's agent is retransformation-capable too, as is the agent's, so the two share the same ordering group. No test runs them together. With `otelBridgeEnabled`, the agent can also read OpenTelemetry's HTTP route. See [endpoints](endpoints). |
| An APM agent that retransforms woven classes | The agent weaves the class again from the plan it stored at first load, so the other agent's retransformation succeeds and the probes keep counting. If the other agent changes a method body, the agent keeps that change. A method whose jumps no longer pair with the class file keeps its entry probe, and its branch counts stay where they were. |
| An agent that calls `redefineClasses` | Handled like a retransformation. A redefinition is refused only when the class file on disk changed. |

Tests launch a JVM with JaCoCo's agent in both orders, and tests retransform and redefine woven classes with another transformer. No test launches AspectJ's weaver or OpenTelemetry's agent. Those rows follow from the same ordering rule and shape check.

## HotSwap and IDE redefinition

HotSwap does not work on a woven class whose code you changed. When you debug with the agent on the JVM and edit a method, the JVM refuses the redefinition. Your IDE reports that HotSwap failed, and the old code keeps running until you restart. The agent logs one WARNING naming the class, and the woven class keeps counting.

The agent refuses on purpose. A report must not describe code that is not the code running. A recompile into a classes directory or a rewritten jar is refused the same way, because the class file no longer matches the one the class was woven from.

A redefinition that leaves the class file unchanged is accepted. That is the shape of a remote debug session or an application run from a jar, where new code never reaches the class file. The report then keeps describing the class file, not the redefined code.

To use HotSwap, restart without the agent or leave it off the task you debug with.

## The CDS warning

The agent appends a small jar to the bootstrap class path at startup. HotSpot then prints a one-line warning once, which reads in part:

```text
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

The line is harmless. OpenTelemetry's agent causes the same one.

If the agent cannot install that jar, for example on a read-only file system or a full `java.io.tmpdir`, it logs an ERROR and disables itself, exporter included. See [troubleshooting](troubleshooting).

## Dependencies inside the agent jar

The agent jar bundles its dependencies and relocates them into `dev.otherlode.shaded`: Byte Buddy (with ASM), protobuf-java, the Kotlin standard library and the JetBrains annotations. Your application can carry its own copies at other versions without a clash. The agent does not read your application's copy, and your application does not see the agent's.

The wire module's packages are relocated the same way, so a test classpath that carries a different version of the testkit's wire module does not replace the agent's.
