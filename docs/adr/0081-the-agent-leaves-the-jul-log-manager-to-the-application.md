---
status: accepted
---

# The agent leaves the java.util.logging LogManager to the application

Decided on 2026-10-31 in the second pre-release review.

Attaching the agent fixed the JVM's `java.util.logging.LogManager` to the JDK's default before the application's `main` ran, even with `enabled=false`. JBoss Modules, which WildFly and JBoss EAP start through, sets `java.util.logging.manager` in its own `main` and then asks for the manager. It got the default, printed "Failed to load the specified log manager class", and WildFly's logging subsystem refused to start (WFLYLOG0078). A launcher that picks its manager in `main` is broken the same way.

Two things created the manager early:

- **The agent's own loggers.** Each agent class asked `System.getLogger` for a logger, many in a field set at class initialisation. For a caller outside the JDK's system modules, the JDK hands the request to its `java.util.logging` provider, which calls `LogManager.getLogManager()`. For a class the boot loader defines, the JDK hands out a surrogate logger instead. It prints INFO and above to standard error and switches to the real backend once something else creates the manager.
- **Shaded protobuf.** `CodedOutputStream`'s static initialiser calls `java.util.logging.Logger.getLogger`, which creates the manager. It runs on the class-listing thread and raced `main`.

## Decision

- **The agent's loggers come from the boot loader.** The bootstrap jar holds `OtherlodeLoggers`, whose `get` calls `System.getLogger`, so the JDK treats each agent logger as a system logger. Every agent logger is created lazily, at its first use. A logger created after the bootstrap jar is on the boot class path is the boot loader's own. One created before is a wrapper that switches to it once the jar is there.
- **Before the bootstrap jar is installed, a line goes to standard error** in the default format of the JDK's console logger, at INFO and above, with `java.util.logging.SimpleFormatter.format` honoured as the surrogate logger honours it. That covers the disabled line, every refusal, a second agent's warning and an install failure.
- **A disabled or refused start does not install the bootstrap jar.** `enabled=false` leaves the JVM as it found it: no temporary jar, no boot class path entry, and no CDS warning from the JVM.
- **`java.util.logging.Logger` is relocated in the agent jar** to a stub that logs through `OtherlodeLoggers`. `verifyAgentJar` fails when any class in the agent jar names `java/util/logging/Logger`.
- **A source test refuses `System.getLogger`** in the agent's main sources outside the bootstrap module.

Proved on JDK 17, 21 and 25 with a forked JVM whose `main` sets `java.util.logging.manager` and asks for the manager: a normal start, `enabled=false` and a refused start each leave the application's manager in place. The startup line reaches standard error, and a line logged after `main` reaches a handler on `dev.otherlode` with its real source class.

## Considered options

- **An agent logger of its own that always writes to standard error**, as the OpenTelemetry Java agent does. Rejected: the agent's lines could not reach the application's logging, which the docs promise once logging is configured.
- **Installing the bootstrap jar first on every start**, so even the disabled line has a boot-loader logger. Rejected: a disabled agent would append to the boot class path, and the JVM then drops CDS for application classes and prints a warning.

## Consequences

- With `-Djava.util.logging.config.file` or `-Djava.util.logging.config.class` on the command line, the JDK does not use surrogate loggers, so the agent's first line after install creates the manager. A launcher that picks its manager in `main` then gets the default again, as it would from the JDK's own platform loggers. A manager named on the command line with `-Djava.util.logging.manager` is the right class in that case. The troubleshooting page says so where it suggests a config file.
- With a `System.LoggerFinder` bridge such as `log4j-jpl` on the class path, the bridge decides when its backend starts. That case is not tested.
- A line written before the bootstrap jar is installed ignores a level set for `dev.otherlode` in the application's logging.
