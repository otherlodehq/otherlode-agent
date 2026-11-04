# Overhead against the OpenTelemetry Java agent

A comparison of three JVMs serving the same app under the same load: no agent, the OpenTelemetry
Java agent, and the Otherlode agent. It asks whether Otherlode costs about what an accepted
industry agent costs. It is a benchmark, not a go-live blocker. The soak week (`soak/DESIGN.md`) and
its smokes are the go-live blockers, since they find gaps, bugs and memory leaks.

## Precedent

OpenTelemetry's own `benchmark-overhead` runs `spring-petclinic-rest` on a fresh Postgres per run,
drives it with k6 in a closed loop (5 virtual users, 5000 iterations, no rate limit), and compares
no agent with its released and snapshot agents. Its agents export OTLP to a collector container
whose pipelines end in a `debug` exporter at basic verbosity. It reads startup as the time to the
first healthy answer, and JFR for heap minimum and maximum, allocation, GC time and pauses, thread
count, JVM and machine CPU, and network rates. k6 gives request and iteration mean and p95. It
does not report RSS, throughput or p99.

This repository's `benchmark-overhead` harness is already built on that precedent: PetClinic on
Postgres, k6 closed loop, OpenTelemetry's `overhead.jfc`, the same containers. It adds what the
precedent lacks: a warmup that ends when throughput is steady, PetClinic pinned to cores of its
own, CPU per request from the cgroup, RSS from `/proc`, p99, a startup-only mode with 21 repeats,
and rules that throw out an invalid run. The comparison is that harness with a third variant. The
JMH benchmarks in `src/jmh` time the agent's own hot path and flush; they cannot run a foreign
agent, so they play no part.

Where it departs from the precedent:

- A warmup until steady, not none. OpenTelemetry's 0 to 60 s leaves the JIT compiling in the
  window, which the harness's drift rule would reject.
- A timed window, not a fixed iteration count, so each variant is measured over the same time.
- A no-op sink, not an OpenTelemetry collector. The collector's own work runs off PetClinic's
  cores either way. A sink of our own counts what arrived, which an `otel/opentelemetry-collector`
  container with a `debug` exporter would only log.

## The app

`spring-petclinic-rest`, pinned to the commit the harness uses, on Java 21 with
`-Xms1g -Xmx1g -XX:+UseG1GC`. It matches OpenTelemetry's benchmark, so the numbers are read the way
theirs are, and the harness already validates it.

`soak-shop` was the other choice. It has no database, so the OpenTelemetry agent would have no
JDBC or connection pool work to instrument, and a comparison on it would flatter OpenTelemetry
against how it is deployed.

## The variants

| Variant | Agent | Options |
|---|---|---|
| `none` | none | |
| `otel` | `opentelemetry-javaagent` 2.32.0 | defaults, plus the sink endpoint and `service.name=petclinic` |
| `agent` | Otherlode at the commit under test | `includePackages=org.springframework.samples.petclinic`, defaults otherwise |

The OpenTelemetry agent keeps its defaults: every instrumentation on, the `parentbased_always_on`
sampler so every request is traced, and traces, metrics and logs exported over OTLP
`http/protobuf`: spans every 5 s, logs every second, metrics every 60 s. That is the agent as an adopter
first installs it. Sampling would lower its cost, and the report says so.

Otherlode keeps the harness's `headline` config: the application's own package, a 60 s flush, the
static baseline off (its default), and export to a real `otherlode-collector` that only logs.

Every variant has both agent jars copied into its container, so the copy costs none of them more
than another.

## The sink

`otel` exports to `http://otlp-sink:4318`, a single-file Java program
(`src/main/resources/NoopOtlpSink.java`) run in a container from the PetClinic image, on the cores
Postgres and the collector share. It answers every `POST` with 200 and an empty
`application/x-protobuf` body, which decodes as an empty export response, and counts the requests
and bytes per path. It decodes nothing. Nothing leaves the runner: the sink sits on the harness's own
Docker network and has no route out that the agent uses.

