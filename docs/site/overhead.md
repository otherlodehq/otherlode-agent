---
title: Runtime overhead
description: What the agent costs a running service, where the cost falls, and what makes it grow.
order: 120
---

## What the agent costs, in four places

The agent costs something at four moments. A woven method pays a few instructions each time it runs. Each class pays once when the JVM loads it. The process pays a larger share at startup, because class loading, the JIT compiler, and the agent's transform work all compete for the same CPU. And a background thread wakes once per flush interval to encode and send the counts.

Almost all of it scales with one number: how many classes `includePackages` matches. The rest of this page follows that number.

## Measured results

The numbers below come from the project's overhead benchmark, which runs `spring-petclinic-rest` against Postgres under k6, with and without the agent. The agent exports to a real collector, so encoding and sending are in the numbers.

The run used GitHub's `ubuntu-latest` runner (4 vCPU) with PetClinic pinned to one core, 6 repeats per variant, and the JVM heap fixed at 1 GiB, on commit `1332f3f`. The agent skipped no class in either configuration. Each figure is the median of the agent variant against the median of the variant with no agent. A change marked "noise" lies inside the no-agent variant's own spread.

| | Narrow rules (`org.springframework.samples.petclinic`) | Broad rules (`org.springframework`) |
|---|---|---|
| Probes | 1,489 | 99,685 |
| Throughput | -0.8% (noise) | -7.1% |
| CPU per request | +1.0% (noise) | +8.3% |
| p95 / p99 latency | +0.8% / +0.5% (noise) | +6.5% / +7.4% |
| GC pause per 1000 requests | +6.5% | +30.9% |
| Resident set size | +76 MiB (+7.4%) | +303 MiB (+29.5%) |
| Minimum heap used | +10 MiB | +122 MiB |
| Metaspace | +19 MiB | +23 MiB |
| Startup, time to first 200 | 18.3 s to 22.2 s (+21.2%) | 13.5 s to 36.0 s (+166%) |

The narrow startup figure comes from the benchmark's startup-only job, which measures startup alone with 21 repeats per variant, on commit `b42e026` on an AMD EPYC 7763 runner. Every other figure comes from the run above.

Read the table as a bound, not a prediction. PetClinic is one application on one core. The narrow column matches how you should configure the agent, with rules that cover your own code. The broad column shows what you pay for a package like `org.springframework`, which instruments a framework's classes, not only yours.

Two limits on these numbers:

- The benchmark measures steady state. The first flush, which sends the whole manifest, falls in the warmup, so its cost shows in startup and warmup, not in the table.
- The benchmark does not report a time per probe hit, so this page gives no nanosecond figure.

## What a probe costs per call

A method probe is one array increment at the method's first instruction: load the class's `long[]`, add one to a slot. A branch probe is the same increment at a branch target. The increment is not atomic and takes no lock, so two threads can lose a count. The agent accepts that, since a count goes out only once per flush interval and the data answers "has this run, and roughly how often".

The probe allocates nothing. The agent's build fails if a woven call allocates more than the same call without the agent.

Two things can still make a probe cost more than its instructions suggest:

- **Sharing a cache line.** Every thread that runs a method writes the same array element. On a many-core host, a very hot method or endpoint makes every core write one cache line, and each write waits for the others. The benchmark above runs on one core, so it cannot show this cost.
- **Method size.** A branch probe adds about 13 bytes of bytecode, and an entry probe slightly less. HotSpot never JIT-compiles a method over 8000 bytes and stops inlining a hot method over 325 bytes. The agent drops a method's branch probes, keeps its entry probe, and logs a warning when the branch probes would push the method past the 8000-byte limit. A method that does not cross 325 bytes before weaving but does after can lose inlining, and the agent does not guard against that.

## What a class costs at load

When the JVM loads a class that matches `includePackages`, the agent reads its bytes, finds its methods, branches, and call edges, and writes the probes into it. That runs on the thread loading the class, so it adds to the time that class takes to load. It is a one-time cost per class, and a class that never loads never pays it.

Three things drive that cost up:

- **More matched classes.** The cost is roughly linear in matched classes. The broad run above wove about 4,200 classes and 99,685 probes. The narrow run wove about 100 classes and 1,489 probes.
- **Locating class files.** The agent reads each class's file from its own loader, and the supertypes it needs. Inside a Spring Boot fat jar that goes through the nested-jar loader. The agent caches these reads (up to 16 MB, evicting the least recently used) and releases per-loader caches when a loader goes quiet.
- **One core.** On one core, class loading, the JIT, and the weave share a CPU. The broad rules took startup from 13.5 s to 36.0 s on the one-core runner, a larger relative cost than any throughput, CPU or latency row of the table.

Heap follows the same shape. The broad run's minimum heap used was +122 MiB, and its resident set size +303 MiB. Once a class's manifest is delivered, the agent releases that class's locations and keeps only what the next flush needs.

## Startup

