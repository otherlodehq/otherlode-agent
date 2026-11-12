---
title: Name the tests that call your code
description: Run the agent in your test JVM with testRun so Otherlode can name the tests that call a method production never does.
order: 100
---

Production holds no test classes, so a method that only your tests call reads as uncalled in production. To have Otherlode name those tests, run the agent in your test JVM too, send to the same tenant as production, and mark the run as a test run. [Test callers](/docs/server/unreached-clusters#test-callers) shows how Otherlode uses it.

## What the agent does with `testRun`

A test run is a run whose agent has `testRun=true`. The agent probes, records call edges and scans as it does anywhere else, with three differences:

- Every payload of the run carries a flag that marks it as a test run. Otherlode keeps a test run out of every finding about production and reads only its call edges.
- If no source sets an environment, the environment is `test`. An environment you set, through an option or through OpenTelemetry's settings, wins over it.
- At shutdown the agent sends its final flush straight away, with up to 10 seconds for it. It does not flush early or wait for your application's own shutdown hooks. With `staticBaselineEnabled` on, it then waits up to 15 seconds for the static baseline scan to end.

## Set up the test JVM

1. Send to the same tenant as production, with the same `serviceName` and `serviceNamespace`. Use the same `exportUrl` and an ingest key of that tenant, or the same collector. A different name makes the test run a different service.
2. Set `testRun=true`.
3. Set `includePackages` so it covers your test classes as well as your production code. When they share packages, production's value works.
4. Set `staticBaselineEnabled=true`. The scan reads call edges from every test class on the classpath, so a test class that never loaded still names its calls. Set it in production too. Without a complete production scan, Otherlode names the tests that call a method but does not call the method "called only by tests".
5. Pin `serviceInstanceId` to one fixed value per test task, such as `my-service-unit-tests`. Otherwise every test JVM gets a random ID and becomes an instance of its own. Each process still gets its own run ID, so a pinned ID does not merge counts across restarts. [What Otherlode deletes on a schedule](/docs/server/data-kept#the-retention-period) says which test runs of an instance Otherlode reads and keeps.
6. Leave the environment unset, so the run reports `test`. That includes `OTEL_RESOURCE_ATTRIBUTES` and `OTHERLODE_ENVIRONMENT`, which CI may pass down to the test JVM.

Every option also has a system property and an environment variable. See [configuration options](configuration) for their names.

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

With `maxParallelForks` above 1, the forks of one task share the pinned ID, and each fork is a run of its own. Otherlode reads manifest edges from the newest of those runs only. The scan still covers every test class, whichever fork sends it.

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

## Expect a longer shutdown

A test run can add up to 25 seconds to a test task's exit: up to 10 seconds for the final flush and up to 15 for the scan. The agent flushes before it waits for the scan, so a runner that halts a slow JVM still delivers the final manifest.

If the scan has not ended after 15 seconds, the agent stops waiting and logs this warning:

```text
otherlode: the static baseline scan did not end within 15s of shutdown, so this test run may send no complete scan
```

Otherlode then reads the run's manifest edges, and the newest complete scan of the same pinned instance if an earlier run sent one. That older scan can name test classes you have since deleted.

## Through a collector with an environment

A collector that stamps its own environment counts each payload of a test run as an environment mismatch. Under `upsert` it also replaces `test` with its own environment, and only the test-run flag keeps the run apart from production. See [handle test runs](/docs/collector/environment-and-namespace#handle-test-runs).

## Related pages

- [Test your app against the agent](testkit) uses the testkit's collector, which ignores the test-run flag.
- [Configuration options](configuration) lists every option and the OpenTelemetry settings the agent reads.
- [Classes](classes) explains the static baseline.
