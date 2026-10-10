---
title: Test your app against the agent
description: Run the agent in your test JVM, start the testkit's collector, and assert which methods, endpoints and classes your tests exercised.
order: 80
---

The testkit is a collector that runs inside your test JVM. It accepts the agent's real payloads, so the agent behaves as it does in production. You start the collector, point the agent at it, exercise your app, and then ask the collector what it saw.

This page covers the setup and the pattern for an assertion. [The testkit reference](testkit-api) lists every query, wait and exception.

## Put the testkit and the agent on the build

The agent runs from its own `-javaagent` jar. The testkit is a second jar that goes on the test classpath. The agent jar is not on the test classpath, and the testkit jar carries no copy of it. Both are on Maven Central, as `dev.otherlode:otherlode-agent` and `dev.otherlode:otherlode-testkit`.

The two always share one version, and the testkit's collector rejects a payload from an agent of another version. The failure names both versions. Upgrade the pair together.

The testkit bundles its own protobuf under `dev.otherlode.testkit.shaded`. Its one dependency is kotlin-stdlib, and JUnit 5 stays optional: the extension needs `junit-jupiter-api` only if you use it. The jar is class-file version 61, so it loads on JDK 17 and later.

In a Gradle build, resolve the agent jar through a configuration of its own, so it never lands on the test classpath, and attach it to the test task:

```kotlin
val otherlodeAgent by configurations.creating { isTransitive = false }

dependencies {
	testImplementation("dev.otherlode:otherlode-testkit:0.1.0")
	otherlodeAgent("dev.otherlode:otherlode-agent:0.1.0")
}

tasks.test {
	useJUnitPlatform()
	jvmArgumentProviders.add(CommandLineArgumentProvider {
		listOf(
			"-javaagent:${otherlodeAgent.singleFile}=" +
				"includePackages=com.acme," +
				"flushIntervalSeconds=1," +
				"exportUrl=http://localhost:4319",
		)
	})
}
```

Attach the agent with the `-javaagent` flag on the test task. The extension never attaches it for you, because JUnit loads test classes, and often the classes under test, before any extension runs. A class that loads before the agent attaches is never woven.

Each option does a job:

- `includePackages` is required. Without it the agent logs an ERROR and sends nothing. See [configuration options](configuration) for the rule.
- `flushIntervalSeconds=1` makes the agent report every second. The default is 60 seconds, longer than the extension's startup timeout, so a test would wait for a flush that comes too late.
- `exportUrl` points at the collector. The extension binds port 4319 unless you change it, as the next section shows.

For the plain agent setup outside tests, see [attach the agent](attach).

## Use the JUnit 5 extension

Register `OtherlodeExtension` on a test class and ask for the collector as a parameter:

```kotlin
import dev.otherlode.testkit.OtherlodeTestCollector
import dev.otherlode.testkit.junit5.OtherlodeExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(OtherlodeExtension::class)
class CheckoutTest {
	@Test
	fun `checkout runs and the legacy promo does not`(collector: OtherlodeTestCollector) {
		OrderService().checkout(order)

		collector.awaitProbe("com.acme.OrderService", "checkout", Duration.ofSeconds(10))
		collector.awaitSettled(Duration.ofSeconds(10))

		assertTrue(collector.wasHit("com.acme.OrderService", "checkout"))
		assertFalse(collector.wasHit("com.acme.OrderService", "applyLegacyPromo"))
	}
}
```

The extension starts one collector for the whole test JVM and shares it between test classes. A test class that cannot take a parameter reads the same collector from `OtherlodeExtension.collector()`. That method throws `IllegalStateException` until a test class with the extension has started.

Before the first test class runs, the extension waits for the agent's first report. If none arrives, the test fails with a message that names the flag to add. Two system properties on the test JVM change the extension's behavior:

| Property | Default | Effect |
|---|---|---|
| `otherlode.testkit.port` | `4319` | The port the collector binds. Set it, and `exportUrl`, together. |
| `otherlode.testkit.startup.timeout.seconds` | `15` | How long to wait for the first report. |

