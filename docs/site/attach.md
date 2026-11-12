---
title: Attach the agent
description: Get the agent jar, add -javaagent to a plain java launch, a Gradle run task, a Spring Boot fat jar, or a container, and confirm the agent is running.
order: 10
---

The agent is a single jar that you attach with the JVM's `-javaagent` flag. It attaches at startup only. It cannot attach to a JVM that is already running, so you add the flag to your service's normal launch configuration and restart once.

## Get the agent jar

A release publishes one agent jar, `otherlode-agent-<version>.jar`, in two places:

- Maven Central, as `dev.otherlode:otherlode-agent`. The jar is at `https://repo1.maven.org/maven2/dev/otherlode/otherlode-agent/<version>/otherlode-agent-<version>.jar`.
- The GitHub release for the same version, which carries the jar, its `.asc` signature, and a `SHA256SUMS` file.

The two jars are byte for byte the same. The jar is self-contained: it bundles and relocates its own libraries, so it cannot collide with copies on your application's classpath. Keep it out of your application's own classpath and only pass it to `-javaagent`.

To build the jar from a checkout instead, run `./gradlew build`. The agent jar is `build/libs/otherlode-agent-<version>.jar`. Ignore the `-plain` jar beside it, which has no manifest entry for the agent.

## Add the flag

The `-javaagent` flag takes the jar's path, then `=`, then the agent's options as comma-separated `key=value` pairs. Only `includePackages` is required.

```bash
java \
	-javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=serviceName=shop,includePackages=com.acme.shop,exportUrl=https://collector.example.com \
	-jar shop.jar
```

Put `-javaagent` before `-jar` or the main class. Anything after the jar name is an argument to your application.

A value in the option string cannot contain a comma. The agent keeps the part before the first comma as the value, reads the rest as further options and logs a warning for a part that is not one. Set such a value through a system property or an environment variable instead. [The configuration options](configuration) list every option and the names of its property and variable.

### Gradle

For a project that uses the `application` plugin, add the flag to `run`:

```kotlin
tasks.named<JavaExec>("run") {
	jvmArgs("-javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=includePackages=com.acme.shop")
}
```

To bake the flag into the start scripts that `installDist` produces, set `applicationDefaultJvmArgs` in the `application` block instead. A Spring Boot project uses `bootRun` in place of `run`:

```kotlin
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	jvmArgs("-javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=includePackages=com.acme.shop")
}
```

To attach the agent to your tests, see [the testkit](testkit).

### Spring Boot fat jar

Run the fat jar with the flag before `-jar`:

```bash
java -javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=includePackages=com.acme.shop -jar shop.jar
```

Choose `includePackages` from your own code's package, not from the Spring Boot launcher's. The agent reads the nested jars under `BOOT-INF/lib` itself, so you add nothing to the fat jar.

### Docker and Kubernetes

An image usually starts the JVM through a script or an entrypoint you would rather not edit. Set `JAVA_TOOL_OPTIONS` instead. Every JVM reads it at startup, and it can carry a `-javaagent` flag:

```dockerfile
COPY otherlode-agent-0.1.0.jar /opt/otherlode/otherlode-agent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otherlode/otherlode-agent.jar"
```

Then set the agent's options as `OTHERLODE_*` environment variables, which a deployment can change without rebuilding the image:

```yaml
env:
  - name: OTHERLODE_INCLUDE_PACKAGES
    value: com.acme.shop
  - name: OTHERLODE_EXPORT_URL
    value: https://collector.example.com
  - name: OTHERLODE_AUTH_TOKEN
    valueFrom:
      secretKeyRef:
        name: otherlode
        key: token
```

The JVM prints `Picked up JAVA_TOOL_OPTIONS: ...` to stderr when it reads the variable. Every JVM that starts in the container reads it, including a health check that runs `java`, so that JVM starts the agent too. If that matters, put the flag in the entrypoint's command line instead. When one JVM gets the agent twice, such as once in `JAVA_TOOL_OPTIONS` and once on the command line, the second copy logs this warning and does nothing while the first is running:

