# Changelog

All notable changes to the agent and the testkit, which share a version, are recorded here in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

`scripts/release.sh` and the release workflow both require a `## [X.Y.Z]` section with a body for each release.
Keep link reference definitions (`[0.1.0]: https://...`) at the bottom of the file: the first one ends
the section being read.

## [Unreleased]

## [0.1.0] - 2026-11-01

The first release of the Otherlode agent and its testkit. The agent is a Java agent that counts which methods, branches and endpoints your application runs, and sends the counts to an Otherlode collector. Otherlode then shows the code your traffic never reaches. The docs are at https://otherlode.dev/docs.

### Added

- **The agent jar**, `dev.otherlode:otherlode-agent` on Maven Central. This release also attaches the jar, its signature and `SHA256SUMS`. It loads on JDK 17 and later and attaches with `-javaagent`. `includePackages` is the one option you must set.
- **Method and branch probes.** As each class your include rules cover loads, the agent probes every concrete method and every outcome of each branch, and counts each hit. A class it cannot weave safely runs unwoven and is reported as skipped.
- **Generated code is marked, not counted as yours.** The agent recognises code that kotlinc, javac, scalac and the Scala 3 compiler generate, kotlinx.serialization's output, and the methods Hibernate, Ebean, EclipseLink and OpenJPA add to entities. It was checked against kotlinc 1.9 to 2.4, javac 17, 21 and 25, scalac 2.12 and 2.13, and Scala 3.3.3 to 3.10.0. Code from another release that looks generated is reported as an unread shape, never as a finding.
- **Endpoints.** Modules for Spring MVC and Spring WebFlux (Spring Framework 5.3 to 7), Javalin 6 and 7, Micronaut 3 to 5, Vert.x Web 4.5 and 5, Ktor 2 and 3, JAX-RS, plain servlets and the JDK `HttpServer` declare each route and count its requests. A route bridge, off by default, counts the routes OpenTelemetry's instrumentation resolved, for a framework with no module. Framework markers report a framework's own routing that no module counts.
- **Classes.** The agent reports each class that loaded, failed to load or failed to initialise. With `staticBaselineEnabled=true` it also sends a static baseline of every class your rules cover, so Otherlode can name classes that never loaded. The class listing, on by default, names the classes your rules cover on the classpath.
- **Call graph and dependencies.** Call edges between your classes let Otherlode group never-hit code into unreached clusters. The agent lists the jars on the classpath and records which of your classes reference each, so Otherlode can name dependencies your code never uses.
- **Optional parameters.** Kotlin functions with default arguments get one omission probe per optional parameter, so Otherlode can name a parameter every call supplies.
- **Test runs.** With `testRun=true` in a test JVM, the agent records which tests call your code, and Otherlode keeps the run out of every finding.
- **The testkit**, `dev.otherlode:otherlode-testkit` on Maven Central at the same version. It is a collector for JUnit 5 tests, so a test can assert what code it reached and what it never hit.
- **Configuration** from the `-javaagent` argument string, system properties or environment variables. OpenTelemetry's service name, version and environment settings fill in what you leave unset.
- **Export** over HTTP and protobuf to a collector, `http://localhost:4319` by default, every 60 seconds and once more at shutdown. Counts are cumulative and stay in memory while the collector cannot be reached.

[Unreleased]: https://github.com/otherlodehq/otherlode-agent/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/otherlodehq/otherlode-agent/releases/tag/v0.1.0