So the OpenTelemetry agent pays for building, batching, encoding and sending its spans, metrics
and logs, as Otherlode pays for its flush. Neither pays for what the receiver does.

## The load

The comparison runs two load shapes, each as a full matrix.

- **Closed loop**, the harness's default: 4 virtual users per PetClinic core, every response
  checked. PetClinic runs at its CPU limit, so throughput, CPU per request, allocation and GC per
  request compare the agents' work. Latency here is mostly queueing: in a closed loop it moves with
  throughput.
- **Open loop at a fixed rate:** k6's `constant-arrival-rate` executor at a quarter of `none`'s steady
  closed-loop iteration rate, measured at the start of the job on the same runner (see below). PetClinic has headroom, so p50, p95 and
  p99 show the time an agent adds to a request, not a queue. A run is invalid when k6 drops an
  iteration, or when PetClinic averages 80% or more of its pinned cores, since it then queues. The
  window's steadiness is judged on median latency, since a fixed rate starts the same number of
  requests in every third: the first third's median over the last's.

Each run warms until steady (30 s slices, at least 90 s, at most 600 s) and then measures for
300 s, so each window holds five Otherlode flushes and five OpenTelemetry metric exports.

## The metrics

All but startup are read over the window, from the sources the harness already uses.

| Metric | How it is sampled |
|---|---|
| Startup | Milliseconds to the first 200 from `/actuator/health`, polled every 100 ms, and the container's CPU to that point. From the startup-only mode, 21 repeats per variant |
| Throughput | k6 requests per second (closed loop) |
| Latency p50, p95, p99 | k6 `http_req_duration`, all requests, `url` tag off |
| CPU | Container cgroup `cpu.stat` before and after the window: average cores and CPU ms per 1000 requests |
| Heap | JFR `jdk.GCHeapSummary`: heap used minimum and maximum. The minimum is the live set after a GC |
| Allocation | JFR `jdk.ThreadAllocationStatistics`, as KiB per request |
| GC pauses | JFR `jdk.GCPhasePause`: total and per 1000 requests |
| RSS | `VmRSS` and `VmHWM` from `/proc/1/status` at the end of the window; JFR `jdk.ResidentSetSize` maximum |
| Metaspace, threads | `jcmd VM.metaspace`, JFR peak thread count |

## Where it runs

Where the harness already runs: GitHub's `ubuntu-latest` (4 vCPU), which costs nothing for a public
repository. A workflow of its own, `benchmark-otel-comparison`, starts only by hand
(`workflow_dispatch`), so the nightly job is unchanged. It has three jobs, each on a runner of its
own: closed loop, open loop and startup only. All three variants of a job run on the same runner,
so every comparison is within one machine. With 4 CPUs the harness pins PetClinic to core 0, k6 to
cores 1 and 2, and Postgres, the collector and the sink to core 3.

The workflow runs `scripts/fetch-otel-agent.sh`, which downloads the OpenTelemetry jar from Maven
Central and checks its size, SHA-1 and SHA-256 against values pinned in the script, and its PGP
signature against a key fingerprint pinned there. A mismatch fails the job before any run.

