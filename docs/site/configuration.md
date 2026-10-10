---
title: Configuration options
description: Every agent option with its default, accepted values, system property and environment variable, how the agent resolves each value, and how it reads OpenTelemetry's settings.
order: 20
---

## Options at a glance

The agent has 16 options. You can set each one in the `-javaagent` argument string, as a JVM system property, or as an environment variable. The agent reads them once, at startup.

```text
-javaagent:/path/to/otherlode-agent-<version>.jar=serviceName=my-service,includePackages=com.acme.shop
```

`includePackages` is the only required option. To add the flag to your launch, see [attaching the agent](attach).

### Names

The system property and environment variable names come from the option name. The agent splits it at each lowercase-to-uppercase change. It lowercases the words and joins them with `.` after the prefix `otherlode.` for the property. It uppercases them and joins them with `_` after the prefix `OTHERLODE_` for the variable.

| Option | System property | Environment variable |
|---|---|---|
| `serviceName` | `otherlode.service.name` | `OTHERLODE_SERVICE_NAME` |
| `serviceNamespace` | `otherlode.service.namespace` | `OTHERLODE_SERVICE_NAMESPACE` |
| `serviceVersion` | `otherlode.service.version` | `OTHERLODE_SERVICE_VERSION` |
| `serviceInstanceId` | `otherlode.service.instance.id` | `OTHERLODE_SERVICE_INSTANCE_ID` |
| `environment` | `otherlode.environment` | `OTHERLODE_ENVIRONMENT` |
| `exportUrl` | `otherlode.export.url` | `OTHERLODE_EXPORT_URL` |
| `authToken` | `otherlode.auth.token` | `OTHERLODE_AUTH_TOKEN` |
| `flushIntervalSeconds` | `otherlode.flush.interval.seconds` | `OTHERLODE_FLUSH_INTERVAL_SECONDS` |
| `includePackages` | `otherlode.include.packages` | `OTHERLODE_INCLUDE_PACKAGES` |
| `excludePackages` | `otherlode.exclude.packages` | `OTHERLODE_EXCLUDE_PACKAGES` |
| `callbackAnnotations` | `otherlode.callback.annotations` | `OTHERLODE_CALLBACK_ANNOTATIONS` |
| `staticBaselineEnabled` | `otherlode.static.baseline.enabled` | `OTHERLODE_STATIC_BASELINE_ENABLED` |
| `enabled` | `otherlode.enabled` | `OTHERLODE_ENABLED` |
| `endpointsEnabled` | `otherlode.endpoints.enabled` | `OTHERLODE_ENDPOINTS_ENABLED` |
| `otelBridgeEnabled` | `otherlode.otel.bridge.enabled` | `OTHERLODE_OTEL_BRIDGE_ENABLED` |
| `testRun` | `otherlode.test.run` | `OTHERLODE_TEST_RUN` |

### Values

| Option | Default | Accepted values | If the value is invalid |
|---|---|---|---|
| `serviceName` | The first of OpenTelemetry's settings, [service-name detection](#service-name-detection), then `unknown_service:java` | Any text, except `.` and `..` | `.` and `..` are skipped with a warning, and the next source gives the name |
| `serviceNamespace` | None, which is the unspecified namespace | Any text, except `.` and `..` | `.` and `..` are skipped with a warning, and the next source gives the namespace |
| `serviceVersion` | OpenTelemetry's `service.version`, else none | Any text | No check |
| `serviceInstanceId` | A random UUID, new for each process | Any text | No check |
| `environment` | OpenTelemetry's `deployment.environment.name`, then `deployment.environment`, then `test` when `testRun` is on, else none | Any text | No check |
| `exportUrl` | `http://localhost:4319` | An absolute `http` or `https` URL with a host, and with no query string or fragment. A path is allowed | Warning, and the default is used |
| `authToken` | None | Any text | No check |
| `flushIntervalSeconds` | `60` | A whole number of seconds from `1` to `86400` | Warning, and the default is used |
| `includePackages` | None. Required. | Dotted package or class prefixes, separated by `;` | Glob and path prefixes are dropped with a warning. If no prefix is left, the agent logs an ERROR and disables itself |
| `excludePackages` | None | Dotted package or class prefixes, separated by `;` | Glob and path prefixes are dropped with a warning |
| `callbackAnnotations` | None | Fully qualified annotation type names, separated by `;` | A name that cannot be a type name is dropped with a warning |
| `staticBaselineEnabled` | `false` | `true` or `false`, in any letter case | Warning, and the default is used |
| `enabled` | `true` | `true` or `false`, in any letter case | Warning, and the default is used |
| `endpointsEnabled` | `true` | `true` or `false`, in any letter case | Warning, and the default is used |
| `otelBridgeEnabled` | `false` | `true` or `false`, in any letter case | Warning, and the default is used |
| `testRun` | `false` | `true` or `false`, in any letter case | Warning, and the default is used |

