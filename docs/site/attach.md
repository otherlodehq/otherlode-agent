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

The `-javaagent` flag takes the jar's path, then `=`, then the agent's options as comma-separated `key=value` pairs. Only `includePackages` is required. The other options in this guide are `serviceName` and `exportUrl`.

```bash
java \
	-javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=serviceName=shop,includePackages=com.acme.shop,exportUrl=https://collector.example.com \
	-jar shop.jar
```

Put `-javaagent` before `-jar` or the main class. Anything after the jar name is an argument to your application.

A value in the option string cannot contain a comma, and a value that needs one is ignored with a warning. Set such a value through a system property or an environment variable instead. [The configuration options](configuration) list every option and the names of its property and variable.

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

The JVM prints `Picked up JAVA_TOOL_OPTIONS: ...` to stderr when it reads the variable. Every JVM that starts in the container reads it, including a health check that runs `java`, so that JVM starts the agent too. If that matters, put the flag in the entrypoint's command line.

An environment variable has the lowest precedence of the three sources. The option string wins over a system property, and a system property wins over a variable. If your image's command line already sets an option, a variable for the same option has no effect. [The configuration options](configuration) explain the precedence.

## Choose includePackages

`includePackages` lists the package prefixes the agent instruments. Separate several prefixes with a semicolon. Write each as a dotted package such as `com.acme.shop`.

```text
includePackages=com.acme.shop;com.acme.billing
```

Choose the root of the code you can delete. That includes your internal libraries that arrive as jars, so a shared `com.acme.common` belongs in the list if you own it. A prefix matches a whole package segment, so `com.acme` matches `com.acme.shop` and does not match `com.acmeinternal`. `excludePackages` removes prefixes from the match, and an exclusion always wins.

The agent has no default for this option and no wildcard. If `includePackages` is missing, empty, or set only through `excludePackages`, the agent logs one error, disables itself for the life of the JVM, and instruments and exports nothing. Your application still starts and runs normally. The collector sees no instance at all, which is the signal that something needs fixing.

A prefix written as a glob (`com.acme.*`) or a path (`com/acme`) matches no class. The agent drops it with a warning that names the spelling that would match, and does not apply that spelling for you. If every prefix is dropped this way, the agent refuses to start, as it does for a missing option.

The error tries to help. When it can find your application's main class, it prints the class and its package:

```text
otherlode: includePackages is not set, so the agent is disabled for this JVM and nothing will be instrumented or exported; set includePackages to your application's own package prefixes, ';'-separated; the main class is com.acme.shop.ShopApplication; includePackages=com.acme.shop covers its package, or name a broader prefix that also covers your shared libraries
```

The agent finds the main class from the launch command. It reads `Start-Class`, else `Main-Class`, from a jar's manifest, and takes the class from a module launch. It prints no suggestion when the main class is in a launcher or framework package that says nothing about your code. Those are Spring Boot's launchers, Ktor's `EngineMain`, and Tomcat's `Bootstrap`. It also prints none when the main class is in the default package, or when it cannot find a main class at all. The agent never applies the suggestion. Copy it into the option yourself.

## Point the agent at a collector

`exportUrl` is the base URL of the collector. It defaults to `http://localhost:4319`. The agent appends its own paths to it, so a path in the URL is kept. A query string or a fragment is refused with a warning, and the agent uses the default.

`authToken` is sent to the collector as `Authorization: Bearer <token>`. Set the token through the `OTHERLODE_AUTH_TOKEN` environment variable, not through the option string. Every user on the host can read a process's arguments with `ps`, and cannot read its environment.

If `authToken` is set and `exportUrl` starts with `http://`, the agent logs a warning that the token travels unencrypted. Use an `https://` URL for any collector that is not on the same host.

The agent sends one batch every 60 seconds by default, at a random offset within the first interval so that a fleet does not report in step. `flushIntervalSeconds` changes the interval. [What the agent sends](data-sent) lists what leaves the process and what happens while the collector is down.

## Turn the agent off

To disable the agent without rebuilding anything, set the `OTHERLODE_ENABLED` environment variable to `false`:

```bash
OTHERLODE_ENABLED=false java -javaagent:/opt/otherlode/otherlode-agent-0.1.0.jar=includePackages=com.acme.shop -jar shop.jar
```

The agent logs one line at INFO and does nothing else. It instruments no class and sends nothing:

```text
otherlode: disabled by configuration, nothing will be instrumented or exported
```

This check runs before the `includePackages` check, so a switched-off agent never complains about a missing option. The variable only works when the option string does not set `enabled` itself, since the option string wins.

## Confirm the agent is running

The agent writes through the JVM's `System.Logger`, which prints to stderr unless your application routes it to its own logging. A healthy start prints one INFO line naming the endpoint modules it installed:

```text
otherlode: installing endpoint modules: jaxrs, jdk-httpserver, ktor-2, ktor-3, spring-webmvc
```

Beyond that line, the agent prints mostly problems, so the absence of an error is the first check. Look in the startup output for lines that start with `otherlode:`. These are the lines that mean the agent is not working:

| Line | Meaning |
|---|---|
| `otherlode: includePackages is not set, so the agent is disabled for this JVM ...` | No usable include prefix. See [Choose includePackages](#choose-includepackages). |
| `otherlode: disabled by configuration, nothing will be instrumented or exported` | `enabled` is `false`. |
| `otherlode: agent failed to start and is disabled for this JVM` | A failure during startup. The line carries the stack trace. |
| `otherlode: could not install the bootstrap holder; the agent is disabled for this JVM` | The agent could not write its small helper jar to `java.io.tmpdir`, for example on a read-only or full filesystem. |
| `otherlode: ignoring unknown agent option '...'` | A misspelled option. The line lists the options that exist. |

The second check is the collector. The agent sends a batch on every flush, even when no count has changed, so an instance that is alive and idle still reports. Within one flush interval of startup, your collector should show the instance under the `serviceName` you set. To see it sooner, add `flushIntervalSeconds=1` while you test.

If a flush cannot reach the collector, the agent logs a warning at the failed send, with the cause, and retries on the next flush:

```text
otherlode: delta export failed, will retry next flush
```

A 401 or 403 response from the collector is not retried within a send. The warning's cause names the status and the URL, such as `unexpected status 401 from http://localhost:4319/...`, and means the token is missing or wrong.

If the instance never appears and no line says why, [troubleshooting](troubleshooting) walks through the causes.

## Next steps

- [The configuration options](configuration) list every option, with its property and environment variable names.
- [What the agent sends](data-sent) explains the data that leaves the process.
- [Compatibility](compatibility) lists the JDKs, compilers, and other agents the agent works with.