Set them with `systemProperty` on the test task. A value that is not a number, or a startup timeout of zero or less, fails the test run with a message naming the property. If the port is taken, the extension fails and names the property to override.

## Write assertions: wait, then ask

The agent reports on a timer, so a hit that just happened has not reached the collector yet. Wait first, then ask. A query that runs before the data arrives gives a wrong answer or throws.

Pick the wait by what you want to know:

- `awaitSettled(timeout)` waits for two reports to arrive after the call. One report can already be mid-flight when your last action finishes and miss its hits. Use it before asking about hit counts.
- `awaitProbe(className, methodName, timeout)` waits until the collector has seen the method at all. Use it before the first query about a class that loads late.
- `awaitEndpoint(verb, route, timeout)` does the same for an endpoint.
- `awaitNextFlush(timeout)` waits for one report. It is enough to know the agent is alive.

Each wait throws `TimeoutException` when the timeout passes.

Class names are the dotted binary names. A Kotlin file's top-level functions live in `com.acme.OrdersKt`, a nested class is `com.acme.Outer$Inner`, and a function in a `@file:JvmMultifileClass` file lives in its part class, such as `com.acme.Orders__OrderTotalsKt`, not in the facade.

Asking about a method the collector has never heard of throws `UnknownProbeException`, not `false`. The message says whether the class was skipped, declared but never loaded, or never mentioned. A route nobody registered throws `UnknownEndpointException` the same way. This keeps "never ran" apart from "the agent never saw it".

This Kotlin test checks a method, an endpoint and the full list of never-hit methods:

```kotlin
@ExtendWith(OtherlodeExtension::class)
class OrderFlowTest {
	@Test
	fun `the order flow reaches checkout and no more`(collector: OtherlodeTestCollector) {
		val timeout = Duration.ofSeconds(10)

		client.post("/checkout", order)

		collector.awaitEndpoint("POST", "/checkout", timeout)
		collector.awaitSettled(timeout)

		assertTrue(collector.wasCalled("POST", "/checkout"))
		assertTrue(collector.callCount("POST", "/checkout") >= 1)
		assertTrue(collector.hitCount("com.acme.OrderService", "checkout") >= 1)

		val untouched = collector.neverHit().map { "${it.className}.${it.methodName}" }
		assertTrue("com.acme.OrderService.applyLegacyPromo" in untouched)
	}
}
```

The same checks in Java, with a collector you start yourself instead of the extension:

```java
import dev.otherlode.testkit.OtherlodeTestCollector;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFlowTest {
	@Test
	void checkoutRuns() throws Exception {
		try (OtherlodeTestCollector collector = OtherlodeTestCollector.start()) {
			// Launch the app with
			// -javaagent:...=includePackages=com.acme,flushIntervalSeconds=1,exportUrl=<collector.getExportUrl()>
			Duration timeout = Duration.ofSeconds(10);

			collector.awaitProbe("com.acme.OrderService", "checkout", timeout);
			collector.awaitSettled(timeout);

			assertTrue(collector.wasHit("com.acme.OrderService", "checkout"));
			assertFalse(collector.wasHit("com.acme.OrderService", "applyLegacyPromo"));
			assertTrue(collector.neverHit().stream()
					.anyMatch(p -> p.getMethodName().equals("applyLegacyPromo")));
		}
	}
}
```

From Java, `@ExtendWith(OtherlodeExtension.class)` injects the collector as a parameter in the same way.

Counts are cumulative across every test class in the JVM, because one collector serves all of them. A test that asserts a count compares it with a reading taken before the action:

```kotlin
val before = collector.hitCount("com.acme.OrderService", "checkout")
OrderService().checkout(order)
collector.awaitSettled(timeout)
assertEquals(before + 1, collector.hitCount("com.acme.OrderService", "checkout"))
```

The agent increments counts without locking, so two threads racing on one probe can lose a count. Assert `>=` on code that runs concurrently.

