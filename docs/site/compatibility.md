---
title: Compatibility
description: The JDKs, class-file versions, compilers, frameworks and other agents the Otherlode agent works with, and what changes when it shares a JVM.
order: 110
---

## JDKs

The agent and the testkit are compiled against the JDK 17 API and emit class-file version 61, so they run on JDK 17 and later.

The build runs the tests on JDK 17, 21 and 25 (Temurin), each as its own CI job. Micronaut 5's suite needs JDK 25 and runs there in every job, and the framework marker tests run on JDK 21. No other JDK version is tested. The agent reads two fields of the JDK's lambda factory to name Java lambda handlers, and a JDK that renames them fails a test.

The agent weaves the two constructors of `java.lang.VerifyError` at startup to learn which classes the JVM's verifier rejects. This works alongside JFR, which on JDK 17 and 21 also transforms `Error` and `Throwable` when it starts. See [classes](classes#rejected-by-the-verifier).

The agent attaches with `-javaagent` only. The jar has no `agentmain` entry point, so you cannot attach it to a running JVM. See [Attach the agent](attach).

## Class-file versions

The agent weaves classes at any class-file version your JDK can define. A test generates classes at versions 45, 46, 47, 48, 49, 50, 51, 52, 55, 61, 65 and 69 (Java 1.1 to Java 25), weaves each, and checks that the woven class loads, initialises, runs and counts as its unwoven twin does. It skips a version newer than the test JVM can define. Another test weaves every class of five corpora: the project's two Kotlin demo applications, its Scala 2 and Scala 3 test fixtures, and the `spring-webmvc` and `ktor-server-core` jars. It checks that each woven class defines and initialises wherever its unwoven twin does.

Two things differ by version:

- **Version 55 and later.** Each probe reads its class's count array as a dynamic constant. The agent adds no field, method or static initialiser to the class.
- **Below version 55.** The agent adds a `private static synthetic long[]` field and two private synthetic methods: an accessor that every probe calls, and the lookup it falls back on. If the class has a static initialiser, the field is `final` and the initialiser fills it. If not, the agent adds no static initialiser, and the first probe stores the array in the field. An interface gets a `public static final` field instead, and a static initialiser if it has none. An interface below version 52 gets no private methods, and its probes are all in its static initialiser. Reflection over such a class shows the extra members.

A class that implements `Serializable` and declares no `serialVersionUID` keeps its default `serialVersionUID`, so data serialised by a JVM without the agent reads in a JVM with it, and the other way round. The exception is a `Serializable` interface below version 55 that the agent instruments, one with a default method, a static method or a static initialiser. Its own default `serialVersionUID` changes, which matters only when its `Class` object is serialised, as in a field of type `Class`.

Classes below version 51 can contain `jsr` and `ret` subroutines. The agent inlines them before it adds probes.

A woven class fails to load only where the unwoven class fails too. The one exception is a class whose supertype is missing at any depth, which the agent skips and reports. See [classes](classes).

## Class loaders that do not delegate to the boot loader

A woven class refers to `dev.otherlode.bootstrap.OtherlodeProbeArrays`, which the agent puts on the boot class path. A class loader that does not pass that package to the boot loader cannot resolve it. JBoss Modules (WildFly) and Apache Felix behave this way by default.

The agent checks each loader once, before it weaves the first class the loader defines. If the loader cannot see `dev.otherlode.bootstrap`, the agent leaves every class of that loader unwoven and reports each as skipped. An endpoint module that would hook a class of that loader switches itself off, so none of its endpoints reads as never called. The agent logs one `WARNING` per loader that names the loader's class, and each module that switches off logs its own. See [skipped classes](classes#skipped-classes).

To instrument the classes of such a loader, delegate the package to the boot loader:

- **JBoss Modules and WildFly.** Add `-Djboss.modules.system.pkgs=dev.otherlode.bootstrap` to the JVM options. If the property is already set, append the package to its comma-separated list.
- **Apache Felix and other OSGi frameworks.** Set `org.osgi.framework.bootdelegation=dev.otherlode.bootstrap`. If the property is already set, append the package to its comma-separated list.