```text
otherlode: the agent is already running in this JVM; this second -javaagent is ignored
```

An environment variable has the lowest precedence. The option string wins over a system property, and a system property wins over a variable, so a variable has no effect on an option that your image's command line already sets. See [where a value comes from](configuration#where-a-value-comes-from).

## Choose includePackages

`includePackages` lists the package prefixes the agent instruments. Separate several prefixes with a semicolon. Write each as a dotted package such as `com.acme.shop`.

```text
includePackages=com.acme.shop;com.acme.billing
```

Choose the root of the code you can delete. That includes your internal libraries that arrive as jars, so a shared `com.acme.common` belongs in the list if you own it. A prefix matches a whole package segment, so `com.acme` matches `com.acme.shop` and does not match `com.acmeinternal`. `excludePackages` removes prefixes from the match, and an exclusion always wins.

The agent has no default for this option and no wildcard. If `includePackages` is missing or empty, even when `excludePackages` is set, the agent logs one ERROR, disables itself for the life of the JVM, and instruments and exports nothing. Your application still starts and runs normally. Otherlode lists no instance for it.

A prefix written as a glob (`com.acme.*`) or a path (`com/acme`) matches no class. The agent drops it with a warning that names the spelling that would match, and does not apply that spelling for you. If every prefix is dropped this way, the agent refuses to start, as it does for a missing option.

When the agent can find your application's main class, the error names the class and a prefix that covers its package:

```text
otherlode: includePackages is not set, so the agent is disabled for this JVM and nothing will be instrumented or exported; set includePackages to your application's own package prefixes, ';'-separated; the main class is com.acme.shop.ShopApplication; includePackages=com.acme.shop covers its package, or name a broader prefix that also covers your shared libraries
```

The agent finds the main class from the launch command. It takes the class you name on the command line, `Start-Class`, else `Main-Class`, from the manifest of a `-jar` launch, or the main class of a module launch. It prints no suggestion when the main class is under `org.springframework.boot.loader`, `io.ktor.server` or `org.apache.catalina`, when it is in the default package, or when the agent cannot find a main class. The agent never applies the suggestion. Copy it into the option yourself.

## Point the agent at Otherlode or a collector

