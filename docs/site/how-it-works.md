---
title: How the agent works
description: Why the agent counts in the class it instruments, pushes cumulative totals to a collector, and never changes how your classes load, so you can judge whether to run it in production.
order: 140
---

## What it can find that static analysis cannot

A compiler and a static analyser can prove a method unreachable. They cannot tell you that a method is reachable but nobody calls it, that an `if` has only ever gone one way, or that a route your framework serves has never received a request. Nothing in that code is wrong, so nothing flags it. The only evidence is what happened in a running process.

The agent collects that evidence. It counts how often each method runs, which way each conditional goes, and how often each endpoint is matched. It does not decide anything from the counts.

## A zero is an observation, not a proof

A count of zero means the code did not run in the processes the agent watched, over the time it watched them. It does not mean the code cannot run. A quarterly job, a failover path and an error handler for a rare outage all read zero until the day they matter.

So the agent reports raw counts and nothing more. It carries no threshold, no "dead" flag and no confidence score. The collector holds every instance's data over weeks and deploys, so the collector decides how long a path must stay at zero before anyone should act on it. This split is the right one because the agent cannot know any of that. One instance sees one slice of traffic, and a rule baked into the agent would be wrong for half of its adopters. Putting the judgement in the collector also lets it change without redeploying the agent into your services.

The same line decides what the agent is careful about. The agent is built so that missing data never reads as a confident zero. Wherever it cannot count something, it says so. The sections below return to this.

## Probes are woven in when a class loads

The agent runs as a Java agent, so the JVM hands it each class's bytes just before the class is defined. For each class that matches your include rules, the agent adds small counters, called probes, at the entry of each method and on each outcome of each conditional. Then the JVM defines the changed class, and your code runs as usual.

Include rules are required. With none set, the agent logs an error and turns itself off, because instrumenting everything would spend production CPU on library code that you cannot delete. See [configuration options](configuration) for `includePackages`.

The agent starts only with `-javaagent`. It does not attach to a running JVM. That is a deliberate limit. Attaching later would mean rewriting classes that already loaded, which cannot add the storage a counter needs, cannot see what ran before the attach, and fails on some classes. Attaching late would also miss the wiring and branching your app runs while it boots. Adding one flag to your normal startup is a small, one-time cost for seeing all of it. See [attach the agent](attach).

## Each class owns an array of counts

Every instrumented class gets one array of 64-bit counters, with one slot per probe. A probe hit is a single increment of its slot. There is no hash, no lookup and no lock on the path your code takes.

The increment is not atomic, and that is a choice. Under heavy concurrency two threads can race and one increment is lost, so counts are approximate and can even step backwards between two flushes. What no race can do is take a probe that ran back to zero, because the lowest value an increment writes is one. That is the only property a never-hit claim needs. It holds on 64-bit HotSpot, which makes aligned 64-bit reads and writes atomic, and the agent relies on that and not on the Java memory model. Exact counts would need an atomic operation on every hit, a cost you would pay on your hottest methods to improve a number nobody uses to the last digit.

Slots hold counts, not flags, because a count costs the same to write and says more. "Never hit across 50 instances and ten million requests" is a stronger finding than "not hit yet".

How a probe finds its class's array depends on the class-file version. From Java 11 (class-file version 55) the class gets nothing added except the probe instructions, and each probe fetches the array when it first runs. Below that, the class gets a static field and a small accessor. Both routes exist for one reason. A superclass's initializer can run your class's code before your class has finished initializing, and a probe that read an unfilled field there would throw a `NullPointerException` inside your application. Neither route can.

## Results go to a collector, not to a scrape endpoint

The agent pushes its data to a collector on a fixed interval, 60 seconds by default. It does not open a port for a metrics system to pull from.

A single instance can only say "not hit here yet". A claim that code is dead needs every instance and every deploy over time, and only a central store holds that. Pushing also survives restarts, where a scraped counter would reset with the process.

Prometheus's model was the obvious alternative, and it fits badly. A scrape records a rate you average. This data is set membership, whether each of tens of thousands of method and branch identifiers has ever fired, and that is a high-cardinality problem for a time-series database.

Each instance picks a random offset for its first flush, then flushes at a fixed rate, so a fleet does not call the collector on the same tick. The interval is fixed, not adaptive, because the goal is a picture over time rather than freshness.

Every flush sends an update of the counts that changed, even when nothing changed. The empty update is a heartbeat. Without it a collector cannot tell an instance that is up but idle from one that has crashed or lost its network. A flush also sends descriptions of newly loaded classes (which class, method and line each probe is), only once per class. A class that loads late is described on the flush after it loads. See [what the agent sends](data-sent) for the contents and what happens when the collector is down.

The transport is plain HTTP with a protobuf body, through the JDK's own client. gRPC would add a networking library to every JVM you run it in, with its own risk of version clashes against an app that already uses gRPC, to gain streaming that a request every 60 seconds does not need.

## Cumulative totals make retries and restarts harmless

Each update carries a probe's total since the process started, not the amount it grew by. The collector keeps the larger of the value it holds and the value it receives.

