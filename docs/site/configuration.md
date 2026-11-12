---
title: Configuration options
description: Every agent option with its default, accepted values, system property and environment variable, how the agent resolves each value, and how it reads OpenTelemetry's settings.
order: 20
---

## Options at a glance

The agent has 19 options. You can set each one in the `-javaagent` argument string, as a JVM system property, or as an environment variable. The agent reads them once, at startup.

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
| `trimNativeHeapIntervalMinutes` | `otherlode.trim.native.heap.interval.minutes` | `OTHERLODE_TRIM_NATIVE_HEAP_INTERVAL_MINUTES` |
| `includePackages` | `otherlode.include.packages` | `OTHERLODE_INCLUDE_PACKAGES` |
| `excludePackages` | `otherlode.exclude.packages` | `OTHERLODE_EXCLUDE_PACKAGES` |
| `callbackAnnotations` | `otherlode.callback.annotations` | `OTHERLODE_CALLBACK_ANNOTATIONS` |
| `staticBaselineEnabled` | `otherlode.static.baseline.enabled` | `OTHERLODE_STATIC_BASELINE_ENABLED` |
| `classListingEnabled` | `otherlode.class.listing.enabled` | `OTHERLODE_CLASS_LISTING_ENABLED` |
| `enabled` | `otherlode.enabled` | `OTHERLODE_ENABLED` |
| `endpointsEnabled` | `otherlode.endpoints.enabled` | `OTHERLODE_ENDPOINTS_ENABLED` |
| `disabledEndpointModules` | `otherlode.disabled.endpoint.modules` | `OTHERLODE_DISABLED_ENDPOINT_MODULES` |
| `otelBridgeEnabled` | `otherlode.otel.bridge.enabled` | `OTHERLODE_OTEL_BRIDGE_ENABLED` |
| `testRun` | `otherlode.test.run` | `OTHERLODE_TEST_RUN` |

### Values

| Option | Default | Accepted values | If the value is invalid |
|---|---|---|---|
| `serviceName` | The first of OpenTelemetry's settings, [service-name detection](#service-name-detection), then `unknown_service:java` | Any text up to 1024 bytes, except `.`, `..` and text with a NUL character | `.`, `..`, a NUL character and a longer value are skipped with a warning, and the next source gives the name |
| `serviceNamespace` | None, which is the unspecified namespace | Any text up to 1024 bytes, except `.`, `..` and text with a NUL character | `.`, `..`, a NUL character and a longer value are skipped with a warning, and the next source gives the namespace |
| `serviceVersion` | OpenTelemetry's `service.version`, else none | Any text up to 1024 bytes, with no NUL character | A longer value or one with a NUL character is skipped with a warning, and the next source gives the version |
| `serviceInstanceId` | A random UUID, new for each process | Any text up to 1024 bytes, with no NUL character | A longer value or one with a NUL character is skipped with a warning, and the next source gives the ID, else the random UUID |
| `environment` | OpenTelemetry's `deployment.environment.name`, then `deployment.environment`, then `test` when `testRun` is on, else none | Any text up to 1024 bytes, with no NUL character | A longer value or one with a NUL character is skipped with a warning, and the next source gives the environment |
| `exportUrl` | `http://localhost:4319` | An absolute `http` or `https` URL with a host, and with no query string, fragment, user name, password or other `@` after the scheme. The host cannot contain an underscore. A path is allowed | ERROR, and the agent is off for this JVM |
| `authToken` | None | Visible ASCII characters only, `!` to `~` | ERROR, and the agent is off for this JVM |
| `flushIntervalSeconds` | `60` | A whole number of seconds from `1` to `86400` | Warning, and the default is used |
| `trimNativeHeapIntervalMinutes` | `60` | A whole number of minutes from `0` to `1440`. `0` turns the trim off | Warning, and the default is used |
| `includePackages` | None. Required. | Dotted package or class prefixes, separated by `;` | Glob and path prefixes are dropped with a warning. If no prefix is left, the agent logs an ERROR and disables itself |
| `excludePackages` | None | Dotted package or class prefixes, separated by `;` | Glob and path prefixes are dropped with a warning |
| `callbackAnnotations` | None | Fully qualified annotation type names, separated by `;` | A name that cannot be a type name is dropped with a warning |
| `staticBaselineEnabled` | `false` | `true` or `false`, in any letter case | Warning, and the default is used |
| `classListingEnabled` | `true` | `true` or `false`, in any letter case | Warning, and the default is used |
| `enabled` | `true` | `true` or `false`, in any letter case | Warning, and the default is used |
| `endpointsEnabled` | `true` | `true` or `false`, in any letter case | Warning, and the default is used |
| `disabledEndpointModules` | None | Endpoint module names, separated by `;` | A name that matches no module is skipped with a warning |
| `otelBridgeEnabled` | `false` | `true` or `false`, in any letter case | Warning, and the default is used |
| `testRun` | `false` | `true` or `false`, in any letter case | Warning, and the default is used |