The open loop's rate is measured on its own runner: the job first runs `none` under the closed loop
until steady, takes the iteration rate of its last slice, and runs the matrix at a quarter of it (the workflow's `open_rate_fraction`, default 0.25). The
rate goes into the results.

A quarter, not half. At half, even `none` collapsed (run 41796109323, an AMD EPYC 9V45 runner,
at 131 iterations/s, half of the 261 its calibration measured before reaching its warmup cap):
PetClinic grew from 28 to 219 threads contending for its 10 database
connections, its one core ran full, GC pauses rose from 6.5 to 25 ms and k6 dropped 9,476
iterations. That is PetClinic's own failure mode on one core, so no agent could be measured
there.

`soak-2` was the other choice. It would hold every job on one machine, but it is an `e2` VM that
shares its host, it needs the collector's source and an image build copied onto it, and it has
Docker 20.10, which the harness has never run on. The runner is where the harness's numbers and
validity rules were tuned.

## Repeats and what counts as comparable

Six repeats per variant for each load shape, in the harness's rotated order so no variant always
goes first, and 21 startup-only repeats per variant. A load-shape job runs 18 times at 8 to 16 minutes
each, so 2.5 to 5 hours, inside the six-hour job limit. The three jobs run at once.

Each table gives each variant's median with its minimum and maximum. Otherlode is above or below
OpenTelemetry on a metric only when their ranges do not overlap. Where they overlap the two are
comparable on it, and the report says so. A change against `none` that lies inside `none`'s own
range is marked as noise, as the harness does.

A run is invalid, and runs again or fails the matrix, by the harness's existing rules, plus these
for `otel`:

- The sink received no trace export over the run, from start to shutdown, so the agent traced
  nothing.
- The OpenTelemetry agent logged at `ERROR`, or logged a failed export.

## What changes in the harness

- `ListingCost.java` called `ClassLister`'s constructor as it stood before `d9021c1`, which added
  three parameters, so the nightly full runs failed at the class listing. `2b1f1cc` fixes it in a
  commit of its own, since the comparison needs it.
- A variant `otel`, the property `-PotelAgentJar`, and `-Pvariants=none,otel,agent`. The default
  variants stay `none,agent,agent-baseline`, so the nightly job is unchanged.
- The sink and its checks.
- `-Pload=open`, the calibration of its rate, and its checks.
- The `benchmark-otel-comparison` workflow.
- `httpReqP50Ms` beside p95 and p99.

## Results

A run's raw results are its workflow artifacts, kept 90 days, as for the nightly job, and are not
committed. The report, committed below, is the tables per metric with the spread, and a plain statement of where
Otherlode sits above or below OpenTelemetry, and why. It says plainly that the OpenTelemetry agent
on its defaults traces every request, which is a different job from Otherlode's counting, and that
the defaults are what an adopter gets, which is why they are the fair comparison.

### Results on 2026-11-04

GitHub's `ubuntu-latest` (4 vCPU), PetClinic on one pinned core, OpenTelemetry Java agent 2.32.0
on its defaults, Otherlode with the headline `includePackages` (1,491 probes). Every run of every
job was valid. Cells are the median with the minimum to maximum, or with the interquartile range
for startup. "noise" marks a median inside `none`'s own range.

Closed loop, run 41746864038 at `9bb2c3a`, AMD EPYC 7763, six repeats per variant:

| | `none` | Otherlode | OpenTelemetry |
|---|---|---|---|
| throughput (req/s) | 1734 (1657 to 1794) | 1769 (1668 to 1830), noise | 1456 (1391 to 1503), -16.0% |
| CPU per request (ms) | 0.561 (0.543 to 0.586) | 0.551 (0.529 to 0.583), noise | 0.676 (0.659 to 0.714), +20.6% |
| p50 latency (ms) | 1.96 (1.87 to 2.06) | 1.93 (1.84 to 2.06), noise | 2.33 (2.27 to 2.45), +18.8% |
| p95 latency (ms) | 4.06 (3.93 to 4.19) | 3.97 (3.93 to 4.21), noise | 4.83 (4.73 to 5.15), +19.0% |
| p99 latency (ms) | 4.99 (4.69 to 5.20) | 4.86 (4.72 to 5.38), noise | 6.23 (6.06 to 7.09), +24.9% |
| allocation per request (KiB) | 93.1 (92.8 to 95.9) | 94.1 (92.6 to 96.0), noise | 120.4 (116.4 to 121.9), +29.3% |
| GC pause per 1000 requests (ms) | 1.00 (0.97 to 1.03) | 1.04 (1.03 to 1.07), +3.5% | 1.61 (1.58 to 1.65), +60.8% |
| minimum heap used (MiB) | 47.2 (46.7 to 47.4) | 56.9 (54.8 to 58.3), +20.4% | 70.8 (68.4 to 72.1), +49.8% |
| RSS (MiB) | 1023 (1011 to 1032) | 1098 (1080 to 1101), +7.3% | 1149 (1143 to 1157), +12.3% |
| metaspace (MiB) | 111.6 (111.5 to 111.7) | 133.9 (133.8 to 133.9), +19.9% | 139.4 (139.3 to 139.5), +24.8% |
| peak threads | 28 (28 to 29) | 35 (34 to 35) | 39 (39 to 40) |
| network write (KiB/s) | 2429 (2320 to 2523) | 2474 (2332 to 2576), noise | 6588 (6274 to 6761), +171% |

One repeat of Otherlode and one of OpenTelemetry drifted past 10% (-14.5% and +30.5%); each
variant's median drift was inside the bound.

Open loop, run 41834023735 at `9955112`, AMD EPYC 7763, six repeats per variant, 34.4
iterations/s (447 req/s), a quarter of the 137.6 iterations/s `none` reached closed. No iteration
was dropped, and PetClinic averaged 0.28 to 0.35 of its core:

| | `none` | Otherlode | OpenTelemetry |
|---|---|---|---|
| CPU per request (ms) | 0.646 (0.629 to 0.678) | 0.639 (0.624 to 0.674), noise | 0.754 (0.710 to 0.789), +16.6% |
| p50 latency (ms) | 0.883 (0.821 to 0.911) | 0.846 (0.818 to 0.888), noise | 0.903 (0.837 to 0.963), noise |
| p95 latency (ms) | 1.75 (1.70 to 1.84) | 1.73 (1.69 to 1.78), noise | 1.87 (1.81 to 1.96), +6.9% |
| p99 latency (ms) | 1.91 (1.87 to 2.02) | 1.92 (1.87 to 2.02), noise | 2.62 (2.54 to 2.76), +37.5% |
| allocation per request (KiB) | 94.2 (92.9 to 94.4) | 94.2 (92.6 to 96.0), noise | 121.4 (119.2 to 122.2), +28.8% |
| GC pause per 1000 requests (ms) | 0.99 (0.97 to 1.95) | 1.00 (0.98 to 1.13), noise | 1.56 (1.46 to 1.65), noise |
| network write (KiB/s) | 632 (626 to 638) | 625 (625 to 631), -1.1% | 2037 (2019 to 2054), +222% |

Memory in the open loop matches the closed loop's to within a few MiB, and peak threads to
within two. One repeat of
`none` and one of OpenTelemetry drifted past 10% (-13.9% and +12.3%); each variant's median drift
was inside the bound.

Startup, run 41746864038 at `9bb2c3a`, AMD EPYC 9V45, 21 repeats per variant:

| | `none` | Otherlode | OpenTelemetry |
|---|---|---|---|
| time to first 200 (s) | 12.7 (11.9 to 13.2) | 15.2 (14.2 to 16.1), +19.7% | 19.8 (18.7 to 20.9), +55.7% |
| startup CPU (s) | 11.8 (11.0 to 12.3) | 14.3 (13.3 to 15.2), +21.3% | 18.9 (17.8 to 20.0), +60.0% |

Otherlode does as well as or better than OpenTelemetry on every metric. In the closed loop and at
startup its range does not overlap OpenTelemetry's on any metric above; in the open loop the two
overlap only on p50. Per request, Otherlode cannot be told from `none`, except for 3.5% more GC
pause in the closed loop: a probe hit is one array increment, and it sends small counter deltas.
OpenTelemetry on its defaults builds spans for every request (the servlet, Spring MVC and each JDBC
call) and exports every one, with metrics and logs. That is about 27 KiB more allocation per
request and 1.4 to 3.4 GB of OTLP per run, which shows in CPU and the p99, and in GC pauses in the
closed loop. It is a different job from Otherlode's counting. Defaults are what an adopter gets, so
they are the fair comparison. Otherlode's costs are fixed ones: about 10 MiB more heap, 75 to 85
MiB more RSS, 22 MiB more metaspace, six or seven more threads, and a fifth longer startup
(OpenTelemetry's is more than half).