Startup is the cost to watch. In the startup-only run, the narrow rules added 3.9 s to an 18.3 s startup on one core (+21.2%), the median of 21 runs per variant. With more cores the cost shrinks, since class loading, the JIT and the weave no longer share one CPU. Startup grows with how many classes your application loads, because the agent checks each loaded class against its rules.

Two pieces of work that would otherwise be startup cost run later:

- The dependency listing, which reads every jar on the classpath to find its identity, starts at the top of the first flush on the export thread, not in `premain`. It reads each nested jar in a fat jar from its central directory, and decompresses only the `pom.properties` files and manifest it needs.
- The first full manifest goes out on the first flush, at a random point inside the first interval.
- The class listing (on by default) runs on its own background thread, named `otherlode-class-listing`, started once at startup and not in `premain`. It reads the file names in each class folder and the central directory of each jar on the classpath. It reads a jar nested in a Spring Boot fat jar the same way, from that jar's central directory, so it decompresses nothing to read entry names. On the benchmark's PetClinic jar, which holds 126 classes of its own and 38,391 in 107 nested jars, a first call to the listing, measured in a JVM of its own on one core of the benchmark runner, took about 180 ms of CPU with the narrow rules and about 190 ms with `org.springframework`, the medians of six nightly runs. Its cost follows the number of jars it reads, not the number of classes your rules admit: on a classpath of 773 jars and about 270,000 classes it stayed near 200 ms on a laptop core. It parses no class file, and it runs once per process. A JVM started with no class path reads the working directory as its class path, and the listing then walks every folder under it, so start the application from its own folder or give it a class path. With `classListingEnabled=false` the thread does not start. See [classes](classes#the-class-listing).

## The flush thread and the network

The agent runs one scheduled thread named `otherlode-export` and two send threads named `otherlode-export-send`, all daemon threads. They do not keep your JVM alive. The class listing runs on one more thread for a short time at startup, and with the static baseline on, another thread scans the classpath. A graceful shutdown starts a short-lived thread for the final flush.

On each flush (every 60 seconds by default, with a random offset for the first one so a fleet does not flush at the same instant), the thread:

- copies each changed class's count array,
- encodes a delta batch, and sends it (a flush with unconfirmed counts sends an empty batch first, and one that catches up ends with another),
- sends any manifest chunks the collector has not confirmed yet,
- walks the loaded classes to count each dependency's loaded classes, and checks for classes no transformer saw. The check for classes that never reached a transformer runs on every tenth flush, about every ten minutes at the default interval.

A timed run of the flush code over 1,195 classes and 22,597 probes, encoding without sending, gave:

| Flush | Time | Allocated |
|---|---|---|
| Steady, a tenth of probes changed | 0.16 ms | 650 KB |
| Empty heartbeat | 0.04 ms | 200 KB |
| First flush, whole manifest | 22 ms | 40 MB |

The allocation is mostly copying the count arrays, about 8 bytes a probe a flush. The loaded-class walk took 0.5 ms against a JVM holding 4,900 classes. The first flush grows with the probe count, and happens once.

On the network, a flush is one POST per payload at most every interval. Payload size follows the number of probes whose counts changed, not total traffic or total probes, because the agent sends deltas. An empty batch still goes out as a heartbeat. Each send retries up to five times with exponential backoff, capped at 30 seconds, and when the collector is down the counts stay in memory and are sent again on the next flush, so memory does not grow while it is unreachable. [What the agent sends](data-sent) lists each payload.

## Opt-in costs

One feature is off by default and costs something when on.

The static baseline (`staticBaselineEnabled=true`) walks the classpath's bytecode for every class under `includePackages`, on a background thread named `otherlode-static-baseline-scan`. It does not run in `premain`, so it does not add to the time before `main`. It does compete with your application for CPU while it runs, and the cost scales with classpath size, not with traffic. In the startup-only run, the narrow rules with the static baseline on took startup from 18.3 s to 23.0 s on one core (+26.1%), against +21.2% with it off. It runs once per process. See [classes](classes) for what it reports and what it cannot see.

## What to do about it

The cost follows matched classes, so the first lever is narrower `includePackages`.

- **Cover your own code and nothing else.** A prefix like `com.acme.orders` instruments your classes. A prefix like `org.springframework` instruments a framework you cannot edit, produces findings you cannot act on, and costs the broad column above. The agent also refuses to start with no include rules at all. See [configuration options](configuration).
- **Use `excludePackages`** to remove generated or hot utility packages from a broad include.
- **Watch startup on a small instance.** If your service runs on one or two CPUs, measure its time to first request with and without the agent, as the benchmark does. Startup grows most there.
- **Leave the static baseline off** unless you need to know about classes that never load. Turn it on for a deliberate run, not on every instance.
- **Lengthen `flushIntervalSeconds`** if you want fewer wakeups. The cost of a steady flush is small, so this changes little, and a longer interval delays what Otherlode sees.

To measure your own service, compare time to first request, throughput, CPU per request, and live heap with and without `-javaagent`. Compare per-request figures, not totals, when the service is saturated: a variant that serves more requests also allocates and collects more in total.