This is the design's central choice. A sender that retries has a hazard: the collector may process a request while the reply is lost, and the agent then sends the same bytes again. If the payload were an increment, the collector would add it twice. A larger-of merge is the same whether a value arrives once, twice or out of order, so the collector needs no deduplication cache and the agent needs no retry queue. The agent only advances its record of what was delivered after the collector confirms a send, so a failed send leaves the record where it was and the next flush reports the live total again.

That also bounds memory. The data is a set of arrays sized by your code, not your traffic, so an unreachable collector cannot make the agent's buffers grow. If the process dies during a long outage, the cost is the counts since the last confirmed flush, at most one interval's worth. Each send retries a handful of times with a growing delay for failures a retry could fix (a connection error, a 5xx, a 408 or a 429), and fails at once on any other 4xx, since the collector has already read the request and refused it. A flush on shutdown, marked as the last one, lets the collector tell an instance that exited cleanly from one that vanished.

Cumulative totals bring one problem, which is a restart. A process that restarts counts from zero, and a larger-of merge would sit at the old, higher total. The agent stops that with a run id, a random value made once at startup and stamped on every payload. A collector keeps each run's data apart and merges within a run only. The instance id defaults to a fresh random value per process, so a restart is normally a new instance anyway. The run id is what keeps results correct when you pin the instance id to something stable, such as a pod name. A restart under a pinned id is then still a new run, and a late update from the old process names the old run and cannot overwrite the new one.

## Shape comes from the class file, probes go in last

Other agents often run in the same JVM. Coverage tools such as JaCoCo and weavers such as AspectJ rewrite classes too, and that causes two problems.

The agent works out what a method is by reading its bytecode: which conditions are the code you wrote, which jumps are compiler plumbing, which methods a compiler generated. If it read the bytecode after JaCoCo had added its own jumps, JaCoCo's output would look like never-hit code you wrote, and the same app would report different results with and without JaCoCo. So the agent reads each class's shape from the class file itself, the bytes the compiler wrote, read through the class's own loader. The result is the same with other agents present as without.

The other problem is the reverse. If this agent wove first, JaCoCo would receive the changed bytes, its checksum of the class would no longer match the class file its report uses, and your coverage report would lose every woven class. Gradle puts a test task's own JVM arguments ahead of the `jacoco` plugin's, so this is the order you would get by default.

The agent solves this by registering its transformers so that the JVM runs them after every transformer that is not marked retransformation-capable. JaCoCo's, AspectJ's and Spring's load-time weaver are not, so the agent always runs after them, whatever order the flags appear in. Its probes are added to the bytes it receives, so another agent's changes survive. Where the received bytes no longer match the class file closely enough to line a method's conditionals up, the agent keeps the method's entry probe and drops that method's branch probes, so you lose a finding and never gain a wrong one. See [compatibility](compatibility) for the supported agents and what to expect from each.

## A woven class behaves like the original

The rule the agent holds itself to is that an instrumented class loads, fails and reflects exactly as the same class would without the agent, apart from counting. A defect in your app should not become the agent's defect, and the agent should not add failures of its own.

Four things follow from the rule.

- The agent keeps the class's original verification frames and writes new ones only at the places it inserts. It does not recompute them. A recomputed frame can load a class the original did not, or skip a load the original needed, and a framework that probes for an optional library by catching the resulting error would see a different answer.
- The agent adds no field or method to classes from Java 11 on, so reflection over your classes returns what it did before.
- A probe never throws into your code. If the agent cannot find a class's array, the probe gets a spare array, a warning is logged once, and that class goes uncounted.
- Tests define and run every woven class from the benchmark code bases and a matrix of class-file versions 45 to 69, beside its unwoven twin, and require that the woven class fail only where the twin fails.

When the agent cannot instrument a class, it skips the class and says so. It logs the skip and sends the class to the collector in a list of skipped classes with a reason. A class it did not manage to instrument is not left to look like code that was never called. A class whose superclass or interface cannot be found is skipped on purpose, because the JVM could not define it either.

The same rule covers a class that was woven but never defined, for example because a dependency was missing at load. The agent holds that class's probes back from the first report until it has evidence the class exists: a count above zero, or the JVM listing it as loaded. A class that never gets that evidence is named as failed to load. That is a deployment problem, not dead code. [Classes](classes) lists these states.

If the agent cannot start safely, it turns itself off completely and sends nothing. It does this when the support class it needs in the bootstrap loader cannot be installed, or when no include rules are set. A missing instance is something a collector shows. An instance that runs but reports zero everywhere would look like a service with no live code.

If a tool such as an IDE swaps a woven class for a changed one, the agent refuses when the class file on disk differs from the one it wove, and the JVM rejects that swap. The class keeps running with its probes. With the class file unchanged, the agent weaves the new bytes against the plan from the first weave and renumbers nothing.

## What this costs you

Each of these choices has a price, and you pay it knowingly.

- The restart. Static attach means a new startup flag and one restart.
- Approximate counts. Use them to ask whether code ran, not how many times it ran, to the last digit.
- Memory outside the Java heap. Because the agent runs after other agents, the JVM keeps a copy of each woven class's received bytes.
- One interval of data lost if a process dies during a collector outage.
- Descriptions sent once. After the collector confirms a class's description, the agent frees it from memory. A collector that loses that description gets it again only when the instance restarts.

See [overhead](overhead) for measured costs.
