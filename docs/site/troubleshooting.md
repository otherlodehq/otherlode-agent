---
title: Troubleshooting
description: Find the cause of an Otherlode agent log line or a missing report, organised by what you see, with the exact message, the cause and the fix.
order: 150
---

Every entry starts from something you see: a log line or a symptom. Each gives the message as the agent writes it, the cause, and what to do. The agent prefixes every message with `otherlode:`. Search your application's startup output for that prefix first.

## Read the agent's log

The agent writes through the JVM's `System.Logger`. With no logging bridge installed, that is `java.util.logging`, which prints to standard error in this shape:

```text
Oct 07, 2026 6:09:02 PM dev.otherlode.config.AgentConfig$Companion parse
WARNING: otherlode: ignoring unknown agent option 'foo' (known options: [authToken, enabled, ...])
```

Two details trip people up:

- The agent logs its startup failures at ERROR. `java.util.logging` prints that level as `SEVERE`.
- The agent logs at WARNING and ERROR for problems it can name, and at INFO for a few events such as `otherlode: disabled by configuration, nothing will be instrumented or exported`. A healthy agent prints a few INFO lines, such as `otherlode: installing endpoint modules: ...` at startup, and the JVM prints its own CDS warning, described [below](#the-jvm-prints-a-cds-warning-at-startup).

If your application sends `System.Logger` output to Logback, Log4j or another backend through a bridge, the agent's lines go there once your logging is configured. Lines written during `premain`, before your application configures logging, go to standard error.

### See more detail

The agent has no log-level option. Its loggers are named after its packages and all start with `dev.otherlode`, so you set the level in your logging system for that name. It logs a few lines at DEBUG, which `java.util.logging` calls `FINE`, such as the per-class count of branch sites left without a probe. With `java.util.logging`, put this in a file and start the JVM with `-Djava.util.logging.config.file=<file>`:

```properties
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=ALL
dev.otherlode.level=ALL
```

Most problems are visible at WARNING and above. The DEBUG lines add detail about individual classes and rarely explain a missing report.

## The agent is disabled

A disabled agent instruments nothing and sends nothing, and your application starts and runs as if the flag were absent. The collector sees no instance at all.

### includePackages is missing

```text
otherlode: includePackages is not set, so the agent is disabled for this JVM and nothing will be instrumented or exported; set includePackages to your application's own package prefixes, ';'-separated
```

When the agent can find your main class, the line ends with `; the main class is com.acme.shop.ShopApplication; includePackages=com.acme.shop covers its package, or name a broader prefix that also covers your shared libraries`.

The option is absent, blank, set only to separators, or set only through `excludePackages`. It is also empty when every prefix was written as a glob or a path. An unset template variable causes this often: `includePackages=${APP_PACKAGES}` with `APP_PACKAGES` unset.

Set `includePackages` to a dotted package prefix, such as `includePackages=com.acme.shop`. The agent never applies the suggestion for you. See [Choose includePackages](attach#choose-includepackages) and [configuration options](configuration#includepackages-and-excludepackages).

### A prefix matches nothing

```text
otherlode: 'com.acme.*' matches no class and is ignored: write a dotted package prefix with no wildcard, such as 'com.acme'
```

The agent drops a prefix that contains `*` or `/`. If the dropped prefix was your only one, the agent then refuses to start with the line above. Write `com.acme`, not `com.acme.*` or `com/acme`.

### The agent could not install its helper jar

```text
otherlode: could not install the bootstrap holder; the agent is disabled for this JVM
```

The exception under it names the cause, one of:

- `otherlode: could not write the bootstrap holder jar to a temp file`: the agent writes a small helper jar to `java.io.tmpdir` at startup and could not. The directory is read-only, full, or not writable by the user running the JVM.
- `otherlode: appended the bootstrap holder jar but dev.otherlode.bootstrap.OtherlodeProbeArrays is still not loadable`
- `otherlode: bootstrap holder jar not found on the agent classpath at META-INF/otherlode/bootstrap-jar.bin`: the agent jar is damaged or is not the published jar. Download it again.

For the first cause, point the JVM at a writable directory with `-Djava.io.tmpdir=/path/to/dir`, or free the space. The agent disables itself rather than run without counting, because an instance that reports zero hits everywhere would read as dead code, and a missing instance does not.

### Another startup failure

```text
otherlode: agent failed to start and is disabled for this JVM
```

The stack trace follows the line. This is a bug in the agent, not in your configuration. Report it with the trace, the agent version and your JDK.

### The agent is switched off

```text
otherlode: disabled by configuration, nothing will be instrumented or exported
```

The `enabled` option is `false`, often through `OTHERLODE_ENABLED=false` in the environment. This is the only deliberate way to turn the agent off, and it is INFO because there is nothing to fix. Remove the variable or set it to `true`. An `enabled=` in the `-javaagent` string overrides the environment variable. See [Turn the agent off](attach#turn-the-agent-off).

### The agent runs but reports no endpoints

```text
otherlode: endpoint instrumentation failed to install, continuing without endpoint tracking
```

The method and branch tiers work. The endpoint tier did not install, and the instance reports no endpoints. A variant, `otherlode: endpoint module discovery failed, continuing without endpoint tracking`, means the agent could not load its list of endpoint modules. The stack trace names the cause. If you do not need endpoints, set `endpointsEnabled=false`, which logs `otherlode: endpointsEnabled=false, no framework's endpoints will be instrumented` instead of the error. Otherwise report the trace.

## An option is ignored

These warnings each mean the agent kept running with a default instead of your value.

| Message | Cause and fix |
|---|---|
| `otherlode: ignoring unknown agent option 'foo' (known options: [...])` | The option name is misspelled or does not exist. The line lists every name. Names are case-sensitive. |
| `otherlode: ignoring malformed agent option 'x' (expected key=value; a value cannot contain a comma, set such a value through a system property or environment variable instead)` | A comma split the argument string into a piece with no `=`. A value that holds a comma, such as several packages joined with commas, must come from a system property or environment variable. Separate packages with `;`. |
| `otherlode: exportUrl must be an absolute http or https URL, ignoring 'collector:4319' and using the default http://localhost:4319` | The value has no scheme or host. The agent falls back to `http://localhost:4319`, which is why a mistyped `exportUrl` looks like a collector that never answers. A query string or fragment gets the same treatment, with its own reason. |
| `otherlode: flushIntervalSeconds must be a whole number from 1 to 86400, ignoring '30s' and using the default of 60s` | Write plain seconds with no unit. |
| `otherlode: enabled must be 'true' or 'false', ignoring 'yes' and using the default of true` | The same message appears for `staticBaselineEnabled`, `endpointsEnabled`, `otelBridgeEnabled` and `testRun`, each naming its own option and default. |
| `otherlode: exportUrl's host 'otherlode_collector' has an underscore, which a URL host name cannot contain; ...` | The host name has an underscore, as a Docker Compose service name can. Java's HTTP client cannot reach such a host. Use a network alias or host name without an underscore, or an IP address. |
| `otherlode: exportUrl uses plain http, so the auth token is sent unencrypted` | `authToken` is set and `exportUrl` starts with `http://`. Use an `https://` URL unless the collector is on the same host. |
| `otherlode: ignoring all of OTEL_RESOURCE_ATTRIBUTES, since its entry 'x' is not key=value; no service name, namespace, version or environment is read from it` | One malformed entry discards the whole list, as the OpenTelemetry specification says. Fix the entry. |
| `otherlode: ignoring '..' from <source>, since no URL path can name a service or namespace '..'` | A service name or namespace of `.` or `..` is refused. |
| `otherlode: callbackAnnotations entry '@com.acme.Handles' is not a fully qualified annotation type name and is ignored: write the dotted name with its package and no wildcard, such as 'com.acme.Handles'` | An entry in [`callbackAnnotations`](configuration#callbackannotations) has a leading `@`, a `/`, a `*`, a trailing `.class`, no package, or a part that is not a Java identifier. The agent drops that entry and keeps the others. Write the annotation's name as you would import it. |

[Configuration options](configuration) lists every option and where its value comes from.

## Nothing reaches the collector

The agent sends a delta batch on every flush, even when no count changed. The first flush comes at a random point within the first `flushIntervalSeconds`, 60 seconds by default. Before you look further, start the JVM with `flushIntervalSeconds=1` and wait a few seconds.

A send that fails logs a warning, then the agent tries again on the next flush. Two payloads fail separately, so you often see both lines each flush:

```text
otherlode: delta export failed, will retry next flush
otherlode: manifest export failed, will retry next flush
```

The exception under the line names the cause. If neither line appears, the sends succeed and the problem is on the collector's side, or the agent is [disabled](#the-agent-is-disabled). The agent never stops retrying, and a failing flush does not stop the schedule.

### The connection fails

The exception is a `java.net.ConnectException` or a timeout.

- The agent cannot reach the address. Check `exportUrl` from the same host or pod with `curl`, using the same URL.
- `exportUrl` fell back to its default because the value was invalid. Look for `exportUrl must be...` [above](#an-option-is-ignored). The default, `http://localhost:4319`, reaches nothing in a container that runs no collector.
- The collector accepts the connection and never answers. The agent gives up on a request after 10 seconds.

One send makes up to 5 attempts, so a single failure takes several seconds to log. [What the agent sends](data-sent#when-the-collector-is-down-or-refuses-a-payload) describes the retry rules.

### The collector answers with a status

The exception message is `otherlode: unexpected status <code> from <exportUrl>/v1/otherlode/<deltas|manifest|static-baseline>`.

| Status | Cause and fix |
|---|---|
| `401`, `403` | The token is missing or wrong, or the collector wants a token and none is set. Set `OTHERLODE_AUTH_TOKEN`. The agent does not retry within a send. It sends again on the next flush, so it recovers after you rotate the token. |
| `404` | The URL does not reach a collector. The agent appends `/v1/otherlode/deltas`, `/v1/otherlode/manifest` and `/v1/otherlode/static-baseline` to `exportUrl`, so a proxy must forward those paths. Drop any path from `exportUrl` that the collector does not expect. |
| `301`, `302`, `307`, `308` | The agent does not follow redirects. A proxy that redirects `http` to `https` causes this. Put the final `https://` URL in `exportUrl`. |
| `400`, `422` | The receiver rejected the content. With the testkit's collector, see [A test fails with a version mismatch](#a-test-fails-with-a-version-mismatch). With another receiver, see its log for the reason. |
| `413` | The receiver refused the request body as too large. A reverse proxy or gateway in front of the collector usually has the limit. Raise it. The agent keeps the static baseline after a `413` and sends it once the limit is raised, with no restart. The agent splits its payloads by entry count, not by size, so the fix is on the receiving side. [What the agent sends](data-sent#chunk-caps) lists the caps. Once a count has been refused, the run says on each later payload that its counts are pending, so a collector does not read its zero hits as never hit. Payloads stamped before the first refusal in that flush say nothing of it. |
| `408`, `429`, `5xx` | The agent retries these within the send. If they persist across flushes, the collector is overloaded or down. |

The agent resends the same payload on the next flush for every status above, so a repeated warning means the answer has not changed. The static baseline is the exception. For `400` and `422` it logs a warning and stops sending the scan, since the same bytes meet the same answer:

```text
otherlode: the collector refused static baseline chunk 3 of 7 with status 400, which resending the same bytes cannot change; the scan is not sent again
```

A static baseline refused this way is not sent again in this process. A `413` is not refused this way: the agent keeps the scan and sends it after a later flush the collector confirms.

### A static baseline send fails

```text
otherlode: failed to send static baseline chunk 1 of 4; it and the chunks after it are sent again after a flush the collector confirms
```

This logs once. The chunks stay pending and go out one per flush after a flush whose own sends the collector confirmed. If the main sends fail, fix those first.

### A test run ended before the scan did

```text
otherlode: the static baseline scan did not end within 15s of shutdown, so this test run may send no complete scan
```

This appears only with `testRun` and `staticBaselineEnabled` both on. The shutdown hook waits 15 seconds for the scan, and the scan was still running. A collector holds an incomplete scan and makes no never-loaded claim from it. Run the scan on a smaller classpath, or accept that this test run reports no complete scan.

## A test fails with a version mismatch

The agent and the testkit are released as one version, and the testkit's collector refuses a payload whose agent version is not its own. The agent logs `unexpected status 400` every flush, and the next query or wait in your test throws `IllegalStateException` with the reasons the collector recorded:

```text
delta batch from instance 3f1c... comes from agent version 0.1.0, but this testkit is version 0.2.0. The agent and the testkit are released as one version: use the testkit of the agent's version, 0.1.0
```

Use the testkit dependency and the `-javaagent` jar with the same version. The testkit's collector also refuses payloads that had an empty run id, a second run under one instance id, or fields stripped by a collector that forwarded them, and it records the reason in each case. `collector.rejectedPayloads()` lists them. See [the testkit](testkit).

Two more testkit messages come from the agent side:

- The testkit's JUnit extension fails with `otherlode-testkit: no delta batch arrived from the agent within <n>s`. The test JVM has no `-javaagent` flag, the agent refused to start for a missing `includePackages`, or the flush interval is longer than the timeout. Add `flushIntervalSeconds=1` to the agent arguments.
- A payload from a second instance when one fork was expected means more than one test JVM posts to one collector. The message names `maxParallelForks`.

## A class is skipped

```text
otherlode: com.acme.shop.Report cannot be defined: its supertype com.acme.shop.Base could not be read from its loader, so it is not instrumented
```

The class is in your include rules, but a superclass or interface, at any depth, has no class file its loader can read. The JVM cannot define such a class, so the agent leaves it alone and reports it as skipped with this reason. The class would fail with `NoClassDefFoundError` without the agent too. Add the missing dependency to the classpath, or remove the class.

```text
otherlode: instrumentation failed for com.acme.shop.Report, class will run uninstrumented
```

The agent could not weave the class. The stack trace under the line says why, and the class runs unchanged. A skipped class appears in the collector with the reason, and the report does not call it dead. [Classes](classes) lists the class states. If the cause is not in your build, report the class, its compiler and the trace.

The testkit lists skipped classes with `collector.skippedClasses()`.

## A class was woven but never defined

```text
otherlode: com.acme.shop.Plugin was woven and registered but the JVM never defined it; its probes are withheld for good
```

At shutdown the agent adds `otherlode: 3 classes were woven and registered but never confirmed defined`.

The agent rewrote the class, and the JVM then never defined it, on two sweeps in a row. The usual cause is that the JVM rejected the class for a reason in your application: a missing class, or a verifier error. A transformer that runs after the agent and replaces the bytes also causes it. Run the application without the agent. If the class loads there, report the class and its loader.

The agent holds back the class's probes, so the report does not list it as dead code.

## A method has no branch probes

A method keeps its entry probe and loses branch probes in three situations, each with its own message.

```text
otherlode: com.acme.shop.Parser#parse(Ljava/lang/String;)V would not fit a class file with branch probes: it is 65000 bytes of code and could grow to 70100 bytes, over the 65535 byte limit; its branch probes are left out and its entry probe is kept
```

The JVM limits a method to 65,535 bytes of code, and the branch probes would take this one past the limit. The agent leaves the branch probes out and keeps the entry probe. A second variant reads `would not be JIT-compiled with branch probes` and names an 8000 byte limit: HotSpot does not compile a method longer than that, so probes would leave it interpreted for good. A third variant, `its entry probe can take it to <n>, over the 8000 byte limit; HotSpot may not compile it, and its branch probes are kept`, applies to a method already near 8000 bytes, where the entry probe alone can push it over. Splitting a large method clears all three.

```text
otherlode: com.acme.shop.Cart reached this agent rewritten by an earlier transformer; the branches of ... do not line up with its class file, so those methods keep their entry probe and get no branch probes
```

At INFO. Another agent, such as an aspect weaver, changed the method's body, and the branches in the bytes the agent received no longer match the class file. The agent keeps the entry probe and skips the branches rather than report counts against the wrong code. See [other agents in the same JVM](compatibility#other-agents-in-the-same-jvm).

```text
otherlode: com.acme.shop.Cart's bytecode could not be read; any method of it that is probed at all gets an entry probe with no line number, and the class gets no branch probes, no call edges, no supertypes, and no inline or generated mark
```

The agent could not read the class's bytes from its loader or from the bytes it received. Methods still count, with less detail. If you see this for ordinary classes, report the class and its loader.

After the first flush, one INFO line counts branch sites left without a probe across the process and splits the count by reason: inlined from code outside your include rules, coroutine machinery, switch lowering, and code-size limits. Most of those are deliberate. [Methods and branches](methods-and-branches) explains what the agent leaves out and why.

## A method a framework calls reads as uncalled

A never-hit method that a framework calls, such as a command handler or a handler your own dispatcher finds by reflection, shows as `UNCALLED` when the framework's annotation is not on the agent's built-in list. Name the annotation in [`callbackAnnotations`](configuration#callbackannotations), and the method reads `CALLED_FROM_OUTSIDE_SCOPE` from the next run. Its counts do not change. The annotation must sit on the method itself: one on an interface method the class implements does not count.

```text
otherlode: callbackAnnotations names 1 annotation not yet seen on any method: com.acme.bus.Handels. A name is matched exactly; check the spelling if its classes have loaded.
```

This INFO line names each annotation the agent has not yet found on a method. It is logged once, at the first flush five minutes or more after startup, or at shutdown if that comes first, and only when some name is unseen. Check the spelling, and that the name is the annotation's own and not the class it sits in. A correct name is listed too when none of the classes that use it has loaded yet.

## A Kotlin class has no line numbers

```text
otherlode: com.acme.shop.Totals is a Kotlin class with no line-number table; its probes carry no line numbers, and its inlined copies cannot be traced
```

Something removed the debug information from the class, such as an obfuscator or a build setting that strips line numbers. The class still counts, but its probes carry no line number, an inline function reads as ordinary code, and a branch copied in from an inline function cannot be told from your own. Keep debug information in the build you instrument. The warning is logged for Kotlin classes only. A Java class without line numbers is not reported.

## The agent reports compiler output it has not read

```text
otherlode: 12 methods in 3 classes look like compiler output this agent has not read, and are reported as unread shapes rather than dead code (...). Scala 3 releases this agent has not read: 3.7.0. A newer agent may read them.
```

Your classes use a compiler version whose generated code the agent does not recognise, so it reports those methods as unread shapes and not as never hit. The message names the families and, for Scala 3, the releases. Nothing needs fixing in your code. Upgrade to an agent release that reads your compiler, or ignore the rows marked unread. A second line, `otherlode: <class> was compiled by Scala <version>, which this agent has not read; ...`, names the class. [Methods and branches](methods-and-branches) lists the shapes.

## The static baseline warns about a blind spot

```text
otherlode: com.acme.shop.web.OrderController registered dynamically but was not in the static baseline computed at startup for this process; the static scan cannot see classes a server or plugin loader finds at run time, such as a deployed WAR, so this deployment may have such a blind spot
```

The warning appears only with `staticBaselineEnabled=true`. The scan walks the classpath the JVM was launched with. A class that loaded without being on it, such as one from a WAR that Tomcat deployed after startup or a plugin directory, is invisible to the scan. The class itself is fine and fully counted.

The consequence is for the never-loaded finding. The scan cannot list what it cannot see, so a class in such a location can never be reported as never loaded. Treat that finding as incomplete for this deployment. A variant of the line says `registered dynamically before the static baseline scan finished`; it has the same cause. [Classes](classes) explains the baseline and its limits.

The dependency listing has its own warnings, such as `otherlode: the startup dependency listing failed; no dependencies will be reported` and `otherlode: could not read <jar> for the dependency listing, skipping it`. They mean the listing skipped a jar or all jars, so dependency findings for this instance are missing or incomplete. [Dependencies](dependencies) describes the listing.

## HotSwap is refused

Your IDE reports that the redefinition failed after you edit a method in a debug session, and the agent logs one WARNING for the class:

```text
otherlode: the class file of com.acme.shop.Cart differs from the one it was woven from, as after a recompile or a HotSwap; it cannot be woven again, so the JVM refuses the redefinition or retransformation, and the class keeps running with its probes
```

The agent refuses on purpose. The report must not describe code other than the code that runs. Restart the JVM to run the new code, or debug without the agent. Another reason can stand in the same sentence, such as `the bytes for com.acme.shop.Cart arrive at class-file version 61, where it was woven at 52, and its probes' form cannot follow`. Both end the same way. See [HotSwap and IDE redefinition](compatibility#hotswap-and-ide-redefinition).

A different line, `otherlode: could not weave com.acme.shop.Cart again for a redefinition or retransformation; ...`, means another agent retransformed the class and the second weave failed. The rest of the line says whether the class keeps counting or runs unwoven from then on.

## The JVM prints a CDS warning at startup

```text
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

This is the JVM's line, not the agent's, and it is harmless. See [the CDS warning](compatibility#the-cds-warning).

## An endpoint module is disabled

```text
otherlode: endpoint module spring-webmvc disabled itself: java.lang.NoSuchMethodError: ...
```

An endpoint module found a framework class that does not match the version it supports and switched itself off for the rest of the process. That happens when its advice throws, when one of its hooks matches no method on the framework class (a framework release renamed or changed the method), or when a framework class it hooks fails to weave. The log line names the cause, and for an unmatched hook it names the hook and the class. The module name is one of `jaxrs`, `jdk-httpserver`, `ktor-2`, `ktor-3`, `spring-webmvc`, or `otel`. The other modules and the method and branch tiers keep working. The instance reports the module as disabled, with a kind naming why (linkage error, advice failure, transform failure, route walk failure or an unmatched hook), so a collector can tell "no endpoints" from "endpoints not instrumented". The testkit lists it with `collector.disabledEndpointModules()`.

Check your framework version against [the supported versions](endpoints) and [compatibility](compatibility). Upgrade or downgrade the framework, or set `endpointsEnabled=false` to turn the endpoint tier off. A module that disabled itself after declaring some routes leaves those routes in the report with no counting, so they read as never called. Treat that module's endpoints as unknown.

Related lines:

| Message | Meaning |
|---|---|
| `otherlode: endpoint module <name> disabled itself: <reason>` | The module is switched off. For an unmatched hook the reason names the hook and the class. |
| `otherlode: endpoint module <name> failed to transform <class>` | The module failed on one class and was switched off for the process. |
| `otherlode: endpoint module <name> failed while declaring its routes` | The module threw while it read the framework's route table. |
| `otherlode: endpoint instrumentation failed for <class>, class will run without endpoint tracking` | One class could not take the module's advice. Every module that matched it is switched off, except JAX-RS. |
| `otherlode: handler lambdas and method references will not be named on this JVM: <reason>` | The JDK internals the agent reads to name a Java lambda handler differ on this JVM. Endpoints still count, and the handler of a lambda route shows no method. |
| `otherlode: jaxrs: <class>#<method> inherits conflicting JAX-RS annotations from more than one interface, using <interface>` | The first interface's annotations win. Remove the conflict. |
| `otherlode: endpoint module discovery stopped early` | A module's provider failed to load and the rest were skipped. Report the trace. |

## A count went backward

```text
otherlode: probe count went backward for com.acme.Foo#12 (was 40, now 3); reporting it as-is
```

The agent increments counts without a lock. On a busy method, a thread that was paused mid-increment can write back an older value over the increments other threads made, so a count can drop between two flushes. That is expected on hot code, and it never turns a method that ran into one that did not. The line is logged once per probe, and a similar line exists for endpoints. A drop on a probe with almost no traffic is unexpected. Report that one with the full line.

## Lines that point to a bug in the agent

These mean the agent hit a case it did not expect. The application is not affected, but the data may be. Report each with the full line, any stack trace, and your agent and JDK versions.

| Message | Meaning |
|---|---|
| `otherlode: flush failed outside its own send guards, will retry next flush` | An unexpected exception in the flush, logged at ERROR. |
| `otherlode: no registered probe array for com.acme.Foo; its hits will not be counted (resolver installed: true)` | A woven class asked for its counter array and the agent had none. The class runs and its hits are not counted. |
| `otherlode: endpoint resolver threw from <operation>: <exception>` | The endpoint tier threw while recording. |
| `otherlode: the loaded-class sweep failed, will retry on a later flush` | The check for classes that no transformer saw failed. |
| `otherlode: an in-flight flush used up the shutdown budget; skipping the final flush` | The collector was slow or down at shutdown. The final flush has 10 seconds in total. Hits since the last confirmed flush are lost. |