No invalid value stops your application. An invalid value gets a warning in the log and the default. The one exception is an empty `includePackages`, which disables the agent for the life of the JVM (see [includePackages and excludePackages](#includepackages-and-excludepackages)).

## Where a value comes from

For every option, the agent takes the first source below that gives a value that is not blank.

1. The argument string after `=` in the `-javaagent` flag.
2. The JVM system property.
3. The environment variable.
4. The option's default.

The argument string wins over the other two because it is the most specific place to write a value. An environment variable therefore cannot override an option that the command line already sets.

A blank value counts as unset and falls through to the next source. An option set to nothing in the argument string, such as `authToken=`, does not hide the environment variable. The agent trims the leading and trailing spaces of every value.

Four options go on past the default. `serviceName`, `serviceNamespace`, `serviceVersion` and `environment` also read [OpenTelemetry's settings](#opentelemetrys-settings) after the three Otherlode sources. `serviceName` then uses [service-name detection](#service-name-detection).

### The argument string

The argument string is a comma-separated list of `key=value` pairs. The agent splits each pair at its first `=`, and trims the key and the value. These rules apply.

- A value cannot contain a comma, and there is no quoting. A value that needs a comma has to come from the system property or the environment variable.
- Keys are case-sensitive. A key the agent does not know, such as `includePackage` or `IncludePackages`, is ignored with a warning that lists the known options. The option does not act under a near-miss spelling.
- A pair with no `=`, or with an empty key, is ignored with a warning.
- When a key appears twice in the string, the last one wins.

### System properties and environment variables

The agent reads system properties and environment variables once, before it installs anything. A property that your application sets later with `System.setProperty` has no effect. The property has to be on the `java` command line as `-D`, or in `JAVA_TOOL_OPTIONS`.

A `-D` flag is as visible as the argument string, since anyone who can list processes on the host can read both. An environment variable is not visible that way (see [authToken](#authtoken)).

## Boolean options

`staticBaselineEnabled`, `enabled`, `endpointsEnabled`, `otelBridgeEnabled` and `testRun` accept `true` or `false` in any letter case. `yes`, `1`, and anything else that is not blank produce a warning and the option's default, not `false`. The warning names the option and the default it falls back to.

| Option | When it is on |
|---|---|
| `enabled` | The agent runs. When it is `false`, the agent logs one INFO line and does nothing else: it installs no transformer, starts no thread and sends nothing. The agent checks this option before `includePackages`. Set `OTHERLODE_ENABLED=false` to switch off an agent that an image already attaches, without rebuilding the image. |
| `endpointsEnabled` | The agent installs its framework endpoint modules. One switch covers all of them, and there are no per-framework flags. A module activates only when its framework is on the application's classpath. With the option off, the agent logs one INFO line and reports no endpoints, and `otelBridgeEnabled` has no effect. The endpoint modules ignore `includePackages` and `excludePackages`. See [endpoints](endpoints). |
| `otelBridgeEnabled` | The agent also counts the route that OpenTelemetry's own HTTP server instrumentation resolved, for a framework no endpoint module covers. It needs `endpointsEnabled`. See [endpoints](endpoints). |
| `staticBaselineEnabled` | The agent scans the classpath once, on a background thread, for classes under `includePackages` that never load. Off by default because the cost of the scan grows with the size of the classpath. See [classes](classes). |
| `testRun` | The agent marks every payload of this run as a test run, and a run with no named environment reports to the environment `test`. At shutdown, after the final flush, a test run with `staticBaselineEnabled` on waits up to 15 seconds for the static baseline scan to finish. See [naming the tests that call your code](test-runs). |

## includePackages and excludePackages

`includePackages` names the code the agent probes. It is a list of prefixes separated by `;`.

```text
includePackages=com.acme.shop;com.acme.billing,excludePackages=com.acme.shop.generated
```

A prefix matches a class when the class name starts with the prefix and the next character is `.` or `$`, or when the name equals the prefix. So `com.acme` matches `com.acme.Order`, `com.acme.shop.Order` and `com.acme.Order$Line`. It does not match `com.acmeinternal.Order`. A prefix can name a package or one class. Matching is case-sensitive.

A class under `excludePackages` is never probed, even when `includePackages` also matches it. Exclusion always wins. The agent's own package, `dev.otherlode`, is never probed.

The agent cleans each prefix before it uses it. It trims spaces, drops a trailing `.`, and ignores empty entries. `com.acme.` and `com.acme` mean the same.

A prefix that contains `*` or `/` matches no class name. The agent drops it and logs a warning that names the spelling that would match. It never applies that spelling for you, because the rewrite can be broader than you meant: `com.*.shop` would become `com`. This applies to both options.

```text
otherlode: 'com.acme.*' matches no class and is ignored: write a dotted package prefix with no wildcard, such as 'com.acme'
```

### An empty includePackages disables the agent

The agent needs a scope to probe. When `includePackages` has no usable prefix, the agent logs one ERROR, disables itself for the life of the JVM, installs nothing and sends nothing. The server lists no instance for it, which is the visible signal. An instance that reported zero probes would read as a service with no dead code.

An empty list is any of these.

- The option is absent, from every source.
- The option is blank, `;`, or only spaces. This happens when a template leaves `includePackages=${APP_PACKAGES}` unset.
- Only `excludePackages` is set.
- Every include prefix is a glob or a path, and the agent dropped them all.

The ERROR starts with `otherlode: includePackages is not set, so the agent is disabled for this JVM`. When the agent can find your application's main class, the message adds that class and its package as a suggestion. The agent reads the main class from the launch command. For a `-jar` launch, it reads the jar's `Start-Class`, else its `Main-Class`. For a module launch, it reads the module's declared main class. It makes no suggestion for a main class in the default package, or one in Spring Boot's launcher, Ktor's `EngineMain` or Tomcat's `Bootstrap`, because those say nothing about where your code lives. The agent never applies the suggestion.

There is no wildcard that selects every class. A broad prefix such as `com` is accepted as a choice you made.

`enabled=false` is checked before the include rules, so a deliberately disabled agent logs one INFO line and not the ERROR.

## callbackAnnotations

`callbackAnnotations` names annotations that a framework calls methods by, for a framework the agent's built-in list does not cover. A never-hit method that carries one is labelled *called from outside scope* rather than *uncalled*. The label changes no count and no cluster. See [outside callers](call-graph#outside-callers) for what the label means and what the built-in list covers.

```text
callbackAnnotations=org.axonframework.commandhandling.CommandHandler;com.acme.bus.Handles
```

- **Names are exact.** Each entry is one annotation type's fully qualified name. There are no prefixes or wildcards, because a prefix would also catch annotations such as `@NotBlank` or `@Nullable` in the same package and hide real dead code.
- **A nested annotation can be written either way.** `com.acme.Bus.Handler` and `com.acme.Bus$Handler` name the same annotation. Prefer the `.` form: in a shell or a Dockerfile, `$Handler` is read as a variable and dropped, leaving `com.acme.Bus`.
- **Retention does not matter for a named annotation.** It counts whether it has `RUNTIME` or `CLASS` retention, so a Java annotation declared with no `@Retention` works. A `SOURCE` annotation never reaches the class file and cannot work. An annotation with `CLASS` retention that you did not name counts only when it carries a named one, never because it carries a framework annotation from the built-in list, since the framework cannot see it at run time.
- **A repeated annotation is matched through its container.** When a repeatable annotation is used twice on a method, the class file holds only its container, such as `@Handles.List`. Name the repeatable annotation alone; the agent recognises the container, and the label names the container, since that is what the method carries.
- **It counts on the method that runs.** Directly on the method, or carried by an annotation you put on the method, at any depth. An annotation on a parameter or on a class marks nothing, and neither does one on an interface or superclass method that the method implements or overrides. Temporal's `@WorkflowMethod`, which sits on the workflow interface, is not covered.
- **It adds to the built-in list.** The built-in names keep counting, and the option cannot remove one.

If you want to mark methods that only your own code calls by reflection, declare an annotation of your own and name it here. The agent ships no annotation for this, so your code takes no dependency on it.

An entry that cannot be an annotation's name, such as one with a `/`, a `*`, a leading `@` or no package, is dropped with a warning that suggests a spelling where it can. The agent cannot tell at startup whether a well-formed name exists. At the first flush five minutes or more after startup, or at shutdown if that comes first, it logs one INFO line naming each annotation it has not yet seen on a method:

```text
otherlode: callbackAnnotations names 1 annotation not yet seen on any method: com.acme.bus.Handels. A name is matched exactly; check the spelling if its classes have loaded.
```

A correctly spelled name appears in that line too when none of the classes that use it has loaded yet.

## exportUrl

`exportUrl` is the base URL of the collector. The agent posts to three paths under it.

| Path | Carries |
|---|---|
| `/v1/otherlode/deltas` | Delta batches, which double as the heartbeat |
| `/v1/otherlode/manifest` | The probe manifest |
| `/v1/otherlode/static-baseline` | The static baseline, when it is enabled |

The agent normalises the value before it uses it. It trims spaces, drops trailing `/` characters, and lowercases the scheme, so `HTTPS://Collector.example.com/` becomes `https://Collector.example.com`. The host and the path keep the letter case you wrote. A path is kept: `https://gateway.example.com/otherlode` posts to `https://gateway.example.com/otherlode/v1/otherlode/deltas`.

The agent refuses these values with a warning, and uses `http://localhost:4319` instead.

- A value that is not an absolute `http` or `https` URL with a host. A relative path such as `/v1/otherlode` and a value with no scheme both fall here.
- A value with a query string.
- A value with a fragment.

A host name that contains an underscore, such as `http://my_collector:4319`, is refused too, with a warning that names the underscore. Java's HTTP client cannot send to such a host. Use a host name or network alias without an underscore, or an IP address.

For what the agent sends to this URL, and what it does when the collector is down, see [what the agent sends](data-sent).

## authToken

When `authToken` is set, the agent sends `Authorization: Bearer <token>` with every request. It trims the token and checks nothing else about it.

The agent's own advice is to set the token through `OTHERLODE_AUTH_TOKEN`. Both the argument string and a `-D` flag appear on the process command line, which every user on the host can read through `ps` and `/proc/<pid>/cmdline`. An environment variable does not.

When a token is set and the final `exportUrl` starts with `http://`, the agent logs this warning once at startup. The default URL is plain `http`, so a token with no `exportUrl` also triggers it.

```text
otherlode: exportUrl uses plain http, so the auth token is sent unencrypted
```

## flushIntervalSeconds

`flushIntervalSeconds` is the time between flushes, in whole seconds. The accepted range is `1` to `86400` (one day). A decimal, a negative number, zero, a value above one day and text such as `30s` all produce a warning and the default of `60`.

The agent starts the first flush at a random point within the first interval, so a fleet of instances that start together does not send at the same moment. The flushes after it follow every interval at a fixed rate.

## Service identity

Each payload names the service that sent it with these values.

| Value | Source order |
|---|---|
| Service name | `serviceName` from the three Otherlode sources, then OpenTelemetry's name, then [detection](#service-name-detection), then `unknown_service:java` |
| Namespace | `serviceNamespace` from the three Otherlode sources, then OpenTelemetry's `service.namespace`, else none |
| Version | `serviceVersion` from the three Otherlode sources, then OpenTelemetry's `service.version`, else none |
| Environment | `environment` from the three Otherlode sources, then OpenTelemetry's `deployment.environment.name`, then `deployment.environment`, then `test` when `testRun` is on, else none |
| Instance ID | `serviceInstanceId` from the three Otherlode sources, else a random UUID |

At each step, the first value that is not blank wins. A service is known by its namespace and its name together. With no namespace, the service is in the unspecified namespace.

A service name or namespace of `.` or `..` is skipped with a warning, as if it were blank, and the next source gives the value. The server shows a service at a URL path that holds both values, and browsers drop `.` and `..` path segments, so neither value can name a service. The check covers every source of a name or a namespace, including OpenTelemetry's and detection.

The agent never reads the instance ID from OpenTelemetry's `service.instance.id`. Many deployment templates give every replica the same value there, and the server counts instances by that ID, so the replicas would read as one instance. Set `serviceInstanceId` yourself to pin the ID, for example to a pod name or to a name for a test task.

Each process also gets a random run ID, which no option sets. A restart under a pinned instance ID is therefore a new run. The server keeps the data of each run apart.

## OpenTelemetry's settings

A service that already runs OpenTelemetry has named itself in OpenTelemetry's settings. The agent reads those settings, so the service's findings carry the same name as its traces. The agent reads them only when no Otherlode source gives the value. To name the service differently in Otherlode on purpose, set the Otherlode option.

The agent reads two OpenTelemetry settings.

| Setting | System property | Environment variable |
|---|---|---|
| Service name | `otel.service.name` | `OTEL_SERVICE_NAME` |
| Resource attributes | `otel.resource.attributes` | `OTEL_RESOURCE_ATTRIBUTES` |

Each setting comes whole from one place, as in OpenTelemetry's Java agent. The system property wins over the environment variable. A system property that is set and not blank hides the whole environment variable, so a `service.version` in `OTEL_RESOURCE_ATTRIBUTES` is not read when `otel.resource.attributes` is also set. A blank system property counts as unset.

The service name is `otel.service.name` (or `OTEL_SERVICE_NAME`), else the `service.name` resource attribute. The first wins.

The agent reads these resource attributes.

| Attribute | Used for |
|---|---|
| `service.name` | The service name, after `otel.service.name` |
| `service.namespace` | The namespace |
| `service.version` | The version |
| `deployment.environment.name` | The environment |
| `deployment.environment` | The environment, when `deployment.environment.name` is absent |

The agent ignores every other attribute.

### Resource attribute syntax

The value is a comma-separated list of `key=value` pairs, parsed as OpenTelemetry's Java SDK parses it.

```text
OTEL_RESOURCE_ATTRIBUTES=service.namespace=shop,service.version=2.4.1,deployment.environment.name=staging
```

- The agent splits the list at commas, and each pair at its first `=`. It trims the key and the value.
- It percent-decodes each value as UTF-8 bytes, so `%20` is a space and `%2C` is a comma. A `+` stays a `+`. A `%` that is not followed by two hex digits stays as written.
- A pair with an empty value is dropped. It is not an error.
- When a key appears twice, the last pair wins.
- If any pair has no `=` or an empty key, the agent discards the whole list and logs a warning that names the setting. It then reads no name, namespace, version or environment from that list. This is what the OpenTelemetry specification prescribes.

The agent does not percent-decode `otel.service.name`.

### Kubernetes

The OpenTelemetry Operator sets `service.namespace` from the pod's `resource.opentelemetry.io/service.namespace` annotation, else from the pod's Kubernetes namespace, and passes it to the container in `OTEL_RESOURCE_ATTRIBUTES`. The agent reads it from there like any other value. Set `OTHERLODE_SERVICE_NAMESPACE` to override it. The agent never derives a namespace for itself.

## Service-name detection

When no Otherlode source and no OpenTelemetry setting gives a service name, the agent detects one. It uses the same sources, in the same order, as OpenTelemetry's Java agent. The first source that finds a name wins. Detection runs at startup. It reads files, the command line, system properties and the environment, and it never loads an application class. A source that fails gives no name, and detection moves on.

1. **The Spring Boot application name.** The first of these that sets `spring.application.name`.
   1. A `--spring.application.name=` argument on the process command line.
   2. The same argument in `sun.java.command`.
   3. The `spring.application.name` system property.
   4. The `SPRING_APPLICATION_NAME` environment variable.
   5. `application.properties`, `application.yml`, then `application.yaml` in the working directory.
   6. `application.properties`, `application.yml`, `application.yaml`, `bootstrap.properties`, `bootstrap.yml`, then `bootstrap.yaml` on the classpath.
2. **The main jar's manifest.** The `Implementation-Title` attribute.
3. **The main jar's file name**, without its extension.

The main jar is the argument after `-jar` on the process command line. When the agent cannot read the command line that way, it takes the shortest leading part of `sun.java.command`, cut at a space, that names a regular file.

Detection reads the classpath files these ways.

- Each entry of `java.class.path` is a directory or a jar. For each file name, the first entry in classpath order that holds it wins.
- When any entry holds `BOOT-INF/classes/`, the JVM is running a Spring Boot executable jar, and the agent looks for every name under that prefix.
- The agent never opens a jar nested inside another jar, such as one under `BOOT-INF/lib/`, and does not follow a manifest's `Class-Path`.
- A `.properties` file is read as ISO 8859-1, as Spring reads it by default.
- A YAML file is read as nested block mappings (`spring:`, then `application:`, then `name:`) with plain, single-quoted or double-quoted values and `#` comments. The first document that names the application wins when a file holds several documents split by `---`. The agent does not read flow mappings, block scalars (`|`, `>`), anchors, aliases, escapes or a dotted key such as `spring.application.name:`.

If detection finds nothing, the name is `unknown_service:java`, OpenTelemetry's name for a Java service that names none. The server treats a name that starts with `unknown_service` as a service that was never named.

## Other settings the agent reads

These settings are not agent options, and the three-source scheme does not cover them.

| Setting | Where | What it does |
|---|---|---|
| `otel.service.name`, `otel.resource.attributes` | System property, and `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` as variables | [OpenTelemetry's settings](#opentelemetrys-settings) above |
| `spring.application.name`, `SPRING_APPLICATION_NAME` | System property, environment variable | Input to [service-name detection](#service-name-detection) |
| `net.bytebuddy.naming` | System property | Read only to tell classes that ByteBuddy generates at runtime from your own. With the value `fixed` or `caller`, the agent treats a class whose name ends in `$ByteBuddy` as runtime-generated and leaves it alone. |
| `otherlode.testkit.port`, `otherlode.testkit.startup.timeout.seconds` | System property | Settings of the testkit's JUnit extension, not of the agent. See [the testkit](testkit). |

The agent also reads `java.class.path`, `sun.java.command` and the process command line to find the main class and the main jar. You do not set these for the agent.