For the other findings the collector computes (never-initialized classes, unreached clusters, optional parameters, dependencies), see [the testkit reference](testkit-api).

## Start a collector by hand for a child JVM

The extension's collector accepts payloads from one agent only. A test that launches a second JVM with the agent, such as a forked process running your service, needs a collector of its own. Start one on a free port, pass its URL to the child, and close it when the test ends:

```kotlin
OtherlodeTestCollector.start().use { collector ->
	val child = ProcessBuilder(
		"java",
		"-javaagent:/path/to/otherlode-agent-0.1.0.jar=" +
			"includePackages=com.acme,flushIntervalSeconds=1,exportUrl=${collector.exportUrl}",
		"-jar", "app.jar",
	).inheritIO().start()

	collector.awaitProbe("com.acme.OrderService", "checkout", Duration.ofSeconds(30))
	// Drive the child, then:
	collector.awaitSettled(Duration.ofSeconds(10))
	assertTrue(collector.wasHit("com.acme.OrderService", "checkout"))
	child.destroy()
}
```

`OtherlodeTestCollector.start(port)` binds a fixed port. With no argument it picks any free one, and `exportUrl` reports it.

## Run forks in parallel

With `maxParallelForks` above 1, each fork is its own JVM, and every fork's agent posts to the same port. The extension's collector holds that port in one fork only, and it accepts one agent. Payloads from any other agent are rejected, and every query in the run then throws an exception that lists the reasons.

Gradle gives every fork of a test task the same `-javaagent` arguments, so the forks cannot each have their own port. Run the tests that use the testkit with `maxParallelForks = 1`. To keep the rest of your tests parallel, tag the testkit tests with `@Tag("otherlode")` and give them a task of their own:

```kotlin
tasks.test {
	useJUnitPlatform { excludeTags("otherlode") }
}

val agentTest by tasks.registering(Test::class) {
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("otherlode") }
	maxParallelForks = 1
	jvmArgumentProviders.add(CommandLineArgumentProvider {
		listOf("-javaagent:${otherlodeAgent.singleFile}=includePackages=com.acme,flushIntervalSeconds=1,exportUrl=http://localhost:4319")
	})
}

tasks.check { dependsOn(agentTest) }
```

The agent then runs only in `agentTest`, so move the `-javaagent` argument off `tasks.test`.

Anything else that posts to the collector's port is rejected the same way.

## Run alongside JaCoCo

JaCoCo and the testkit can share a test task. The order of the two `-javaagent` flags does not matter. Gradle's `jacoco` plugin adds its agent after your `jvmArgs`, and that order is fine. The agent runs after every agent that is not retransformation-capable, JaCoCo's included, and reads each class's shape from its class file, so neither tool's results change.

An agent that adds or reorders a method's conditional jumps before this one does, such as AspectJ's weaver, leaves that method with its entry probe but no branch probes. See [compatibility](compatibility) for the other agents.

## Debug a test with the agent attached

HotSwap does not work on a woven class whose code you changed. If you debug a test from your IDE with the agent on the task and edit a method mid-session, the JVM refuses the redefinition, the IDE reports that HotSwap failed, and the old code keeps running. The agent refuses on purpose, so its report never describes code that is not the code running.

Restart the test, or debug with a task that leaves the agent off. A redefinition that leaves the class file unchanged, as a remote debugger or an APM agent does, is not affected.

## Name the tests that call your code

To have the server name the tests that call a method in production, run the agent in your test JVM with `testRun=true`. That is a different setup from this page. See [name the tests that call your code](test-runs).

## Handle new enum values

Result types and enums only grow in a minor release. A Kotlin `when` over `ProbeKind`, `GeneratedBy`, `RoutineKind`, `UnreadShape`, `OutsideCallerKind`, `RootKind`, `ClassFinding`, `EndpointDiscoverySource`, `DependencyDiscoverySource` or `DependencyUsage` needs an `else` branch, so a new value does not break your build.