## Compilers

The agent tells a compiler's generated code, such as a data class's `copy`, a case class's `productElement` or a suspend function's state machine, from yours by the exact body the compiler wrote. Those bodies change between compiler releases, so the marks are exact only for releases the agent was checked against:

| Language | Compiler releases checked |
|---|---|
| Kotlin | kotlinc 1.9.25, 2.1.21, 2.2.21 and 2.4.21, standing for 1.9 to 2.4. 2.1.21 stands for the releases that default to `-jvm-default=disable`. The kotlinx.serialization plugin's output is checked with each release's own plugin, and with 2.1.21 and 2.2.21 also under `-language-version 1.9`, which compiles with the K1 frontend. |
| Java | javac 17, 21 and 25 |
| Scala 2 | scalac 2.12.20, 2.13.15, 2.13.16 and 2.13.18. A Scala 2 class does not name its compiler, so the rules accept every body shape found in a survey of 2.12.18 to 2.12.21 and 2.13.14 to 2.13.18. |
| Scala 3 | Each release from 3.3.3 to 3.10.0. A Scala 3 class names the release that wrote it. The releases the agent reads are listed in `scala3-read-releases.txt` inside the agent jar. |

Code from any other compiler, or from a release that changed a shape, is probed and counted like any other code. If its outline looks like compiler output but its body is not one the agent has read, the agent reports it as an unread shape, which is never called dead code. On the first flush that finds one, the agent logs a WARNING that counts them by family and names each Scala 3 release it has not read. See [Unread shapes](methods-and-branches#unread-shapes).

A scheduled CI job compiles the fixtures every week with the newest release of kotlinc, scalac 2.12, scalac 2.13 and Scala 3. A release that writes a shape the agent has not read fails that build.

## JPA enhancers

The agent reads classes that Hibernate, Ebean, EclipseLink and OpenJPA enhanced. See [methods and branches](methods-and-branches#methods-a-jpa-enhancer-adds). The rules are checked against entities that Ebean 19.7, EclipseLink 4.0 and OpenJPA 4.1 enhanced at build time.

Enhance OpenJPA entities that use property access at build time. When OpenJPA enhances one as it loads, a getter or setter it wrapped keeps its entry probe and gets no branch probes, so the branches you wrote in it are not reported. A `writeObject` you wrote in a serializable entity is the same.

OpenJPA's `openjpa.RuntimeUnenhancedClasses=supported` is not supported. Enhance the entities at build time instead.

## Frameworks

The agent counts HTTP endpoints for Spring MVC and Spring WebFlux, each with its functional routes, Javalin 6 and 7, Micronaut 3, 4 and 5, Ktor 2 and 3, Vert.x Web 4.5 to 5, JAX-RS (`javax` and `jakarta`), plain servlets (`javax.servlet` and `jakarta.servlet`, from Servlet 4.0, on Tomcat, Jetty and Undertow) and the JDK `HttpServer`. The versions of each are on [endpoints](endpoints).

## Other agents in the same JVM

Every transformer of the agent that changes a class is retransformation-capable, and the JVM calls those after every transformer that is not, whatever order the `-javaagent` flags list. So the agent runs after JaCoCo's agent, AspectJ's weaver and Spring's load-time weaver. It reads each class's shape from the class file on disk, not from the bytes it receives, so its report does not depend on the flag order. The agent's class listing also registers an observer that is not retransformation-capable, and it changes no class.

| Other agent | What changes |
|---|---|
| JaCoCo, listed before or after | Nothing. The agent weaves after JaCoCo, so JaCoCo's class ids still match the class files. The agent's manifest equals a run without JaCoCo. Gradle's `jacoco` plugin can stay applied to the test task that carries the agent. |
| AspectJ's weaver, Spring's load-time weaver | The agent sees the weaver's output as the bytes it receives. A method whose conditional jumps the weaver added, removed or reordered keeps its entry probe and gets no branch probes. A method missing from the bytes the agent receives gets no probes. The agent logs one INFO line per class, which starts `otherlode: <class> reached this agent rewritten by an earlier transformer`. |
| OpenTelemetry's agent | Tested in both orders. A Spring MVC request counts once, and no endpoint module switches off. With `otelBridgeEnabled`, the agent can also read OpenTelemetry's HTTP route. See [endpoints](endpoints). |
| An APM agent that retransforms woven classes | The agent weaves the class again from the plan it stored at first load, so the retransformation succeeds and the probes keep counting. A change the other agent makes to a method body stays. A method whose jumps no longer line up with the class file keeps its entry probe, its branch counts stay where they were, and the agent logs one INFO line per class. |
| An agent that calls `redefineClasses` | Handled like a retransformation. |

The agent refuses a retransformation or redefinition of a woven class in two cases:

- The class file on disk differs from the one the class was woven from, as after a recompile or a HotSwap.
- The new bytes arrive at a class-file version that needs another probe form, such as below 55 for a class woven at 55 or later.

The JVM then refuses the change, the class keeps running with its probes, and the agent logs one WARNING per class, such as:

```text
otherlode: the class file of com.example.Orders differs from the one it was woven from, as after a recompile or a HotSwap; it cannot be woven again, so the JVM refuses the redefinition or retransformation, and the class keeps running with its probes
```

Tests launch a JVM with JaCoCo's agent in both orders, and retransform and redefine woven classes with another transformer. No test launches AspectJ's weaver. OpenTelemetry's agent is launched only by the route bridge's tests, in both orders.

## HotSwap and IDE redefinition

HotSwap does not work on a woven class whose code you changed. When you debug with the agent on the JVM and edit a method, the JVM refuses the redefinition. Your IDE reports that HotSwap failed, and the old code keeps running until you restart. The agent logs the WARNING above, and the woven class keeps counting. A recompile into a classes directory or a rewritten jar is refused the same way, because the class file no longer matches the one the class was woven from.

A redefinition that leaves the class file unchanged and keeps the probe form is accepted. That happens in a remote debug session or an application run from a jar, where new code never reaches the class file. The report then keeps describing the class file, not the redefined code.

To use HotSwap, restart without the agent or leave it off the task you debug with.

A debugger that breaks on uncaught exceptions sees an exception leaving a static initialiser as caught. The agent wraps each static initialiser the class file declares in a handler that counts the exception and throws it again, so the debugger also reports a second throw from that handler. The class fails as it would without the agent.

## The CDS warning

The agent appends a small jar to the bootstrap class path when it starts. A disabled or refused start appends nothing. HotSpot then prints a one-line warning once, which reads in part:

```text
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

The agent works as usual with this warning.

The jar is a file named `otherlode-bootstrap-<random>.jar` in `java.io.tmpdir`, with a `.lock` file beside it that the JVM holds while it runs. On Linux and macOS, a JVM that exits normally deletes both. A JVM that is killed leaves both behind, and so does any JVM on Windows, where an open jar cannot be deleted. An agent that starts later with the same `java.io.tmpdir` deletes them at its first scheduled flush, if they are at least 10 minutes old by then. It keeps the files of every JVM still running.

If the agent cannot install that jar, for example on a read-only file system or a full `java.io.tmpdir`, it logs an ERROR and disables itself, exporter included. See [troubleshooting](troubleshooting).

## Dependencies inside the agent jar

The agent jar bundles its dependencies and relocates them into `dev.otherlode.shaded`: Byte Buddy (with ASM), protobuf-java, the Kotlin standard library and the JetBrains annotations. Your application can carry its own copies at other versions without a clash.

The wire module's packages are relocated the same way, so a test classpath that carries a different version of the testkit's wire module does not replace the agent's.
