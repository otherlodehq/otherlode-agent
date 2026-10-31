---
status: accepted, amended by 0070
---

# The static baseline is an opt-in classpath scan sent as its own chunked message

Every other tier is reactive: a class gets a manifest entry only once it loads. A class that never loads during the observation window is indistinguishable on the wire from one that does not exist. The static baseline is a second, load-independent inventory: a scan of `java.class.path` by bytecode reading through ByteBuddy's `TypePool`, sent as `StaticBaseline` so a collector can diff it against the reactive manifest. A class in the baseline that never appears in any manifest was never even constructed.

The scan is off by default (`staticBaselineEnabled=false`). It runs once per process on a daemon thread, so `premain` latency stays independent of classpath size. It reports classes and methods but not branches, since a branch cannot be counted correctly without the live control-flow context the rewriter has. It recognises `BOOT-INF/classes` and `WEB-INF/classes` as nested roots and does not open the jars under `BOOT-INF/lib` or `WEB-INF/lib`: dependency code is outside the tool's purpose.

## Considered options

- Extending `ProbeManifest`. Rejected: the manifest's incremental delivery is built for a list that grows as classes register. The baseline is computed once, possibly large, and sent as a snapshot. A separate message keeps the change additive.
- One POST for the whole scan. Rejected once the exporter stopped retrying a 413: one oversized POST would lose the baseline for the life of the process. `StaticBaselineChunker` packs whole classes into chunks of about 20000 entries. Every chunk carries `chunk_index` and `chunk_count`, and a collector may only diff a scan it holds completely.

## Consequences

- Three buckets sit beside the declared list: statically unsafe (the same `@Target` check as 0007, run against unloaded bytecode), unreadable, and unprobed (an interface with only abstract methods, which the dynamic tier never registers and which would otherwise read as never loaded).
- The scan is blind to code whose location another system decides at runtime: a WAR discovered by Tomcat from `server.xml`, an OSGi bundle, a plugin directory. The reactive tier has no such blind spot, since the JVM invokes `addTransformer` for every class from every loader. Any class that registers dynamically without having appeared in the scan is proof of a blind spot. `StaticBaselinePublisher` logs a warning per such class, sweeping classes already loaded when the scan finishes as well as those loaded later. Classifying that finding belongs to the collector (0015).
- In a Spring Boot fat jar the safety check cannot resolve annotation types packed under `BOOT-INF/lib`, and `TypePool` drops an unresolvable annotation silently, so a `@file:JvmName` class there is declared rather than marked unsafe. Only the label is lost: such a class always appears in the manifest's skipped list once it loads, so it can never produce a false "never loaded".
- The scan runs once. A plugin system loading jars later needs a restart to be captured, the same restart-to-adopt story as 0013.
- A chunk the collector does not confirm is kept and sent again, in order, one per flush and with one attempt each, after a flush whose own sends the collector confirmed, so a collector that starts after the scan ends (the testkit's, bound in `beforeAll`) still receives the whole baseline. A resent chunk is harmless: a collector keeps received chunks by index. A refusal of the content (400, 413, 422) drops the scan for the process; any other refusal keeps it. A baseline whose first send failed at chunk k of n is complete after n − k more confirmed flushes. Amended 2026-10-03 in a review; the first cut sent once and gave up.

Amended 2026-10-07 in the grill on the customer docs round's follow-ups: a 413 keeps the scan, as a 5xx does, and only a 400 or 422 drops it. A 413 usually comes from a reverse proxy whose limit its operator can raise while the process runs, and delta batches recover from one then (ADR 0009); dropping the scan would leave a restart as the only way to get it. The chunks are already held while any other refusal is retried, so keeping them adds no memory.

Amended 2026-10-14 by ADR 0070: a scan carries the include and exclude rules it applied, as the class listing does, so the server can tell a class outside them from one that is gone.

Amended 2026-10-24 in a grilling session after a security audit, on two points. The scan keeps only the first copy of a class name in the JVM's search order, and walks the jars a manifest `Class-Path` names depth first, as `URLClassPath` searches them. A later copy under the same name never loads, so declaring it could make a loaded class read as never loaded. Also, a chunk is bounded by its encoded bytes and its element count as well as its entries, and a class that alone breaks a bound drops the scan for the process (ADR 0075).

Amended 2026-10-31, from the first-copy change's follow-ups: the scan builds the whole search order before it reads a class. Every flat entry comes first, in classpath order, and then the `BOOT-INF/classes/` and `WEB-INF/classes/` folders, since Spring Boot's launcher asks its parent loader first. A class a multi-release jar holds only under a `META-INF/versions/N/` the running JVM reads takes its name, and is not declared. A jar whose manifest cannot be read is left out with its `Class-Path`. A `Class-Path` is read through the same `classPathEntries` the class listing uses. A cross-class read takes the copy the referencing class's loader finds first. `STATUS.md` lists where the walk still differs from the JVM's.
