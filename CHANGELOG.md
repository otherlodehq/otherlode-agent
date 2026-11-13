# Changelog

This file records every notable change to the agent and the testkit, which share a version, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

`scripts/release.sh` and the release workflow both require a `## [X.Y.Z]` section with a body for each release.
Keep link reference definitions (`[0.1.0]: https://...`) at the bottom of the file, since the first one ends
the section that the release tooling reads.

## [Unreleased]

## [0.1.0] - 2026-11-13

The first release of the Otherlode agent and its testkit. The agent counts which methods, branches and endpoints your JVM application runs and sends the counts to Otherlode, straight or through an Otherlode collector, so Otherlode can show the code your traffic never reaches. The docs are at https://otherlode.dev/docs.

### Added

- **The agent**, `dev.otherlode:otherlode-agent` on Maven Central. It attaches with `-javaagent` on JDK 17 or later. `includePackages` is the one option you must set.
- **Methods and branches.** It counts each method body you wrote and each outcome of your own conditionals, in Java, Kotlin and Scala. It marks code that a compiler or a JPA enhancer generated, such as Kotlin's delegation and `@JvmStatic` forwarders, and does not count it as yours. It reports a shape it hasn't read as unread, never as dead.
- **Endpoints** for Spring MVC, Spring WebFlux, Javalin, Micronaut, Vert.x Web, Ktor, JAX-RS, servlets and the JDK `HttpServer`.
- **Classes, call graph and dependencies.** It reports which classes loaded or failed to, plus call edges and the jars each class references. From these, Otherlode groups never-hit code into clusters and names dependencies your code never uses. With `staticBaselineEnabled=true` the agent also sends a baseline, so Otherlode can name classes that never loaded.
- **Test runs.** With `testRun=true` a run records which tests reached your code and stays out of every finding.
- **The testkit**, `dev.otherlode:otherlode-testkit`, a JUnit 5 collector for asserting what a test reached.
- **Export** every 60 seconds and at a graceful shutdown, to Otherlode's ingest with an ingest key or to a collector, by default at `http://localhost:4319`. Only a `202` counts as stored. Counts stay in memory while the receiver is down. The agent sends names and string literals from your code in clear. A collector can redact them. With a key set, the agent refuses a plain `http` URL to Otherlode.
- **Native heap trim.** On Linux with glibc, the agent asks the JVM once an hour to return the native memory it has freed. `trimNativeHeapIntervalMinutes` sets the interval, and `0` turns it off.
- **Beside other agents.** A class it can't weave safely runs unwoven, and the agent reports it. It works alongside JaCoCo, OpenTelemetry's agent and APM agents that retransform classes.

[Unreleased]: https://github.com/otherlodehq/otherlode-agent/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/otherlodehq/otherlode-agent/releases/tag/v0.1.0
