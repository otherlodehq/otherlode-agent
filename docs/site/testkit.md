---
title: Test your app against the agent
description: Run the agent in your test JVM, start the testkit's collector, and assert which methods, endpoints and classes your tests exercised.
order: 80
---

The testkit is a collector that runs inside your test JVM. It accepts the agent's real payloads, so the agent behaves as it does in production. You start the collector, point the agent at it, exercise your app, and then ask the collector what it saw.

This page covers the setup and the pattern for an assertion. [The testkit reference](testkit-api) lists every query, wait and exception.

## Put the testkit and the agent on the build

The agent runs from its own `-javaagent` jar. The testkit is a second jar that goes on the test classpath, and it carries no copy of the agent. Both are on Maven Central, as `dev.otherlode:otherlode-agent` and `dev.otherlode:otherlode-testkit`.

Use the same version of both. The testkit's collector rejects a payload from an agent of another version, and the rejection names both versions.

The testkit bundles its own protobuf under `dev.otherlode.testkit.shaded`. Its one dependency is `kotlin-stdlib`, and Kotlin test code needs Kotlin 1.9 or later to compile against it. The extension needs `junit-jupiter-api` on the test classpath, and the rest of the testkit doesn't. The jar's classes are class-file version 61 or lower, so it loads on JDK 17 and later.

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

The extension never attaches the agent for you. JUnit loads test classes, and often the classes under test, before any extension runs, and the agent never weaves a class that loads before it attaches.

Each option does a job:

