---
status: accepted, amends ADR 0070
---

# A run says when its class listing missed a root

Decided on 2026-11-02, after the claims-against-evidence-order audit that followed the soak test's smoke run (server ADR 0087).

ADR 0070's listing reads the classpath, `Class-Path` attributes and a Spring Boot application's `BOOT-INF` or `WEB-INF` classes and libraries. A JVM can load classes from roots it does not read: a war that an external Tomcat deploys, an OSGi bundle, a plugin folder, Spring Boot's `loader.path`. The server claims a class removed when a listing named it before and no live run's complete listing names it now (server ADR 0066). Its guard against a blind spot is by name only: an earlier listing named the class. So a dead class whose jar moves into a root the listing cannot read keeps its name, stays covered by the include rules, and reads removed until the run whose listing named it expires. ADR 0070's consequence, "a blind spot never turns into a false removal", does not hold for a class that moves.

## Decision

- **The agent watches where included classes load from.** It keeps the roots its listing read: each jar, each class folder, and each nested jar or class folder inside a Spring Boot application, by the location the JVM's loaders give a class from it. When a class the include rules admit loads from a code source that is none of those roots, the run's listing is blind.
- **A manifest says so.** `ProbeManifest.class_listing_blind` is true on every manifest the run sends once its listing is blind, and false before. It never goes back to false. A run whose classpath the listing could not read whole never sets it.
- **A class with no code source, or a runtime-generated class, says nothing.** Neither has a root to compare, and a proxy or a lambda's class takes its loader's name, not a file's.
- **The check runs on the transform path the agent already takes for every class,** compares the class's code source location with a set of strings, and does it once per distinct location, so a root that loads a thousand classes is compared once.

## Considered options

- **The server alone treats a run that loaded an included class its listing lacks as blind.** Rejected: it notices only the moved class itself, and only once it loads. A sibling jar in the same plugin folder that loads first proves the folder is unread, which only the code source shows.
- **The listing reads more roots.** Rejected as the whole answer: roots a container or a framework adds at run time cannot all be known at startup. Reading `loader.path` stays a STATUS item.

## Consequences

- A run whose JVM loads code from a root the listing did not read makes its environment remove no class, and the server says nothing is removed there rather than something false. A deployment that loads every class from read roots is unchanged.
- A root whose classes never load during the run stays undetected. A jar of only dead classes in a folder where nothing else loads can still read removed. That residue is recorded in STATUS.
- The flag reaches the server one flush after the first class loads from the root. Server ADR 0091 holds removal for a run that has not settled, which covers the classes that load before the first flush.

## Settled while building it, 2026-11-02

- **A class with no code source is judged by its name.** A loader that defines classes with no protection domain gives them none, and many plugin and hot-reload loaders do, so the decision's "says nothing" let a dead class moved into such a plugin read removed. The listing's names are already in hand: an included class with no code source whose name the listing lacks makes the run blind. A copy that a framework defines again, such as Spring's `OverridingClassLoader`, keeps a name the listing has.
- **Classes the JVM loaded before the watch was registered are judged when the listing completes,** from the JVM's list of loaded classes. Another agent's `premain` can load application classes first.
- **What the watch could not remember makes the run blind.** It keeps up to 4096 locations, and as many classes with no code source, seen before the listing completes. A run that sees more than that before then is blind, since something went unjudged.
- **A location is compared once, and the include check runs for every class.** A location whose classes the include rules all leave out is never remembered, so its classes pay the prefix check each time.
- **The INFO line is logged outside the watch's lock,** since logging can load classes.
- **The watch keeps the listed names for the life of the run,** to judge a class with no code source that loads later. That is one set of the names the include rules admit, the size of the listing.
- **The JDK's classes say nothing.** A class of a named module of the boot layer counts as the JDK's when the boot or platform loader defines it, or its module's name starts with `java.` or `jdk.` and its version is `java.base`'s, and the agent never instruments one. Without this, a broad include rule such as `org` made every run blind through `org.w3c.dom`. An application module in the boot layer, from an upgrade module path or a linked image run with `-cp`, is on the application loader and is judged. The lister refuses `--upgrade-module-path` and `--patch-module`, which put classes into the boot layer's modules, and a JVM without `java.management`, whose runtime bean is the only place `--patch-module` shows after startup. A JDK vendor's extra modules, such as the JetBrains Runtime's JCEF, make a run blind when their classes fall under the include rules.
- **A class the JVM hands over with no name is named from its bytes,** so a loader that defines a class without naming it is still judged.
- **A class the sweep cannot read makes the run blind,** rather than ending the sweep.
- **Some code generators make a run blind.** A generator that defines classes with no code source, such as Jackson Afterburner, gives names the listing lacks. That withholds removals; it invents none.