`exportUrl` is where the agent sends. Set it to `https://ingest.otherlode.dev` to send straight to Otherlode, or to the base URL of your collector. It defaults to `http://localhost:4319`. [Add a collector](/docs/collector/add) says when you need one. The agent appends its own paths to the URL and keeps a path you write. A value the agent cannot use, such as one with a query string or a fragment, turns the agent off for the life of the JVM with one ERROR. The agent does not fall back to the default. [exportUrl](configuration#exporturl) lists the values it refuses.

`authToken` is sent as `Authorization: Bearer <token>`. Sending straight to Otherlode, it is your ingest key. Sending through a collector, it is the token the collector checks. It may hold only the visible ASCII characters `!` to `~`. A token with any other character, such as an invisible one copied from a web page, turns the agent off with one ERROR. Set the token through the `OTHERLODE_AUTH_TOKEN` environment variable, not the option string, since any user on the host can read a process's arguments with `ps`.

If `authToken` is set and `exportUrl` starts with `http://`, the agent logs a warning that the token travels unencrypted. It skips the warning when the host is `localhost`, an address from `127.0.0.0` to `127.255.255.255` or `[::1]`. Use an `https://` URL for any other host. If the host is `otherlode.dev` or ends with `.otherlode.dev`, the agent refuses the `http://` URL and turns itself off with one ERROR. If you set a token and no `exportUrl`, the agent logs a warning that the token goes to the default `http://localhost:4319`.

The agent sends one batch every 60 seconds by default, starting at a random point within the first interval. `flushIntervalSeconds` changes the interval. [What the agent sends](data-sent) lists what leaves the process and what happens while Otherlode or the collector is down.

## Turn the agent off

To disable the agent without rebuilding anything, set the `OTHERLODE_ENABLED` environment variable to `false`:

```bash
OTHERLODE_ENABLED=false java -javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=includePackages=com.acme.shop -jar shop.jar
```

The agent logs one line at INFO and does nothing else. It instruments no class and sends nothing:

```text
otherlode: disabled by configuration, nothing will be instrumented or exported
```

The agent acts on `enabled` before it refuses any other value, so a switched-off agent logs no ERROR about `includePackages`, `exportUrl` or `authToken`. The variable works only when neither the option string nor the `otherlode.enabled` system property sets `enabled`, since both win over a variable.

## Confirm the agent is running

The agent writes through the JVM's `System.Logger`, which prints to stderr unless your application routes it to its own logging. A disabled or refused start prints its line straight to stderr, in the same format. Each message starts with `otherlode:`. In the JVM's default format, the logger puts the level in front, as in `INFO: otherlode:`, below a line with the time and the class that logged it. An ERROR prints as `SEVERE: otherlode:`, so search for `SEVERE` to find one. A healthy start prints one INFO message naming the endpoint modules it installed:

```text
otherlode: installing endpoint modules: javalin-6, javalin-7, jaxrs, jdk-httpserver, ktor-2, ktor-3, micronaut, micronaut-3, servlet, spring-webflux, spring-webmvc, vertx-web
```

Beyond that line, the agent prints mostly problems, so the absence of an error is the first check. Look in the startup output for lines that contain `otherlode:`. These lines mean the agent is off for this JVM, or an option name in the option string is misspelled:

| Line | Meaning |
|---|---|
| `otherlode: includePackages is not set, so the agent is disabled for this JVM ...` | No usable include prefix. See [Choose includePackages](#choose-includepackages). |
| `otherlode: ... exportUrl from ... is refused, so the agent is off for this JVM` | An `exportUrl` the agent cannot use, or a plain `http://` URL to an `otherlode.dev` host while `authToken` is set. The line starts with the reason. See [exportUrl](configuration#exporturl). |
| `otherlode: authToken from ... holds a character outside visible ASCII (! to ~), so the agent is off for this JVM; ...` | The token holds a character outside `!` to `~`, such as an invisible one copied from a web page. See [authToken](configuration#authtoken). |
| `otherlode: disabled by configuration, nothing will be instrumented or exported` | `enabled` is `false`. This line is INFO. |
| `otherlode: agent failed to start and is disabled for this JVM` | A failure during startup. The line carries the stack trace. |
| `otherlode: could not install the bootstrap holder; the agent is disabled for this JVM` | The agent could not write its small helper jar to `java.io.tmpdir`, for example on a read-only or full filesystem. |
| `otherlode: ignoring unknown agent option '...'` | A misspelled option in the option string. The option does not apply. The line lists the options that exist. |

The second check is in Otherlode. The agent sends a batch on every flush, even when no count has changed, so an idle instance still reports. Within one flush interval of startup, Otherlode shows the service under the `serviceName` you set. See [send data to Otherlode](/docs/server/connect#confirm-the-service-appears). To see it sooner, add `flushIntervalSeconds=1` while you test.

If a flush cannot reach Otherlode or the collector, the agent logs a warning at the failed send, with the cause, and retries on the next flush:

```text
otherlode: delta export failed, will retry next flush
```

A 401 or 403 response is not retried within a send. The warning's cause names the status and the URL, such as `unexpected status 401 from https://ingest.otherlode.dev/v1/otherlode/deltas`, and means the ingest key or the collector's token is missing or wrong. [Read what ingest answers](/docs/server/connect#read-what-ingest-answers) says what each status means.

If the instance never appears and no line says why, [troubleshooting](troubleshooting) walks through the causes.

## Next steps

- [The configuration options](configuration) list every option, with its property and environment variable names.
- [What the agent sends](data-sent) explains the data that leaves the process.
- [Compatibility](compatibility) lists the JDKs, compilers, and other agents the agent works with.