No invalid value stops your application. An invalid value gets a warning in the log, and the last column says what the agent uses instead. Three values disable the agent for the life of the JVM instead: an empty `includePackages` (see [includePackages and excludePackages](#includepackages-and-excludepackages)), an `exportUrl` the agent cannot use (see [exportUrl](#exporturl)) and an `authToken` with a character outside visible ASCII (see [authToken](#authtoken)).

## Where a value comes from

For every option, the agent takes the first source below that gives a value that is not blank.

1. The argument string after `=` in the `-javaagent` flag.
2. The JVM system property.
3. The environment variable.
4. The option's default.

An environment variable cannot override an option that the command line already sets, as an option string or a `-D` flag.

A blank value counts as unset and falls through to the next source. An option set to nothing in the argument string, such as `authToken=`, does not hide the environment variable. The agent trims the leading and trailing spaces of every value.

Four options go on past the default. `serviceName`, `serviceNamespace`, `serviceVersion` and `environment` also read [OpenTelemetry's settings](#opentelemetrys-settings) after the three Otherlode sources. `serviceName` then uses [service-name detection](#service-name-detection).

### The argument string

The argument string is a comma-separated list of `key=value` pairs. The agent splits each pair at its first `=`, and trims the key and the value. These rules apply.

- A value cannot contain a comma, and there is no quoting. A value that needs a comma has to come from the system property or the environment variable.
- Keys are case-sensitive. A key the agent does not know, such as `includePackage` or `IncludePackages`, is ignored with a warning that lists the known options.
- A pair with no `=`, or one that starts with `=`, is ignored with a warning. The warning gives the position of the pair in the string and never quotes it.
- After an `authToken` pair, the agent quotes no pair up to the next pair with a known key, since such a pair may be the rest of a token that holds a comma. It logs one warning instead, which says the token may be cut short and names `otherlode.auth.token` and `OTHERLODE_AUTH_TOKEN` as the places to set it.
- When a key appears twice in the string, the last one wins.

### System properties and environment variables

The agent reads system properties and environment variables once, before it installs anything. A property that your application sets later with `System.setProperty` has no effect. The property has to be on the `java` command line as `-D`, or in `JAVA_TOOL_OPTIONS`.

## Boolean options

`staticBaselineEnabled`, `classListingEnabled`, `enabled`, `endpointsEnabled`, `otelBridgeEnabled` and `testRun` accept `true` or `false` in any letter case. `yes`, `1`, and anything else that is not blank produce a warning and the option's default, not `false`:

```text
otherlode: staticBaselineEnabled must be 'true' or 'false', ignoring 'yes' and using the default of false
```

| Option | When it is on |
|---|---|
| `enabled` | The agent runs. When it is `false`, the agent logs one INFO line and does nothing else: it installs no transformer, starts no thread and sends nothing. The agent acts on it before it checks `includePackages`, `exportUrl` or `authToken`. Set `OTHERLODE_ENABLED=false` to switch off an agent that an image already attaches, without rebuilding the image. See [Turn the agent off](attach#turn-the-agent-off). |
| `endpointsEnabled` | The agent installs its framework endpoint modules. A module counts only when its framework is on the application's classpath, and it ignores `includePackages` and `excludePackages`. [disabledEndpointModules](#disabledendpointmodules) keeps single modules off. With the option off, the agent logs one INFO line and reports no endpoints. It reports each module it would have installed as disabled, with the kind `CONFIGURED_OFF`, and includes `otel` only when `otelBridgeEnabled` is on. See [endpoints](endpoints). |
| `otelBridgeEnabled` | The agent also counts the route that OpenTelemetry's own HTTP server instrumentation resolved, for a framework no endpoint module covers. It needs `endpointsEnabled`. See [endpoints](endpoints). |
| `staticBaselineEnabled` | The agent scans the classpath once, on a background thread, for classes under `includePackages` that never load. The scan's cost grows with the size of the classpath. See [classes](classes). |
| `classListingEnabled` | The agent lists the names of the classes the classpath holds under `includePackages`, once, on a background thread, and sends the list. It reads the central directories of jars and class folders and parses no class file. With it off, Otherlode cannot tell a class you removed from one that has not loaded yet. See [classes](classes). |
| `testRun` | The agent marks every payload of this run as a test run, and a run with no named environment reports to the environment `test`. At shutdown, after the final flush, a test run with `staticBaselineEnabled` on waits up to 15 seconds for the static baseline scan to finish. See [naming the tests that call your code](test-runs). |

## includePackages and excludePackages

`includePackages` names the code the agent probes. It is a list of prefixes separated by `;`.

```text
includePackages=com.acme.shop;com.acme.billing,excludePackages=com.acme.shop.generated
```

A prefix matches a class when the class name starts with the prefix and the next character is `.` or `$`, or when the name equals the prefix. So `com.acme` matches `com.acme.Order`, `com.acme.shop.Order` and `com.acme.Order$Line`. It does not match `com.acmeinternal.Order`. A prefix can name a package or one class. Matching is case-sensitive.

A class under `excludePackages` is never probed, even when `includePackages` also matches it. Exclusion always wins. The agent's own package, `dev.otherlode`, is never probed.

The agent cleans each prefix before it uses it. It trims spaces, drops a trailing `.`, and ignores empty entries. `com.acme.` and `com.acme` mean the same.

A prefix that contains `*` or `/` matches no class name, in either option. The agent drops it and logs a warning that suggests a dotted spelling. It never applies that spelling for you.

```text
otherlode: 'com.acme.*' matches no class and is ignored: write a dotted package prefix with no wildcard, such as 'com.acme'
```

### An empty includePackages disables the agent

When `includePackages` has no usable prefix, the agent logs one ERROR, disables itself for the life of the JVM, installs nothing and sends nothing. Otherlode lists no instance for it.

The list is empty in cases such as these.

- The option is absent, from every source.
- The option is blank, `;`, or only spaces. This happens when a template leaves `includePackages=${APP_PACKAGES}` unset.
- Only `excludePackages` is set.
- Every include prefix is a glob or a path, and the agent dropped them all.

The ERROR starts with `otherlode: includePackages is not set, so the agent is disabled for this JVM`. When the agent can find your application's main class, the message adds that class and its package as a suggestion. The agent reads the main class from the launch command. It takes the class named on the command line, the `Start-Class`, else `Main-Class`, from the manifest of a `-jar` launch, or the main class of a module launch. It makes no suggestion for a main class in the default package, or one under `org.springframework.boot.loader`, `io.ktor.server` or `org.apache.catalina`. The agent never applies the suggestion. [Choose includePackages](attach#choose-includepackages) shows the full message.

There is no wildcard that selects every class. The agent accepts a broad prefix such as `com`.

With `enabled=false`, the agent logs its one INFO line and not this ERROR.

## callbackAnnotations

`callbackAnnotations` names annotations that a framework calls methods by, for a framework the agent's built-in list does not cover. A never-hit method that carries one reads `CALLED_FROM_OUTSIDE_SCOPE` instead of `UNCALLED`. The label changes no count. See [outside callers](call-graph#outside-callers) for what the label means and what the built-in list covers.

```text
callbackAnnotations=com.acme.bus.Handles;com.acme.jobs.OnJob
```

- **Names are exact.** Each entry is one annotation type's fully qualified name, with no prefix or wildcard.
- **A nested annotation can be written either way.** `com.acme.Bus.Handler` and `com.acme.Bus$Handler` name the same annotation. In a shell or a Dockerfile, `$Handler` is read as a variable and dropped, leaving `com.acme.Bus`, so write the `.` form there.
- **A named annotation counts with `RUNTIME` or `CLASS` retention**, so a Java annotation declared with no `@Retention` works. A `SOURCE` annotation never reaches the class file and cannot work. An unnamed annotation with `CLASS` retention counts only when it carries a named one, never because it carries a framework annotation from the built-in list.
- **A repeated annotation is matched through its container.** When a repeatable annotation is used twice on a method, the class file holds only its container, such as `@Handles.List`. Name the repeatable annotation alone. The agent recognises the container, and the label names the container.
- **It counts on a method, or on one it overrides.** The annotation can be on the method itself or carried by an annotation on the method, at any depth. An annotation on a parameter or on a class marks nothing. A method that implements or overrides an interface or superclass method carrying the annotation takes it too, whether that method is abstract or has a body, and the label names the annotation as written there.
- **It adds to the built-in list.** The option cannot remove a built-in name.

To mark methods that only your own code calls by reflection, declare an annotation of your own and name it here. The agent ships no annotation for this.

An entry that cannot be an annotation's name, such as one with a `/`, a `*`, a leading `@` or no package, is dropped with a warning that suggests a spelling where it can. The agent cannot tell at startup whether a well-formed name exists. At the first flush five minutes or more after startup, or at shutdown if that comes first, it logs one INFO line naming each annotation it has not yet seen on a method:

```text
otherlode: callbackAnnotations names 1 annotation not yet seen on any method: com.acme.bus.Handels. A name is matched exactly; check the spelling if its classes have loaded.
```

A correctly spelled name appears in that line too when none of the classes that use it has loaded yet.

## disabledEndpointModules

`disabledEndpointModules` keeps single [endpoint modules](endpoints#supported-frameworks) from installing, while the other modules keep reporting.

```text
disabledEndpointModules=jaxrs;ktor-2
```

- **Names are the module names.** They are `javalin-6`, `javalin-7`, `jaxrs`, `jdk-httpserver`, `ktor-2`, `ktor-3`, `micronaut`, `micronaut-3`, `servlet`, `spring-webflux`, `spring-webmvc`, `vertx-web` and `otel`, as the [supported frameworks](endpoints#supported-frameworks) table lists them.
- **A kept-off module does nothing.** It matches no class and counts nothing, whether or not its framework is on the classpath.
- **The agent reports it.** Each module the option keeps off is sent as a disabled module with the kind `CONFIGURED_OFF` and a reason that names the option.
- **`otel` is reported only when the bridge would have run.** With `otelBridgeEnabled` off, naming `otel` changes nothing.

The agent logs one INFO line listing the modules the option keeps off, which starts `otherlode: disabledEndpointModules keeps these endpoint modules off:`. A name that matches no module is skipped with one WARNING that lists the known names.

## exportUrl

`exportUrl` is the base URL the agent sends to: `https://ingest.otherlode.dev` to send straight to Otherlode, or your collector's URL. The agent posts to four paths under it.

| Path | Carries |
|---|---|
| `/v1/otherlode/deltas` | Delta batches, which double as the heartbeat |
| `/v1/otherlode/manifest` | The probe manifest |
| `/v1/otherlode/static-baseline` | The static baseline, when it is enabled |
| `/v1/otherlode/class-listing` | The class listing, unless it is switched off |

The agent normalises the value before it uses it. It trims spaces, drops trailing `/` characters, and lowercases the scheme, so `HTTPS://Collector.example.com/` becomes `https://Collector.example.com`. The host and the path keep the letter case you wrote. A path is kept: `https://gateway.example.com/otherlode` posts to `https://gateway.example.com/otherlode/v1/otherlode/deltas`.

The agent refuses these values. It logs one ERROR and turns itself off for the life of the JVM: it instruments nothing and sends nothing. It does not fall back to the default `http://localhost:4319`, which applies only when you set no `exportUrl`.

- A value that is not an absolute `http` or `https` URL with a host. A relative path such as `/v1/otherlode` and a value with no scheme both fall here.
- A value with a query string.
- A value with a fragment.
- A value with a user name or password, such as `http://user:secret@collector:4319`, or any other `@` after the scheme. The agent never sends a user name or password, so set the token with [authToken](#authtoken).
- A value that starts with `http://` when `authToken` is set and the host is `otherlode.dev` or ends with `.otherlode.dev`, in any letter case. Without a token, the agent accepts the value.
- A host name that contains an underscore, such as `http://my_collector:4319`. Use a host name or network alias without an underscore, or an IP address.

The ERROR names `exportUrl` and the source that gave the value: the agent option, the system property or the environment variable. It does not repeat the value, apart from the host in the underscore case. It starts with the reason, as in `otherlode: exportUrl must be an absolute http or https URL; exportUrl from the environment variable OTHERLODE_EXPORT_URL is refused, so the agent is off for this JVM`. The instance sends no data until you fix the value and restart the JVM. When the agent is disabled with `enabled=false`, it logs no ERROR for these values.

The agent connects through the JVM's proxy settings, such as `-Dhttps.proxyHost` and `-Dhttps.proxyPort`, and trusts the certificates in the JVM's truststore. For JVMs with no route to the internet, send through a collector. See [Add a collector](/docs/collector/add).

For what the agent sends to this URL, and what it does when Otherlode or the collector is down, see [what the agent sends](data-sent).

## authToken

When `authToken` is set, the agent sends `Authorization: Bearer <token>` with every request. To send straight to Otherlode, set it to an ingest key. To send through a collector, set it to the token the collector checks. The agent trims the token. The token may hold only the visible ASCII characters `!` to `~`. A token with a space inside it, a control character, an invisible character such as U+FEFF or U+200B, or any other non-ASCII character turns the agent off for the life of the JVM. A token copied from a web page or a document can carry an invisible character. The agent logs one ERROR that names the source of the token and never the token or its length:

```text
otherlode: authToken from the environment variable OTHERLODE_AUTH_TOKEN holds a character outside visible ASCII (! to ~), so the agent is off for this JVM; set a token of visible ASCII characters only
```

Set the token through `OTHERLODE_AUTH_TOKEN`. Both the argument string and a `-D` flag appear on the process command line, which every user on the host can read through `ps` and `/proc/<pid>/cmdline`.

When a token is set and the final `exportUrl` starts with `http://`, the agent logs this warning once at startup. It does not log it when the host is `localhost`, an IPv4 address from `127.0.0.0` to `127.255.255.255`, or `[::1]`. The agent reads the host as written and never looks a name up, so `localhost.example.com` and a name that resolves to `127.0.0.1` still log it.

```text
otherlode: exportUrl uses plain http, so the auth token is sent unencrypted
```

When the host is `otherlode.dev` or ends with `.otherlode.dev`, with or without a final dot, the agent does not warn. It refuses the URL, as [exportUrl](#exporturl) describes. The ERROR reads as below, with the source that gave the URL.

```text
otherlode: exportUrl must use https for an otherlode.dev host while authToken is set, since the token is an ingest key; exportUrl from the environment variable OTHERLODE_EXPORT_URL is refused, so the agent is off for this JVM
```

When a token is set and no source sets `exportUrl`, the token goes to the default `http://localhost:4319`. The agent logs this warning once at startup. It does not log it when you set `exportUrl` to that same value.

```text
otherlode: authToken is set but exportUrl is not, so the token goes to the default http://localhost:4319
```

## flushIntervalSeconds

`flushIntervalSeconds` is the time between flushes, in whole seconds. The accepted range is `1` to `86400` (one day). A decimal, a negative number, zero, a value above one day and text such as `30s` all produce a warning and the default of `60`.

The agent starts the first flush at a random point within the first interval. The flushes after it follow every interval at a fixed rate. Every payload carries the interval as `flush_interval_seconds`.

A running instance says `starting` on every payload for at least one interval after the agent starts, and for at most 10. [Pending counts](data-sent#pending-counts) says when it stops.

## trimNativeHeapIntervalMinutes

`trimNativeHeapIntervalMinutes` is the time between native heap trims, in whole minutes, from `0` to `1440` (one day). `0` turns the trim off. A decimal, a negative number, a value above one day and text such as `60m` produce a warning and the default of `60`.

A trim returns the native memory that glibc holds free to the operating system, and leaves memory in use alone. The first trim comes one interval after startup. See [Memory over days](overhead#memory-over-days).

The agent trims only on Linux with glibc, and not when the JVM already trims with `-XX:TrimNativeHeapInterval` above `0`. After a trim fails, it stops trimming for the life of the JVM.

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

A service name or namespace of `.` or `..` is skipped with a warning, as if it were blank, and the next source gives the value. The check covers every source of a name or a namespace, including OpenTelemetry's and detection.

The name, namespace, version, environment and instance ID each hold at most 1024 bytes in UTF-8. Multi-byte characters count by their bytes, so 512 characters of two bytes each fit and 513 do not. The agent skips a longer value, or one with a NUL character, with a warning that names its source and never the value. The next source gives the value, and an instance ID that every source skips falls back to the random UUID.

The agent never reads the instance ID from OpenTelemetry's `service.instance.id`. Otherlode counts instances by this ID, so to pin it, set `serviceInstanceId` to a value that no two running processes share, such as a pod name or a name for a test task.

Each process also gets a random run ID, which no option sets, so a restart under a pinned instance ID is a new run.

## OpenTelemetry's settings

When no Otherlode source gives the service name, namespace, version or environment, the agent reads it from OpenTelemetry's settings. To name the service differently in Otherlode, set the Otherlode option.

The agent reads two OpenTelemetry settings.

| Setting | System property | Environment variable |
|---|---|---|
| Service name | `otel.service.name` | `OTEL_SERVICE_NAME` |
| Resource attributes | `otel.resource.attributes` | `OTEL_RESOURCE_ATTRIBUTES` |

Each setting comes whole from one place. The system property wins over the environment variable. A system property that is set and not blank hides the whole environment variable, so a `service.version` in `OTEL_RESOURCE_ATTRIBUTES` is not read when `otel.resource.attributes` is also set. A blank system property counts as unset.

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

The value is a comma-separated list of `key=value` pairs.

```text
OTEL_RESOURCE_ATTRIBUTES=service.namespace=shop,service.version=2.4.1,deployment.environment.name=staging
```

- The agent splits the list at commas, and each pair at its first `=`. It trims the key and the value.
- It percent-decodes each value as UTF-8 bytes, so `%20` is a space and `%2C` is a comma. A `+` stays a `+`. A `%` that is not followed by two hex digits stays as written.
- A pair with an empty value is dropped. It is not an error.
- When a key appears twice, the last pair wins.
- If any pair has no `=` or an empty key, the agent discards the whole list and logs a warning that names the setting. It then reads no name, namespace, version or environment from that list.

The agent does not percent-decode `otel.service.name`.

### Kubernetes

When the OpenTelemetry Operator injects `OTEL_RESOURCE_ATTRIBUTES` into a pod, the agent reads its `service.namespace` like any other value. Set `OTHERLODE_SERVICE_NAMESPACE` to override it. The agent never derives a namespace from the pod for itself.

## Service-name detection

When no Otherlode source and no OpenTelemetry setting gives a service name, the agent detects one. The first source that finds a name wins. Detection runs at startup. It reads files, the command line, system properties and the environment, and it never loads an application class. A source that fails gives no name, and detection moves on.

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
- A `.properties` file is read as ISO 8859-1.
- A YAML file is read as nested block mappings (`spring:`, then `application:`, then `name:`) with plain, single-quoted or double-quoted values and `#` comments. The first document that names the application wins when a file holds several documents split by `---`. The agent does not read flow mappings, block scalars (`|`, `>`), anchors, aliases, escapes or a dotted key such as `spring.application.name:`.

If detection finds nothing, the name is `unknown_service:java`.

## Other settings the agent reads

These settings are not agent options, and the three-source scheme does not cover them.

| Setting | Where | What it does |
|---|---|---|
| `otel.service.name`, `otel.resource.attributes` | System property, and `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` as variables | [OpenTelemetry's settings](#opentelemetrys-settings) above |
| `spring.application.name`, `SPRING_APPLICATION_NAME` | System property, environment variable | Input to [service-name detection](#service-name-detection) |
| `net.bytebuddy.naming` | System property | Read only to tell classes that ByteBuddy generates at runtime from your own. With the value `fixed` or `caller`, the agent treats a class whose name ends in `$ByteBuddy` as runtime-generated and leaves it alone. |
| `otherlode.testkit.port`, `otherlode.testkit.startup.timeout.seconds` | System property | Settings of the testkit's JUnit extension, not of the agent. See [the testkit](testkit). |

The agent also reads `java.class.path`, `sun.java.command` and the process command line to find the main class and the main jar. You do not set these for the agent.
