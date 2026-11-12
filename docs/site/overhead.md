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
| Startup, time to first 200 | 16.8 s to 21.0 s (+24.6%) | 13.5 s to 36.0 s (+166%) |

The narrow startup figure comes from the benchmark's startup-only job, which measures startup alone with 21 repeats per variant, on commit `9b1f44bb` on an AMD EPYC 9V74 runner. Every other figure comes from the run above.

Read the table as a bound, not a prediction. PetClinic is one application on one core. The narrow column matches how you should configure the agent, with rules that cover your own code. The broad column shows what you pay for a package like `org.springframework`, which instruments a framework's classes, not only yours.

Three limits on these numbers:

- Each run lasts minutes, so the resident set size is the figure after warmup, not after days. See [memory over days](#memory-over-days).
- The benchmark measures steady state. The first flush, which sends the whole manifest, falls in the warmup, so its cost shows in startup and warmup, not in the table.
- The benchmark does not report a time per probe hit, so this page gives no nanosecond figure.

## Against the OpenTelemetry Java agent

The same benchmark also runs the OpenTelemetry Java agent 2.32.0 as a third variant, so you can compare Otherlode's cost with an agent many teams already run. OpenTelemetry ran on its defaults: every instrumentation on, every request traced, and spans, metrics and logs exported over OTLP to a receiver that answered each request and discarded it. Otherlode ran with the narrow rules above and exported to a real collector. Each figure is the variant's median against the median with no agent, on commit `9bb2c3a`, with 6 repeats per variant.

| | Otherlode | OpenTelemetry |
|---|---|---|
| Throughput | +2.0% (noise) | -16.0% |
| CPU per request | -1.8% (noise) | +20.6% |
| p99 latency | -2.6% (noise) | +24.9% |
| p99 latency at a fixed rate | +0.5% (noise) | +37.5% |
| Allocation per request | +1.1% (noise) | +29.3% |
| GC pause per 1000 requests | +3.5% | +60.8% |
| Minimum heap used | +10 MiB | +24 MiB |
| Resident set size | +75 MiB (+7.3%) | +126 MiB (+12.3%) |
| Metaspace | +22 MiB | +28 MiB |
| Startup, time to first 200 | +2.5 s (+19.7%) | +7.1 s (+55.7%) |

The fixed-rate row comes from a second run, on commit `9955112`, that held the load at a quarter of the rate PetClinic reached with no agent. PetClinic then had spare CPU, so the latency is the time an agent adds to a request, not time spent in a queue. Startup comes from the startup-only job, with 21 repeats per variant.

Per request, Otherlode's cost lies inside the no-agent variant's own spread, apart from 3.5% more GC pause. What it adds does not grow with traffic: memory, six or seven threads, and startup. The [benchmark's write-up](https://github.com/otherlodehq/otherlode-agent/blob/master/benchmark-overhead/OTEL-COMPARISON.md) has the method and every figure with its spread.

## What a probe costs per call

A method probe is one array increment at the method's first instruction: load the class's `long[]` and add one to a slot. A branch probe is the same increment at a branch target. The increment is not atomic and takes no lock, so two threads that hit the same probe at once can lose a count. A count is exact enough to tell whether code ran and roughly how often.

The probe allocates nothing. The agent's build fails if a woven call allocates more than the same call without the agent.

Two things can still make a probe cost more than its instructions suggest:

- **Sharing a cache line.** Every thread that runs a method writes the same array element. On a many-core host, a very hot method or endpoint makes every core write one cache line, and each write waits for the others. The benchmark above runs on one core, so it cannot show this cost.
- **Method size.** A branch probe adds about 13 bytes of bytecode, and an entry probe slightly less. HotSpot never JIT-compiles a method over 8000 bytes and stops inlining a hot method over 325 bytes. When the branch probes would push a method past 8000 bytes, the agent drops them, keeps its entry probe, and logs a warning. A method that crosses 325 bytes only after weaving can lose inlining, and the agent does not guard against that.

## What a class costs at load

When the JVM loads a class that matches `includePackages`, the agent reads its bytes, finds its methods, branches, and call edges, and writes the probes into it. That runs on the thread loading the class, so it adds to the time that class takes to load. It is a one-time cost per class, and a class that never loads never pays it.

Three things drive that cost up:

- **More matched classes.** The cost is roughly linear in matched classes. The broad run above wove about 4,200 classes and 99,685 probes. The narrow run wove about 100 classes and 1,489 probes.
- **Locating class files.** The agent reads each class's file from its own loader, and the supertypes it needs. Inside a Spring Boot fat jar that goes through the nested-jar loader. The agent caches these reads (up to 16 MB, evicting the least recently used) and releases per-loader caches when a loader goes quiet.
- **One core.** On one core, class loading, the JIT, and the weave share a CPU. The broad rules took startup from 13.5 s to 36.0 s on the one-core runner, a larger relative cost than any throughput, CPU or latency row of the table.

Heap follows the same shape. The broad run's minimum heap used was +122 MiB, and its resident set size +303 MiB. Once a class's manifest is delivered, the agent releases that class's locations and keeps only what the next flush needs.

## Startup

Startup is the cost to watch. In the startup-only run, the narrow rules added 4.1 s to a 16.8 s startup on one core (+24.6%), the median of 21 runs per variant. With more cores the cost shrinks, since class loading, the JIT and the weave no longer share one CPU. Startup grows with how many classes your application loads, because the agent checks each loaded class against its rules.

Three pieces of work that would otherwise be startup cost run later:

- The dependency listing, which reads every jar on the classpath to find its identity, starts at the top of the first flush on the export thread, not in `premain`. It reads each nested jar in a fat jar from its central directory, and decompresses only the `pom.properties` files and manifest it needs.
- The first full manifest goes out on the first flush, at a random point inside the first interval.
- The class listing (on by default) runs on its own background thread, named `otherlode-class-listing`, started once at startup and not in `premain`. It reads the file names in each class folder and the central directory of each jar on the classpath. It reads a jar nested in a Spring Boot fat jar the same way, from that jar's central directory, so it decompresses nothing to read entry names. On the benchmark's PetClinic jar, a first call to the listing, measured in a JVM of its own on one core of the benchmark runner, took about 180 ms of CPU both with the narrow rules, which list 122 classes, and with `org.springframework`, which lists 11,791. Those are the medians of six nightly runs. Its cost follows the number of jars it reads, not the number of classes your rules admit. On a classpath of 773 jars holding about 186,000 classes, with rules that matched none of them, it took about 160 ms of CPU on one laptop core. It parses no class file, and it runs once per process. A JVM started with no class path reads the working directory as its class path, and the listing then walks every folder under it, so start the application from its own folder or give it a class path. With `classListingEnabled=false` the thread does not start. See [classes](classes#the-class-listing).

## The flush thread and the network

The agent runs one scheduled thread named `otherlode-export`, two send threads named `otherlode-export-send`, and the selector and worker threads of the JDK HTTP client it sends with. On Linux, one more thread, `otherlode-trim`, trims the native heap once an hour. While classes load, short-lived `otherlode-code-source-warmup` threads open each kind of jar location before the agent reads class files from it, one thread per attempt and at most 16 attempts per kind. The class listing runs on `otherlode-class-listing` for a short time at startup, and with the static baseline on, `otherlode-static-baseline-scan` scans the classpath. A graceful shutdown runs the hook thread `otherlode-shutdown-hook`, a thread for the early flush named `otherlode-shutdown-early-flush` with two send threads named `otherlode-export-early-send`, and a thread for the final flush named `otherlode-shutdown-flush`. Every thread except `otherlode-shutdown-hook` is a daemon thread, so none of them keeps your JVM alive. The JVM starts `otherlode-shutdown-hook` only once it is already exiting.

On each flush (every 60 seconds by default, with a random offset for the first one so a fleet does not flush at the same instant), the thread:

- copies each changed class's count array,
- encodes a delta batch, and sends it (a flush with unconfirmed counts sends an empty batch first, and one that catches up ends with another),
- sends any manifest chunks that are not stored yet,
- walks the loaded classes. Once the dependency listing has completed, it does so on every flush, to count each dependency's loaded classes and to find classes no transformer saw. Until then, it looks for classes no transformer saw on the first flush and then every tenth, about every ten minutes at the default interval. On the flushes between, it walks the loaded classes while a woven class awaits confirmation that the JVM defined it, or while a [framework marker](endpoints#frameworks-with-no-module) is still unseen.

A timed run of the flush code on a laptop, over 1,488 classes and 26,380 probes, encoding without sending, gave:

| Flush | Time | Allocated |
|---|---|---|
| Steady, a tenth of probes changed | 0.20 ms | 890 KB |
| Empty heartbeat | 0.05 ms | 240 KB |
| First flush, whole manifest | 32 ms | 98 MB |

A heartbeat's allocation is mostly copying the count arrays, about 9 bytes a probe a flush. The loaded-class walk took 0.5 ms against a JVM holding 4,800 classes. The first flush grows with the probe count, and happens once.

On the network, a flush sends each payload as one or more POST requests, once per interval. Payload size follows the number of probes whose counts changed, not total traffic or total probes, because the agent sends deltas. An empty batch still goes out as a heartbeat. Each send makes up to five attempts, waiting 200 ms after the first failure and doubling the wait up to 30 seconds. A static baseline or class listing chunk resent at a later flush gets one attempt. When the receiver is down, the counts stay in memory and the next flush sends them again, so memory does not grow while it is unreachable. [What the agent sends](data-sent) lists each payload.

## Memory over days

On Linux, a JVM's resident set size can grow for days while the memory it uses stays level. The JIT compiler frees the memory it compiles with, and glibc keeps that memory instead of returning it. The agent's flush code runs once a minute, so the JIT keeps compiling it after your own code has settled.

Once an hour the agent asks the JVM to trim its native heap, which returns that memory. A trim takes a few milliseconds. [`trimNativeHeapIntervalMinutes`](configuration#trimnativeheapintervalminutes) sets the interval, and `0` turns the trim off.

With the trim, resident set size held level through a 24-hour test. With it off, agent JVMs in a week-long test reached 120 to 185 MB above JVMs with no agent.

## Opt-in costs

One feature is off by default and costs something when on.

The static baseline (`staticBaselineEnabled=true`) walks the classpath's bytecode for every class under `includePackages`, on a background thread named `otherlode-static-baseline-scan`. It does not run in `premain`, so it does not add to the time before `main`. It does compete with your application for CPU while it runs, and the cost scales with classpath size, not with traffic. In the startup-only run, the narrow rules with the static baseline on took startup from 16.8 s to 21.9 s on one core (+30.1%), against +24.6% with it off. It runs once per process. See [classes](classes) for what it reports and what it cannot see.

## What to do about it

The cost follows matched classes, so the first lever is narrower `includePackages`.

- **Cover your own code and nothing else.** A prefix like `com.acme.orders` instruments your classes. A prefix like `org.springframework` instruments a framework you cannot edit, produces findings you cannot act on, and costs the broad column above. The agent also refuses to start with no include rules at all. See [configuration options](configuration).
- **Use `excludePackages`** to remove generated or hot utility packages from a broad include.
- **Watch startup on a small instance.** If your service runs on one or two CPUs, measure its time to first request with and without the agent, as the benchmark does. Startup grows most there.
- **Leave the static baseline off** unless you need to know about classes that never load. Turn it on for a deliberate run, not on every instance.
- **Lengthen `flushIntervalSeconds`** if you want fewer wakeups. The cost of a steady flush is small, so this changes little, and a longer interval delays what Otherlode sees.

To measure your own service, compare time to first request, throughput, CPU per request, and live heap with and without `-javaagent`. Compare per-request figures, not totals, when the service is saturated: a variant that serves more requests also allocates and collects more in total.