- `includePackages` is required. Without it the agent logs an ERROR and sends nothing. See [configuration options](configuration) for the rule.
- `flushIntervalSeconds=1` makes the agent report every second. The default is 60 seconds, longer than the extension's 15-second wait for the first report.
- `exportUrl` points at the collector. The extension's collector binds port 4319, the port in the agent's default `exportUrl`. Set both when you change the port, as the next section shows.

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
		collector.awaitSettled(Duration.ofSeconds(30))

		assertTrue(collector.wasHit("com.acme.OrderService", "checkout"))
		assertFalse(collector.wasHit("com.acme.OrderService", "applyLegacyPromo"))
	}
}
```

From Java, `@ExtendWith(OtherlodeExtension.class)` injects the collector in the same way.

The extension starts one collector for the whole test JVM and shares it between test classes. Code that cannot take a parameter reads the same collector from `OtherlodeExtension.collector()`, which throws `IllegalStateException` until a test class with the extension has started.

Before the first test class with the extension runs, the extension waits for a report from the agent. If none arrives in time, the test fails with a message that gives the `-javaagent` flag to add. Two system properties on the test JVM change the extension's behaviour:

| Property | Default | Effect |
|---|---|---|
| `otherlode.testkit.port` | `4319` | The port the collector binds. Change it and `exportUrl` together. |
| `otherlode.testkit.startup.timeout.seconds` | `15` | How many seconds to wait for the first report. |

Set them with `systemProperty` on the test task. A port that is not a number, or a startup timeout that is not a positive whole number, fails the test with a message that names the property. If the port is taken, the test fails with a message that names the property to override.

## Write assertions: wait, then ask

The agent reports on a timer, so a hit that just happened has not reached the collector yet. Wait first, then ask. A query that runs before the data arrives gives a wrong answer or throws.

Pick the wait by what you want to know:

- `awaitSettled(timeout)` waits until every instance that has reported has sent two complete flushes since the call, or has exited with a complete shutdown flush. It also waits while an instance says it is starting or its counts are pending. The agent says `starting` for up to ten flush intervals while the application loads its classes, so the examples on this page use `flushIntervalSeconds=1` and a 30-second timeout. The first of the two can have read its counts before your last action, and the second carries them. Use it before asking about hit counts. An instance that stops without a complete shutdown flush, such as a child JVM you kill with `kill -9`, never settles, and the timeout message names it.
- `awaitProbe(className, methodName, timeout)` waits until the collector has seen the method at all. Use it before the first query about a class that loads late.
- `awaitEndpoint(verb, route, timeout)` does the same for an endpoint.
- `awaitNextFlush(timeout)` waits for one report. It is enough to know the agent is alive.

Each wait throws `TimeoutException` when the timeout passes.

Class names are dotted binary names, such as `com.acme.Outer$Inner` for a nested class and `com.acme.OrdersKt` for the top-level functions in `Orders.kt`. [Class and method names](testkit-api#class-and-method-names) covers `@file:JvmName` and multi-file facades.

Asking about a method the collector has no record of throws `UnknownProbeException`, not `false`. The message says why: the class could not be instrumented, loaded where no transformer saw it, was declared by the static baseline but never loaded, has no method by that name, or was never mentioned. A route that no manifest mentioned throws `UnknownEndpointException` in the same way. So a query never reads "the agent never saw it" as "it never ran".

A query that rests on a zero count can also refuse. A class the JVM's verifier rejects fires no probe. While an instance's agent could not install its hook on `VerifyError`, or counted a `VerifyError` message it could not read, a zero count from that instance proves nothing. Queries that rest on one, such as `neverHit`, `neverCalled`, `alwaysSupplied`, `unreachedClusters`, `neverInitialized` and `neverInstantiated`, then throw `IllegalStateException` and name the instance and the reason. An instance that has pending counts or has sent no delta batch makes them refuse the same way, even after its JVM has exited. An instance that is still starting makes them refuse until it ends or goes silent for three flush intervals. Call `awaitSettled` first. `neverLoaded`, and a dependency query that would mark a site never loaded, refuse in the same cases while the instance is live, since it may still load the class. `unloadedDependencies` refuses in the same cases for an instance that listed the dependency. Queries that read no zero count, such as `wasHit` and `hitCount`, keep answering. [Queries that refuse](testkit-api#queries-that-refuse) lists both groups.

This Kotlin test checks a method, an endpoint and the list of never-hit methods:

```kotlin
@ExtendWith(OtherlodeExtension::class)
class OrderFlowTest {
	@Test
	fun `the order flow reaches checkout and no more`(collector: OtherlodeTestCollector) {
		val timeout = Duration.ofSeconds(30)

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
			Duration timeout = Duration.ofSeconds(30);

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

Counts are cumulative across every test class in the JVM, because one collector serves all of them. To assert a count, compare it with a reading taken before the action:

```kotlin
val before = collector.hitCount("com.acme.OrderService", "checkout")
OrderService().checkout(order)
collector.awaitSettled(timeout)
assertEquals(before + 1, collector.hitCount("com.acme.OrderService", "checkout"))
```

The agent increments counts without locking, so two threads that hit one probe at once can lose a count. Assert `>=` on code that runs concurrently.

For the other findings the collector computes, such as never-initialised classes, unreached clusters, optional parameters and dependencies, see [the testkit reference](testkit-api).

## Start a collector by hand for a child JVM

The extension's collector accepts payloads from one agent instance only. A test that launches a second JVM with the agent, such as a forked process that runs your service, needs a collector of its own. Start one on a free port, pass its URL to the child, and close it when the test ends:

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
	collector.awaitSettled(Duration.ofSeconds(30))
	assertTrue(collector.wasHit("com.acme.OrderService", "checkout"))
	child.destroy()
}
```

`OtherlodeTestCollector.start(port)` binds a fixed port. With no argument it picks a free one, and `exportUrl` gives the URL with that port.

A collector from `start()` accepts every agent instance that posts to it, and its queries add up their hits. It keeps one run per instance ID, though. If you restart a child JVM with a pinned `serviceInstanceId` against the same collector, the restarted child is a second run under that ID, and the collector rejects its payloads. Start a new collector for each launch, or leave `serviceInstanceId` unset so each process gets its own.

## When the collector rejects a payload

Once the collector has rejected a payload, every query and wait throws `IllegalStateException` with a message that starts `this collector rejected` and lists each reason. `rejectedPayloads()` returns the same reasons. The collector rejects a payload, for example, when it comes from an agent of another version, from a second run under one instance ID, from a second instance on the extension's collector, or through a collector that stripped fields. [Rejected payloads](testkit-api#rejected-payloads) lists every reason.

The testkit's collector listens on `localhost` and checks no token. Any process on the same machine that reaches its port can post payloads to it, and the collector rejects an unexpected payload or counts it with the rest. Run the testkit only on machines where you trust the other processes, as you would for the build itself.

## Run forks in parallel

With `maxParallelForks` above 1, each fork is its own JVM, and Gradle gives every fork the same `-javaagent` arguments, so every fork's agent posts to the same port. One fork's extension binds that port, accepts the first agent that posts to it and rejects the others, so its queries throw. The other forks' extensions cannot bind the port, so their tests fail too.

Run the tests that use the testkit with `maxParallelForks = 1`. To keep the rest of your tests parallel, tag the testkit tests with `@Tag("otherlode")` and give them a task of their own:

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

Move the `-javaagent` argument off `tasks.test`, so the agent runs only in `agentTest`.

## Run alongside JaCoCo

JaCoCo and the testkit can share a test task, in either order of the two `-javaagent` flags, and neither tool's results change. An agent that adds or reorders a method's conditional jumps, such as AspectJ's weaver, leaves that method with its entry probe and no branch probes. See [other agents in the same JVM](compatibility#other-agents-in-the-same-jvm).

## Debug a test with the agent attached

HotSwap does not work on a woven class whose code you changed. If you debug a test from your IDE with the agent on the task and edit a method, the JVM refuses the redefinition, the IDE reports that HotSwap failed, and the old code keeps running. Restart the test to run the edited code, or, to use HotSwap, debug with a task that leaves the agent off. See [HotSwap and IDE redefinition](compatibility#hotswap-and-ide-redefinition).

## Name the tests that call your code

To have Otherlode name the tests that call a method in production, run the agent in your test JVM with `testRun=true` and send to Otherlode, not to the testkit. See [name the tests that call your code](test-runs).

## Handle new enum values

Result types and enums only grow in a minor release. A Kotlin `when` over one of the testkit's enums needs an `else` branch, so a new value does not break your build. [Compatibility](testkit-api#compatibility) lists the enums.
