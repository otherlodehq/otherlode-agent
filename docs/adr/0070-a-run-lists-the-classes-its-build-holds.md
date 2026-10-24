---
status: accepted
---

# A run lists the classes its build holds

Decided on 2026-10-14 in a grilling session that spanned the agent, the collector and the server. Server ADR 0066 records how the server uses what this record sends.

A customer who deletes a class that Otherlode called dead expects it to leave the findings once the new build runs. Nothing on the wire says what a build holds. A probe manifest names only the classes that loaded, so a class that is missing from a new run's manifest may be deleted, or it may simply not have loaded yet. The static baseline names every class, but it parses each class file, its cost grows with the classpath, and it is off by default (ADR 0014). `serviceVersion` is optional, and two builds can share one version.

## Decision

- **A class listing.** Once per process, on a background thread, the agent lists the names of the classes on the classpath that pass the include and exclude rules. It reads jar central directories and class folders and parses no class file. It reads the same roots as the baseline: each `java.class.path` entry, the jars that `Class-Path` attributes name, and `BOOT-INF/classes` and `WEB-INF/classes`. It also opens the jars under `BOOT-INF/lib` and `WEB-INF/lib`, since a multi-module application keeps its own classes there. The include rules filter those jars like any other root.
- **Its own message and path.** `ClassListing` is sent to `/v1/otherlode/class-listing`. Like the baseline, it is sent in chunks of about 20,000 names, each with `chunk_index` and `chunk_count`. A listing is complete only once every chunk is held. A chunk the collector does not confirm is sent again under ADR 0014's rules.
- **It carries the rules it applied.** The listing carries the include and exclude rules it was filtered by, so a reader can tell a class outside them from a class that is gone. The static baseline gains the same field.
- **On by default.** The setting is `classListingEnabled`, `otherlode.class.listing.enabled` or `OTHERLODE_CLASS_LISTING_ENABLED`, and it defaults to `true`. With it off, the server can never prove a run's code is gone.
- **The flush interval on the resource.** `ResourceAttributes` gains `flush_interval_seconds`. The server needs it to tell a run that was killed without its final flush from one that is quiet between flushes. The interval is set once per process, like the other resource fields.

The listing holds names only. It carries no per-class hash. A name is enough to prove a class is gone. A changed hash would not prove a method is gone, since the server cannot know a class's methods without its record or a baseline.

## Considered options

- **The static baseline on by default.** Rejected: it parses every class file, and the cost grows with the classpath. Names are all the server needs to prove that a class is gone.
- **A listing mode of `StaticBaseline`.** Rejected: a declared class needs at least one method to probe. A baseline with method-less classes would make every reader tell two kinds of scan apart.
- **Fields on `ProbeManifest`.** Rejected: the manifest goes out on every flush. A large one-off list would ride on a payload built for a list that grows as classes load. ADR 0014 rejected the same idea for the baseline.
- **A build digest that the agent computes and sends.** Rejected: the server can compute a digest over the listing it stores, as it does for class records (server ADR 0061). The agent reads no response body, so a digest on the wire would not let it skip a send.
- **`serviceVersion` as the build's identity.** Rejected: it is optional, the customer sets it, and two builds can share one version.
- **Per-class CRCs from the central directory.** Rejected: they change no outcome. See the paragraph above the options.

## Consequences

- The listing finds classes only where the baseline looks, plus nested lib jars. It does not see a WAR that Tomcat deploys, an OSGi bundle or a plugin folder. The server accepts a class's absence only when an earlier listing named that class, so a blind spot never turns into a false removal.
- A run sends one more payload at startup. The `overhead` page states its cost once it is measured.
- The new message and fields are additive (`buf breaking`). The collector gets a route for the new path and a bindings bump, since redaction strips a field the bindings do not know.
- The `configuration`, `data-sent`, `overhead` and `classes` pages change in the commit that ships the listing.

## Settled while building it, 2026-10-14

- **A listing with a hole is never sent.** When a root, jar or nested jar the agent reads fails to open or read, the run sends no listing and logs a WARNING that names the file. The server reads a class's absence from a complete listing as proof the build lacks it, so a partial listing would turn an unreadable jar into removed code. The blind spots in the consequences above are different: the agent never reads those places, and the server's coverage check keeps them from proving anything.
- **The include rules travel as the agent applied them**, after configuration parsing trims each entry, drops a trailing `.` and drops entries with `*` or `/`. A prefix covers a name that equals it or continues it with `.` or `$`, as `TypeMatchPolicy` matches.
- **The listing reads the classpath the way the system class loader does.** An entry of `java.class.path` is read through its real path, so `Class-Path` resolves against a symbolic link's target. `Class-Path` splits on the whitespace the JDK splits on and accepts what `URL` accepts. A jar that names an agent is read like any other, since the system loader loads it; the include rules leave out this agent's own classes. A directory that holds an unpacked Spring Boot application is read by the launcher's layout. A review found each of these as a way to send a complete listing that missed a class the JVM loads.
- **Some launches send no listing.** An empty `java.class.path` or one with an empty element, which the JVM reads as the working directory, and a JVM with a module path or a main module, which the listing does not read, each give one WARNING and no listing.
- **A multi-release jar's versioned-only class is listed** by its path after `META-INF/versions/N/`, whatever the jar's manifest or the runtime version says. Listing a class the JVM would not load only keeps code from being called removed.

Amended 2026-10-24 by ADR 0075: a listing chunk is also bounded by its encoded bytes, so long class names cannot build a chunk the collector refuses.
