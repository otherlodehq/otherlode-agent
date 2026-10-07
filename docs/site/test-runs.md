---
title: Name the tests that call your code
description: Run the agent in your test JVM with testRun so a collector can name the tests that call a method production never does.
order: 100
---

Production holds no test classes. A method that only your tests call therefore reads as uncalled in production, the same as a method that nothing calls. To have the collector name those tests, run the agent in your test JVM as well, against the same collector, and mark that run as a test run.

This page covers the agent's side. How the server shows a test run in its findings is in the server's docs.

## What the agent does with `testRun`

A test run is a run whose agent has `testRun=true`. The agent probes, records call edges and scans as it does anywhere else. It differs in three ways:

- Every payload of the run carries a flag saying it is a test run. The collector uses that flag to leave the run out of every finding about production and to read only its call edges.
- If no source sets an environment, the environment is `test`. Any environment you set, through an option or through OpenTelemetry's settings, wins over it.
- At shutdown, the agent sends its final flush, then waits up to 15 seconds for the static baseline scan to end. It logs a warning if the scan has not ended by then. The wait happens only when `staticBaselineEnabled` is on.

## Set up the test JVM

1. Point the test JVM at the same collector as production, with the same `serviceName` and `serviceNamespace`. A different name makes the test run a different service.
2. Set `testRun=true`.
3. Set `includePackages` so it covers your test classes as well as your production code. The two usually share packages, so production's value works.
4. Set `staticBaselineEnabled=true`. The scan then reads call edges from every test class on the classpath, not only from the tests that ran.
5. Pin `serviceInstanceId` to one fixed value per test task, such as `my-service-unit-tests`.
6. Leave the environment unset. That includes `OTEL_RESOURCE_ATTRIBUTES` and `OTHERLODE_ENVIRONMENT`, which CI may pass down to the test JVM.

### Gradle

```kotlin
// build.gradle.kts
tasks.test {
	jvmArgs(
		"-javaagent:/path/to/otherlode-agent.jar=serviceName=my-service,testRun=true," +
			"staticBaselineEnabled=true,serviceInstanceId=my-service-unit-tests," +
			"includePackages=com.acme.myservice,exportUrl=http://localhost:4319",
	)
}
```

The `jacoco` plugin can stay applied. The order of the two agents changes neither tool's results.

### Maven

Pass the same `-javaagent` argument to the forked test JVM through Surefire's `argLine`:

```xml
<plugin>
	<artifactId>maven-surefire-plugin</artifactId>
	<configuration>
		<argLine>-javaagent:/path/to/otherlode-agent.jar=serviceName=my-service,testRun=true,staticBaselineEnabled=true,serviceInstanceId=my-service-unit-tests,includePackages=com.acme.myservice,exportUrl=http://localhost:4319</argLine>
	</configuration>
</plugin>
```

If another plugin, such as JaCoCo's `prepare-agent`, also sets `argLine`, put `@{argLine}` at the start of this value so both survive.

Every option also has a system property and an environment variable. See the [configuration options](configuration) for their names.

## Why each setting matters

**Matching service name.** The collector keys findings by service. A test run under another name never meets production's methods.

**`staticBaselineEnabled` in the test JVM.** Without the scan, the agent reports edges only from test classes that loaded. A test class that never loaded has its edges missing.

**`staticBaselineEnabled` in production.** Set it there too. Without a complete production scan, the collector cannot rule out a caller in a production class that never loaded. It then names the tests that call a method but does not call the method "called only by tests".

**A pinned `serviceInstanceId`.** The agent makes a fresh random instance ID for each JVM unless you set one. Every test JVM then becomes its own instance, and the collector keeps its edges until it prunes that instance. A fixed ID per test task gives the collector one current picture per task. A pinned ID does not merge counts across restarts, because each process also gets its own run ID.

With `maxParallelForks` above 1, the forks of one task share the pinned ID. The collector keeps only the newest manifest per instance, so the newest fork's manifest hides the others'. The scan still covers every test class, whichever fork sends it.

**An unset environment.** The `test` default keeps a test run's data in an environment of its own, apart from production's, as a second guard beside the flag.

## Older and environment-stamping collectors

A collector built before the test-run flag existed drops it when its redaction is on. The run then reaches the backend as an ordinary run in the `test` environment, apart from production's. If that collector also stamps its own environment with `upsert`, the test run lands in production's environment. Update the collector before you turn `testRun` on.

A collector with an environment of its own and `insert` keeps the agent's `test`, and counts each of the run's payloads as an environment mismatch.

## Expect a longer shutdown

The wait for the scan can add up to 15 seconds to a test task's exit, and more when the collector is slow or unreachable. A test JVM often exits before the scan ends, and the scan is the only source of edges from test classes that never loaded. The agent flushes first, so a runner that halts a slow JVM still delivers the final manifest.

If the scan does not end in time, the agent logs a warning that the run may send no complete scan. The collector then falls back to the run's manifest edges, and to an earlier complete scan of the same pinned instance if there is one. That older scan can name test classes you have since deleted.

## Related pages

- [Test your app against the agent](testkit) uses the testkit's collector, which ignores the flag.
- [Configuration options](configuration) lists every option and the OpenTelemetry settings the agent reads.
- [Classes](classes) explains the static baseline.
