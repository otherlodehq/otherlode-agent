---
title: Troubleshooting
description: Find the cause of an Otherlode agent log line or a missing report, organised by what you see, with the exact message, the cause and the fix.
order: 150
---

Each entry starts from a log line or a symptom, quotes the line as the agent writes it, and gives the cause and the fix. Every agent line starts with `otherlode:`, so search your application's output for that prefix first.

## Read the agent's log

The agent logs through the JVM's `System.Logger`. With no logging bridge installed, that is `java.util.logging`, which prints to standard error in this shape:

```text
Oct 07, 2026 6:09:02 PM dev.otherlode.config.AgentConfig$Companion parse
WARNING: otherlode: ignoring unknown agent option 'foo' (known options: [authToken, callbackAnnotations, classListingEnabled, ...])
```

The agent logs startup failures at ERROR, which `java.util.logging` prints as `SEVERE`. It logs problems it can name at WARNING, and a few events at INFO, such as `otherlode: disabled by configuration, nothing will be instrumented or exported`. A healthy agent prints a few INFO lines, such as `otherlode: installing endpoint modules: ...` at startup, and the JVM prints its own [CDS warning](#the-jvm-prints-a-cds-warning-at-startup).

If a bridge such as `log4j-jpl` or `slf4j-jdk-platform-logging` sends `System.Logger` output to Logback, Log4j or another backend, the agent's lines go there from 10 seconds after it starts. Until then the agent writes its INFO and higher lines to standard error and sends no payload, so your `main` has time to configure the backend. Without a bridge, lines logged during `premain`, before your application configures logging, go to standard error.

The agent leaves the choice of `java.util.logging` manager to your application, so a launcher such as JBoss Modules that sets `java.util.logging.manager` in its own `main` gets its manager. If the command line sets `java.util.logging.config.file` or `java.util.logging.config.class`, the JDK creates its default manager when the agent first logs.

From the start of the JVM's exit, the agent writes most of its lines to standard error in the shape above, whatever logging your application uses, since `java.util.logging` drops its handlers as the JVM exits. A few lines still go through `System.Logger` at exit, such as `endpoint module <name> disabled itself`, `endpoint replay buffer is full`, `endpoint resolver threw from`, the `jaxrs:` annotation lines, the route lines of the Vert.x Web and Spring functional modules, and `no registered probe array`.

### See more detail

The agent has no log-level option. Its loggers are named after its packages, which all start with `dev.otherlode`, so you set the level for that name in your logging system. A few lines are at DEBUG, which `java.util.logging` calls `FINE`, such as the per-class count of branch sites left without a probe. With `java.util.logging`, put this in a file and start the JVM with `-Djava.util.logging.config.file=<file>`:

```properties
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=ALL
dev.otherlode.level=ALL
```

On JBoss Modules or WildFly, set the level in the server's logging configuration instead, since `java.util.logging.config.file` makes the JDK create its default manager before JBoss Modules can choose its own.

Most problems show at WARNING and above. The DEBUG lines add detail about single classes.

## The agent is disabled

A disabled agent instruments nothing and sends nothing, and your application runs as it does without the flag. Otherlode lists no instance for it.

### includePackages is missing

```text
otherlode: includePackages is not set, so the agent is disabled for this JVM and nothing will be instrumented or exported; set includePackages to your application's own package prefixes, ';'-separated
```

When the agent can find your main class, the line goes on with `; the main class is com.acme.shop.ShopApplication; includePackages=com.acme.shop covers its package, or name a broader prefix that also covers your shared libraries`. The agent never applies that suggestion itself.

The option is absent, blank, set only to separators, or set only through `excludePackages`, or every prefix in it was a glob or a path. An unset template variable is a common cause: `includePackages=${APP_PACKAGES}` with `APP_PACKAGES` unset.

Set `includePackages` to a dotted package prefix, such as `includePackages=com.acme.shop`. See [Choose includePackages](attach#choose-includepackages) and [configuration options](configuration#includepackages-and-excludepackages).

### A prefix matches nothing

```text
otherlode: 'com.acme.*' matches no class and is ignored: write a dotted package prefix with no wildcard, such as 'com.acme'
```

The agent drops a prefix that holds `*` or `/`. If that was your only prefix, the agent then refuses to start with the `includePackages is not set` line. Write `com.acme`, not `com.acme.*` or `com/acme`.

### The export URL is refused

```text
otherlode: exportUrl must be an absolute http or https URL; exportUrl from the environment variable OTHERLODE_EXPORT_URL is refused, so the agent is off for this JVM
```

The line names the source of the value: the agent option, the system property or the environment variable. It does not repeat the value, apart from the host in the underscore case. The first part of the line gives the reason:

- `exportUrl must be an absolute http or https URL`: the value is not an absolute `http` or `https` URL with a host. A mistyped `collector:4319` with no scheme lands here.
- `exportUrl must not have a query string`, or `exportUrl must not have a fragment`.
- `exportUrl must not hold an @ after the scheme, ...`: the value holds a user name or password, or any other `@` after the scheme. Set the token with `authToken`, and write an `@` in a path as `%40`.
- `exportUrl must use https for an otherlode.dev host while authToken is set, ...`: the value starts with `http://`, `authToken` is set, and the host is `otherlode.dev` or ends with `.otherlode.dev`. Use `https://ingest.otherlode.dev`.
- `exportUrl's host 'otherlode_collector' has an underscore, ...`: Java's HTTP client cannot reach such a host, which a Docker Compose service name can be. Use a network alias or host name without an underscore, or an IP address.

Fix the value at the source the line names and restart the JVM. The agent does not fall back to `http://localhost:4319`.

### The auth token is refused

```text
otherlode: authToken from the environment variable OTHERLODE_AUTH_TOKEN holds a character outside visible ASCII (! to ~), so the agent is off for this JVM; set a token of visible ASCII characters only
```

The token holds a character outside `!` to `~`: a space inside it, a control character, an invisible character such as U+FEFF or U+200B, or a non-ASCII character. The log never shows the token or its length. Copy the token again as plain text, set it at the source the line names, and restart the JVM.

### The agent could not install its helper jar

```text
otherlode: could not install the bootstrap holder; the agent is disabled for this JVM
```

The exception under it names the cause:

- `otherlode: could not write the bootstrap holder jar to a temp file`: the agent writes a small helper jar to `java.io.tmpdir` at startup, and that directory is read-only, full, or not writable by the user that runs the JVM. Point the JVM at a writable directory with `-Djava.io.tmpdir=/path/to/dir`, or free space.
- `otherlode: appended the bootstrap holder jar but dev.otherlode.bootstrap.OtherlodeProbeArrays is still not loadable`
- `otherlode: bootstrap holder jar not found on the agent classpath at META-INF/otherlode/bootstrap-jar.bin`: the agent jar is damaged or is not the published jar. Download it again.

### Another startup failure

```text
otherlode: agent failed to start and is disabled for this JVM
```

A stack trace follows the line. This is a bug in the agent, not in your configuration. Report it with the trace, the agent version and your JDK.

### The agent is switched off

```text
otherlode: disabled by configuration, nothing will be instrumented or exported
```

The `enabled` option is `false`, often through `OTHERLODE_ENABLED=false` in the environment. The line is INFO. Remove the variable or set it to `true`. An `enabled=` in the `-javaagent` string overrides the environment variable. See [Turn the agent off](attach#turn-the-agent-off).

### The agent runs but reports no endpoints

```text
otherlode: endpoint instrumentation failed to install, continuing without endpoint tracking
```

The method and branch tiers work, and the instance reports no endpoints. The stack trace names the cause. A variant, `otherlode: endpoint module discovery failed, continuing without endpoint tracking`, means the agent could not load its list of endpoint modules. It loads that list even with `endpointsEnabled=false`, to report each module as kept off, so this line can appear with the option off too. The instance then reports no module at all, so Otherlode cannot tell that its endpoints were not instrumented. Report the trace.

## An option is ignored

With each of these warnings the agent keeps running without your value, with three exceptions. The plain http warning keeps the value as given, the warning about an unset `exportUrl` uses the default, and the `authToken` comma warning keeps the token cut at the comma.

| Message | Cause and fix |
|---|---|
| `otherlode: ignoring unknown agent option 'foo' (known options: [...])` | The option name is misspelled or does not exist. Names are case-sensitive. The line lists every name. |
| `otherlode: ignoring malformed agent option 2 (no '=' after a key; expected key=value; a value cannot contain a comma, set such a value through a system property or environment variable instead)` | A comma split the argument string into a piece with no `=` after a key. The number is that piece's position in the argument string, counted from 1. A value that holds a comma must come from a system property or environment variable. Separate packages with `;`. |
| `otherlode: the authToken value in the agent arguments appears to contain a comma, which the argument string cannot carry, so the token may be cut short. Set the token through the system property otherlode.auth.token or the environment variable OTHERLODE_AUTH_TOKEN instead` | A piece with no known option name follows `authToken=` in the argument string. The agent ignores that piece and the pieces after it up to the next known option. Set the token as the line says. |
| `otherlode: flushIntervalSeconds must be a whole number from 1 to 86400, ignoring '30s' and using the default of 60s` | Write plain seconds with no unit. |
| `otherlode: trimNativeHeapIntervalMinutes must be a whole number from 0 to 1440, ignoring '60m' and using the default of 60 minutes` | Write plain minutes with no unit. |
| `otherlode: enabled must be 'true' or 'false', ignoring 'yes' and using the default of true` | `staticBaselineEnabled`, `classListingEnabled`, `endpointsEnabled`, `otelBridgeEnabled` and `testRun` give the same line, each with its own name and default. |
| `otherlode: exportUrl uses plain http, so the auth token is sent unencrypted` | `authToken` is set, `exportUrl` starts with `http://`, and its host is not `localhost`, an address from `127.0.0.0` to `127.255.255.255`, or `[::1]`. Use an `https://` URL, or a loopback address when the collector is on the same host. |
| `otherlode: authToken is set but exportUrl is not, so the token goes to the default http://localhost:4319` | No agent option, system property or environment variable sets `exportUrl`. Set it to `https://ingest.otherlode.dev` or to your collector. An `exportUrl` you set to `http://localhost:4319` yourself gives no warning. |
| `otherlode: ignoring all of OTEL_RESOURCE_ATTRIBUTES, since its entry 'x' is not key=value; no service name, namespace, version or environment is read from it` | One malformed entry discards the whole list. Fix the entry. |
| `otherlode: ignoring '..' from <source>, since no URL path can name a service or namespace '..'` | A service name or namespace of `.` or `..` is refused. |
| `otherlode: ignoring the value from <source>, since it is longer than 1024 bytes in UTF-8` | A service name, namespace, version, environment or instance ID is too long. The agent skips it and takes the value from the next source. Shorten it at the source the line names. |
| `otherlode: ignoring the value from <source>, since it holds a NUL character` | A service name, namespace, version, environment or instance ID holds a NUL character. The agent skips it and takes the value from the next source. Remove the character at the source the line names. |
| `otherlode: callbackAnnotations entry '@com.acme.Handles' is not a fully qualified annotation type name and is ignored: write the dotted name with its package and no wildcard, such as 'com.acme.Handles'` | An entry in [`callbackAnnotations`](configuration#callbackannotations) has a leading `@`, a `/`, a `*`, a trailing `.class`, no package, or a part that is not a Java identifier. The agent drops that entry and keeps the others. Write the name as you would import it. |
| `otherlode: disabledEndpointModules entry 'ktor-9' names no endpoint module and is ignored: the modules are ...` | An entry in [`disabledEndpointModules`](configuration#disabledendpointmodules) is not a module name, so the module you meant stays on. The agent keeps the other entries. The line lists the module names. |

[Configuration options](configuration) lists every option and where its value comes from.

## Nothing reaches Otherlode or the collector

The agent sends a delta batch on every flush, even when no count changed. The first flush comes at a random point within the first `flushIntervalSeconds`, 60 seconds by default. Before you look further, start the JVM with `flushIntervalSeconds=1` and wait a few seconds. A new instance reads as counts behind for at least one interval, and until its classes stop loading, so a short interval shortens that wait too. [Pending counts](data-sent#pending-counts) says when it ends.

A failed send logs a warning, and the agent tries again on the next flush. The two payloads fail separately, so you often see both lines each flush:

```text
otherlode: delta export failed, will retry next flush
otherlode: manifest export failed, will retry next flush
```

The exception under the line names the cause. If neither line appears, the sends succeed and the problem is on the receiving side, or the agent is [disabled](#the-agent-is-disabled). The agent never stops retrying, and a failed flush does not stop the schedule.

### The connection fails

The exception is a `java.net.ConnectException` or a timeout.

- The agent cannot reach the address. From the same host or pod, run `curl` against the same URL.
- The host reaches the internet only through a proxy. Set the JVM's `-Dhttps.proxyHost` and `-Dhttps.proxyPort`, or send through a collector (see [Add a collector](/docs/collector/add)).
- You set no `exportUrl`, so the agent sends to its default, `http://localhost:4319`, which reaches nothing in a container that runs no collector. An `exportUrl` the agent cannot use turns the agent off instead (see [the export URL is refused](#the-export-url-is-refused)).
- The receiver accepts the connection and never answers. The agent gives up on a request after 10 seconds.

One send makes up to 5 attempts, so a failure can take several seconds to log. An answer with a `Retry-After` over 2 seconds ends the send at once. [What the agent sends](data-sent#when-the-receiver-is-down-or-refuses-a-payload) gives the retry rules.

### Otherlode or the collector answers with a status

The exception message is `otherlode: unexpected status <code> from <exportUrl>/v1/otherlode/<deltas|manifest|static-baseline|class-listing>`. A `2xx` other than `202` has its own message, in the first row.

| Status | Cause and fix |
|---|---|
| `200`, `201`, `204` or any `2xx` but `202` | The message is `otherlode: <url> answered <code>, not 202, so the payload counts as not stored`. Only a `202` counts as stored. A proxy or a wrong URL that answers `2xx` to every request causes this. Point `exportUrl` at a collector or at Otherlode's ingest. The agent does not retry within the send, and sends again on the next flush. |
| `401`, `403` | The token is missing or wrong, or the receiver wants a token and none is set. Set `OTHERLODE_AUTH_TOKEN` to an ingest key when the agent sends straight to Otherlode, or to the collector's token. [Read what ingest answers](/docs/server/connect#read-what-ingest-answers) gives each `403` reason. The agent does not retry within the send. It sends again on the next flush, so it recovers once the receiver accepts the token the agent holds. The agent reads the token at startup, so a token you change for the agent takes effect after you restart the JVM. |
| `404` | The URL does not reach Otherlode's ingest or a collector. The agent appends `/v1/otherlode/deltas`, `/v1/otherlode/manifest`, `/v1/otherlode/static-baseline` and `/v1/otherlode/class-listing` to `exportUrl`, so a proxy must forward those paths. For Otherlode, set `exportUrl` to `https://ingest.otherlode.dev` with no path. For a collector, drop any path it does not expect. |
| `301`, `302`, `307`, `308` | The agent does not follow redirects. A proxy that redirects `http` to `https` causes this. Put the final `https://` URL in `exportUrl`. |
| `400`, `422` | The receiver rejected the content. With the testkit's collector, see [A test fails with a version mismatch](#a-test-fails-with-a-version-mismatch). With another receiver, its log gives the reason. |
| `413` | A reverse proxy or gateway in front of the receiver refused the request body as too large. The agent keeps each payload under 8 MiB and under 90% of the ingest element limit ([chunk caps](data-sent#chunk-caps)), so raise the limit in front of the receiver. The agent keeps the static baseline and the class listing after a `413` and sends them once the limit is raised, with no restart. Once a count has been refused, each later payload of the run marks its counts as pending (`counts_pending_since`), so Otherlode does not read its zero hits as never hit. Payloads stamped before the first refusal in that flush do not carry the mark. |
| `408`, `429`, `5xx` | The agent retries within the send and honours a `Retry-After` of up to 2 seconds. A longer `Retry-After` ends the send, and the next flush sends again. If these persist across flushes, Otherlode or the collector is overloaded or down, or the collector reports that Otherlode is. |

The agent resends the same payload on the next flush for every status above, so a repeated warning means the answer has not changed. The static baseline and the class listing are the exception. After a `400` or `422`, each logs a warning and is not sent again in this process:

```text
otherlode: static baseline chunk 3 of 7 was refused with status 400, which resending the same bytes cannot change; the scan is not sent again
```

The class listing logs the same line with `class listing chunk` and `the listing is not sent again`.

### A static baseline send fails

```text
otherlode: failed to send static baseline chunk 1 of 4; it and the chunks after it are sent again after a flush whose payloads are stored
```

This logs once. The chunks stay pending and go out one per flush, after a flush whose own sends the receiver stored. If the delta and manifest sends fail too, fix those first.

### A class is too large to send

```text
otherlode: the static baseline is not sent: com.acme.Huge alone exceeds the limit of 8 MiB on encoded bytes for one chunk, and a scan without it would read as complete
```

One declared class is over 8 MiB of encoded bytes or 180,000 elements, too large for a static baseline chunk. The agent never splits a class, so it sends no chunk of the scan. The run has no complete scan, and Otherlode makes no never-loaded claim for it. Such a class has very long string constants in its conditions, or tens of thousands of methods or call edges. Exclude the class with `excludePackages`, or split it, and restart.

```text
otherlode: com.acme.Huge is reported as skipped: its manifest entries alone exceed the ingest limits on encoded bytes or elements, so its methods are not reported
```

The class's manifest entries are too large. The agent reports the class as skipped with the reason `too large to report: its manifest entries exceed the ingest limits` and sends none of its hits. Otherlode counts the class as loaded and claims nothing about its methods. Split the class to get its methods reported, or exclude it.

### A class listing send fails

```text
otherlode: failed to send class listing chunk 1 of 2; it and the chunks after it are sent again after a flush whose payloads are stored
```

This logs once, and the chunks go out one per flush after a flush whose own sends the receiver stored, as for the static baseline. A `404` here while deltas succeed means the collector does not serve `/v1/otherlode/class-listing`. Update the collector, and the listing goes out once the route exists.

### No class listing is sent

```text
otherlode: could not read /opt/app/lib/broken.jar for the class listing, so no class listing is sent: zip END header not found
```

A directory, a jar, or a jar nested in a Spring Boot executable jar could not be read, so this process sends no class listing at all. The line names the file and the cause. Fix or remove the file and restart. A classpath entry that does not exist is not an error: the listing skips it.

```text
otherlode: java.class.path is empty or has an empty element, which the JVM reads as the working directory, so no class listing is sent
otherlode: this JVM runs from a module path or a linked image, which the class listing does not read, so no class listing is sent
otherlode: this JVM upgrades or patches modules, which the class listing does not read, so no class listing is sent
otherlode: this JVM has no java.management module, so the agent cannot tell whether it patches modules, so no class listing is sent
```

The listing reads only the class path, and only when the class path names every folder the JVM reads. Name the class path, or remove a leading, trailing or doubled path separator, to get a listing. An application that runs from the module path, launches a `jlink` image's main module, or uses `--upgrade-module-path` or `--patch-module` sends none. So does a linked image without `java.management`, since the agent needs that module to see `--patch-module`.

### The class listing missed a root

```text
otherlode: com.acme.plugin.Exporter loaded from file:/opt/app/plugins/exporter.jar, which the class listing did not read, so Otherlode removes no class from this environment while this run is live
otherlode: com.acme.plugin.Exporter loaded with no code source, and the class listing does not name it, so Otherlode removes no class from this environment while this run is live
otherlode: more classes loaded before the class listing finished than the agent keeps, so Otherlode removes no class from this environment while this run is live
otherlode: a class loaded whose name the agent could not read, so Otherlode removes no class from this environment while this run is live
otherlode: the agent could not read where com.acme.plugin.Exporter loaded from, so Otherlode removes no class from this environment while this run is live
```

Each line means a class loaded that the class listing may not cover:

- The first is a class from a jar or folder the listing did not read, such as a plugin folder, a WAR an application server deployed, an OSGi bundle or a `loader.path` folder.
- The second is a class whose loader gave it no code source, as many plugin loaders and some code generators do, and whose name the listing lacks.
- The third is a JVM that, before the listing finished, loaded such classes from more than 4096 places, or more than 4096 of them with no code source, so the agent could not check them all.
- The last two are a class the agent could not name, or could not tell where it loaded from.

The agent logs one such line per run, at INFO, and marks every manifest the run sends from then on with `class_listing_blind`. While the run is live, Otherlode claims no class removed in its environment. To get removal claims, put that code on the class path the listing reads. The mark belongs to the run, so a restarted process starts without it.

### A test run ended before the scan did

```text
otherlode: the static baseline scan did not end within 15s of shutdown, so this test run may send no complete scan
```

This appears only with `testRun` and `staticBaselineEnabled` both on. The shutdown hook waited 15 seconds for the scan, and the scan was still running. Otherlode makes no never-loaded claim from an incomplete scan. Scan a smaller classpath, or accept that this test run reports no complete scan.

## Otherlode stores a payload but does not track it

An agent that sends straight to Otherlode logs one of these warnings, at most once an hour each:

```text
otherlode: Otherlode stores this service's payloads but does not track the service, because the tenant's plan tracks no more services
otherlode: Otherlode stores this environment's payloads but does not track the environment, because the service already watches as many environments as its cap allows
```

Otherlode stored the payload, but no finding counts it. [Scope and environments](/docs/server/scope) says how to free a slot. A line that ends `this agent does not know: <name>` is a notice from a newer Otherlode. Through a collector, the collector logs these notices instead.

## The flush at shutdown is late, missing or not waiting

On a graceful exit the agent's shutdown hook has 28 seconds in all. It flushes at once for up to 5 seconds, waits until 18 seconds in for your application's own shutdown hooks, then runs the final flush with the time left, at least 10 seconds. [What the agent sends](data-sent#when-payloads-are-sent) describes each step. These lines mean a step did not go as planned.

```text
otherlode: an in-flight flush used up the early shutdown flush's budget; skipping it
```

A scheduled flush was still sending to a slow or unreachable receiver when shutdown began, and it did not end within 5 seconds. The agent skips the early flush and goes on to the wait.

```text
otherlode: a send of the early shutdown flush ran out of time
```

A send of the early flush had not ended after 5 seconds. The agent stops waiting for it and goes on to the wait.

```text
otherlode: the application's shutdown hooks were still running 18s into shutdown, so the final flush runs now and code they run later will not be counted
```

A shutdown hook of your application, such as a graceful drain, had not ended after 18 seconds. The agent runs the final flush anyway. Hits the hook makes after that flush are lost, so Otherlode can report the code it ran as never hit. Shorten the hook.

```text
otherlode: an in-flight flush used up the shutdown budget; skipping the final flush
```

The same scheduled flush was still sending when the 28 seconds ran out, so no final flush ran. Hits since the last confirmed flush are lost, and the run reads as one that went silent.

```text
otherlode: a send of the shutdown flush ran out of time; closing with the run marked pending
```

A send of the final flush had not ended when its time was nearly up. The agent stops waiting for it and ends the run with a closing batch that marks some counts as not confirmed.

```text
otherlode: cannot watch the application's shutdown hooks, so the final flush will not wait for them and code they run after it will not be counted: java.lang.IllegalStateException: transforming java.lang.ApplicationShutdownHooks failed: ...
```

This appears at startup. The agent could not install its watch on your application's shutdown hooks. Another Java agent that rewrites `java.lang.ApplicationShutdownHooks` is one cause, and the text after the colon names the cause on your JVM. The agent still flushes at the start of shutdown and runs the final flush at the end, but code your hooks run in between is not counted. Remove the other agent, or report the line.

A send that fails during shutdown logs to standard error in the shape of [the agent's log](#read-the-agents-log):

```text
Nov 02, 2026 2:12:28 AM dev.otherlode.shaded.export.ExportScheduler sendDeltaBatch
WARNING: otherlode: delta export failed, will retry next flush
```

## A test fails with a version mismatch

The testkit's collector refuses a payload whose agent version is not the testkit's own. The agent logs `unexpected status 400` every flush, and the next query or wait in your test throws `IllegalStateException` with the reasons the collector recorded:

```text
delta batch from instance 3f1c... comes from agent version 0.1.0, but this testkit is version 0.2.0. The agent and the testkit are released as one version: use the testkit of the agent's version, 0.1.0
```

Use the testkit dependency and the `-javaagent` jar of the same version. The testkit's collector also refuses a payload with an empty run id, a second run under one instance id, or fields stripped by a collector that forwarded it. `collector.rejectedPayloads()` lists each refusal with its reason. See [the testkit](testkit).

Two more testkit messages point at the agent:

- The JUnit extension fails with `otherlode-testkit: no delta batch arrived from the agent within <n>s`. The test JVM has no `-javaagent` flag, the agent refused to start for a missing `includePackages`, or the flush interval is longer than the timeout. Add `flushIntervalSeconds=1` to the agent arguments.
- A refusal that reads `... but this collector serves one test JVM and already heard from instance ...` means more than one test JVM posts to one collector, such as Gradle forks with `maxParallelForks` above 1. Run the tests that use the testkit with `maxParallelForks = 1`.

## A class is skipped

```text
otherlode: com.acme.shop.Report cannot be defined: its supertype com.acme.shop.Base could not be read from its loader, so it is not instrumented
```

The class is in your include rules, but a superclass or interface, at any depth, has no class file its loader can read. The class fails with `NoClassDefFoundError` without the agent too, so the agent leaves it alone and reports it as skipped with this reason. Add the missing dependency to the classpath, or remove the class.

```text
otherlode: the class loader org.jboss.modules.ModuleClassLoader cannot see dev.otherlode.bootstrap, so the agent does not instrument the classes it defines; delegate dev.otherlode.bootstrap to the boot loader, for example with -Djboss.modules.system.pkgs=dev.otherlode.bootstrap or org.osgi.framework.bootdelegation=dev.otherlode.bootstrap
```

The loader does not delegate `dev.otherlode.bootstrap` to the boot loader, so a woven class could not reach the agent's probe holder. The agent logs this once per loader, skips every class of that loader, and reports each with the reason `its class loader cannot see dev.otherlode.bootstrap, so it is not instrumented`. Delegate the package with the setting for your runtime, which [Compatibility](compatibility#class-loaders-that-do-not-delegate-to-the-boot-loader) lists.

```text
otherlode: instrumentation failed for com.acme.shop.Report, class will run uninstrumented
```

The agent could not weave the class. The stack trace under the line says why, and the class runs unchanged. Otherlode counts a skipped class as loaded and does not call it dead. [Classes](classes) lists the class states. If the cause is not in your build, report the class, its compiler and the trace.

The testkit lists skipped classes with `collector.skippedClasses()`.

## A class was woven but never defined

```text
otherlode: com.acme.shop.Plugin was woven and registered but the JVM never defined it; its probes are withheld for good
```

At shutdown the agent adds `otherlode: 3 classes were woven and registered but never confirmed defined`.

The agent rewrote the class, and then two sweeps in a row found it not defined. Most often the JVM refused to define the class for a reason in your application, such as a supertype it cannot load. A transformer that runs after the agent and replaces the bytes also causes it. A class the verifier rejects is defined, so it does not cause this line; see [failed to initialise](classes#failed-to-initialise). Run the application without the agent. If the class loads there, report the class and its loader.

The agent withholds the class's probes, so the report does not list it as dead code.

A JAX-RS resource class that declared routes and was never defined gets its own line, which counts the routes nothing else reports. Those routes stay out of the report:

```text
otherlode: com.acme.shop.PluginResource declared 2 endpoints while being woven but the JVM never defined it; its endpoints are withheld for good
```

At shutdown the agent adds `otherlode: 1 classes declared endpoints while being woven but were never confirmed defined`. A route that another class or framework also serves is still reported, and a class whose routes all have another source gets no line.

## A method has no branch probes

A method keeps its entry probe and loses its branch probes in three situations, each with its own message.

```text
otherlode: com.acme.shop.Parser#parse(Ljava/lang/String;)V would not fit a class file with branch probes: it is 65000 bytes of code and could grow to 70100 bytes, over the 65535 byte limit; its branch probes are left out and its entry probe is kept
```

The branch probes would take the method past the JVM's limit of 65,535 bytes of code. A second form reads `would not be JIT-compiled with branch probes` and names an 8000 byte limit, over which HotSpot does not compile a method, so probes would leave it interpreted for good. A third form, `its entry probe can take it to <n>, over the 8000 byte limit; HotSpot may not compile it, and its branch probes are kept`, is for a method near 8000 bytes that the entry probe alone can push over. Splitting the method clears all three.

```text
otherlode: com.acme.shop.Cart reached this agent rewritten by an earlier transformer; the branches of ... do not line up with its class file, so those methods keep their entry probe and get no branch probes
```

At INFO. Another agent, such as an aspect weaver, changed the methods' bodies, so the branches in the bytes the agent received do not match the class file. See [other agents in the same JVM](compatibility#other-agents-in-the-same-jvm).

```text
otherlode: could not read com.acme.shop.Cart's bytecode; any method of it that is probed at all gets an entry probe with no line number, and the class gets no branch probes, no call edges, no supertypes, and no inline or generated mark
```

The agent could not read the class's bytes from its loader or from the bytes it received. Its methods still count, with less detail. If you see this for ordinary classes, report the class and its loader.

After the first flush, one INFO line counts the branch sites left without a probe across the process, split by reason: inlined from code outside your include rules, coroutine machinery, switch lowering, code-size limits, and checks a JPA enhancer adds. [Methods and branches](methods-and-branches) says what the agent leaves out.

## A method a framework calls reads as uncalled

A never-hit method that a framework calls, such as a handler your own dispatcher finds by reflection, shows as `UNCALLED` when the framework's annotation is not on the agent's built-in list. Name the annotation in [`callbackAnnotations`](configuration#callbackannotations), and the method reads `CALLED_FROM_OUTSIDE_SCOPE` from the next run. Its counts do not change. The annotation counts on the method itself or on a method it overrides, from any superclass or interface.

```text
otherlode: callbackAnnotations names 1 annotation not yet seen on any method: com.acme.bus.Handels. A name is matched exactly; check the spelling if its classes have loaded.
```

This INFO line names each listed annotation the agent has not yet found on a method. It is logged once, only when some name is unseen, at the first flush five minutes or more after startup, or at shutdown if that comes first. Check the spelling, and that the name is the annotation's own and not the class it sits in. A correct name is listed too when none of the classes that use it has loaded yet.

## A Kotlin class has no line numbers

```text
otherlode: com.acme.shop.Totals is a Kotlin class with no line-number table; its probes carry no line numbers, and its inlined copies cannot be traced
```

Something removed the debug information from the class, such as an obfuscator or a build setting that strips line numbers. The class still counts, but its probes carry no line number, an inline function reads as ordinary code, and a branch copied in from an inline function reads as your own. Keep debug information in the build you instrument. The agent logs this for Kotlin classes only.

## The agent reports compiler output it has not read

```text
otherlode: 12 methods in 3 classes look like compiler output this agent has not read, and are reported as unread shapes rather than dead code (...). Scala 3 releases this agent has not read: 3.11.0. A newer agent may read them.
```

Your classes use a compiler version whose generated code the agent does not recognise, so it reports those methods as unread shapes, not as never hit. The message names the families and, for Scala 3, the releases. Nothing in your code needs fixing. Upgrade to an agent release that reads your compiler, or ignore the rows marked unread. A second line, `otherlode: <class> was compiled by Scala <version>, which this agent has not read; ...`, names the class. [Methods and branches](methods-and-branches) lists the shapes.

## The static baseline warns about a blind spot

```text
otherlode: com.acme.shop.web.OrderController registered dynamically but was not in the static baseline computed at startup for this process; the static scan cannot see classes a server or plugin loader finds at run time, such as a deployed WAR, so this deployment may have such a blind spot
```

This appears only with `staticBaselineEnabled=true`. The scan walks the classpath the JVM was launched with, so it cannot see a class loaded from elsewhere, such as a WAR that Tomcat deployed after startup or a plugin directory. The class itself is fully counted. A class in such a location is never reported as never loaded, so treat that finding as incomplete for this deployment. A variant says `registered dynamically before the static baseline scan finished` and has the same cause. [Classes](classes) explains the baseline.

The dependency listing has its own warnings, such as `otherlode: the startup dependency listing failed; no dependencies will be reported` and `otherlode: could not read <path> for the dependency listing, skipping it`. The listing skipped a jar, the libraries of an unpacked Spring Boot application, or all jars, so this instance's dependency findings are missing or incomplete. [Dependencies](dependencies) describes the listing.

## HotSwap is refused

Your IDE reports that the redefinition failed after you edit a method in a debug session, and the agent logs one WARNING for the class:

```text
otherlode: the class file of com.acme.shop.Cart differs from the one it was woven from, as after a recompile or a HotSwap; it cannot be woven again, so the JVM refuses the redefinition or retransformation, and the class keeps running with its probes
```

Restart the JVM to run the new code, or debug without the agent. Another reason can take the place of the first clause, such as `the bytes for com.acme.shop.Cart arrive at class-file version 61, where it was woven at 52, and its probes' form cannot follow`, with the same ending. See [HotSwap and IDE redefinition](compatibility#hotswap-and-ide-redefinition).

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

An endpoint module met a framework it cannot handle and switched itself off for the rest of the process. Causes include:

- its advice throws,
- one of its hooks matches no method on the framework class, as when a framework release renamed or changed the method,
- a framework class it hooks fails to weave, or its type matcher throws on a class,
- the class loader that defines a framework class it hooks cannot see `dev.otherlode.bootstrap` (see [A class is skipped](#a-class-is-skipped)),
- it cannot read the framework's route table, such as a servlet mapped in none of the forms the servlet specification defines,
- the servlet container does not implement Servlet 4.0's request mappings,
- it throws while the agent builds a manifest, which also logs `otherlode: endpoint module <name> failed before a manifest was built`.

The text after `disabled itself:` gives the reason. For a linkage error it is the error and its message, which names the framework class or member. For an unmatched hook it names the hook and the class. For a servlet mapping it names the servlet and the mapping. For any other exception it is the exception's class name alone. For a cause that is not an exception, such as a class loader or a container, the reason is a sentence that names it.

The module name is one of `javalin-6`, `javalin-7`, `jaxrs`, `jdk-httpserver`, `ktor-2`, `ktor-3`, `micronaut`, `micronaut-3`, `servlet`, `spring-webflux`, `spring-webmvc`, `vertx-web` or `otel`. The other modules and the method and branch tiers keep working. The instance reports the module as disabled with a kind: `LINKAGE_ERROR`, `ADVICE_FAILED`, `TRANSFORM_FAILED`, `ROUTE_WALK_FAILED`, `HOOK_UNMATCHED`, `CONFIGURED_OFF`, `ROUTES_NOT_COUNTED` or `UNSPECIFIED`, so Otherlode can tell "no endpoints" from "endpoints not instrumented". The testkit lists disabled modules with `collector.disabledEndpointModules()`.

A module with the kind `CONFIGURED_OFF` is not a fault. `disabledEndpointModules` or `endpointsEnabled=false` kept it from installing, and it logs no WARNING.

A module with the kind `ROUTES_NOT_COUNTED` found a router but no dispatch point it can count requests at, so it declares no endpoint. Micronaut modules do this on a runtime that, within one flush interval of building the router, constructs none of Netty's `RoutingInBoundHandler`, micronaut-servlet's `ServletHttpHandler`, micronaut-aws 3.x's `MicronautLambdaContainerHandler` or, on Micronaut 4 and 5, `RouteExecutor`. The reason names the missing class.

A module gets the kind `UNSPECIFIED` with the reason `endpoint replay buffer overflow: records were dropped before a resolver was installed` when it records an endpoint while the agent's buffer already holds 4096 records, from all modules, that the agent was not yet ready to take.

Check your framework version against [the supported versions](endpoints) and [compatibility](compatibility). Upgrade or downgrade the framework, name the module in `disabledEndpointModules`, or set `endpointsEnabled=false` to turn the endpoint tier off. A module that disabled itself after it declared some routes leaves those routes in the report with no counting, so they read as never called. Treat that module's endpoints as unknown.

Related lines:

| Message | Meaning |
|---|---|
| `otherlode: endpoint module <name> found 8192 endpoints at dispatch, its limit; it records no new ones, and the endpoints it holds keep counting` | The module has created its limit of endpoints that no registration declared. Requests for endpoints it already holds still count. |
| `otherlode: endpoint module <name> bound 8192 request keys at dispatch, its limit; a request with a new key still counts, and the agent resolves its endpoint again each time` | Requests reached the module's endpoints under 8,192 different keys, such as raw paths a route bridge saw. Every request still counts. Nothing needs fixing unless the module also reports its endpoint limit. See [declared and discovered](endpoints#declared-and-discovered). |
| `otherlode: endpoint module <name> failed to transform <class>` | The module failed on one class and is switched off for the process. |
| `otherlode: endpoint module <name> could not decide whether to match <class>, so it is switched off` | The module's type matcher threw on the class. The agent logs this line with the stack trace, then the `disabled itself` line. Only that module is switched off, with the kind `TRANSFORM_FAILED`, or `LINKAGE_ERROR` when the matcher threw a linkage error. Other modules that match the class weave it as usual. A class with a supertype that cannot be loaded at all does not cause this line. Report the trace. |
| `otherlode: endpoint module <name> failed while declaring its routes` | The module threw while it read the framework's route table. |
| `otherlode: endpoint module <name> could not weave its advice into <class> again for a redefinition or retransformation, so it is switched off` | Another agent retransformed, or a debugger redefined, a class the module already wove, and the module threw while weaving it again. The `disabled itself` line follows. |
| `otherlode: endpoint instrumentation failed for <class>, class will run without endpoint tracking` | One class could not take the module's advice. Every module that matched it is switched off, except JAX-RS on the class's first weave. |
| `otherlode: endpoint replay buffer is full (4096); dropping new endpoint records until a resolver is installed` | Endpoint records filled the buffer before the agent was ready to take them. Logged once. Each module whose record was dropped is switched off with the kind `UNSPECIFIED`. |
| `otherlode: handler lambdas and method references will not be named on this JVM: <reason>` | The JDK internals the agent reads to name a Java lambda handler differ on this JVM. Endpoints still count, and the handler of a lambda route shows no method. |
| `otherlode: jaxrs: <class>#<method> inherits conflicting JAX-RS annotations from more than one interface, using <interface>` | The first interface's annotations win. Remove the conflict. |
| `otherlode: endpoint module discovery stopped early` | A module's provider failed to load and the rest were skipped. Report the trace. |

## A count went backward

```text
otherlode: probe count went backward for com.acme.Foo#12 (was 40, now 3); reporting it as-is
```

The agent increments counts without a lock. On a busy method, a thread paused mid-increment can write back an older value over other threads' increments, so a count can drop between two flushes. That is expected on hot code, and it never turns a method that ran into one that did not. The agent logs the line once per probe, and a similar line exists for endpoints. A drop on a probe with almost no traffic is unexpected: report it with the full line.

## Lines that point to a bug in the agent

These mean the agent hit a case it did not expect. The application is not affected, but the data may be. Report each with the full line, any stack trace, and your agent and JDK versions.

| Message | Meaning |
|---|---|
| `otherlode: flush failed outside its own send guards, will retry next flush` | An unexpected exception in the flush, logged at ERROR. |
| `otherlode: no registered probe array for com.acme.Foo; its hits will not be counted (resolver installed: true)` | A woven class asked for its counter array and the agent had none. The class runs and its hits are not counted. |
| `otherlode: endpoint resolver threw from <operation>: <exception>` | The endpoint tier threw while recording. |
| `otherlode: the loaded-class sweep failed, will retry on a later flush` | The check for classes that no transformer saw failed. |
